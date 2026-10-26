package com.ai.assistance.operit.core.companion

import org.junit.Assert.*
import org.junit.Test

class InternalStateEventsTest {
    @Test fun readingProgressCompletionAndFailureAreDifferentExperiences() {
        assertEquals("reading", InternalStateEvents.toolSource("companion_reading", "get", null, true))
        assertNull(InternalStateEvents.toolSource("companion_reading", "get", null, false))
        assertEquals("todo_progress", InternalStateEvents.toolSource("companion_todo", "comment", null, true))
        assertEquals("todo_completed", InternalStateEvents.toolSource("companion_todo", "save", "done", true))
        assertEquals("todo_failed", InternalStateEvents.toolSource("companion_todo", "save", "done", false))
        assertNull(InternalStateEvents.toolSource("companion_todo", "list", null, true))
        assertNull(InternalStateEvents.toolSource("companion_internal_state", "get", null, true))
    }
    @Test fun groupRepliesBelongOnlyToTheirRecordedAuthor() {
        assertEquals(listOf("b"), InternalStateEvents.actors(true, false, "a", listOf("a", "b"), "b"))
        assertEquals(emptyList<String>(), InternalStateEvents.actors(true, false, "a", listOf("a", "b"), null))
        assertEquals(listOf("a", "b"), InternalStateEvents.actors(true, true, "a", listOf("a", "b", "a"), null))
        assertEquals(listOf("a"), InternalStateEvents.actors(false, false, "a", emptyList(), null))
    }
}
