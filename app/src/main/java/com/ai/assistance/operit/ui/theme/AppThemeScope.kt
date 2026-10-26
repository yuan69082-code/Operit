package com.ai.assistance.operit.ui.theme

import androidx.compose.runtime.*

/** Owned by the main window, never by an assistant or a background service. */
@Stable
class AppThemeScope {
    var conversationVisible by mutableStateOf(false)
}

val LocalAppThemeScope = compositionLocalOf<AppThemeScope?> { null }
