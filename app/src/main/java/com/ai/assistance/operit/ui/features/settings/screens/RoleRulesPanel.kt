package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.experience.RoleRules
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun RoleRulesEntry(role: String) {
    var open by remember(role) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("表达偏好与动态世界书") }
    if (!open) return
    val context = LocalContext.current
    val store = remember { RoleRules(context) }
    var document by remember(role) { mutableStateOf(store.read(role)) }
    var tone by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf("6") }
    var priority by remember { mutableStateOf("0") }
    var always by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun save(next: JSONObject) {
        try { store.save(role, next); document = next; error = "" }
        catch (e: Exception) { AppLogger.e("RoleRules", "Cannot save rules", e); error = e.message.orEmpty() }
    }
    AlertDialog(onDismissRequest = { open = false }, title = { Text("角色表达与资料触发") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅作用于这位助手。表达偏好每轮加入；资料按最近消息匹配，优先级越大越靠前。")
            OutlinedTextField(tone, { tone = it }, label = { Text("添加表达偏好") })
            TextButton(enabled = tone.isNotBlank(), onClick = {
                val next = JSONObject(document.toString())
                val rows = next.optJSONArray("tone") ?: JSONArray()
                rows.put(tone.trim()); next.put("tone", rows); save(next); tone = ""
            }) { Text("添加偏好") }
            val tones = document.optJSONArray("tone")
            if (tones != null) for (i in 0 until tones.length()) {
                TextButton(onClick = {
                    val next = JSONObject(document.toString()); next.getJSONArray("tone").remove(i); save(next)
                }) { Text("${tones.getString(i)} · 删除") }
            }
            HorizontalDivider()
            Text("动态世界书", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(pattern, { pattern = it }, label = { Text("匹配文字或正则表达式") })
            Row { Checkbox(always, { always = it }); Text("始终加入") }
            OutlinedTextField(body, { body = it }, label = { Text("触发后提供的资料") })
            OutlinedTextField(depth, { depth = it }, label = { Text("检查最近多少条消息（1–100）") })
            OutlinedTextField(priority, { priority = it }, label = { Text("优先级") })
            TextButton(enabled = body.isNotBlank() && (always || pattern.isNotBlank()), onClick = {
                try {
                    val next = JSONObject(document.toString())
                    val rows = next.optJSONArray("worldbook") ?: JSONArray()
                    rows.put(JSONObject().put("pattern", pattern).put("text", body).put("always", always)
                        .put("depth", depth.toInt()).put("priority", priority.toInt()))
                    next.put("worldbook", rows); save(next)
                    if (error.isEmpty()) { pattern = ""; body = "" }
                } catch (e: Exception) { AppLogger.e("RoleRules", "Invalid rule", e); error = e.message.orEmpty() }
            }) { Text("添加资料规则") }
            val rules = document.optJSONArray("worldbook")
            if (rules != null) for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                Text(rule.getString("text"))
                TextButton(onClick = {
                    val next = JSONObject(document.toString()); next.getJSONArray("worldbook").remove(i); save(next)
                }) { Text("${rule.optString("pattern")} · 删除") }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("完成") } })
}
