package com.ai.assistance.operit.core.companion
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ThoughtPoolTest {
    @get:Rule val folder = TemporaryFolder()
    private fun context(): Context {
        val context = mock<Context>(); val prefs = mock<SharedPreferences>()
        whenever(context.applicationContext).thenReturn(context); whenever(context.filesDir).thenReturn(folder.root)
        whenever(context.getSharedPreferences("companion_options", Context.MODE_PRIVATE)).thenReturn(prefs)
        return context
    }

    @Test fun sinkFloatsUpAfterItsTimeAndDefaultsToTwoDays() {
        val context = context()
        val t = 1_000_000L
        val a = ThoughtPool.save(context, "r", null, "想问她做戒指那天的事", "sink", null, now = t)
        ThoughtPool.save(context, "r", null, "过一小时再想", "sink", 60, now = t)
        ThoughtPool.save(context, "r", null, "这个现在就说", "now", null, now = t)
        ThoughtPool.save(context, "r", null, "算了", "drop", null, now = t)
        val items = ThoughtPool.all(context, "r")
        assertEquals(listOf("过一小时再想"), ThoughtPool.surfaced(items, t + 61 * 60_000L).map { it.getString("text") })
        assertEquals(2, ThoughtPool.surfaced(items, t + ThoughtPool.DEFAULT_SINK_MINUTES * 60_000L).size)
        assertTrue(ThoughtPool.prompt(items, t).isEmpty())
        assertTrue(ThoughtPool.prompt(items, t + 61 * 60_000L).contains("过一小时再想"))

        // 浮上来以后改主意：扔掉，就不再浮
        ThoughtPool.save(context, "r", a.getString("id"), null, "drop", null, now = t + 1)
        val after = ThoughtPool.all(context, "r")
        assertEquals("想问她做戒指那天的事", after.first { it.getString("id") == a.getString("id") }.getString("text"))
        assertEquals(1, ThoughtPool.surfaced(after, t + ThoughtPool.DEFAULT_SINK_MINUTES * 60_000L).size)
        assertTrue(ThoughtPool.all(context, "other").isEmpty())
    }

    @Test fun rejectsBadChoicesAndEmptyThoughts() {
        val context = context()
        assertThrows(IllegalArgumentException::class.java) { ThoughtPool.save(context, "r", null, "x", "later", null) }
        assertThrows(IllegalArgumentException::class.java) { ThoughtPool.save(context, "r", null, " ", "sink", null) }
        assertThrows(IllegalArgumentException::class.java) { ThoughtPool.save(context, "r", "nope", null, "drop", null) }
    }

    @Test fun readsShareDriveFromXc() {
        val snap = JSONObject().put("additionalContext", "当前驱力：牵挂=0.780；分享欲：有话想说=0.550；情欲=0.5")
        assertEquals(0.55, ThoughtPool.shareDrive(snap)!!, 1e-9)
        assertNull(ThoughtPool.shareDrive(null))
        assertTrue(ThoughtPool.hint(0.8).contains("现在讲"))
        assertTrue(ThoughtPool.hint(null).contains("自己判断"))
    }
}