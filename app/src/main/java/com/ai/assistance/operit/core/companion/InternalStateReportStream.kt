package com.ai.assistance.operit.core.companion

import com.squareup.moshi.JsonReader
import okio.Buffer
import org.json.JSONObject

/**
 * Removes a leading state report before text reaches reply history, UI, speech, or tool detection.
 * This class only stages a report. The owner decides whether a successful business turn may commit it.
 *
 * Ordinary text, including provider thinking and Markdown code examples, is preserved unchanged.
 * A report is accepted only before public prose, outside those quoted regions.
 * Calls, including provider savepoint/rollback events, must be serialized by the stream's owner.
 */
class InternalStateReportStream(private val maxReportChars: Int = 16_384) {
    enum class ErrorCode {
        MALFORMED_REPORT_TAG,
        LATE_REPORT,
        DUPLICATE_REPORT,
        TRUNCATED_REPORT,
        REPORT_TOO_LARGE,
        INVALID_JSON,
        STREAM_FINISHED
    }

    /** No report text or underlying JSON exception is retained in an externally logged exception. */
    class ProtocolException(val code: ErrorCode) :
        IllegalArgumentException("Internal state report protocol: ${code.name}")

    private enum class Mode { TEXT, THINKING, REPORT, CODE_DELIMITER, INLINE_CODE, FENCED_CODE }

    private data class Snapshot(
        val mode: Mode,
        val textProbe: String,
        val thinkingEnd: String,
        val thinkingProbe: String,
        val payload: String,
        val closingProbe: String,
        val inJsonString: Boolean,
        val jsonEscaped: Boolean,
        val reportJson: String?,
        val bodyStarted: Boolean,
        val finished: Boolean,
        val failure: ErrorCode?,
        val lineIndent: Int,
        val textEscaped: Boolean,
        val codeChar: Char,
        val codeRun: Int,
        val codeAtLineStart: Boolean,
        val inlineClosingRun: Int,
        val fenceClosingRun: Int,
        val fenceClosingWhitespace: Boolean
    )

    private data class Checkpoint(val sequence: Long, val snapshot: Snapshot)

    private var mode = Mode.TEXT
    private val textProbe = StringBuilder()
    private var thinkingEnd = ""
    private val thinkingProbe = StringBuilder()
    private val payload = StringBuilder()
    private val closingProbe = StringBuilder()
    private var inJsonString = false
    private var jsonEscaped = false
    private var reportJson: String? = null
    private var bodyStarted = false
    private var finished = false
    private var failure: ErrorCode? = null
    private var lineIndent = 0
    private var textEscaped = false
    private var codeChar = '`'
    private var codeRun = 0
    private var codeAtLineStart = false
    private var inlineClosingRun = 0
    private var fenceClosingRun = 0
    private var fenceClosingWhitespace = false
    private var checkpointSequence = 0L
    private val checkpoints = linkedMapOf<String, Checkpoint>()

    init {
        require(maxReportChars > 0) { "maxReportChars must be positive" }
    }

    /** A defensive copy, available only after a complete, valid JSON object and its closing tag. */
    val report: JSONObject?
        get() = if (failure == null) reportJson?.let(::JSONObject) else null

    fun append(chunk: String): String {
        requireUsable()
        val output = StringBuilder()
        chunk.forEach { consume(it, output) }
        return output.toString()
    }

    /**
     * A provider may retry exceptions thrown by its text callback. Keep a local protocol failure
     * latched instead: no further private text is published, and [finish] throws outside that
     * retry boundary. Only a real provider [rollback] can restore an earlier valid parser state.
     */
    fun appendDeferringFailure(chunk: String): String = try {
        append(chunk)
    } catch (_: ProtocolException) {
        ""
    }

    /** Flushes non-report lookahead. An absent report is represented by [report] remaining null. */
    fun finish(): String {
        failure?.let { throw ProtocolException(it) }
        if (finished) return ""
        val output = StringBuilder()
        when (mode) {
            Mode.REPORT -> fail(ErrorCode.TRUNCATED_REPORT)
            Mode.TEXT -> {
                val pending = textProbe.toString()
                if (pending.length > 1 &&
                    (OPEN_REPORT.startsWith(pending, ignoreCase = true) ||
                        CLOSE_REPORT.startsWith(pending, ignoreCase = true))) {
                    fail(ErrorCode.TRUNCATED_REPORT)
                }
                emit(pending, output)
                textProbe.clear()
            }
            Mode.THINKING -> {
                emit(thinkingProbe.toString(), output)
                thinkingProbe.clear()
            }
            Mode.CODE_DELIMITER, Mode.INLINE_CODE, Mode.FENCED_CODE -> Unit
        }
        finished = true
        return output.toString()
    }

    fun savepoint(id: String) {
        requireUsable()
        checkpoints[id] = Checkpoint(++checkpointSequence, snapshot())
    }

