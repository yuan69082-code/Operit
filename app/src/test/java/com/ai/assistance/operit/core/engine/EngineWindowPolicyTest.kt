package com.ai.assistance.operit.core.engine

import org.junit.Assert.*
import org.junit.Test

class EngineWindowPolicyTest {
    @Test fun connectionOffKeepsAssociationAndNeverRestoresSoftwareModel() {
        assertFalse(EngineWindowPolicy.usesLocalModel(associated = true))
        assertFalse(EngineWindowPolicy.maySubmit(true, false, setOf("messages"), "message.user"))
        assertTrue(EngineWindowPolicy.usesLocalModel(associated = false))
    }

    @Test fun permissionToTalkDoesNotGrantPhoneObservationOrAuxiliaryDecisions() {
        assertTrue(EngineWindowPolicy.maySubmit(true, true, setOf("messages"), "message.user"))
        listOf("phone.unlock", "software.model", "call.declined", "group.turn_requested", "helper.wake_requested").forEach {
            assertFalse(EngineWindowPolicy.maySubmit(true, true, setOf("messages"), it))
        }
        assertFalse(EngineWindowPolicy.maySubmit(false, true, setOf("phone"), "phone.unlock"))
    }

    @Test fun eachOptInIsScopedAndDoesNotAuthorizeUnknownCommands() {
        listOf("phone" to "phone.app_enter", "software" to "software.settings",
            "call" to "call.cancelled", "group" to "group.turn_requested",
            "helpers" to "helper.wake_requested").forEach { (grant, event) ->
            assertTrue(EngineWindowPolicy.maySubmit(true, true, setOf(grant), event))
            assertFalse(EngineWindowPolicy.maySubmit(true, true, emptySet(), event))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun noGrantCanAuthorizeSubjectCreation() {
        EngineWindowPolicy.maySubmit(true, true, EngineWindowPolicy.grants, "engine.create_subject")
    }
}
