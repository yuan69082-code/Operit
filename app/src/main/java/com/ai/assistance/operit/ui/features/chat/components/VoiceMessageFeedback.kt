package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel

@Composable
fun VoiceMessageOutboxRows(viewModel: ChatViewModel, chatId: String?) {
    val all by viewModel.voiceMessages.collectAsState()
    val pending = all.filter { it.chatId == chatId }
    if (pending.isEmpty()) return
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(pending, key = { it.id }) { item ->
            Surface(shape = RoundedCornerShape(16.dp),
                color = if (item.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(.88f).clickable(enabled = item.failed && !item.interrupted && (!item.analysisFailed || item.sendWithoutAnalysis)) { viewModel.retryVoiceMessage(item.id) }) {
                Column(Modifier.padding(12.dp)) {
                    Text("[语音条] ${item.durationMillis / 1000} 秒 · ${item.status}",
                        color = if (item.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium)
                    if (item.interrupted) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.discardInterruptedVoiceMessage(item.id) }) { Text("重录") }
                            TextButton(onClick = { viewModel.retryVoiceMessage(item.id) }) { Text("发送") }
                        }
                    } else if (item.failed && item.analysisFailed && !item.sendWithoutAnalysis && item.text.isNotBlank()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.retryVoiceMessage(item.id, reanalyze = true) }) { Text("重新分析") }
                            TextButton(onClick = { viewModel.sendVoiceMessageWithoutAnalysis(item.id) }) { Text("直接发送") }
                        }
                    }
                    if (!item.failed) {
                        Spacer(Modifier.height(6.dp))
                        if (item.total > 1) LinearProgressIndicator(
                            progress = { item.completed.toFloat() / item.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Visual overlay only: the microphone retains the original pointer gesture until release. */
@Composable
fun VoiceRecordingFeedback(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val recording by viewModel.voiceRecording.collectAsState()
    if (!recording.active) return
    val color = MaterialTheme.colorScheme.primary
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.weight(1f).height(40.dp)) {
                val levels = List(48 - recording.levels.size) { 0f } + recording.levels
                val step = size.width / levels.size
                levels.forEachIndexed { i, level ->
                    val amplitude = (level * 8).coerceIn(.06f, 1f) * size.height / 2
                    val x = (i + .5f) * step
                    drawLine(color, Offset(x, size.height / 2 - amplitude), Offset(x, size.height / 2 + amplitude),
                        strokeWidth = (step * .5f).coerceAtLeast(1f), cap = StrokeCap.Round)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${recording.millis / 1000} 秒", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (recording.cancelArmed) "松开取消" else "松开发送 · 上滑取消",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (recording.cancelArmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
