package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.preferences.VoiceCallAnalysisPreferences

@Composable
fun VoiceCallAnalysisSettingsButton() {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }) { Text("通话音频分析设置") }
    if (show) VoiceCallAnalysisSettingsDialog { show = false }
}

@Composable
fun VoiceCallAnalysisSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { VoiceCallAnalysisPreferences(context) }
    var endpoint by remember { mutableStateOf(prefs.endpoint) }
    var model by remember { mutableStateOf(prefs.model) }
    var key by remember { mutableStateOf(prefs.apiKey) }
    var prompt by remember { mutableStateOf(prefs.extraPrompt) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("通话音频分析") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("录音由独立音频模型转写并描述声音，当前聊天模型收到文字。设置在本机保存。")
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("完整 HTTPS 接口地址") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("音频模型") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(prompt, { prompt = it }, label = { Text("补充背景（可选）") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Text("可补充称呼或背景；听不清的候选词仍会保留。不会固定说话者身份，也不会强行纠正同音字。")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            try { prefs.save(endpoint, model, key, prompt); onDismiss() }
            catch (failure: IllegalArgumentException) { error = failure.message.orEmpty() }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
