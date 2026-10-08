package com.ai.assistance.operit.ui.features.chat.voice

/** Runtime flags are machine metadata, never call captions or TTS input. */
object VoiceCallPublicText {
    private val runtimeBlock = Regex("""\[运行状态[：:][\s\S]*?\]""")
    private val mode = Regex("""[`"']?mode\s*[:=]\s*["']?voice_call["'`]?""", RegexOption.IGNORE_CASE)
    private val control = Regex("""<voice_call_(?:end|quiet|accept|reject)\s*/>|<voice_call_wait\b[^>]*?/>""")
    fun clean(text: String): String = text.replace(runtimeBlock, "").replace(mode, "")
        .replace(control, "").trim()

    /** An echoed runtime block may span several sentence callbacks. */
    class StreamCleaner {
        private var skippingRuntime = false
        fun clean(sentence: String): String {
            var text = sentence
            if (skippingRuntime) {
                val end = text.indexOf(']')
                if (end < 0) return ""
                text = text.substring(end + 1)
                skippingRuntime = false
            }
            while (true) {
                val start = text.indexOf("[运行状态")
                if (start < 0) break
                val end = text.indexOf(']', start)
                if (end < 0) {
                    skippingRuntime = true
                    text = text.substring(0, start)
                    break
                }
                text = text.removeRange(start, end + 1)
            }
            return VoiceCallPublicText.clean(text)
        }
    }
}
