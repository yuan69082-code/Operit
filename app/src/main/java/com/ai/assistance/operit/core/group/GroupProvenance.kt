package com.ai.assistance.operit.core.group

import android.content.Context
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import org.json.JSONObject

/** Immutable author IDs survive renames and equal display names. Old unknown authors stay unknown. */
object GroupProvenance {
    fun record(context: Context, chat: String, message: ChatMessage, actor: String) {
        GroupStore(context).update("authors:$chat") { map ->
            map.put(message.timestamp.toString(), JSONObject().put("id", actor).put("name", message.roleName))
        }
    }

    fun author(context: Context, chat: String, message: ChatMessage): String? =
        GroupStore(context).read("authors:$chat").optJSONObject(message.timestamp.toString())?.optString("id")

    fun label(context: Context, chat: String, message: ChatMessage): String {
        if (message.sender == "user") return "用户"
        if (message.sender == "summary") return "群聊公共纪要（分别归属各发言者）"
        val source = GroupStore(context).read("authors:$chat").optJSONObject(message.timestamp.toString())
        return if (source != null) "${source.optString("name")} [助手ID=${source.optString("id")}]"
            else "${message.roleName} [历史发言，助手ID未记录，不推定为当前助手]"
    }

    suspend fun forRequest(context: Context, chat: String, actor: String?, messages: List<ChatMessage>): List<ChatMessage> {
        val name = actor?.let { CharacterCardManager.getInstance(context).getCharacterCard(it).name }
        return GroupContext.publicGameHistory(messages).map { msg ->
            if (msg.sender == "ai") {
                val own = GroupPolicy.ownsTurn(actor, author(context, chat, msg))
                msg.copy(speakerIsCurrentAssistant = own, roleName = if (own) name.orEmpty() else label(context, chat, msg),
                    content = GroupTurnPolicy.cleanLegacyPrefix(msg.content))
            } else if (msg.sender == "summary") msg.copy(content = "[群聊纪要：其中的我只能指对应署名者，不代表当前阅读者。来源不明就保持不明。]\n${msg.content}")
            else msg
        }
    }

    fun forSummary(context: Context, chat: String, messages: List<ChatMessage>): List<ChatMessage> =
        GroupContext.publicGameHistory(messages).map { it.copy(roleName = label(context, chat, it)) }

    fun summaryInstructions(base: String): String = base.lineSequence().filterNot {
        it.contains("第一人称") || it.contains("first-person") || it.contains("Narrate as the conversation AI")
    }.joinToString("\n") + "\n\n" + SUMMARY_RULE

    const val SUMMARY_RULE = "这是多位独立助手的群聊公共纪要。按发言者姓名和助手ID分别记录言论、看法、行为和经历，不使用未署名的‘我’或‘我们’，不能把A的话记成B说的。阅读他人记录属于观察，不是亲历。保留原始时间和来源；来源未知明确标注未知。游戏身份与事件必须标为本局游戏，不改变日常身份。"
}
