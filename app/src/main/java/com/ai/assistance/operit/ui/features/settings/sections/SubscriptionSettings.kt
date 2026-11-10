package com.ai.assistance.operit.ui.features.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.api.DeviceAuthorization
import com.ai.assistance.operit.data.api.GeminiAuthorization
import com.ai.assistance.operit.data.api.SubscriptionAccounts
import com.ai.assistance.operit.data.api.SubscriptionChannels
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun SubscriptionSettings(config: ModelConfigData, manager: ModelConfigManager, onCodex: () -> Unit = {}) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val accounts = remember { SubscriptionAccounts.get(context) }
    var expanded by remember(config.id) { mutableStateOf(config.subscriptionChannel.isNotEmpty()) }
    var selected by remember(config.id, config.subscriptionChannel) { mutableStateOf(config.subscriptionChannel.ifEmpty { "codex" }) }
    var secret by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var accepted by remember(selected) { mutableStateOf(false) }
    var auth by remember { mutableStateOf<DeviceAuthorization?>(null) }
    var googleAuth by remember { mutableStateOf<GeminiAuthorization?>(null) }
    var googleCode by remember { mutableStateOf("") }
    var googleProject by remember { mutableStateOf("") }
    var manualModel by remember(selected) { mutableStateOf("") }
    var operation by remember { mutableStateOf<Job?>(null) }
    var models by remember(selected) { mutableStateOf(emptyList<JSONObject>()) }
    var quota by remember(selected) { mutableStateOf<JSONObject?>(null) }
    var quotaOpen by remember { mutableStateOf(false) }
    val channel = SubscriptionChannels.get(selected)
    LaunchedEffect(selected) {
        auth?.let { accounts.cancelLogin(it.attempt) }; operation?.cancel(); auth = null; busy = false
        googleAuth?.let { accounts.cancelLogin(it.attempt) }; googleAuth = null; googleCode = ""; secret = ""
        connected = withContext(Dispatchers.IO) { accounts.connected(selected) }
        status = ""
    }
    DisposableEffect(Unit) { onDispose {
        auth?.let { accounts.cancelLogin(it.attempt) }; googleAuth?.let { accounts.cancelLogin(it.attempt) }; operation?.cancel()
    } }
    LaunchedEffect(googleAuth?.attempt) {
        val pending = googleAuth ?: return@LaunchedEffect
        delay((pending.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
        accounts.cancelLogin(pending.attempt); operation?.cancel(); googleAuth = null; googleCode = ""
        status = "Google 授权已超时，可重新登录"
    }
    fun run(block: suspend () -> Unit) {
        operation = scope.launch {
            busy = true; status = ""
            try { block(); connected = withContext(Dispatchers.IO) { accounts.connected(selected) } }
            catch (e: CancellationException) { status = "已取消"; throw e }
            catch (e: Exception) { status = e.message ?: "请求失败"; com.ai.assistance.operit.util.AppLogger.e("SubscriptionSettings", "订阅操作失败", e) }
            finally { busy = false }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "订阅账号 ▴" else "订阅账号 ▾") }
            if (expanded) {
                Text("选择订阅渠道", style = MaterialTheme.typography.labelLarge)
                SubscriptionChannels.entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { entry ->
                            FilterChip(selected = selected == entry.id, onClick = { selected = entry.id },
                                label = { Text(entry.title) }, enabled = !busy, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text(channel.limitation, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { uri.openUri(channel.documentation) }) { Text("官方说明") }
                    TextButton(onClick = { uri.openUri(channel.accountPage) }) { Text("账号与额度") }
                }
                if (selected == "codex") {
                    if (config.subscriptionChannel.isNotEmpty()) {
                        TextButton(onClick = { run { manager.updateSubscription(config.id, "", config.modelName) } }, enabled = !busy) {
                            Text("解除当前绑定，打开原有 Codex 配置")
                        }
                    } else TextButton(onClick = onCodex) { Text("使用下方原有 Codex 登录") }
                } else if (channel.endpoint == null) {
                    Text("当前未开放，未保存会员凭据；按量 API 仍是单独的配置。", style = MaterialTheme.typography.labelMedium)
                } else {
                    Text(if (connected) "已保存凭据；实际权限以服务返回为准" else "未连接账号")
                    Row {
                        Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                        Text("我已了解上方服务限制、账号风险及计费范围，并选择连接此渠道。",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                    if (selected in setOf("kimi", "glm")) {
                        OutlinedTextField(secret, { secret = it }, label = { Text("${channel.title} 套餐 Key") },
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        Button(onClick = { run { withContext(Dispatchers.IO) { accounts.savePlanKey(selected, secret) }; secret = ""; status = "凭据已加密保存，请读取模型目录或填写账号支持的模型" } },
                            enabled = accepted && secret.isNotBlank() && !busy) { Text(if (connected) "更换凭据" else "连接套餐") }
                    } else if (selected == "gemini") {
                        OutlinedTextField(googleProject, { googleProject = it }, label = { Text("Google Cloud 项目 ID（个人会员通常无需填写）") },
                            modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        Button(onClick = {
                            googleAuth?.let { accounts.cancelLogin(it.attempt) }
                            val pending = accounts.beginGemini(); googleAuth = pending; googleCode = ""
                            uri.openUri(pending.url)
                        }, enabled = accepted && !busy) { Text(if (connected) "重新授权 / 切换 Google 账号" else "在 Google 授权") }
                        googleAuth?.let { pending ->
                            Text("完成 Google 授权后，将网页显示的授权码填在这里。有效至 ${java.util.Date(pending.expiresAt)}。", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(googleCode, { googleCode = it }, label = { Text("本次 Google 授权码") },
                                modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { uri.openUri(pending.url) }, enabled = !busy) { Text("打开授权网页") }
                                Button(onClick = { run {
                                    accounts.completeGemini(pending, googleCode, googleProject)
                                    googleAuth = null; googleCode = ""; status = "已授权并取得 Code Assist 项目，可读取模型或填写账号模型"
                                } }, enabled = accepted && !busy && googleCode.isNotBlank()) { Text("完成连接") }
                                TextButton(onClick = {
                                    accounts.cancelLogin(pending.attempt); operation?.cancel(); googleAuth = null; googleCode = ""; status = "已取消"
                                }) { Text("取消授权") }
                            }
                        }
                    } else if (selected == "grok") {
                        Button(onClick = { run {
                            val pending = accounts.beginGrok(); auth = pending
                            uri.openUri(pending.url)
                            accounts.awaitGrok(pending); auth = null; status = "已授权，请读取账号模型目录"
                        } }, enabled = accepted && !busy) { Text(if (connected) "重新登录 / 切换账号" else "在 xAI 登录") }
                    }
                    auth?.let { pending ->
                        SelectionContainer { Text("授权码：${pending.userCode}\n截止：${java.util.Date(pending.expiresAt)}") }
                        TextButton(onClick = { uri.openUri(pending.url) }) { Text("重新打开授权网页") }
                    }
                    if (busy) TextButton(onClick = { auth?.let { accounts.cancelLogin(it.attempt) }; operation?.cancel(); auth = null }) { Text("取消") }
                    if (connected) {
                        Row {
                            TextButton(onClick = { run { models = accounts.models(selected); status = "目录来自本次服务返回，未调用模型生成内容" } }, enabled = !busy) { Text("读取模型") }
                            TextButton(onClick = { run { quota = accounts.usage(selected); quotaOpen = true } }, enabled = !busy) { Text("查询额度") }
                            TextButton(onClick = { run { withContext(Dispatchers.IO) { accounts.logout(selected) }; models = emptyList(); quota = null; status = "已退出，助手资料保留" } }, enabled = !busy) { Text("退出") }
                        }
                        models.forEach { model ->
                            OutlinedButton(onClick = { run {
                                manager.updateSubscription(config.id, selected, model.getString("id"),
                                    if (model.isNull("context_window")) null else model.getLong("context_window"))
                                status = "已用于此模型配置，原助手和对话保持不变"
                            } }, enabled = !busy) {
                                Text(model.getString("id") + if (model.isNull("context_window")) " · 服务未提供窗口大小" else " · 窗口 ${model.get("context_window")}")
                            }
                        }
                        if (selected in setOf("gemini", "glm")) {
                            Text("目录未提供模型时，可填写官方账号支持的模型；填写不代表服务端已授予使用权限。窗口大小沿原模型设置核对。", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(manualModel, { manualModel = it }, label = { Text("模型名称") },
                                modifier = Modifier.fillMaxWidth(), enabled = !busy)
                            Button(onClick = { run {
                                manager.updateSubscription(config.id, selected, manualModel.trim())
                                status = "已绑定此订阅渠道，助手身份、内在状态和历史保持不变"
                            } }, enabled = !busy && manualModel.isNotBlank()) { Text("使用此模型") }
                        }
                    }
                    if (config.subscriptionChannel.isNotEmpty()) {
                        Text("此配置使用 ${SubscriptionChannels.get(config.subscriptionChannel).title} · ${config.modelName}")
                        TextButton(onClick = { run { manager.updateSubscription(config.id, "", config.modelName) } }, enabled = !busy) { Text("解除订阅绑定，返回普通配置") }
                    }
                    Text("额度未知时不会显示无限。额度查询失败不关闭聊天；请求失败不会切换其他付费通道。", style = MaterialTheme.typography.labelSmall)
                }
                if (status.isNotBlank()) SelectionContainer { Text(status, style = MaterialTheme.typography.bodySmall) }
                if (quotaOpen) AlertDialog(onDismissRequest = { quotaOpen = false }, title = { Text("服务返回的额度") },
                    text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("更新时间和字段均来自本次查询。没有提供的额度、恢复时间或费用状态仍为未知。")
                        Text(quota?.toString(2) ?: "未知")
                    } } }, confirmButton = { TextButton(onClick = { quotaOpen = false }) { Text("关闭") } })
            }
        }
    }
}
