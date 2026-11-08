package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMessageLazyListAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offscreenMessagesAreNotComposedAndLocatorIndicesRemainStable() {
        val active = mutableSetOf<Long>()
        val messages = (0L until 500L).map { ChatMessage("user", "Message $it", timestamp = it + 1) }
        compose.setContent {
            ChatMessageLazyList(messages, "chat", rememberLazyListState(),
                Modifier.size(300.dp).testTag("list"),
                header = { Spacer(Modifier.height(24.dp)) },
                footer = { Spacer(Modifier.height(16.dp)) }) { _, message ->
                DisposableEffect(message.timestamp) {
                    active.add(message.timestamp)
                    onDispose { active.remove(message.timestamp) }
                }
                Text(message.content, Modifier.height(80.dp).testTag("message:${message.timestamp}"))
            }
        }
        compose.runOnIdle { assertTrue("Offscreen bubbles must not all exist", active.size < 20) }
        compose.onNodeWithTag("list").performScrollToIndex(301)
        compose.onNodeWithTag("message:301").assertIsDisplayed()
        compose.runOnIdle { assertTrue(active.size < 20) }
    }

    @Test fun prependingHistoryRetainsAnchorAndDuplicateTimestampsAreSupported() {
        val messages = mutableStateOf((100L until 150L).map { ChatMessage("user", timestamp = it) })
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            ChatMessageLazyList(messages.value, "chat", state, Modifier.size(300.dp).testTag("list"),
                header = {}, footer = {}) { _, message ->
                Text(message.timestamp.toString(), Modifier.height(80.dp).testTag("message:${message.timestamp}"))
            }
        }
        compose.onNodeWithTag("list").performScrollToIndex(21)
        var anchor: Any? = null
        compose.runOnIdle { anchor = state.layoutInfo.visibleItemsInfo.first().key }
        compose.runOnIdle {
            messages.value = listOf(ChatMessage("user", timestamp = 1), ChatMessage("ai", timestamp = 1)) + messages.value
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(anchor, state.layoutInfo.visibleItemsInfo.first().key) }
        compose.onNodeWithTag("list").performScrollToIndex(1)
        compose.waitForIdle()
    }
}
