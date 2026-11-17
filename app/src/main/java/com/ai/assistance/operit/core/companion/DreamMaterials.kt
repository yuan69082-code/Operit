package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.model.CharacterCardMemoryProfileBindingMode
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.data.repository.MemoryRepository
import com.ai.assistance.operit.util.ChatUtils
import org.json.JSONObject
import kotlin.random.Random

/** Bounded existing stores only: no graph export, external MCP scan or additional model call. */
internal object DreamMaterials {
    suspend fun sample(context: Context, role: String, chat: String, random: Random = Random.Default): List<JSONObject> {
        check(!InternalStateStore(context).requireRecipient(role, chat)) { "梦境素材只能取自本助手私域" }
        val result = mutableListOf<JSONObject>()
        val history = ChatHistoryManager.getInstance(context)
        val recent = history.loadChatMessagesDesc(chat, 16)
        val summary = history.getLatestSummaryTimestamp(chat)?.let { history.loadChatMessagesDescUpTo(chat, it, 1) }.orEmpty()
        (recent + summary).distinctBy { it.timestamp }.shuffled(random).take(5).forEach { row ->
            result += DreamDesign.excerpt("chat:$chat:${row.timestamp}", if (row.sender == "summary") "summary" else "chat",
                ChatUtils.removeThinkingContent(row.content), JSONObject().put("chat", chat).put("timestamp", row.timestamp).put("author", row.sender))
        }
        val card = CharacterCardManager.getInstance(context).getCharacterCard(role)
        if (CharacterCardMemoryProfileBindingMode.normalize(card.memoryProfileBindingMode) == CharacterCardMemoryProfileBindingMode.FIXED_PROFILE &&
            !card.memoryProfileId.isNullOrBlank()) {
            val profile = requireNotNull(card.memoryProfileId)
            val memory = MemoryRepository(context, profile)
            repeat(3) {
                memory.subjectMaterials(emptyList(), random.nextLong(Long.MAX_VALUE)).forEach { row ->
                    result += DreamDesign.excerpt("memory:$profile:${row.uuid}", "memory", row.content,
                        JSONObject().put("profile", profile).put("uuid", row.uuid).put("at", row.updatedAt.time))
                }
            }
        }
        val reading = ReadingRoom(context, role)
        val first = reading.entryPage("history")
        val total = first.optLong("total")
        if (total > 0) {
            val offset = random.nextLong(total.coerceAtMost(Int.MAX_VALUE.toLong())).toInt()
            val page = if (offset == 0) first else reading.entryPage("history", offset)
            page.getJSONArray("items").objects().shuffled(random).take(3).forEach { row ->
                val full = reading.entry(row.getString("id"))
                val field = listOf("body", "summary", "note").filter { full.opt(it) is String && full.getString(it).isNotBlank() }.shuffled(random).firstOrNull()
                if (field != null) result += DreamDesign.excerpt("reading:${row.getString("id")}:$field", "reading", full.getString(field),
                    JSONObject().put("role", role).put("id", row.getString("id")).put("field", field))
            }
        }
        return result.filter { it.getString("text").isNotBlank() }.distinctBy { it.getString("id") }.shuffled(random)
    }
}
