package com.ai.assistance.operit.core.companion
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.random.Random

class EchoThemesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun context(): Context {
        val context = mock<Context>(); val prefs = mock<SharedPreferences>()
        whenever(context.applicationContext).thenReturn(context); whenever(context.filesDir).thenReturn(folder.root)
        whenever(context.getSharedPreferences("companion_options", Context.MODE_PRIVATE)).thenReturn(prefs)
        return context
    }
    private val sex = EchoThemes.Theme("性", setOf("做爱", "dirty talk", "高潮管理"))
    private fun item(id: String, title: String, tags: Set<String> = emptySet(), body: String = "") =
        EchoThemes.Candidate(id, title, "资料库/x", tags, body)

    @Test fun themeWordPullsTaggedAndTitledItemsEvenWithoutLiteralOverlap() {
        val items = listOf(item("a", "10-09 她说的喜好", setOf("性")), item("b", "高潮管理笔记"), item("c", "象山看日出"))
        val picked = EchoThemes.pick(items, "今晚想听你 dirty talk", listOf(sex), random = Random(1))
        assertEquals(setOf("a", "b"), picked.map { it.getString("id") }.toSet())
        assertTrue(picked.all { it.getString("why") == "主题：性" && !it.has("content") })
    }

    @Test fun nothingTouchedFallsBackToLiteral() {
        val items = listOf(item("c", "象山看日出"), item("d", "鹈鹕骑车小游戏"))
        assertEquals(listOf("c"), EchoThemes.pick(items, "还想再去象山", listOf(sex)).map { it.getString("id") })
        assertTrue(EchoThemes.pick(items, "继续做下一个", listOf(sex)).isEmpty())
    }

    @Test fun capsAtFive() {
        val items = (1..9).map { item("$it", "做爱 $it") }
        assertEquals(5, EchoThemes.pick(items, "做爱", listOf(sex)).size)
    }

    @Test fun themesPersistAndLibraryTagsSurviveEdits() {
        val context = context()
        EchoThemes.saveTheme(context, "r", "性", listOf("做爱", " dirty talk ", "做爱"))
        assertEquals(setOf("做爱", "dirty talk"), EchoThemes.themes(context, "r").single().words)
        EchoThemes.saveTheme(context, "r", "性", listOf("高潮管理"))
        assertEquals(setOf("高潮管理"), EchoThemes.themes(context, "r").single().words)
        assertTrue(EchoThemes.themes(context, "other").isEmpty())

        val store = CompanionStore(context)
        val saved = store.saveEntry("role:r", null, "喜好", "她", "knowledge", "正文", null, listOf("性", "偏好"))
        val edited = store.saveEntry("role:r", saved.getString("id"), "喜好2", "她", "knowledge", "正文2", null)
        assertEquals(2, edited.getJSONArray("tags").length())
        val candidate = EchoThemes.candidates(context, "role:r", null).single()
        assertEquals(setOf("性", "偏好"), candidate.tags)

        EchoThemes.deleteTheme(context, "r", "性")
        assertTrue(EchoThemes.themes(context, "r").isEmpty())
    }
}