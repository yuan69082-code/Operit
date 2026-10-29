package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternalStateReportStreamTest {
    private val open = "<operit_state_report>"
    private val close = "</operit_state_report>"
    private val valid = """{"frame_id":"host-nonce","updates":[],"create":[]}"""

    private fun framed(json: String = valid) = open + json + close

    private fun collect(parser: InternalStateReportStream, chunks: List<String>): String =
        buildString {
            chunks.forEach { append(parser.append(it)) }
            append(parser.finish())
        }

    private fun error(
        code: InternalStateReportStream.ErrorCode,
        action: () -> Unit
    ): InternalStateReportStream.ProtocolException {
        val failure = assertThrows(InternalStateReportStream.ProtocolException::class.java) { action() }
        assertEquals(code, failure.code)
        assertNull(failure.cause)
        assertEquals("Internal state report protocol: ${code.name}", failure.message)
        return failure
    }

    @Test fun reportAndProseAreSeparatedAtEveryPossibleChunkBoundary() {
        val text = framed() + "你好。🙂\n<companion_quiet/>"
        for (split in 0..text.length) {
            val parser = InternalStateReportStream()
            val output = collect(parser, listOf(text.take(split), text.drop(split)))
            assertEquals("split=$split", "你好。🙂\n<companion_quiet/>", output)
            assertEquals("host-nonce", parser.report!!.getString("frame_id"))
        }
    }

    @Test fun singleCharacterChunksNeverPublishReportFragments() {
        val parser = InternalStateReportStream()
        framed().forEach { char -> assertEquals("", parser.append(char.toString())) }
        assertEquals("host-nonce", parser.report!!.getString("frame_id"))
        assertEquals("公开回复", collect(parser, listOf("公", "开", "回", "复")))
    }

    @Test fun reportIsUnavailableUntilBothJsonAndClosingTagAreComplete() {
        val parser = InternalStateReportStream()
        assertEquals("", parser.append(open + valid))
        assertNull(parser.report)
        assertEquals("", parser.append(close.dropLast(1)))
        assertNull(parser.report)
        assertEquals("正文", parser.append(close.takeLast(1) + "正文"))
        assertNotNull(parser.report)
        assertEquals("", parser.finish())
    }

    @Test fun ordinaryTextAndItsFirstCharacterArePreservedWithoutInventingAReport() {
        val examples = listOf(
            "", "a", "你好", " ", "\n\tHi", "<", "x < y", "<ordinary>正文</ordinary>",
            "<tool name=\"example\"><param>text</param></tool>", "<companion_quiet/>",
            "price ~5, then text", "\\`escaped delimiter <normal/>"
        )
        examples.forEach { text ->
            val parser = InternalStateReportStream()
            assertEquals(text, collect(parser, text.map(Char::toString)))
            assertNull(parser.report)
        }
    }

    @Test fun whitespaceAndProviderThinkingCanPrecedeTheActualReport() {
        for (tag in listOf("think", "thinking")) {
            val thinking = "  \n<$tag>考虑示例 ${framed()}，尚未作报告。</$tag>\n"
            val parser = InternalStateReportStream()
            assertEquals(thinking + "正文", collect(parser, (thinking + framed() + "正文").map(Char::toString)))
            assertEquals("host-nonce", parser.report!!.getString("frame_id"))
        }
    }

    @Test fun reportMarkersInThinkingAloneDoNotStageAnything() {
        val text = "<think>引用 ${framed()} 只属于思考</think>正文"
        val parser = InternalStateReportStream()
        assertEquals(text, collect(parser, text.map(Char::toString)))
        assertNull(parser.report)
    }

    @Test fun codeExamplesPreserveLiteralStateAndThinkingTags() {
        val examples = listOf(
            "`${framed()}` 后文",
            "``literal ` ${framed()} `` 后文",
            "```json\n${framed()}\n```\n后文",
            "  ~~~~xml\n<think>${framed()}</think>\n  ~~~~ \n后文",
            "````text\n```\n${framed()}\n````\n后文"
        )
        examples.forEach { text ->
            val parser = InternalStateReportStream()
            assertEquals(text, collect(parser, text.map(Char::toString)))
            assertNull(parser.report)
        }
    }

    @Test fun leavingCodeDoesNotAllowALateReport() {
        for (quoted in listOf("`example`", "```text\nexample\n```\n", "~~~\nexample\n~~~\n")) {
            val parser = InternalStateReportStream()
            assertEquals(quoted, parser.append(quoted))
            error(InternalStateReportStream.ErrorCode.LATE_REPORT) { parser.append(framed()) }
            assertNull(parser.report)
        }
    }

    @Test fun reportJsonStringsMayContainClosingTagsQuotesAndBackslashes() {
        val privateText = "before $close secret \\\"quoted\\\" path\\tail and $open"
        val json = JSONObject(valid)
            .put("updates", JSONArray().put(JSONObject().put("detail", privateText)))
            .toString().replace("\\/", "/")
        assertTrue(json.contains(close))
        val parser = InternalStateReportStream()
        assertEquals("正文", collect(parser, (framed(json) + "正文").map(Char::toString)))
        assertEquals(privateText, parser.report!!.getJSONArray("updates").getJSONObject(0).getString("detail"))
    }

    @Test fun malformedJsonIsRejectedWithoutExposingPayloadOrParserCause() {
        val invalid = listOf(
            "not-json", "[]", "null", "{} {}", "{\"secret\":}",
            "{secret:1}", "{'secret':1}", "{\"secret\":NaN}", "{/*private*/\"secret\":1}"
        )
        invalid.forEach { json ->
            val parser = InternalStateReportStream()
            error(InternalStateReportStream.ErrorCode.INVALID_JSON) { parser.append(framed(json)) }
            assertNull(parser.report)
        }
    }

    @Test fun truncationNeverFlushesBufferedReportDataAsProse() {
        val complete = framed()
        for (cut in 2 until complete.length) {
            val parser = InternalStateReportStream()
            assertEquals("", parser.append(complete.take(cut)))
            error(InternalStateReportStream.ErrorCode.TRUNCATED_REPORT) { parser.finish() }
            assertNull(parser.report)
        }
    }

    @Test fun anUnterminatedJsonStringContainingClosingMarkupRemainsPrivate() {
        val parser = InternalStateReportStream()
        assertEquals("", parser.append(open + "{\"detail\":\"$close secret tail"))
        error(InternalStateReportStream.ErrorCode.TRUNCATED_REPORT) { parser.finish() }
        assertNull(parser.report)
    }

    @Test fun malformedOpeningAndClosingTagsFailWithoutPublishingTheirContents() {
        val inputs = listOf(
            "<operit_state_report extra=\"private\">$valid$close",
            "<operit_state_report/>$valid$close",
            "<OPERIT_STATE_REPORT>$valid$close",
            "$open$valid</wrong>"
        )
        inputs.forEach { text ->
            val parser = InternalStateReportStream()
            val output = StringBuilder()
            error(InternalStateReportStream.ErrorCode.MALFORMED_REPORT_TAG) {
                text.forEach { output.append(parser.append(it.toString())) }
            }
            assertEquals("", output.toString())
            assertNull(parser.report)
        }
    }

    @Test fun duplicateAndLateReportsAreNotPublishedOrAvailableForCommit() {
        val duplicate = InternalStateReportStream()
        assertEquals("", duplicate.append(framed()))
        assertNotNull(duplicate.report)
        error(InternalStateReportStream.ErrorCode.DUPLICATE_REPORT) { duplicate.append(framed()) }
        assertNull(duplicate.report)

        val late = InternalStateReportStream()
        val output = StringBuilder()
        error(InternalStateReportStream.ErrorCode.LATE_REPORT) {
            ("Hello " + framed()).forEach { output.append(late.append(it.toString())) }
        }
        assertEquals("Hello ", output.toString())
        assertNull(late.report)
    }

    @Test fun reportSizeLimitFailsExplicitlyInsteadOfTruncatingJson() {
        val exact = InternalStateReportStream(maxReportChars = valid.length)
        assertEquals("正文", collect(exact, listOf(framed() + "正文")))
        assertNotNull(exact.report)

        val tooSmall = InternalStateReportStream(maxReportChars = valid.length - 1)
        error(InternalStateReportStream.ErrorCode.REPORT_TOO_LARGE) { tooSmall.append(framed()) }
        assertNull(tooSmall.report)
        error(InternalStateReportStream.ErrorCode.REPORT_TOO_LARGE) { tooSmall.finish() }
    }

    @Test fun rollbackBeforeReportDiscardsStagingAndInvalidatesLaterSavepoints() {
        val parser = InternalStateReportStream()
        parser.savepoint("before")
        parser.append(framed())
        parser.savepoint("after")
        parser.append("discarded prose")
        assertNotNull(parser.report)
        assertTrue(parser.rollback("before"))
        assertNull(parser.report)
        assertFalse(parser.rollback("after"))

        val replacement = JSONObject(valid).put("frame_id", "replacement").toString()
        assertEquals("正文", collect(parser, listOf(framed(replacement) + "正文")))
        assertEquals("replacement", parser.report!!.getString("frame_id"))
    }

    @Test fun rollbackRestoresPartialJsonStringAndClosingTagLookahead() {
        val parser = InternalStateReportStream()
        parser.append(open + "{\"frame_id\":\"")
        parser.savepoint("in-string")
        parser.append("first\"}" + close)
        assertTrue(parser.rollback("in-string"))
        assertNull(parser.report)
        parser.append("$close private\"}" + close.take(8))
        parser.savepoint("closing")
        assertEquals("old", parser.append(close.drop(8) + "old"))
        assertTrue(parser.rollback("closing"))
        assertNull(parser.report)
        assertEquals("new", collect(parser, listOf(close.drop(8) + "new")))
        assertEquals("$close private", parser.report!!.getString("frame_id"))
    }

    @Test fun rollbackRestoresThinkingAndCodeParsingState() {
        val thinking = InternalStateReportStream()
        thinking.append("<thi")
        thinking.savepoint("prefix")
        thinking.append("nk>old</think>" + framed())
        assertTrue(thinking.rollback("prefix"))
        val rest = "nking>${framed()}</thinking>" + framed() + "正文"
        assertEquals("<thinking>${framed()}</thinking>正文", collect(thinking, rest.map(Char::toString)))

        val code = InternalStateReportStream()
        code.append("```json\n")
        code.savepoint("code")
        code.append(framed() + "\n```\n")
        assertTrue(code.rollback("code"))
        assertEquals(framed() + "\n```\n正文", collect(code, listOf(framed() + "\n```\n正文")))
        assertNull(code.report)
    }

    @Test fun rollbackCanDiscardAProtocolFailureBeforeAnotherProviderAttempt() {
        val parser = InternalStateReportStream()
        parser.savepoint("attempt")
        error(InternalStateReportStream.ErrorCode.INVALID_JSON) { parser.append(framed("broken")) }
        assertTrue(parser.rollback("attempt"))
        assertEquals("正文", collect(parser, listOf(framed() + "正文")))
        assertNotNull(parser.report)
    }

    @Test fun rollbackAfterReportRetainsItAndDoesNotPermitAnotherReport() {
        val parser = InternalStateReportStream()
        parser.append(framed())
        parser.savepoint("body")
        parser.append("discarded")
        assertTrue(parser.rollback("body"))
        assertNotNull(parser.report)
        error(InternalStateReportStream.ErrorCode.DUPLICATE_REPORT) { parser.append(framed()) }
    }

    @Test fun reportCopiesCannotModifyStagedDataAndUnknownRollbackIsExplicit() {
        val parser = InternalStateReportStream()
        parser.append(framed())
        parser.report!!.put("frame_id", "mutated outside parser")
        assertEquals("host-nonce", parser.report!!.getString("frame_id"))
        assertFalse(parser.rollback("unknown"))
        assertEquals("host-nonce", parser.report!!.getString("frame_id"))
    }

    @Test fun finishIsIdempotentButAppendingAfterFinishIsAnError() {
        val parser = InternalStateReportStream()
        assertEquals("", parser.append("<"))
        assertEquals("<", parser.finish())
        assertEquals("", parser.finish())
        error(InternalStateReportStream.ErrorCode.STREAM_FINISHED) { parser.append("late") }
    }

    @Test fun deferredProtocolFailureDoesNotBecomeAProviderRetryOrPublicText() {
        val parser = InternalStateReportStream()
        var attempts = 0
        val visible = StringBuilder()
        // This models providers that retry exceptions escaping their text callback. The local
        // error must be delivered only after this boundary, without requesting another response.
        fun provider(consume: (String) -> Unit) {
            repeat(5) {
                attempts++
                try {
                    consume(framed("{\"private\":}"))
                    consume("private continuation after the invalid report")
                    return
                } catch (_: InternalStateReportStream.ProtocolException) {
                    // A direct append() here would incorrectly request another model attempt.
                }
            }
        }
        provider { visible.append(parser.appendDeferringFailure(it)) }
        assertEquals(1, attempts)
        assertEquals("", visible.toString())
        assertNull(parser.report)
        error(InternalStateReportStream.ErrorCode.INVALID_JSON) { parser.finish() }
    }

    @Test fun genuineProviderRollbackCanRestoreDeferredFailure() {
        val parser = InternalStateReportStream()
        parser.savepoint("network-attempt")
        assertEquals("", parser.appendDeferringFailure(framed("invalid")))
        assertEquals("", parser.appendDeferringFailure("must remain private"))
        assertTrue(parser.rollback("network-attempt"))
        assertEquals("正文", parser.appendDeferringFailure(framed() + "正文"))
        assertEquals("", parser.finish())
        assertEquals("host-nonce", parser.report!!.getString("frame_id"))
    }
}
