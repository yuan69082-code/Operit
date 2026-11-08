package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.ai.assistance.operit.data.model.ChatMessage

/** Header and footer always occupy one item each, so message N is consistently at lazy index N + 1. */
@Composable
internal fun ChatMessageLazyList(
    messages: List<ChatMessage>,
    identity: String,
    state: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    messageContent: @Composable (Int, ChatMessage) -> Unit,
) {
    val keys = remember(identity, messages) {
        val occurrences = mutableMapOf<Long, Int>()
        messages.map { message ->
            val occurrence = occurrences[message.timestamp] ?: 0
            occurrences[message.timestamp] = occurrence + 1
            "$identity:message:${message.timestamp}:$occurrence"
        }
    }
    LazyColumn(state = state, modifier = modifier, contentPadding = contentPadding) {
        item(key = "$identity:header", contentType = "header") { header() }
        items(count = messages.size, key = { keys[it] }, contentType = { messages[it].sender }) { index ->
            messageContent(index, messages[index])
        }
        item(key = "$identity:footer", contentType = "footer") { footer() }
    }
}
