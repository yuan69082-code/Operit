package com.ai.assistance.operit.core.companion

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EstablishedWindowReceiptTest {
    @Test fun readingNeverOpensAndObservedDeadlineCannotBeReopenedByOlderBackupOrClock() {
        val context = RuntimeEnvironment.getApplication()
        val owner = "window-receipt-contract"
        val data = JSONObject()
        assertFalse(EstablishedWindowReceipt.reconcile(context, owner, data, 100))
        assertEquals("not_opened", EstablishedContent.status(data, 100).getString("status"))
        EstablishedContent.open(data, owner, 100)
        EstablishedWindowReceipt.reconcile(context, owner, data, 101)
        val backup = JSONObject(data.toString())
        val closedAt = 100 + EstablishedContent.DURATION
        EstablishedWindowReceipt.reconcile(context, owner, data, closedAt)
        assertEquals("closed", EstablishedContent.status(data, closedAt).getString("status"))
        EstablishedWindowReceipt.reconcile(context, owner, backup, 102)
        assertEquals("closed", EstablishedContent.status(backup, 102).getString("status"))
        assertThrows(IllegalArgumentException::class.java) {
            EstablishedContent.declaration(backup, owner, "late", "preference", 102, null)
        }
        val predatingBackup = JSONObject()
        EstablishedWindowReceipt.reconcile(context, owner, predatingBackup, 103)
        assertEquals(100L, EstablishedContent.open(predatingBackup, owner, 103).getLong("opened_at"))
        assertEquals(closedAt, EstablishedContent.status(predatingBackup, 103).getLong("closes_at"))
        assertFalse(EstablishedWindowReceipt.reconcile(context, "another-owner", JSONObject(), closedAt))
    }
}
