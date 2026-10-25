package com.ai.assistance.operit.core.awareness

import org.junit.Assert.*
import org.junit.Test

class PhonePresenceTest {
    @Test fun internalActivityChangeDoesNotCreateAnotherVisit() {
        val phone = PhonePresence()
        phone.resume("a", 1000)
        assertTrue(phone.resume("a", 5000).isEmpty())
        assertEquals(5000L, phone.close(6000).single().durationMs)
    }
    @Test fun switchingEndsThePreviousVisitAtTheNextEntry() {
        val phone = PhonePresence()
        phone.resume("a", 1000)
        val switched = phone.resume("b", 2800)
        assertEquals(listOf("app_leave", "app_enter"), switched.map { it.kind })
        assertEquals(2800L, switched.first().at)
        assertEquals(1800L, switched.first().durationMs)
        assertEquals("b", phone.foreground)
    }
    @Test fun desktopIsAlsoAnIntervalBoundary() {
        val phone = PhonePresence()
        phone.resume("a", 1000)
        assertEquals(5000L, phone.resume("launcher", 6000).first().durationMs)
    }
    @Test fun screenOffClosesVisitAndStopsCountingLockedTime() {
        val phone = PhonePresence()
        phone.resume("a", 1000)
        assertEquals(2000L, phone.close(3000).single().durationMs)
        assertTrue(phone.close(90_000).isEmpty())
        assertEquals("app_enter", phone.resume("a", 95_000).single().kind)
        assertEquals(1000L, phone.resume("b", 96_000).first().durationMs)
    }
    @Test fun serviceStartsWithoutInventingAnEntryTime() {
        val phone = PhonePresence()
        assertTrue(phone.close(6000).isEmpty())
    }
    @Test fun onlyFreshExternalEntryOrUnlockCanWake() {
        assertTrue(PhonePresence.shouldWake("unlock", true, false))
        assertTrue(PhonePresence.shouldWake("app_enter", true, false))
        assertFalse(PhonePresence.shouldWake("app_enter", true, true))
        assertFalse(PhonePresence.shouldWake("unlock", false, false))
        assertFalse(PhonePresence.shouldWake("screen_on", true, false))
        assertFalse(PhonePresence.shouldWake("app_leave", true, false))
    }
}
