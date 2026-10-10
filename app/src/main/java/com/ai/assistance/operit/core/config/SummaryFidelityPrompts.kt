package com.ai.assistance.operit.core.config

/** Applied to both drafting and review, including when the user customizes summary sections. */
internal object SummaryFidelityPrompts {
    fun forLanguage(useEnglish: Boolean): String = if (useEnglish) EN else ZH

    const val ZH = """
        【摘要忠实性检查】
        记录交流内容，不给整段交流另起情境标签。名字、亲昵称呼、情绪表达、关系约定、AI 身份或软件中的角色卡，都不能作为“角色扮演”“剧情”“虚构关系”“人设互动”等定性的依据。
        只有原文明确要求创作、表演或角色扮演时，才能在对应的创作内容范围内这样记录，并说明是谁提出的；不要把局部创作扩展为整段交流的性质。
        旧摘要及草稿是待核对的记录，不是上述定性的原始证据。若这类标签没有原文依据，删除标签，直接写双方实际谈论的话题、表达与约定，不替双方重新定义关系。原文明确纠正的内容优先于旧摘要；保留纠正，不继续沿用被否认的标签。
        从本次对话中 AI 的第一人称“我”写，用户沿用原文名字或“她”；分清我表达的、她表达的、已确认的与未确定的，不把一方的要求写成双方已经同意。
        审阅时必须检查以上项目。发现未经原文支持的情境或关系定性时，返回 revise 及去掉该定性的完整摘要，不能只因为草稿沿用了旧摘要就 approve。
    """

    const val EN = """
        [Summary fidelity check]
        Record the exchange without assigning it a new scenario label. Names, affectionate forms of address, emotions, relationship agreements, AI identity, or the app's character cards are not evidence of roleplay, a fictional relationship, a plot, or persona acting.
        Only label content as creative writing, acting, or roleplay when the source explicitly requests it; attribute that request and keep the label scoped to that content, not the entire exchange.
        Previous summaries and drafts are records to verify, not original evidence for such labels. Remove unsupported labels and describe the actual topics, expressions, and agreements without redefining the participants' relationship. Explicit source corrections take precedence over old summaries; preserve the correction instead of perpetuating the rejected label.
        Narrate as the conversation AI using I; use the user's established name or she. Distinguish my statements from hers, confirmed facts from uncertainty, and one participant's demands from mutual agreements.
        Review must check these points. If the draft imposes an unsupported scenario or relationship label, return revise with a complete corrected summary; inheriting a label from a previous summary is not a reason to approve it.
    """
}
