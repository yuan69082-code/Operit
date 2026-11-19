package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AutonomousJournalTest {
    private lateinit var context: Context
    private lateinit var store: CompanionStore
    private val role = "journal-role"
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        store = CompanionStore(context)
        store.preferences.edit().putBoolean("autonomous_enabled", true).remove("autonomous_pause_until").commit()
        store.update("autonomous-runtime") { it.put("phase", "waiting") }
    }
    @Test fun transientRequestRetriesOnceAndKeepsBothAttemptsAcrossNewInstance() = runBlocking {
        var calls = 0
        AutonomousJournal(context).execute(role, "chat", "activity", "自主学习", pause = { }) {
            if (++calls == 1) throw IOException("connection reset")
        }
        assertEquals(2, calls)
        val rows = AutonomousJournal(context).page(role).getJSONArray("items")
        assertEquals(2, rows.length())
        assertEquals("completed", rows.getJSONObject(0).getString("status"))
        assertEquals("retried", rows.getJSONObject(1).getString("status"))
        assertEquals(rows.getJSONObject(0).getString("chain"), rows.getJSONObject(1).getString("chain"))
        assertTrue(rows.getJSONObject(1).getString("stack").contains("connection reset"))
    }
    @Test fun exhaustedRetriesDoNotBlockNextActivity() = runBlocking {
        var calls = 0
        try { AutonomousJournal(context).execute(role, "chat", "bad", "主动唤醒", pause = { }) {
            calls++; throw IOException("offline")
        }; fail() } catch (_: IOException) { }
        assertEquals(2, calls)
        assertTrue(AutonomousRuntimeControl(context).allowed())
        AutonomousJournal(context).execute(role, "chat", "next", "生活安排") { }
        assertEquals("next", AutonomousJournal(context).page(role).getJSONArray("items").getJSONObject(0).getString("activity"))
    }
    @Test fun toolsAreNeverReplayedAndFailureStillHasStepAndStack() = runBlocking {
        var calls = 0
        try { AutonomousJournal(context).execute(role, "chat", "side-effect", "空闲接续", pause = { }) {
            calls++
            AutonomousJournal.progress(context, "chat", "正在调用工具", "send_message")
            throw InternalStateReportStream.ProtocolException(InternalStateReportStream.ErrorCode.INVALID_JSON)
        }; fail() } catch (_: InternalStateReportStream.ProtocolException) { }
        assertEquals(1, calls)
        val row = AutonomousJournal(context).page(role).getJSONArray("items").getJSONObject(0)
        assertEquals("skipped", row.getString("status"))
        assertTrue(row.getJSONArray("steps").toString().contains("send_message"))
        assertTrue(row.getString("error").contains("INVALID_JSON"))
    }
    @Test fun cancellationDoesNotRetryAndLateFailureDoesNotLeakOwnership() = runBlocking {
        var calls = 0
        try { AutonomousJournal(context).execute(role, "chat", "cancelled", "主动唤醒", pause = { }) {
            calls++; throw CancellationException("user arrived")
        }; fail() } catch (_: CancellationException) { }
        assertEquals(1, calls)
        assertEquals("interrupted", AutonomousJournal(context).page(role).getJSONArray("items").getJSONObject(0).getString("status"))
        AutonomousJournal(context).execute(role, "chat", "next", "生活安排") { }
    }
    @Test fun programmingErrorsDoNotRetryAndHistoryIsPagedNewestFirst() = runBlocking {
        assertFalse(AutonomousJournal.canRetry(IllegalStateException("bad state"), false, 1))
        for (i in 1..10) AutonomousJournal(context).execute(role, "chat", "work-$i", "空闲接续") { }
        val first = AutonomousJournal(context).page(role)
        assertEquals(8, first.getJSONArray("items").length())
        assertEquals("work-10", first.getJSONArray("items").getJSONObject(0).getString("activity"))
        val second = AutonomousJournal(context).page(role, first.getInt("next_offset"))
        assertEquals(2, second.getJSONArray("items").length())
        assertTrue(second.isNull("next_offset"))
        assertEquals(0, AutonomousJournal(context).page("another-role").getJSONArray("items").length())
    }
    @Test fun restartMarksInterruptedRunButDoesNotReplayOrLoseError() {
        store.activityWrite(role, "runtime_run", "old-run", "private") {
            JSONObject().put("id", "old-run").put("parent", "private").put("source_chat", "chat").put("activity", "unfinished")
                .put("status", "running").put("tools_touched", true)
        }
        AutonomousRuntimeControl(context).begin(role, "unfinished")
        AutonomousRuntimeControl(context).recover()
        assertTrue(AutonomousRuntimeControl(context).allowed())
        val row = AutonomousJournal(context).page(role).getJSONArray("items").getJSONObject(0)
        assertEquals("interrupted", row.getString("status"))
        SubjectLife.recordFailure(context, role, "测试错误", IllegalStateException("exact reason"))
        assertTrue(SubjectLife.failure(context, role)!!.contains("测试错误"))
        assertEquals("exact reason", CompanionStore(context).activityRead(role, "runtime_failure", "latest")!!.getString("error"))
    }
}
