package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Displays XC's structured dynamic_state only. Private handoff/box material is never rendered. */
@Composable
internal fun XcEmotionDashboard(receipt: JSONObject, enabled: Boolean) {
    val snapshot = receipt.optJSONObject("snapshot")
    val sections = snapshot?.optJSONArray("sections")
    val dynamic = sections?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        .firstOrNull { it.optString("id") == "dynamic_state" }?.optJSONObject("data") }
    val emotion = dynamic?.optJSONObject("emotion")
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("心潮 · 当前状态", style = MaterialTheme.typography.titleLarge)
            Text(if (enabled) "随对话同步" else "自动同步已关闭", color = MaterialTheme.colorScheme.primary)
            if (receipt.optString("error").isNotBlank()) Text(receipt.getString("error"), color = MaterialTheme.colorScheme.error)
            if (snapshot == null || emotion == null) {
                Text("还没有情绪快照。绑定并开启后，下一次对话会自动从 XC 读取。")
            } else {
                Text(emotion.optString("shown").ifBlank { emotion.optString("label") }, style = MaterialTheme.typography.headlineMedium)
                Text("同步于 " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(receipt.getLong("synced_at"))), style = MaterialTheme.typography.bodySmall)
                Text("这是最近一次对话收到的状态，未同步期间的变化以 XC 为准。", style = MaterialTheme.typography.bodySmall)
                val valence = emotion.optDouble("valence", Double.NaN)
                val arousal = emotion.optDouble("arousal", Double.NaN)
                if (valence in 0.0..1.0 && arousal in 0.0..1.0) {
                    val pointColor = MaterialTheme.colorScheme.primary
                    val gridColor = MaterialTheme.colorScheme.outlineVariant
                    Text("情绪坐标 · 上方更活跃，右侧更愉悦", style = MaterialTheme.typography.labelLarge)
                    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics {
                        contentDescription = "XC 情绪坐标：愉悦度 ${(valence * 100).roundToInt()}%，活跃度 ${(arousal * 100).roundToInt()}%"
                    }) {
                        val padding = 12.dp.toPx()
                        val width = size.width - padding * 2
                        val height = size.height - padding * 2
                        drawRect(gridColor.copy(alpha = 0.12f))
                        drawLine(gridColor, Offset(padding, size.height / 2), Offset(size.width - padding, size.height / 2))
                        drawLine(gridColor, Offset(size.width / 2, padding), Offset(size.width / 2, size.height - padding))
                        val center = Offset(padding + width * valence.toFloat(), padding + height * (1 - arousal.toFloat()))
                        drawCircle(pointColor.copy(alpha = 0.16f), 20.dp.toPx(), center)
                        drawCircle(pointColor, 7.dp.toPx(), center)
                    }
                    XcMetric("愉悦度", valence)
                    XcMetric("活跃度", arousal)
                }
                val trend = emotion.optJSONObject("trend")
                val labels = trend?.optJSONArray("labels")
                if (labels != null && labels.length() > 0) {
                    Text("近 ${checkNotNull(trend).optInt("hours")} 小时的变化", style = MaterialTheme.typography.titleSmall)
                    Text((0 until labels.length()).map { labels.getString(it) }.joinToString(" → "))
                }
                val drives = dynamic?.optJSONArray("topDrives")
                if (drives != null && drives.length() > 0) {
                    HorizontalDivider()
                    Text("当前较强的驱力", style = MaterialTheme.typography.titleSmall)
                    for (i in 0 until drives.length()) {
                        val drive = drives.getJSONObject(i)
                        XcMetric(drive.optString("label"), drive.optDouble("value", Double.NaN))
                    }
                }
            }
        }
    }
}

@Composable
private fun XcMetric(label: String, value: Double) {
    if (value !in 0.0..1.0) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
    }
    LinearProgressIndicator(progress = { value.toFloat() }, modifier = Modifier.fillMaxWidth())
}
