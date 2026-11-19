package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.chat.ContextUsageDiagnostics
import java.text.NumberFormat

@Composable
internal fun ContextUsageDialog(chat: String?, windowLimit: Long, onClose: () -> Unit) {
    val snapshots by ContextUsageDiagnostics.byChat.collectAsState()
    val snapshot = chat?.let { snapshots[it] }
    val numbers = NumberFormat.getIntegerInstance()
    val percentage = NumberFormat.getPercentInstance()
    fun window(value: Long): String = numbers.format(value) + if (windowLimit > 0) {
        "（${percentage.format(value.toDouble() / windowLimit)}）"
    } else ""
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("窗口占用分项") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (snapshot == null) {
                Text("这一窗口还没有占用估算。打开有内容的聊天或完成一轮后可查看。")
            } else {
                Text("当前窗口 ${window(snapshot.current.windowTokens)} tokens")
                snapshot.current.parts.entries.sortedByDescending { it.value }.forEach { (name, tokens) ->
                    Text("$name　${numbers.format(tokens)}")
                }
                Text("分项是准备下一轮时的文本估算。模型的计算口径、媒体及消息封装可能让总量与分项之和不同。",
                    style = MaterialTheme.typography.bodySmall)
                snapshot.summary?.let { comparison ->
                    Text("最近一次压缩 · 前 → 后", style = MaterialTheme.typography.titleSmall)
                    Text("${window(comparison.before.windowTokens)} → ${window(comparison.after.windowTokens)} tokens")
                    val names = comparison.before.parts.keys + comparison.after.parts.keys
                    names.forEach { name ->
                        Text("$name　${numbers.format(comparison.before.parts[name] ?: 0)} → ${numbers.format(comparison.after.parts[name] ?: 0)}")
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }
    )
}
