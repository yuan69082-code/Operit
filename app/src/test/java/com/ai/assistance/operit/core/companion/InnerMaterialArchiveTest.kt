package com.ai.assistance.operit.core.companion

import android.app.Application
import org.json.JSONArray
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
class InnerMaterialArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun value(id: String, description: String, version: Int = 1, status: String = "active") = JSONObject()
        .put("id", id).put("version", version).put("origin", "subject_development").put("source", "subject_adoption")
        .put("content", JSONObject().put("id", id).put("family", "preference").put("kind", "兴趣")
            .put("description", description).put("status", status))

    private fun write(archive: InternalStateArchive, commit: String, vararg values: JSONObject) {
        val rows = values.map { InnerRows.envelope("inner_record", it.getString("id"), it, "other", commit) }
        archive.applyCategoryJournal(JSONObject().put("schema", 1).put("id", commit).put("rows", JSONArray(rows)))
    }

    @Test fun archiveSearchFindsLaterRowsAndRefreshesSameIdChangesAndRetirement() {
        val file = File(temporary.newFolder(), "history.sqlite")
        InternalStateArchive(file, "internal-state:owner").use { archive ->
            write(archive, "initial", *(0..24).map { value("record-$it", "安静阅读") }.toTypedArray())
            write(archive, "adopt", value("record-24", "星空和天文", 2))
            assertEquals(listOf("record-24"), archive.relevantInnerRows("看星空", "other", 8).map { it.getString("id") })
        }
        // Stores create a new archive handle per read. Reused indexes must see later versions.
        InternalStateArchive(file, "internal-state:owner").use { archive ->
            write(archive, "change", value("record-24", "一起野餐", 3))
            assertTrue(archive.relevantInnerRows("看星空", "other", 8).isEmpty())
            assertEquals(3, archive.relevantInnerRows("野餐", "other", 8).single().getInt("version"))
            write(archive, "retire", value("record-24", "一起野餐", 4, "retired"))
            assertTrue(archive.relevantInnerRows("野餐", "other", 8).isEmpty())
            assertEquals("一起野餐", archive.categoryRow("inner_record", "record-24")!!
                .getJSONObject("value").getJSONObject("content").getString("description"))
        }
    }

    @Test fun cachedMatchesStayWithinTheExactArchiveAndSubjectScope() {
        val shared = File(temporary.newFolder(), "history.sqlite")
        InternalStateArchive(shared, "internal-state:first").use { archive ->
            write(archive, "adopt", value("same-id", "看星空"))
            assertEquals(1, archive.relevantInnerRows("星空", "other", 8).size)
        }
        InternalStateArchive(shared, "internal-state:second").use { archive ->
            write(archive, "adopt", value("same-id", "去野餐"))
            assertTrue(archive.relevantInnerRows("星空", "other", 8).isEmpty())
            assertEquals(1, archive.relevantInnerRows("野餐", "other", 8).size)
        }
        InternalStateArchive(File(temporary.newFolder(), "history.sqlite"), "internal-state:first").use { archive ->
            write(archive, "adopt", value("same-id", "听音乐"))
            assertTrue(archive.relevantInnerRows("星空", "other", 8).isEmpty())
        }
    }
}
