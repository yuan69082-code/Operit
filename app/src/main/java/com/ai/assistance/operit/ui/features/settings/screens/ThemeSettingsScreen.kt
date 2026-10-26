package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.ui.features.settings.screens.theme.ThemeSettingsContent

@Composable
fun ThemeSettingsScreen() {
    ThemeSettingsContent()
}

/** Reuses the full editor without changing the globally active chat assistant. */
@Composable
internal fun AssistantThemeDialog(role: String, onDismiss: () -> Unit) {
    var closeRequest by remember(role) { mutableIntStateOf(0) }
    Dialog(onDismissRequest = { closeRequest++ }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                SpaceHeader("主题与外观", { closeRequest++ })
                ThemeSettingsContent(fixedTarget = ActivePrompt.CharacterCard(role), closeRequest = closeRequest, onClose = onDismiss)
            }
        }
    }
}
