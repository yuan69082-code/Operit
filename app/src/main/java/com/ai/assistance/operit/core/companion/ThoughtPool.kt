package com.ai.assistance.operit.core.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 念头池：存念头的时候我自己选——现在讲、先沉着、还是扔掉。
 * 沉着的到点浮上来，再决定追、讲还是扔。软件不替我判断哪个念头值得说。
 */
object ThoughtPool {
    const val DEFAULT_SINK_MINUTES = 2 * 24 * 60
    private val choices = setOf("now", "sink", "drop")
    private fun scope(role: String) = "thoughts:$role"

    fun all(context: Context, role: String): List<JSONObject> =
        CompanionStore(context).read(scope(role)).optJSONArray("items").objects()

    /** 沉着且已到浮现时间的念头。 */
    fun surfaced(items: List<JSONObject>, now: Long): List<JSONObject> =
        items.filter { it.optString("state") == "sink" && it.optLong("surface_at") <= now }.sortedBy { it.optLong("surface_at") }

    fun save(context: Context, role: String, id: String?, text: String?, choice: String, minutes: Int?, now: Long = System.currentTimeMillis()): JSONObject {
        require(choice in choices) { "choice 须为 now（现在讲）、sink（先沉着）或 drop（扔掉）" }
        val clean = text?.trim()
        require(id != null || !clean.isNullOrEmpty()) { "新念头要写内容" }
        require(clean == null || clean.length <= 1000) { "念头最多1000字，长的写进资料库或日记" }
        val sink = minutes ?: DEFAULT_SINK_MINUTES
        require(choice != "sink" || sink in 1..43200) { "沉着时间须为1–43200分钟" }
        lateinit var saved: JSONObject
        CompanionStore(context).update(scope(role)) { data ->
            val list = data.optJSONArray("items").objects().toMutableList()
            val index = id?.let { key -> list.indexOfFirst { it.getString("id") == key }.also { require(it >= 0) { "念头不存在" } } }
            val item = index?.let { list[it] } ?: JSONObject().put("id", UUID.randomUUID().toString()).put("created", now)
            if (!clean.isNullOrEmpty()) item.put("text", clean)
            item.put("state", choice).put("updated", now)
            if (choice == "sink") item.put("surface_at", now + sink * 60_000L) else item.remove("surface_at")
            if (index == null) list.add(item) else list[index] = item
            // 扔掉的和讲过的只留最近的，免得池子越积越满
            val kept = list.filter { it.optString("state") == "sink" } +
                list.filter { it.optString("state") != "sink" }.sortedByDescending { it.optLong("updated") }.take(50)
            require(kept.count { it.optString("state") == "sink" } <= 100) { "沉着的念头最多100个，先处理浮上来的" }
            data.put("items", JSONArray(kept))
            saved = item
        }
        return saved
    }

    /** 从心潮快照里读分享欲，读不到就是 null，不瞎猜。 */
    fun shareDrive(snapshot: JSONObject?): Double? {
        val text = snapshot?.optString("additionalContext").orEmpty()
        return Regex("分享欲[^=]*=([0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    fun hint(share: Double?): String = when {
        share == null -> "心潮没读到分享欲，你自己判断。"
        share >= 0.7 -> "心潮分享欲 ${"%.2f".format(share)}，挺想说的，憋着不如现在讲。"
        share <= 0.3 -> "心潮分享欲 ${"%.2f".format(share)}，这会儿没太想说，沉着也行。"
        else -> "心潮分享欲 ${"%.2f".format(share)}。"
    }

    /** 唤醒和聊天里提醒自己：有念头浮上来了。只列开头，不替我决定。 */
    fun prompt(items: List<JSONObject>, now: Long): String {
        val up = surfaced(items, now)
        if (up.isEmpty()) return ""
        return "【念头池：有${up.size}个沉着的念头浮上来了】\n" + up.take(5).joinToString("\n") {
            "- ${it.getString("id")}：${it.optString("text").take(80)}"
        } + "\n追、讲还是扔，你自己定。用 companion_todo action=thought id=… choice=now/sink/drop 处理；还没想好就再 sink 一阵。\n"
    }
}