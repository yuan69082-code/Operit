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
    var toneIndex by remember { mutableStateOf<Int?>(null) }
    var bookIndex by remember { mutableStateOf<Int?>(null) }
    var transformIndex by remember { mutableStateOf<Int?>(null) }
    var expression by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("both") }
    var sample by remember { mutableStateOf("") }
    var transformed by remember { mutableStateOf("") }
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
                if (toneIndex == null) rows.put(tone.trim()) else rows.put(requireNotNull(toneIndex), tone.trim())
                next.put("tone", rows); save(next)
                if (error.isEmpty()) { tone = ""; toneIndex = null }
            }) { Text(if (toneIndex == null) "添加偏好" else "保存偏好修改") }
            val tones = document.optJSONArray("tone")
            if (tones != null) for (i in 0 until tones.length()) {
                TextButton(onClick = { tone = tones.getString(i); toneIndex = i }) { Text("编辑：${tones.getString(i)}") }
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
                    val entry = JSONObject().put("pattern", pattern).put("text", body).put("always", always)
                        .put("depth", depth.toInt()).put("priority", priority.toInt())
                    if (bookIndex == null) rows.put(entry) else rows.put(requireNotNull(bookIndex), entry)
                    next.put("worldbook", rows); save(next)
                    if (error.isEmpty()) { pattern = ""; body = ""; bookIndex = null }
                } catch (e: Exception) { AppLogger.e("RoleRules", "Invalid rule", e); error = e.message.orEmpty() }
            }) { Text(if (bookIndex == null) "添加资料规则" else "保存资料修改") }
            val rules = document.optJSONArray("worldbook")
            if (rules != null) for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                Text(rule.getString("text"))
                TextButton(onClick = {
                    bookIndex = i; pattern = rule.optString("pattern"); body = rule.getString("text")
                    always = rule.optBoolean("always"); depth = rule.optInt("depth", 6).toString(); priority = rule.optInt("priority", 0).toString()
                }) { Text("编辑资料规则") }
                TextButton(onClick = {
                    val next = JSONObject(document.toString()); next.getJSONArray("worldbook").remove(i); save(next)
                }) { Text("${rule.optString("pattern")} · 删除") }
            }
            HorizontalDivider()
            Text("文字替换", style = MaterialTheme.typography.titleMedium)
            Text("输入规则作用于发给模型的正文，原消息保留；输出规则在回复完成后处理正文。代码、工具标签和附件不替换。规则按列表顺序执行。")
            Row { listOf("input" to "输入", "output" to "输出", "both" to "双向").forEach { (value, label) ->
                FilterChip(direction == value, { direction = value }, { Text(label) })
            } }
            OutlinedTextField(expression, { expression = it }, label = { Text("正则表达式") })
            OutlinedTextField(replacement, { replacement = it }, label = { Text("替换内容，可用分组引用") })
            OutlinedTextField(sample, { sample = it }, label = { Text("试验文本") })
            TextButton(onClick = {
                try {
                    val row = JSONObject().put("pattern", expression).put("replacement", replacement).put("direction", direction)
                    val rows = JSONArray().put(row)
                    com.ai.assistance.operit.core.experience.TextTransforms.validate(rows)
                    transformed = com.ai.assistance.operit.core.experience.TextTransforms.apply(sample, rows, if (direction == "both") "input" else direction)
                    error = ""
                } catch (e: Exception) { error = e.message.orEmpty() }
            }) { Text("预览替换结果") }
            if (transformed.isNotEmpty()) Text(transformed)
            TextButton(onClick = {
                val next = JSONObject(document.toString())
                val rows = next.optJSONArray("transforms") ?: JSONArray()
                val row = JSONObject().put("pattern", expression).put("replacement", replacement).put("direction", direction).put("enabled", true)
                if (transformIndex == null) rows.put(row) else rows.put(requireNotNull(transformIndex), row)
                save(next.put("transforms", rows))
                if (error.isEmpty()) { transformIndex = null; expression = ""; replacement = "" }
            }) { Text(if (transformIndex == null) "添加替换规则" else "保存替换修改") }
            val transforms = document.optJSONArray("transforms")
            if (transforms != null) for (i in 0 until transforms.length()) {
                val row = transforms.getJSONObject(i)
                Text(row.getString("pattern") + " → " + row.getString("replacement"))
                Row {
                    Checkbox(row.optBoolean("enabled", true), { enabled ->
                        val next = JSONObject(document.toString()); next.getJSONArray("transforms").getJSONObject(i).put("enabled", enabled); save(next)
                    })
                    TextButton(onClick = { transformIndex = i; expression = row.getString("pattern"); replacement = row.getString("replacement"); direction = row.getString("direction") }) { Text("编辑") }
                    TextButton(onClick = { val next = JSONObject(document.toString()); next.getJSONArray("transforms").remove(i); save(next); transformIndex = null }) { Text("删除") }
                }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("完成") } })
}
