package com.ai.assistance.operit.ui.main.layout

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver

/** This state changes presentation only; the chat service continues running when the list is shown. */
@Stable
class ChatNavigationState(initialDirectory: Boolean = true) {
    var directoryVisible by mutableStateOf(initialDirectory)
        private set
    var externalRequest by mutableLongStateOf(0L)
        private set
    fun requestConversation() { openConversation(); externalRequest += 1 }
    fun openConversation() { directoryVisible = false }
    fun showDirectory() { directoryVisible = true }
    companion object {
        val saver = Saver<ChatNavigationState, Boolean>(save = { it.directoryVisible }, restore = { ChatNavigationState(it) })
    }
}

val LocalRouteSurfaceVisible = compositionLocalOf { true }

val LocalChatNavigation = compositionLocalOf<ChatNavigationState?> { null }
