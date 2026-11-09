package com.ai.assistance.operit.core.companion

import android.content.Context
import androidx.core.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Local receipt of a user's one-time opening, not another psychological-state store.
 * A full snapshot can predate that opening. Restoring it must not silently grant another
 * 48 hours. The opening is also in the normal backup; this small non-backed-up receipt
 * only preserves the earliest opening / latest observed time on the same installation.
 * Called under CompanionStore's existing owner lock. No history rows or wakeups. */
internal object EstablishedWindowReceipt {
    fun reconcile(context: Context, owner: String, data: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
        val target = AtomicFile(File(context.noBackupFilesDir, "established-window-" + InnerStructure.hash(owner) + ".json"))
        val receipt = if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists())
            target.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) } else null
        val current = data.optJSONObject("inner_structure")?.optJSONObject("established_window")
        if (current == null && receipt == null) return false // Reading cannot open a window.
        listOfNotNull(current, receipt).forEach {
            require(it.getString("owner") == owner && it.getString("opened_by") == "user")
            require(it.getLong("closes_at") == Math.addExact(it.getLong("opened_at"), EstablishedContent.DURATION))
        }
        val opened = listOfNotNull(current, receipt).minOf { it.getLong("opened_at") }
        val observed = maxOf(now, current?.optLong("last_observed_at") ?: opened, receipt?.optLong("last_observed_at") ?: opened)
        val fact = JSONObject().put("owner", owner).put("opened_by", "user").put("opened_at", opened)
            .put("closes_at", Math.addExact(opened, EstablishedContent.DURATION)).put("last_observed_at", observed)
        if (receipt == null || InnerStructure.hash(receipt) != InnerStructure.hash(fact)) {
            val out = target.startWrite()
            try { out.write(fact.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(out) }
            catch (error: Throwable) { target.failWrite(out); throw error }
        }
        val next = JSONObject((current ?: fact).toString())
        fact.keys().forEach { next.put(it, fact.get(it)) }
        if (!next.has("submitted")) next.put("submitted", 0L)
        val changed = current == null || InnerStructure.hash(current) != InnerStructure.hash(next)
        if (changed) InnerStructure.root(data).put("established_window", next)
        return changed
    }
}
