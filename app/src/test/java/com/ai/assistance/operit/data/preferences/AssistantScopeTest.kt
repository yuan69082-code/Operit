package com.ai.assistance.operit.data.preferences

import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AssistantScopeTest {
    @Test fun parallelGroupAndBackgroundTurnsRetainTheirOwnIdentity() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val a = async(AssistantScope.executionContext("alice")) {
            ready.await()
            repeat(20) {
                withContext(Dispatchers.IO) { yield(); assertEquals("alice", AssistantScope.callerId()) }
            }
        }
        val b = async(AssistantScope.executionContext("bob")) {
            ready.complete(Unit)
            repeat(20) {
                withContext(Dispatchers.Default) { yield(); assertEquals("bob", AssistantScope.callerId()) }
            }
        }
        awaitAll(a, b)
        assertNull(AssistantScope.callerId())
    }

    @Test fun synchronousToolBridgeDoesNotLoseAssistantOnIoDispatcher() = runBlocking {
        withContext(AssistantScope.executionContext("background-owner")) {
            val bridge = ToolExecutionManager.inheritedRuntimeContext()
            runBlocking(Dispatchers.IO + bridge) {
                assertEquals("background-owner", AssistantScope.callerId())
                withContext(Dispatchers.Default) { assertEquals("background-owner", AssistantScope.callerId()) }
            }
        }
        assertNull(AssistantScope.callerId())
    }

    @Test fun nestedAssistantRestoresParentAndCannotLeakIntoNextTurn() = runBlocking {
        withContext(AssistantScope.executionContext("primary")) {
            withContext(AssistantScope.executionContext("member")) { assertEquals("member", AssistantScope.callerId()) }
            assertEquals("primary", AssistantScope.callerId())
        }
        assertNull(AssistantScope.callerId())
    }
}
