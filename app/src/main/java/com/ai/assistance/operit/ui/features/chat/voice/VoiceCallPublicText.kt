package com.ai.assistance.operit.ui.features.chat.voice

/** Runtime flags are machine metadata, never call captions or TTS input. */
object VoiceCallPublicText {
    private val runtimeBlock = Regex("""\[运行状态[：:][\s\S]*?\]""")
    private val softwareBlock = Regex("""<operit_state>[\s\S]*?</operit_state>""")
    private val mode = Regex("""[`"']?mode\s*[:=]\s*["']?voice_call["'`]?""", RegexOption.IGNORE_CASE)
    private val control = Regex("""<voice_call_(?:end|quiet|accept|reject)\s*/>|<voice_call_wait\b[^>]*?/>""")
    fun clean(text: String): String = text.replace(softwareBlock, "").replace(runtimeBlock, "").replace(mode, "")
        .replace(control, "").trim()

    /** An echoed runtime block may span several sentence callbacks. */
    class StreamCleaner {
        private var skippingRuntime = false
        private var skippingSoftware = false
        fun clean(sentence: String): String {
            var text = sentence
            if (skippingSoftware) {
                val end = text.indexOf("</operit_state>")
                if (end < 0) return ""
                text = text.substring(end + "</operit_state>".length)
                skippingSoftware = false
            }
            while (true) {
                val start = text.indexOf("<operit_state>")
                if (start < 0) break
                val end = text.indexOf("</operit_state>", start)
                if (end < 0) {
                    skippingSoftware = true
                    text = text.substring(0, start)
                    break
                }
                text = text.removeRange(start, end + "</operit_state>".length)
            }
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
