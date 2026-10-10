package com.ai.assistance.operit.util

import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.model.CustomEmoji
import com.ai.assistance.operit.data.repository.EmojiScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StickerProtocolTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun userCollectionDoesNotCollideWithPublishedAiOrGroupCollections() {
        assertEquals("character_card_custom_emoji_alice_", EmojiScope.preferencePrefix(ActivePrompt.CharacterCard("alice")))
        assertEquals("character_group_custom_emoji_alice_", EmojiScope.preferencePrefix(ActivePrompt.CharacterGroup("alice")))
        val owners = listOf(null, ActivePrompt.CharacterCard("alice"), ActivePrompt.CharacterGroup("alice"), ActivePrompt.CharacterCard("user"))
        assertEquals(4, owners.map { EmojiScope.preferencePrefix(it) }.toSet().size)
        assertEquals(4, owners.map { EmojiScope.directory(it) }.toSet().size)
        assertEquals("character_card_alice", EmojiScope.directory(ActivePrompt.CharacterCard("alice")))
    }

    @Test fun publishedEmojiMetadataStillLoadsUnchanged() {
        val emoji = Json.decodeFromString<CustomEmoji>("""{"id":"old-id","emotionCategory":"happy","fileName":"old.gif","isBuiltInCategory":true,"createdAt":7}""")
        assertEquals("old-id", emoji.id)
        assertEquals("old.gif", emoji.fileName)
        assertEquals(7L, emoji.createdAt)
    }

    @Test fun chineseAndExistingCategoriesWorkButPathsAndMarkupDoNot() {
        listOf("收藏", "晚安", "happy", "miss_you", "可爱 猫猫", "good-night").forEach { assertTrue(it, StickerProtocol.validCategory(it)) }
        listOf("", "../escape", "a/b", "a\\b", ".", "<emotion>", "a\nb", " x", "x ", "x".repeat(41)).forEach {
            assertFalse(it, StickerProtocol.validCategory(it))
        }
    }

    @Test fun markerRecognizesOnlyStickersAndKeepsSurroundingUserText() {
        val first = StickerProtocol.markdown("哭哭", "file:///saved/a.gif")
        val second = StickerProtocol.markdown("开心", "file:///saved/b.webp")
        val text = "你看 $first 还有 $second ![普通图片](file:///a.jpg)"
        val matches = StickerProtocol.pattern.findAll(text).toList()
        assertEquals(listOf(first, second), matches.map { it.value })
        assertEquals("file:///saved/a.gif", matches[0].groupValues[2])
        assertEquals("你看  还有  ![普通图片](file:///a.jpg)", StickerProtocol.pattern.replace(text, ""))
        assertFalse(StickerProtocol.pattern.containsMatchIn("![sticker:happy](https://example.com/a.gif)"))
    }

    @Test fun sendingKeepsAnimationBytesAndSurvivesCollectionDeletion() {
        val source = temp.newFile("original.gif")
        val bytes = "GIF89a-animation-frames".toByteArray() + byteArrayOf(0, 1)
        source.writeBytes(bytes)
        val directory = temp.newFolder("sent")
        val sent = StickerProtocol.snapshot(directory, source)
        assertEquals(sent, StickerProtocol.snapshot(directory, source))
        assertEquals(1, directory.listFiles()!!.size)
        source.delete()
        assertArrayEquals(bytes, sent.readBytes())
    }

    @Test fun distinctImagesNeverOverwriteEarlierMessages() {
        val source = temp.newFile("same-name.webp")
        val directory = temp.newFolder("messages")
        source.writeText("first")
        val first = StickerProtocol.snapshot(directory, source)
        source.writeText("second")
        val second = StickerProtocol.snapshot(directory, source)
        assertNotEquals(first, second)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
    }

    @Test fun oversizedAndEmptyImportsAreRejected() {
        listOf(ByteArray(0), ByteArray(StickerProtocol.MAX_BYTES.toInt() + 1)).forEach { bytes ->
            try {
                StickerProtocol.copyBounded(ByteArrayInputStream(bytes), ByteArrayOutputStream())
                fail("invalid import accepted")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun failedSnapshotLeavesNoPartialChatImage() {
        val source = temp.newFile("too-big.gif")
        java.io.RandomAccessFile(source, "rw").use { it.setLength(StickerProtocol.MAX_BYTES + 1) }
        val directory = temp.newFolder("failed")
        try { StickerProtocol.snapshot(directory, source); fail("oversize accepted") }
        catch (_: IllegalArgumentException) {}
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun invalidPaginationIsRejectedInsteadOfLoopingTheFirstPage() {
        assertEquals(0, StickerProtocol.pageOffset(null))
        assertEquals(24, StickerProtocol.pageOffset("24"))
        listOf("-1", "abc", "999999999999999").forEach {
            try { StickerProtocol.pageOffset(it); fail("invalid offset accepted") }
            catch (_: IllegalArgumentException) {}
        }
    }
}
