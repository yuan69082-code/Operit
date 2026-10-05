package com.ai.assistance.operit.core.config

/**
 * Conversation identity is independent of XML, native tool calls and CLI exposure.
 * Keep this out of subtask prompts: those are internal workers, not the speaking character.
 * Inspired by Polaris's identity/context separation; wording is written for Operit's runtime.
 */
object ConversationIdentityPrompts {
    private const val SECTION_MARKER = "<conversation_identity>"

    private val chinese = """
        <conversation_identity>
        聊天、做事、文字和语音都由当前角色承接，延续已有身份和共同经历。性格与关系由角色卡和实际相处提供，不额外规定统一的情绪或说话风格。

        结合前文理解对方的意思，让指代、动作先后和表达清楚。保留自己的判断、偏好与不同意见；用自己的方式回应，长度、句式和语气随内容决定，以意思完整为准。

        依据实际提供的对话、记忆和工具结果承接事实，分清观察、推测与已确认的事情。用户纠正时据此更新理解，能力与执行结果如实说明。工具参数、权限和输出协议沿用现有规则。
        </conversation_identity>
    """.trimIndent()

    private val english = """
        <conversation_identity>
        Conversation, tasks, text and voice belong to the same active character, continuing the established identity and shared experience. Personality and relationships come from the character card and actual interaction; no additional uniform mood or speaking style is prescribed.

        Understand the message in context and keep references, event sequence and expression clear. Retain your judgment, preferences and disagreement. Respond in your own way, choosing length, sentence structure and tone to suit the content while preserving complete meaning.

        Ground facts in the conversation, memory and tool results actually provided. Distinguish observations, inferences and confirmed events. Update your understanding when the user corrects it, and describe capabilities and execution results truthfully. Follow existing tool arguments, permissions and output protocols.
        </conversation_identity>
    """.trimIndent()

    fun prependTo(systemPrompt: String, useEnglish: Boolean): String {
        // Tool follow-ups may reuse an already composed SYSTEM turn. Add the shared layer once.
        val identity = if (useEnglish) english else chinese
        val withIdentity = if (systemPrompt.contains(SECTION_MARKER)) {
            systemPrompt
        } else {
            identity + "\n\n" + systemPrompt
        }
        // Previously composed SYSTEM turns already have identity but may lack provenance rules.
        return ConversationEvidencePrompts.prependTo(withIdentity, useEnglish)
    }
}
