package com.ai.assistance.operit.integrations.http

import android.content.Context
import com.ai.assistance.operit.core.companion.*
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.StringReader

/** The authenticated web client reads the phone's authority, never a desktop state copy.
 * UI reads do not register model-read eligibility, advance time, or request a model. */
internal class WebPrivateStateBridge(private val context: Context) {
    suspend fun handle(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val chat = body.getString("chat_id")
        val metadata = requireNotNull(ChatHistoryManager.getInstance(context).getChatMetadata(chat)) { "会话不存在" }
        require(metadata.characterGroupId.isNullOrBlank()) { "请在本助手私聊中进入私域，群聊不能读取私域" }
        val role = requireNotNull(metadata.characterCardId) { "此会话尚未绑定助手" }
        val card = requireNotNull(CharacterCardManager.getInstance(context).getCharacterCard(role)) { "助手不存在" }
        val store = InternalStateStore(context)
        store.requireRecipient(role, chat)
        when (body.getString("action")) {
            "status" -> JSONObject().put("owner", role).put("name", card.name).put("enabled", store.enabled(role))
                .put("window", store.establishedStatus(role))
            "open_window" -> store.openEstablishedWindow(role)
            "page" -> {
                val kind = body.getString("kind")
                // Existing private dream text and companion_note retain their old audience.
                // The web UI exposes only the new explicitly user-visible waking notes.
                require(kind in setOf("state", "inner", "inner_family", "inner_pending", "episode", "episode_category",
                    "thought", "focus", "experience_link", "union", "category_versions", "source", "action", "action_versions",
                    "decision", "feedback", "rest", "activity", "activity_part", "waking_note")) { "此材料不属于用户可见的私域页面" }
                val requestedId = body.optString("id")
                if (kind == "category_versions") require(!requestedId.startsWith("dream:") && !requestedId.startsWith("dream_understanding:"))
                val id = body.optString("id").takeIf { it.isNotBlank() }
                if (kind == "state") {
                    val observation = InternalStateEngine.observation(store.snapshot(role, chat, false), chat, System.currentTimeMillis(), body.optBoolean("reveal"))
                    val visible = InternalStateEngine.rows(observation, "states")
                    val offset = body.optInt("offset", 0).also { require(it >= 0) }
                    return@withContext if (id != null) JSONObject().put("item", visible.firstOrNull { it.getString("id") == id } ?: JSONObject.NULL)
                    else JSONObject().put("items", org.json.JSONArray(visible.drop(offset).take(8)))
                        .put("next_offset", if (visible.size > offset + 8) offset + 8 else JSONObject.NULL)
                }
                val result = store.inspectCategory(role, chat, kind, id, body.optInt("offset", 0))
                // Bind edits to exact full candidate content, just like the native editor.
                if (kind == "inner_pending") {
                    result.optJSONObject("item")?.let { result.put("candidate_hash", InnerStructure.hash(it)) }
                }
                result
            }
            "submit" -> store.submitEstablishedContent(role, chat, "web:" + InnerStructure.hash(JSONObject(body.toString()).apply { remove("chat_id") }), body.getJSONObject("record"),
                body.optString("original").takeIf { it.isNotBlank() }, body.optString("source").takeIf { it.isNotBlank() },
                if (body.has("formed_at") && !body.isNull("formed_at")) body.getLong("formed_at") else null,
                body.optString("replaces").takeIf { it.isNotBlank() }, body.optString("previous_hash").takeIf { it.isNotBlank() },
                if (body.has("expected_target_version")) body.getInt("expected_target_version") else null)
            "withdraw" -> store.withdrawCandidate(role, chat, body.getString("id"), body.getString("candidate_hash"))
            "import" -> JSONObject().put("submitted", InnerContentImport.read(StringReader(body.getString("content")), store, role, chat,
                body.optString("original").takeIf { it.isNotBlank() }, body.optString("source").takeIf { it.isNotBlank() },
                currentSubmission = body.optString("source_mode") == "new") { })
            else -> throw IllegalArgumentException("不支持的私域操作")
        }
    }
}
