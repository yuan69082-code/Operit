package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.CompanionStore
import com.ai.assistance.operit.core.companion.InnerFoundations
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class PrivateTimeChoice(val key: String, val title: String, val detail: String, val value: String, val enabled: Boolean = true)

/** Configuration only: listing a source never calls MCP, reads a vault or runs a model. */
internal suspend fun privateMemoryChoices(context: Context, role: String): List<PrivateTimeChoice> = withContext(Dispatchers.IO) {
    val manager = PackageManager.getInstance(context, AIToolHandler.getInstance(context))
    val access = CharacterCardToolAccessResolver.getInstance(context).resolve(role, manager)
    buildList {
        if (access.isBuiltinToolAllowed("query_memory")) add(PrivateTimeChoice("local", "软件本地记忆",
            "使用本助手已有的本地记忆与工具权限", "使用软件已有的本地记忆能力，回顾和整理本助手自己的记忆。"))
        manager.getAvailableServerPackages().toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, server) ->
            if (name in access.allowedMcpServerNames) add(PrivateTimeChoice("mcp:$name", name,
                "MCP · " + server.description.take(240), "使用已启用的 MCP 服务「$name」，回顾和整理本助手自己的记忆；资料范围沿用该服务现有配置。"))
        }
        access.allowedPackageNames.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { name ->
            if (!manager.isToolPkgContainer(name)) manager.getPackageTools(name)?.let { pack ->
                add(PrivateTimeChoice("package:$name", name, "工具包 · " + pack.description.resolve(context).take(240),
                    "使用已启用的工具包「$name」，回顾和整理本助手自己的记忆；资料范围沿用该工具现有配置。"))
            }
        }
    }
}

internal fun privateFatigueChoice(row: JSONObject): PrivateTimeChoice {
    val c = row.getJSONObject("content")
    val label = InnerFoundations.find(c.optString("category_ref"))?.name.orEmpty()
    val meaning = c.optString("understanding").ifBlank { c.optString("reason") }
    val intensity = c.optDouble("intensity", Double.NaN)
    return PrivateTimeChoice(row.getString("id"), meaning.take(100).ifBlank { label.ifBlank { "未命名感受" } },
        listOf(label, if (intensity.isFinite()) "当前强度 $intensity" else "尚未确定强度，暂不能用于数值提醒")
            .filter(String::isNotBlank).joinToString(" · "), row.getString("id"), intensity.isFinite())
}

/** Eight current records per page, never the complete psychological history. */
internal fun privateFatigueChoices(context: Context, role: String, offset: Int): Pair<List<PrivateTimeChoice>, Boolean> {
    val rows = CompanionStore(context).activityPage(role, "episode", null, offset)
    return rows.take(8).map(::privateFatigueChoice) to (rows.size > 8)
}

@Composable
internal fun PrivateTimeChoiceDialog(
    title: String, explanation: String, paged: Boolean = false,
    load: suspend (Int) -> Pair<List<PrivateTimeChoice>, Boolean>,
    onChoose: (PrivateTimeChoice) -> Unit, onDismiss: () -> Unit
) {
    var offset by remember { mutableIntStateOf(0) }
    var options by remember { mutableStateOf(emptyList<PrivateTimeChoice>()) }
    var next by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(offset) {
        busy = true
        try { val result = load(offset); options = result.first; next = result.second; error = "" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { options = emptyList(); next = false; error = failure.message ?: "读取失败" }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(explanation)
            if (!paged) OutlinedTextField(query, { query = it }, label = { Text("按名称查找，例如 ob") }, singleLine = true)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            val visible = options.filter { query.isBlank() || it.title.contains(query, true) || it.detail.contains(query, true) }
            if (!busy && error.isBlank() && visible.isEmpty()) Text(if (paged)
                "这一页没有已有感受。尚未形成疲劳记录时可以先不启用疲劳提醒，定时睡觉提醒仍可使用。"
                else "没有找到。请在工具页确认服务已启用，并已允许这位助手使用。")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(visible, key = { it.key }) { option ->
                    TextButton(onClick = { onChoose(option) }, enabled = !busy && option.enabled) {
                        Column(Modifier.fillMaxWidth()) { Text(option.title); Text(option.detail, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (paged) Row {
                TextButton(enabled = !busy && offset > 0, onClick = { offset -= 8 }) { Text("上一页") }
                TextButton(enabled = !busy && next, onClick = { offset += 8 }) { Text("下一页") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}