    /** Restores staged state as well as text parsing. The owner separately rolls back public text. */
    fun rollback(id: String): Boolean {
        val checkpoint = checkpoints[id] ?: return false
        restore(checkpoint.snapshot)
        checkpoints.entries.removeAll { it.value.sequence > checkpoint.sequence }
        return true
    }

    private fun consume(char: Char, output: StringBuilder) {
        when (mode) {
            Mode.TEXT -> consumeText(char, output)
            Mode.THINKING -> consumeThinking(char, output)
            Mode.REPORT -> consumeReport(char)
            Mode.CODE_DELIMITER -> consumeCodeDelimiter(char, output)
            Mode.INLINE_CODE -> consumeInlineCode(char, output)
            Mode.FENCED_CODE -> consumeFencedCode(char, output)
        }
    }

    private fun consumeText(char: Char, output: StringBuilder) {
        if (textProbe.isNotEmpty()) {
            textProbe.append(char)
            val token = textProbe.toString()
            when (token) {
                OPEN_REPORT -> {
                    if (reportJson != null) fail(ErrorCode.DUPLICATE_REPORT)
                    if (bodyStarted) fail(ErrorCode.LATE_REPORT)
                    textProbe.clear()
                    mode = Mode.REPORT
                    return
                }
                CLOSE_REPORT -> fail(ErrorCode.MALFORMED_REPORT_TAG)
                OPEN_THINK, OPEN_THINKING -> {
                    thinkingEnd = if (token == OPEN_THINK) CLOSE_THINK else CLOSE_THINKING
                    emit(token, output)
                    textProbe.clear()
                    mode = Mode.THINKING
                    return
                }
            }
            if (token.equals(OPEN_REPORT, ignoreCase = true) ||
                token.equals(CLOSE_REPORT, ignoreCase = true)) {
                fail(ErrorCode.MALFORMED_REPORT_TAG)
            }
            if (TEXT_TOKENS.any { it.startsWith(token) } ||
                OPEN_REPORT.startsWith(token, ignoreCase = true) ||
                CLOSE_REPORT.startsWith(token, ignoreCase = true)) return
            if (token.startsWith(OPEN_REPORT_STEM, ignoreCase = true) ||
                token.startsWith(CLOSE_REPORT_STEM, ignoreCase = true)) {
                fail(ErrorCode.MALFORMED_REPORT_TAG)
            }
            // Reprocess the unmatched suffix: it may itself begin a tag or a code delimiter.
            textProbe.clear()
            emitProse(token.first(), output)
            token.drop(1).forEach { consume(it, output) }
            return
        }

        if (textEscaped) {
            textEscaped = false
            if (char == '`' || char == '~' || char == '\\') {
                emitProse(char, output)
                return
            }
        }
        when {
            char == '<' -> textProbe.append(char)
            char == '`' || (char == '~' && lineIndent in 0..3) -> {
                codeChar = char
                codeRun = 1
                codeAtLineStart = lineIndent in 0..3
                mode = Mode.CODE_DELIMITER
                emitProse(char, output)
            }
            else -> {
                if (char == '\\') textEscaped = true
                emitProse(char, output)
            }
        }
    }

    private fun consumeThinking(char: Char, output: StringBuilder) {
        if (thinkingProbe.isEmpty() && char != '<') {
            emit(char, output)
            return
        }
        thinkingProbe.append(char)
        val token = thinkingProbe.toString()
        if (token == thinkingEnd) {
            emit(token, output)
            thinkingProbe.clear()
            mode = Mode.TEXT
        } else if (!thinkingEnd.startsWith(token)) {
            thinkingProbe.clear()
            emit(token.first(), output)
            token.drop(1).forEach { consumeThinking(it, output) }
        }
    }

    private fun consumeReport(char: Char) {
        // Closing markup inside a JSON string is private data, never a framing boundary.
        if (closingProbe.isNotEmpty() || (!inJsonString && char == '<')) {
            closingProbe.append(char)
            val token = closingProbe.toString()
            if (token == CLOSE_REPORT) {
                reportJson = parseReport()
                payload.clear()
                closingProbe.clear()
                mode = Mode.TEXT
            } else if (!CLOSE_REPORT.startsWith(token)) {
                fail(ErrorCode.MALFORMED_REPORT_TAG)
            }
            return
        }
        if (payload.length >= maxReportChars) fail(ErrorCode.REPORT_TOO_LARGE)
        payload.append(char)
        if (inJsonString) {
            if (jsonEscaped) jsonEscaped = false
            else if (char == '\\') jsonEscaped = true
            else if (char == '"') inJsonString = false
        } else if (char == '"') {
            inJsonString = true
        }
    }

