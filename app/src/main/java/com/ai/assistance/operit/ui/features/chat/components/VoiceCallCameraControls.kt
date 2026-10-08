package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallController
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallRuntime

@Composable
fun VoiceCallCameraControls(call: VoiceCallController, requestCamera: ((Boolean, Int) -> Unit)? = null) {
    val context = LocalContext.current
    var video by remember(call.supportsCameraVideo) { mutableStateOf(call.supportsCameraVideo) }
    var interval by remember { mutableStateOf(10) }
    Column {
        if (call.cameraEnabled) {
            call.cameraPreview?.let { bytes ->
                AsyncImage(bytes, "前置摄像头预览", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)))
            }
            Text(if (call.cameraVideoMode) "视频片段 · ${call.cameraIntervalSeconds}秒" else "单帧画面 · ${call.cameraIntervalSeconds}秒", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = call::disableCamera) { Text("关闭摄像头") }
        } else {
            if (call.supportsCameraVideo && call.supportsCameraImages) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = video, onClick = { video = true }, label = { Text("视频片段") })
                    FilterChip(selected = !video, onClick = { video = false }, label = { Text("定时画面") })
                }
            }
            if (call.supportsCameraVideo || call.supportsCameraImages) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = interval == 10, onClick = { interval = 10 }, label = { Text("10秒") })
                    FilterChip(selected = interval == 30, onClick = { interval = 30 }, label = { Text("30秒") })
                }
                TextButton(enabled = call.isRunning && call.phase != VoiceCallController.Phase.ERROR && call.phase != VoiceCallController.Phase.ENDED, onClick = {
                    if (requestCamera != null) requestCamera(video, interval)
                    else try { VoiceCallRuntime.enableCamera(context, video, interval) }
                    catch (error: Exception) { call.reportCameraError(error.message.orEmpty()) }
                }) { Text("开启摄像头") }
            } else Text("当前聊天模型未启用图片或视频输入", style = MaterialTheme.typography.bodySmall)
        }
        if (call.cameraWarning.isNotBlank()) Text(call.cameraWarning, color = MaterialTheme.colorScheme.error)
    }
}
