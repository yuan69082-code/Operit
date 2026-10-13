package com.ai.assistance.operit.core.awareness

import org.junit.Assert.*
import org.junit.Test

class SoftwarePresenceTest {
    private val a = SoftwarePresence.View("chat-a", "a", "A", "")
    private val b = SoftwarePresence.View("chat-b", "b", "B", "")
    @Test fun enablingStartsNowWithoutInventingEarlierVisits() {
        val p = SoftwarePresence()
        p.foreground(true, 100, 100)
        p.view("main", a, 200, 200)
        val events = p.recording(true, 1000, 1000)
        assertEquals(listOf("app_foreground", "chat_enter"), events.map { it.kind })
        assertEquals(1000L, events.last().at)
        assertEquals(500L, p.foreground(false, 1500, 1500).first().duration)
    }
    @Test fun assistantSwitchClosesOnlyPreviousVisit() {
        val p = SoftwarePresence()
        p.recording(true, 0, 0); p.foreground(true, 100, 100); p.view("main", a, 200, 200)
        val events = p.view("main", b, 900, 900)
        assertEquals(listOf("chat_leave", "chat_enter"), events.map { it.kind })
        assertEquals("a", events.first().view?.role)
        assertEquals(700L, events.first().duration)
        assertTrue(p.view("main", b, 950, 950).isEmpty())
    }
    @Test fun backgroundTimeIsNotChatDwell() {
        val p = SoftwarePresence()
        p.recording(true, 0, 0); p.foreground(true, 100, 100); p.view("main", a, 200, 200)
        assertEquals(800L, p.foreground(false, 1000, 1000).first().duration)
        assertTrue(p.active(8000, 8000).isEmpty())
        p.foreground(true, 9000, 9000)
        assertEquals(500L, p.view("main", null, 9500, 9500).single().duration)
    }
    @Test fun leavingForSettingsClosesVisibleChat() {
        val p = SoftwarePresence()
        p.recording(true, 0, 0); p.foreground(true, 100, 100); p.view("main", a, 200, 200)
        assertEquals("chat_leave", p.view("main", null, 1000, 1000).single().kind)
        assertTrue(p.active(1500, 1500).isEmpty())
    }
    @Test fun wallClockAdjustmentDoesNotMakeNegativeDuration() {
        val p = SoftwarePresence()
        p.recording(true, 0, 0); p.foreground(true, 10000, 100); p.view("main", a, 10000, 200)
        assertEquals(800L, p.view("main", null, 2000, 1000).single().duration)
    }
    @Test fun disablingDiscardsActiveIntervalsAndRequiresNewStart() {
        val p = SoftwarePresence()
        p.recording(true, 0, 0); p.foreground(true, 100, 100); p.view("main", a, 200, 200)
        assertTrue(p.recording(false, 300, 300).isEmpty())
        assertTrue(p.active(500, 500).isEmpty())
        p.recording(true, 1000, 1000)
        assertEquals(300L, p.foreground(false, 1300, 1300).first().duration)
    }
}
