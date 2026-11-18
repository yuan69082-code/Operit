package com.ai.assistance.operit.core.companion

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CategoryJournalFingerprintTest {
    private fun journal() = JSONObject().put("schema", 1).put("id", "frame-number").put("rows", JSONArray().put(
        JSONObject().put("storage_kind", "episode").put("id", "feeling").put("parent", "emotion")
            .put("frame_id", "frame-number").put("version", 1).put("at", 100L)
            .put("content", JSONObject().put("intensity", 1.0).put("meaning", "保留原文"))
    ))

    @Test fun numericFingerprintSurvivesBothStoragePathsButRejectsContentChanges() {
        val original = journal()
        val copied = JSONObject(original.toString())
        val output = StringWriter()
        InternalStateHistory.write(JSONObject().put("category_commit", original), output)
        val streamed = InternalStateHistory.read(StringReader(output.toString())) { _, _ -> error("no history") }
            .getJSONObject("category_commit")
        assertEquals(CategoryJournalFingerprint.digest(original), CategoryJournalFingerprint.digest(copied))
        assertEquals(CategoryJournalFingerprint.digest(original), CategoryJournalFingerprint.digest(streamed))
        copied.getJSONArray("rows").getJSONObject(0).getJSONObject("content").put("intensity", 1.1)
        assertNotEquals(CategoryJournalFingerprint.digest(original), CategoryJournalFingerprint.digest(copied))
    }

    private fun legacyFile(): File {
        val file = File.createTempFile("journal", ".db", RuntimeEnvironment.getApplication().cacheDir)
        val original = journal()
        InternalStateArchive(file, "internal-state:role").use { it.applyCategoryJournal(original) }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("UPDATE category_commits SET digest=?", arrayOf(InnerStructure.hash(original)))
        }
        return file
    }

    @Test fun legacyNumericDigestIsUpgradedWithoutReplayingHistory() {
        val file = legacyFile()
        InternalStateArchive(file, "internal-state:role").use { archive ->
            val copied = JSONObject(journal().toString())
            archive.applyCategoryJournal(copied)
            archive.applyCategoryJournal(copied)
            assertEquals(1, archive.categoryVersions("episode:feeling", 0).size)
            assertEquals("保留原文", archive.categoryRow("episode", "feeling")!!.getJSONObject("content").getString("meaning"))
        }
    }

    @Test fun repairingOldChecksumDoesNotReplaceANewerCurrentVersion() {
        val file = legacyFile()
        val newer = journal().put("id", "later-frame")
        newer.getJSONArray("rows").getJSONObject(0).put("frame_id", "later-frame").put("version", 2)
            .getJSONObject("content").put("intensity", 2)
        InternalStateArchive(file, "internal-state:role").use { archive ->
            archive.applyCategoryJournal(newer)
            archive.applyCategoryJournal(JSONObject(journal().toString()))
            assertEquals(2, archive.categoryRow("episode", "feeling")!!.getInt("version"))
            assertEquals(2, archive.categoryVersions("episode:feeling", 0).size)
        }
    }

    @Test fun sameCommitIdInAnotherAssistantDoesNotShareItsDigestOrState() {
        val file = legacyFile()
        val other = journal()
        other.getJSONArray("rows").getJSONObject(0).getJSONObject("content").put("meaning", "另一个助手")
        InternalStateArchive(file, "internal-state:other").use { it.applyCategoryJournal(other) }
        InternalStateArchive(file, "internal-state:role").use { archive ->
            archive.applyCategoryJournal(JSONObject(journal().toString()))
            assertEquals("保留原文", archive.categoryRow("episode", "feeling")!!.getJSONObject("content").getString("meaning"))
        }
    }

    @Test fun legacyCommitWithDifferentContentIsNotRepaired() {
        val file = legacyFile()
        val changed = journal()
        changed.getJSONArray("rows").getJSONObject(0).getJSONObject("content").put("meaning", "被改写")
        InternalStateArchive(file, "internal-state:role").use { archive ->
            assertThrows(IllegalStateException::class.java) { archive.applyCategoryJournal(changed) }
            assertEquals("保留原文", archive.categoryRow("episode", "feeling")!!.getJSONObject("content").getString("meaning"))
        }
    }
}
