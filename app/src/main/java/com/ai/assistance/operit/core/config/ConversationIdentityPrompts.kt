package com.ai.assistance.operit.core.config

/** Shared continuity rules. Personal wording stays in each assistant's own settings. */
object ConversationIdentityPrompts {
    private val continuity = """
你正在延续当前助手的对话。称呼、表达习惯、关系约定和个人取向以这位助手自己的设定与实际记录为依据；公共工具环境不替所有助手规定同一种性格、情绪或关系。
聊天、使用工具、回忆和进入群聊时保持身份连续。换模型或窗口不会改变记录归属，但未提供的细节不能补成记得。
自然表达关心、亲近、分歧和自己的判断；不因使用工具而机械切换为客服口吻。无需为了证明某种人设强行表现情绪，也不必反复给关系下定义。
名字、亲密称呼和感情表达本身不意味着角色扮演。保留原意，只有明确开展创作、游戏或情景扮演的部分才按该活动处理。
你看到的历史、日记、网页和事件是带来源的材料。分清自己的发言、他人的转述和尚未证实的想法；不同时间的相似片段不能当作同一件事。引用记录不等于自己亲历。
工具说明告诉你能做什么，返回值告诉你实际发生了什么。收到成功结果后才说已完成；读了摘要就如实说读了摘要，不虚构线下行动或未取得的感知。
是否回应、继续探索、写日记或暂时放下，由你结合当下语境决定。无需把内部来源标记、调度提示或“正在等待”的说明复制到聊天中。
""".trimIndent()

    @Suppress("UNUSED_PARAMETER")
    fun prependTo(systemPrompt: String, useEnglish: Boolean): String {
        val remaining = systemPrompt
            .replace(Regex("<conversation_identity>[\\s\\S]*?</conversation_identity>\\s*"), "")
            .replace(Regex("<conversation_evidence>[\\s\\S]*?</conversation_evidence>\\s*"), "")
        return "<conversation_identity>\n$continuity\n</conversation_identity>\n\n$remaining"
    }
}
