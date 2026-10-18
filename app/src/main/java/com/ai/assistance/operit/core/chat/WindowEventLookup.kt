package com.ai.assistance.operit.core.chat

import android.content.Context
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import org.json.JSONArray
import org.json.JSONObject

/** The saved latest summary is the window archive. No duplicate file or automatic cross-window payload. */
internal object WindowEventLookup {
    suspend fun windows(context: Context, actor: String, query: String, offset: Int): JSONObject {
        require(offset >= 0) { "offset 须为非负整数" }
        val repo = ChatHistoryManager.getInstance(context)
        val windows = repo.getAllChatMetadata().filter {
            it.characterGroupId.isNullOrBlank() && it.characterCardId == actor && it.title.contains(query, ignoreCase = true)
        }.sortedByDescending { it.updatedAt }
        val shown = windows.drop(offset).take(20).map { chat ->
            JSONObject().put("chat_id", chat.id).put("title", chat.title).put("updated_at", chat.updatedAt.toString())
                .put("summary_at", repo.getLatestSummaryTimestamp(chat.id) ?: JSONObject.NULL)
        }
        val next = offset.toLong() + shown.size
        return JSONObject().put("windows", JSONArray(shown)).put("total", windows.size)
            .put("next_offset", if (next < windows.size) next else JSONObject.NULL)
            .put("note", "包括尚无事件摘要的窗口。events 查事件；history 指定窗口ID分页查看尚未整理的聊天。")
    }

    suspend fun search(
        context: Context, actor: String, chatId: String?, query: String,
        from: Long?, end: Long?, status: String?, offset: Int
    ): JSONObject {
        require(offset >= 0) { "offset 须为非负整数" }
        require(from == null || from > 0) { "from 须为正时间戳" }
        require(end == null || end > 0 && (from == null || end >= from)) { "end 须不早于 from" }
        require(status == null || status in setOf("悬着", "已结束")) { "status 须为 悬着/已结束" }
        val repo = ChatHistoryManager.getInstance(context)
        val own = repo.getAllChatMetadata().filter {
            it.characterGroupId.isNullOrBlank() && it.characterCardId == actor && (chatId == null || it.id == chatId)
        }
        if (chatId != null) check(own.size == 1) { "窗口不存在或不属于当前助手" }
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val rows = mutableListOf<JSONObject>()
        own.forEach { chat ->
            val at = repo.getLatestSummaryTimestamp(chat.id) ?: return@forEach
            val summary = repo.loadChatMessagesWindow(chat.id, at, at).singleOrNull()?.content ?: return@forEach
            val parsed = SegmentedSummary.parse(summary)
            (parsed.closed + parsed.open).forEach event@{ block ->
                val index = SegmentedSummary.index(block) ?: return@event
                val state = if (block.lineSequence().first().contains("悬着")) "悬着" else "已结束"
                if (from != null && index.to < from || end != null && index.from > end) return@event
                if (status != null && state != status) return@event
                if (!terms.all { (chat.title + "\n" + block).contains(it, ignoreCase = true) }) return@event
                rows += JSONObject().put("event_id", "${chat.id}:${index.from}")
                    .put("chat_id", chat.id).put("window_title", chat.title).put("summary_at", at)
                    .put("from", index.from).put("to", index.to).put("status", state)
                    .put("event", SegmentedSummary.compact(block))
            }
        }
        val ordered = rows.sortedWith(compareByDescending<JSONObject> { it.getLong("to") }
            .thenBy { it.getString("event_id") })
        val shown = ordered.drop(offset).take(10)
        val next = offset.toLong() + shown.size
        return JSONObject().put("events", JSONArray(shown)).put("total", ordered.size)
            .put("next_offset", if (next < ordered.size) next else JSONObject.NULL)
            .put("note", "查询的是已压缩存档的事件，尚未整理的新消息不在目录中。history 用 chat_id/after=from/end=to；history_message 可展开长消息、工具或附件标签；feelings 读取当时保存的完整感受。窗口总摘要可用 history_message message_at=summary_at part=raw 读取。")
    }

    suspend fun feelings(context: Context, actor: String, chatId: String, from: Long, end: Long, offset: Int): JSONObject {
        require(from > 0 && end >= from) { "请填写有效的 from/end 时间范围" }
        val chat = requireNotNull(ChatHistoryManager.getInstance(context).getChatMetadata(chatId)) { "窗口不存在" }
        check(chat.characterGroupId.isNullOrBlank() && chat.characterCardId == actor) { "只能读取自己的私聊感受" }
        val last = ChatHistoryManager.getInstance(context).loadChatMessagesWindow(chatId, end, end).singleOrNull()
        // 感受可能在最后一条回复生成途中记下，消息开始时间不是生成完成时间。
        val through = maxOf(end, last?.completedAt ?: end)
        val original = FeelingLog.since(context, chatId, from).filter { it.at <= through }
            .joinToString("\n\n") { "[${it.at}] ${it.text}" }
        val page = HistoryTextPage.read(original, offset)
        return JSONObject().put("chat_id", chatId).put("from", from).put("end", end)
            .put("text", page.text).put("total_characters", page.total)
            .put("next_offset", page.next ?: JSONObject.NULL).put("complete", page.next == null)
            .put("note", "仅返回实际保存的原话；旧版本曾限制保留200条，已被旧版清除的感受需回查历史摘要或原聊天。")
    }
}
