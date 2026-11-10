package com.ai.assistance.operit.core.companion

import com.ai.assistance.operit.core.chat.RuntimeContextSections
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

    /** Explicit export only. Normal requests and pages use bounded queries below. */
    fun all(context: Context, role: String): List<JSONObject> = CompanionStore(context).withThoughts(role) { archive ->
        buildList {
            var offset = 0
            while (true) {
                val rows = archive.categoryPage("thought", offset, 20, null)
                addAll(rows)
                if (rows.size < 20) break
                offset += rows.size
            }
        }
    }

    fun selected(context: Context, role: String, cursor: Long = 0): List<JSONObject> =
        CompanionStore(context).withThoughts(role) { archive ->
            val count = archive.categoryCount("thought", "sink")
            if (count == 0L) emptyList() else {
                val offset = Math.floorMod(cursor, count).toInt()
                archive.categoryPage("thought", offset, 8, "sink")
            }
        }

    fun page(context: Context, role: String, offset: Int): JSONObject {
        require(offset >= 0)
        return CompanionStore(context).withThoughts(role) { archive ->
            val rows = archive.categoryPage("thought", offset, 9, null)
            JSONObject().put("items", JSONArray(rows.take(8))).put("count", archive.categoryCount("thought", null))
                .put("next_offset", if (rows.size > 8) offset + 8 else JSONObject.NULL)
        }
    }

    /** 沉着且已到浮现时间的念头。 */
    fun surfaced(items: List<JSONObject>, now: Long): List<JSONObject> =
        items.filter { it.optString("state") == "sink" && it.optLong("surface_at") <= now }.sortedBy { it.optLong("surface_at") }

    fun save(context: Context, role: String, id: String?, text: String?, choice: String, minutes: Int?, now: Long = System.currentTimeMillis()): JSONObject {
        require(choice in choices) { "choice 须为 now（现在讲）、sink（先沉着）或 drop（扔掉）" }
        val clean = text?.trim()
        require(id != null || !clean.isNullOrEmpty()) { "新念头要写内容" }
        val sink = minutes ?: DEFAULT_SINK_MINUTES
        require(choice != "sink" || sink in 1..43200) { "沉着时间须为1–43200分钟" }
        lateinit var saved: JSONObject
        CompanionStore(context).withThoughts(role) { archive ->
            val key = id ?: UUID.randomUUID().toString()
            if (id != null) require(archive.categoryRow("thought", key) != null) { "念头不存在" }
            saved = archive.activityWrite("thought", key, choice) { old ->
                val item = old ?: JSONObject().put("id", key).put("created", now).put("version", 1)
                if (!clean.isNullOrEmpty()) item.put("text", clean)
                item.put("state", choice).put("parent", choice).put("storage_kind", "thought")
                    .put("owner", role).put("updated", now).put("source", "assistant_thought")
                if (choice == "sink") item.put("surface_at", now + sink * 60_000L) else item.remove("surface_at")
                item
            }
        }
        return saved
    }

    /** 唤醒和聊天里提醒自己：有念头浮上来了。只列开头，不替我决定。 */
    fun prompt(items: List<JSONObject>, now: Long): String = promptSections(items, now).let {
        if (it.state.isEmpty()) "" else it.asText()
    }

    fun promptSections(items: List<JSONObject>, now: Long): RuntimeContextSections {
        val up = surfaced(items, now)
        return RuntimeContextSections(
            instructions = "\n念头池中浮现的念头，追、讲还是扔，你自己定。用 companion_todo action=thought id=… choice=now/sink/drop 处理；还没想好就再 sink 一阵。\n",
            state = if (up.isEmpty()) "" else "【念头池：有${up.size}个沉着的念头浮上来了】\n" + up.take(5).joinToString("\n") {
            "- ${it.getString("id")}：${it.optString("text").take(80)}"
            } + "\n"
        )
    }
}