    private fun parseReport(): String {
        try {
            val json = payload.toString()
            // org.json accepts non-JSON syntax on Android; validate strict framing before exposing it.
            JsonReader.of(Buffer().writeUtf8(json)).use { reader ->
                reader.isLenient = false
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) fail(ErrorCode.INVALID_JSON)
                reader.readJsonValue()
                if (reader.peek() != JsonReader.Token.END_DOCUMENT) fail(ErrorCode.INVALID_JSON)
            }
            return JSONObject(json).toString()
        } catch (_: Exception) {
            fail(ErrorCode.INVALID_JSON)
        }
    }

    private fun consumeCodeDelimiter(char: Char, output: StringBuilder) {
        if (char == codeChar) {
            codeRun++
            emit(char, output)
            return
        }
        mode = when {
            codeAtLineStart && codeRun >= 3 -> Mode.FENCED_CODE
            codeChar == '`' -> Mode.INLINE_CODE
            else -> Mode.TEXT
        }
        inlineClosingRun = 0
        fenceClosingRun = 0
        fenceClosingWhitespace = false
        consume(char, output)
    }

    private fun consumeInlineCode(char: Char, output: StringBuilder) {
        if (char == '`') {
            inlineClosingRun++
            emit(char, output)
        } else if (inlineClosingRun == codeRun) {
            inlineClosingRun = 0
            mode = Mode.TEXT
            consumeText(char, output)
        } else {
            inlineClosingRun = 0
            emit(char, output)
        }
    }

    private fun consumeFencedCode(char: Char, output: StringBuilder) {
        if (char == '\n' || char == '\r') {
            if (fenceClosingRun >= codeRun) mode = Mode.TEXT
            fenceClosingRun = 0
            fenceClosingWhitespace = false
        } else if (fenceClosingRun > 0) {
            when {
                char == codeChar && !fenceClosingWhitespace -> fenceClosingRun++
                (char == ' ' || char == '\t') && fenceClosingRun >= codeRun ->
                    fenceClosingWhitespace = true
                else -> {
                    fenceClosingRun = 0
                    fenceClosingWhitespace = false
                }
            }
        } else if (lineIndent in 0..3 && char == codeChar) {
            fenceClosingRun = 1
        }
        emit(char, output)
    }

    private fun emitProse(char: Char, output: StringBuilder) {
        if (!char.isWhitespace()) bodyStarted = true
        emit(char, output)
    }

    private fun emit(text: String, output: StringBuilder) = text.forEach { emit(it, output) }

    private fun emit(char: Char, output: StringBuilder) {
        output.append(char)
        lineIndent = when {
            char == '\n' || char == '\r' -> 0
            char == ' ' && lineIndent >= 0 -> (lineIndent + 1).coerceAtMost(4)
            char == '\t' && lineIndent >= 0 -> 4
            else -> -1
        }
    }

    private fun requireUsable() {
        failure?.let { throw ProtocolException(it) }
        if (finished) fail(ErrorCode.STREAM_FINISHED)
    }

    private fun fail(code: ErrorCode): Nothing {
        failure = code
        throw ProtocolException(code)
    }

    private fun snapshot() = Snapshot(
        mode, textProbe.toString(), thinkingEnd, thinkingProbe.toString(), payload.toString(),
        closingProbe.toString(), inJsonString, jsonEscaped, reportJson, bodyStarted, finished,
        failure, lineIndent, textEscaped, codeChar, codeRun, codeAtLineStart, inlineClosingRun,
        fenceClosingRun, fenceClosingWhitespace
    )

    private fun restore(snapshot: Snapshot) {
        mode = snapshot.mode
        textProbe.clear().append(snapshot.textProbe)
        thinkingEnd = snapshot.thinkingEnd
        thinkingProbe.clear().append(snapshot.thinkingProbe)
        payload.clear().append(snapshot.payload)
        closingProbe.clear().append(snapshot.closingProbe)
        inJsonString = snapshot.inJsonString
        jsonEscaped = snapshot.jsonEscaped
        reportJson = snapshot.reportJson
        bodyStarted = snapshot.bodyStarted
        finished = snapshot.finished
        failure = snapshot.failure
        lineIndent = snapshot.lineIndent
        textEscaped = snapshot.textEscaped
        codeChar = snapshot.codeChar
        codeRun = snapshot.codeRun
        codeAtLineStart = snapshot.codeAtLineStart
        inlineClosingRun = snapshot.inlineClosingRun
        fenceClosingRun = snapshot.fenceClosingRun
        fenceClosingWhitespace = snapshot.fenceClosingWhitespace
    }

    private companion object {
        const val OPEN_REPORT_STEM = "<operit_state_report"
        const val CLOSE_REPORT_STEM = "</operit_state_report"
        const val OPEN_REPORT = "$OPEN_REPORT_STEM>"
        const val CLOSE_REPORT = "$CLOSE_REPORT_STEM>"
        const val OPEN_THINK = "<think>"
        const val CLOSE_THINK = "</think>"
        const val OPEN_THINKING = "<thinking>"
        const val CLOSE_THINKING = "</thinking>"
        val TEXT_TOKENS = listOf(OPEN_REPORT, CLOSE_REPORT, OPEN_THINK, OPEN_THINKING)
    }
}
