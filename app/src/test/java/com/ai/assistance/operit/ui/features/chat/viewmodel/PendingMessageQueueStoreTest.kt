package com.ai.assistance.operit.ui.features.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMessageQueueStoreTest {
    @Test
    fun messageAddedDuringIdleDebounceDoesNotErasePendingDispatch() {
        val store = PendingMessageQueueStore()
        store.enqueue("chat-a", "first", true)
        // The background turn ends; the UI waits before consuming the transition.
        store.enqueue("chat-a", "second", false)
        assertTrue(store.consumeAutoDequeueSignal("chat-a", false))
        assertFalse(store.consumeAutoDequeueSignal("chat-a", false))
        assertEquals(listOf("first", "second"), store.states.value.getValue("chat-a").messages.map { it.text })
    }

    @Test
    fun queueRemainsAvailableWhenAnotherChatIsVisited() {
        val store = PendingMessageQueueStore()

        store.enqueue(chatId = "chat-a", text = "send after reply", isQueueBlocked = true)

        assertFalse(store.consumeAutoDequeueSignal(chatId = "chat-b", isQueueBlocked = false))
        assertEquals(listOf("send after reply"), store.states.value.getValue("chat-a").messages.map { it.text })
        assertTrue(store.consumeAutoDequeueSignal(chatId = "chat-a", isQueueBlocked = false))
    }

    @Test
    fun cancellingCurrentTurnConsumesOnlyTheNextAutoDequeue() {
        val store = PendingMessageQueueStore()

        store.enqueue(chatId = "chat-a", text = "first", isQueueBlocked = true)
        store.suppressNextAutoDequeue("chat-a")

        assertFalse(store.consumeAutoDequeueSignal(chatId = "chat-a", isQueueBlocked = false))
        assertFalse(store.consumeAutoDequeueSignal(chatId = "chat-a", isQueueBlocked = false))
    }
}
