package com.ai.assistance.operit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechCueTextTest {
    @Test
    fun removesAdjacentDirectionsFromScreenshot() {
        assertEquals("Rue. 打过来了。",
            SpeechCueText.clean("[calm]Rue. [pause][conversational tone]打过来了。[soft chuckle][continues softly]"))
    }

    @Test
    fun tagsSplitAtEveryCharacterNeverLeak() {
        val cleaner = SpeechCueText.StreamingCleaner()
        val input = "[calm]喂，Rue。[pause][quietly]去插充电线。"
        val output = buildString {
            input.forEach { append(cleaner.append(it.toString())) }
            append(cleaner.finish())
        }
        assertEquals("喂，Rue。去插充电线。", output)
    }

    @Test
    fun cueNamedLinkAndLiteralEnglishRemain() {
        val input = "[pause](https://example.com) calm pause conversational tone [ordinary words]"
        assertEquals(input, SpeechCueText.clean(input))
    }

    @Test
    fun codeExamplesAndEscapedBracketsRemain() {
        val input = "`[calm]`\n```text\n[pause]\n```\n\\[quietly]"
        assertEquals(input, SpeechCueText.clean(input))
    }

    @Test
    fun linkLookaheadWorksAcrossChunks() {
        val cleaner = SpeechCueText.StreamingCleaner()
        assertEquals("", cleaner.append("[pause]"))
        assertEquals("[pause](url)", cleaner.append("(url)"))
        assertEquals("", cleaner.finish())
    }

    @Test
    fun unknownAndIncompleteAnnotationsRemain() {
        assertEquals("[听不清] [unknown cue] [cal", SpeechCueText.clean("[听不清] [unknown cue] [cal"))
    }
}
