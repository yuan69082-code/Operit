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

/** Storage/index regressions, separate from normal-entry subject behaviour evidence. */
@RunWith(OperitStorageTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class SubjectRecallArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun row(id: String, meaning: String, version: Int = 1, kind: String = "feedback") = JSONObject()
        .put("storage_kind", kind).put("id", id).put("parent", "private").put("version", version)
        .put("frame_id", "$id:$version").put("owner", "owner").put("at", version)
        .put("content", JSONObject().put("meaning", meaning).put("status", "active"))
    private fun write(archive: InternalStateArchive, vararg rows: JSONObject) {
        archive.applyCategoryJournal(JSONObject().put("schema", 1).put("id", InnerStructure.hash(JSONArray(rows)))
            .put("rows", JSONArray(rows)))
    }

    @Test fun reopenedArchiveInvalidatesEditedAndEndedMeaningsWithoutDeletingHistory() {
        val file = File(temporary.newFolder(), "history.sqlite")
        InternalStateArchive(file, "owner").use { archive ->
            write(archive, *(0..25).map { row("f%02d".format(it), "其他经验") }.toTypedArray())
            write(archive, row("f25", "星空研究需要校准", 2))
            assertEquals("f25", archive.relevantSubjectRows("feedback", "星空", "chat", 4).single().getString("id"))
        }
        InternalStateArchive(file, "owner").use { archive ->
            write(archive, row("f25", "现在研究野餐", 3))
            assertTrue(archive.relevantSubjectRows("feedback", "星空", "chat", 4).isEmpty())
            assertEquals(3, archive.relevantSubjectRows("feedback", "野餐", "chat", 4).single().getInt("version"))
            write(archive, row("u", "星空与独处", kind = "union"))
            assertEquals(1, archive.relevantSubjectRows("union", "星空", "chat", 4).size)
            write(archive, row("u", "星空与独处", 2, "union").apply { getJSONObject("content").put("status", "ended") })
            assertTrue(archive.relevantSubjectRows("union", "星空", "chat", 4).isEmpty())
            assertNotNull(archive.categoryRow("union", "u"))
            assertEquals(26L, archive.categoryCount("feedback", "private"))
        }
    }

    @Test fun cacheHasCapacityAndDoesNotCrossSubjectsOrWindows() {
        val file = File(temporary.newFolder(), "history.sqlite")
        repeat(8) { n -> InternalStateArchive(file, "owner$n").use { archive ->
            write(archive, row("same", "星空$n"), row("private", "秘密研究").put("local_chat", "private-chat"))
            assertEquals("星空$n", archive.relevantSubjectRows("feedback", "星空", "chat", 4).single()
                .getJSONObject("content").getString("meaning"))
            assertTrue(archive.relevantSubjectRows("feedback", "秘密", "chat", 4).isEmpty())
        } }
        val field = InternalStateArchive::class.java.getDeclaredField("recallCache").apply { isAccessible = true }
        assertTrue((field.get(null) as Map<*, *>).size <= 4)
        // Eviction clears only the disposable index; the first subject still has its records.
        InternalStateArchive(file, "owner0").use { archive ->
            assertEquals("星空0", archive.relevantSubjectRows("feedback", "星空", "chat", 4).single()
                .getJSONObject("content").getString("meaning"))
        }
    }

    @Test fun oversizedIndexIsNotRetainedButRecordsRemainSearchable() {
        val file = File(temporary.newFolder(), "history.sqlite")
        InternalStateArchive(file, "large-owner").use { archive ->
            write(archive, *(0 until 700).map { n -> row("r$n", "星空 " + (0..40).joinToString(" ") { "term${n}_$it" }) }.toTypedArray())
            assertTrue(archive.relevantSubjectRows("feedback", "term699_40", "chat", 4).any { it.getString("id") == "r699" })
            val field = InternalStateArchive::class.java.getDeclaredField("recallCache").apply { isAccessible = true }
            assertFalse((field.get(null) as Map<*, *>).containsKey(Triple(file.absolutePath, "large-owner", "feedback")))
            assertEquals(700L, archive.categoryCount("feedback", "private"))
            assertNotNull(archive.categoryRow("feedback", "r0"))
        }
    }

    @Test fun thousandsOfExperiencesStayOnDiskAndRelevantMethodSurvivesReopenAndRevision() {
        val file = File(temporary.newFolder(), "growth.sqlite")
        InternalStateArchive(file, "owner").use { archive ->
            repeat(10) { batch ->
                write(archive, *(0 until 501).map { index ->
                    row("e%05d".format(batch * 501 + index), "other", kind = "experience").apply {
                        getJSONObject("content").put("text", "无关经历 " + index).put("scope", "别的问题")
                    }
                }.toTypedArray())
            }
            val method = row("z-method", "", kind = "experience").apply {
                getJSONObject("content").put("text", "星空异常点应与其他样本分别比较")
                    .put("scope", "统计方法").put("uncertainty", "不保证适用于所有分布")
                    .put("bindings", JSONObject().put("source", "私密原始材料".repeat(2000)))
            }
            write(archive, method)
            val document = InternalStateDocument(JSONObject(), archive)
            val material = LifeUnderstandings.materials(document, "星空异常点", "chat")
            assertEquals("z-method", material.first().getJSONObject("locator").getString("id"))
            assertFalse(material.toString().contains("私密原始材料"))
            assertTrue(material.first().getString("text").contains("不保证适用于所有分布"))
            val payload = JSONObject().put("retrieved_materials", JSONArray(material))
                .put("inner_structure", JSONObject()).put("category_events", JSONObject()).put("unions", JSONObject())
            val selected = SubjectMaterialBudget.select(payload)
            assertTrue(selected.toString().contains("z-method"))
            assertTrue(SubjectMaterialBudget.estimate(selected.toString()) + SubjectMaterialBudget.ENVELOPE_RESERVE <= 2048)
            assertEquals(5011L, archive.categoryCount("experience", "private"))
            assertEquals(method.getJSONObject("content").toString(), archive.categoryRow("experience", "z-method")!!.getJSONObject("content").toString())
        }
        InternalStateArchive(file, "owner").use { archive ->
            assertEquals(1, archive.relevantSubjectRows("experience", "星空", "chat", 4).size)
            write(archive, row("z-method", "", 2, "experience").apply {
                getJSONObject("content").put("text", "现在研究潮汐")
            })
            assertTrue(archive.relevantSubjectRows("experience", "星空", "chat", 4).isEmpty())
            assertEquals(2, archive.relevantSubjectRows("experience", "潮汐", "chat", 4).single().getInt("version"))
            assertEquals(8, archive.categoryPage("experience", 5000, 8, "private").size)
        }
        InternalStateArchive(file, "another-owner").use { archive ->
            assertTrue(archive.relevantSubjectRows("experience", "潮汐", "chat", 4).isEmpty())
        }
    }
}
