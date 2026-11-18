package com.ai.assistance.operit.core.companion

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(OperitStorageTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class ArchiveSchemaTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun legacySchemaIsInitializedOnceAndExistingPagesRemainReadable() {
        val file = File(temporary.newFolder(), "history.sqlite")
        InternalStateArchive(file, "owner").use { archive ->
            archive.activityWrite("waking_note", "note", "private") { JSONObject().put("id", "note")
                .put("parent", "private").put("storage_kind", "waking_note").put("text", "醒后的随记") }
        }
        SQLiteDatabase.openDatabase(file.path, null, 0).use { db ->
            db.execSQL("DROP INDEX category_recent")
            db.version = 0 // Published archives before the initialization marker.
        }
        repeat(3) {
            InternalStateArchive(file, "owner").use { archive ->
                assertEquals("醒后的随记", archive.categoryPage("waking_note", 0, 9, "private").single().getString("text"))
            }
        }
        SQLiteDatabase.openDatabase(file.path, null, 0).use { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='category_recent'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        }
        InternalStateArchive(file, "other").use { assertTrue(it.categoryPage("waking_note", 0, 9, "private").isEmpty()) }
    }
}
