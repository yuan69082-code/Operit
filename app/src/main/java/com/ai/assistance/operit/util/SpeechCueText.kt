package com.ai.assistance.operit.util

import java.util.Locale
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.stream

/** Remove speech directions before Markdown consumes their brackets and exposes their text. */
object SpeechCueText {
    private val cues = setOf(
        "calm", "pause", "short pause", "long pause", "quiet", "quietly",
        "softly", "continues softly", "conversational tone", "slow down",
        "whisper", "whispers", "whispering", "soft chuckle", "chuckle", "chuckles",
        "laugh", "laughs", "laughing", "sigh", "sighs", "sighing", "breath",
        "breathes", "shaky breath", "deep breath", "inhale", "exhale",
        "playful", "warm", "gentle", "serious", "sad", "happy", "excited",
    )
    private val whitespace = Regex("\\s+")

    private fun isCue(candidate: String): Boolean =
        candidate.removePrefix("[").removeSuffix("]").trim()
            .lowercase(Locale.ROOT).replace(whitespace, " ") in cues

    fun clean(text: String): String {
        val cleaner = StreamingCleaner()
        return cleaner.append(text) + cleaner.finish()
    }

    fun cleanStream(source: Stream<String>): Stream<String> = stream {
        val cleaner = StreamingCleaner()
        source.collect { piece ->
            val text = cleaner.append(piece)
            if (text.isNotEmpty()) emit(text)
        }
        val tail = cleaner.finish()
        if (tail.isNotEmpty()) emit(tail)
    }

    /** Buffers only a bracket candidate and lookahead, never an entire reply. */
    class StreamingCleaner {
        private val candidate = StringBuilder()
        private var closedCandidate = false
        private var backtickRun = 0
        private var codeDelimiter = 0
        private var escaped = false

        fun append(piece: String): String = buildString {
            piece.forEach { char ->
                // A cue-named Markdown link such as [pause](url) is real content.
                if (closedCandidate) {
                    if (char == '(' || !isCue(candidate.toString())) append(candidate)
                    candidate.clear()
                    closedCandidate = false
                }
                if (candidate.isNotEmpty()) {
                    candidate.append(char)
                    if (char == ']') closedCandidate = true
                    else if (char == '\n' || candidate.length > 96) {
                        append(candidate)
                        candidate.clear()
                    }
                } else if (char == '`' && !escaped) {
                    backtickRun++
                    append(char)
                } else {
                    if (backtickRun > 0) {
                        codeDelimiter = when {
                            codeDelimiter == 0 -> backtickRun
                            codeDelimiter == backtickRun -> 0
                            else -> codeDelimiter
                        }
                        backtickRun = 0
                    }
                    if (char == '[' && codeDelimiter == 0 && !escaped) {
                        candidate.append(char)
                    } else {
                        append(char)
                    }
                    escaped = char == '\\' && !escaped
                }
            }
        }

        fun finish(): String {
            val tail = if (closedCandidate && isCue(candidate.toString())) "" else candidate.toString()
            candidate.clear()
            closedCandidate = false
            return tail
        }
    }
}
