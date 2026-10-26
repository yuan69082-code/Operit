package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.ui.features.settings.screens.theme.ThemeSettingsContent

@Composable
fun ThemeSettingsScreen() {
    var section by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("选择外观范围", style = MaterialTheme.typography.headlineSmall)
        SpaceLinkCard("软件公共外观", "首页、好友列表、空间、工具与设置共用，不随助手切换。", Icons.Default.Palette,
            onClick = { section = "shared" })
        SpaceLinkCard("聊天窗口外观", "每位助手和每个群聊独立保存背景、字体与气泡。", Icons.Default.ChatBubbleOutline,
            onClick = { section = "chat" })
    }
    section?.let { target ->
        var closeRequest by remember(target) { mutableIntStateOf(0) }
        Dialog(onDismissRequest = { closeRequest++ }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().imePadding()) {
                    SpaceHeader(if (target == "shared") "软件公共外观" else "聊天窗口外观", { closeRequest++ })
                    ThemeSettingsContent(sharedAppearance = target == "shared", closeRequest = closeRequest, onClose = { section = null })
                }
            }
        }
    }
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
