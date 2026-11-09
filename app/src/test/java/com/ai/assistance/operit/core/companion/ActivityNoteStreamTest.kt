package com.ai.assistance.operit.core.companion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ActivityNoteStreamTest {
    @Test fun chunkedNotesAreDurableBeforeCompletionAndNeverCopyPrivateOrReasoning(): Unit = runBlocking {
        val notes = mutableListOf<Pair<String, Boolean>>()
        val stream = ActivityNoteStream("r0", { _, text, waking -> notes.add(text to waking) }, {}, {})
        val public = StringBuilder()
        val text = "<think><activity_note>hidden</activity_note></think>" +
            "`<activity_note>example</activity_note>`<companion_note>secret</companion_note>" +
            "<activity_note>我想看看那片星云。</activity_note><waking_note>只剩下海的感觉。</waking_note><companion_quiet/>"
        text.forEach { public.append(stream.append(it.toString())) }
        assertEquals(listOf("我想看看那片星云。" to false, "只剩下海的感觉。" to true), notes)
        assertFalse(public.contains("我想看看"))
        assertTrue(public.contains("<companion_note>secret</companion_note>"))
        assertTrue(public.contains("<think><activity_note>hidden</activity_note></think>"))
        assertEquals("", stream.finish())
    }

    @Test fun rollbackWithdrawsOnlyCompletedNewSegmentsAndReplaysSameIdentity(): Unit = runBlocking {
        val saved = mutableListOf<String>(); val withdrawn = mutableListOf<String>()
        val stream = ActivityNoteStream("round", { key, _, _ -> saved.add(key) }, { withdrawn.add(it) }, {})
        stream.append("<activity_note>before</activity_note>")
        stream.savepoint("tool")
        stream.append("<activity_note>after</activity_note>")
        stream.rollback("tool")
        assertEquals(listOf(saved[1]), withdrawn)
        stream.append("<activity_note>after</activity_note><activity_note>unfinished")
        assertEquals(saved[1], saved[2])
        assertEquals("", stream.finish())
        assertEquals(3, saved.size)
    }

    @Test fun explicitPrivateChoiceIsNotInferredFromMissingNotes(): Unit = runBlocking {
        var privateCount = 0
        val stream = ActivityNoteStream("r", { _, _, _ -> fail("no note") }, {}, { privateCount++ })
        assertEquals("<companion_quiet/>", stream.append("<companion_quiet/>"))
        assertEquals(0, privateCount)
        stream.append("<activity_private/>")
        assertEquals(1, privateCount)
    }

    @Test fun persistenceFailureCrossesRetryingProviderAsCancellation(): Unit = runBlocking {
        val failed = IllegalStateException("disk")
        val stream = ActivityNoteStream("r", { _, _, _ -> throw failed }, {}, {})
        try { stream.append("<activity_note>完整</activity_note>"); fail("must not claim success") }
        catch (failure: ActivityNoteStream.SaveFailure) { assertSame(failed, failure.failure) }
    }
}
