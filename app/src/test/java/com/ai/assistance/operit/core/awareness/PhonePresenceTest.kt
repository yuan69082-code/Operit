package com.ai.assistance.operit.core.awareness

import org.junit.Assert.*
import org.junit.Test

class PhonePresenceTest {
    @Test fun internalActivityChangeDoesNotCreateAnotherVisit() {
        val phone = PhonePresence()
        phone.resume("a", 1000)
        phone.pause("a", 2000)
        assertTrue(phone.resume("a", 2300).isEmpty())
        assertTrue(phone.flush(5000).isEmpty())
        assertEquals(5000L, phone.close(6000).single().durationMs)
    }
    @Test fun switchingUsesTheActualPauseTime() {
        val phone = PhonePresence()
        phone.resume("a", 1000)
        phone.pause("a", 2500)
        val switched = phone.resume("b", 2800)
        assertEquals(listOf("app_leave", "app_enter"), switched.map { it.kind })
        assertEquals(2500L, switched.first().at)
        assertEquals(1500L, switched.first().durationMs)
        assertEquals("b", phone.foreground)
    }
    @Test fun latePauseFromPreviousAppCannotCloseNewApp() {
        val phone = PhonePresence()
        phone.resume("a", 1000); phone.resume("b", 2000)
        phone.pause("a", 2100)
        assertTrue(phone.flush(5000).isEmpty())
        assertEquals("b", phone.foreground)
    }
    @Test fun leaveWithoutAnotherAppIsRecordedOnce() {
        val phone = PhonePresence()
        phone.resume("a", 1000); phone.pause("a", 2000)
        assertTrue(phone.flush(2900).isEmpty())
        assertEquals(1000L, phone.flush(3100).single().durationMs)
        assertTrue(phone.flush(4000).isEmpty())
    }
    @Test fun screenOffClosesVisitAndStopsCountingLockedTime() {
        val phone = PhonePresence()
        phone.resume("a", 1000)
        assertEquals(2000L, phone.close(3000).single().durationMs)
        assertTrue(phone.close(90_000).isEmpty())
        assertEquals("app_enter", phone.resume("a", 95_000).single().kind)
    }
    @Test fun serviceStartsWithoutInventingAnEntryTime() {
        val phone = PhonePresence()
        phone.pause("already.open", 5000)
        assertTrue(phone.close(6000).isEmpty())
    }
    @Test fun delayedReturnCreatesTwoVisits() {
        val phone = PhonePresence()
        phone.resume("a", 1000); phone.pause("a", 2000)
        assertEquals(2, phone.resume("a", 5000).size)
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
