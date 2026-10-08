package com.ai.assistance.operit.util

/** Display adapter only. Persisted acoustic context remains available to the AI and summaries. */
object VoiceCallMessageText {
    private val analysisSection = Regex("""【(?:疑似听词|声音|停顿|语气|语调|情绪变化|情绪走向|环境音|环境|说话者|声源判断|旁人声音|说话片段|言外之意|语音文件)】""")
    private val media = Regex("""<link\s+type=["'](?:image|video|audio)["'][^>]*>[\s\S]*?</link>""")

    fun forDisplay(content: String): String {
        val text = content.trimStart()
        if (text.startsWith("[语音通话转写]")) {
            val body = text.removePrefix("[语音通话转写]").trim()
            val original = if (body.startsWith("【原话】")) body.removePrefix("【原话】").trim() else body
            val end = analysisSection.find(original)?.range?.first ?: original.length
            return "[语音通话]\n" + original.substring(0, end).replace(media, "").trim()
        }
        if (text.startsWith("[语音通话音频")) {
            return if (text.contains("转写失败")) "[语音通话]\n转写失败，录音已保存" else "[语音通话]\n已发送语音"
        }
        if (text.startsWith("[通话事件]")) return "[语音通话]\n已接通"
        return content
    }
}
