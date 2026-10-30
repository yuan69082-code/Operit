package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The caller freezes this visible snapshot until save/cancel; passive polling never edits a draft. */
@Composable
internal fun InternalStateEditDialog(
    state: JSONObject,
    save: suspend (StateEditSubmission) -> Unit,
    close: () -> Unit,
) {
    val coroutine = rememberCoroutineScope()
    var draft by remember { mutableStateOf(stateEditDraft(state)) }
    var expandedRules by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val hadTimeRules = stateEditDraft(state).time != null
    Dialog(onDismissRequest = { if (!saving) close() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !saving, dismissOnClickOutside = !saving,
    )) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f).imePadding(),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("编辑 · " + state.getString("name"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !saving, onClick = close) { Text("取消") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("保存后记为用户修改，助手之后会收到更新后的状态。", style = MaterialTheme.typography.bodySmall)
                    StateEditNumberField("当前值", draft.value, !saving,
                        "范围 " + stateEditNumber(state.getDouble("min")) + " 至 " + stateEditNumber(state.getDouble("max")),
                        { draft = draft.copy(value = it) })
                    OutlinedTextField(value = draft.reason, onValueChange = { draft = draft.copy(reason = it) }, enabled = !saving,
                        label = { Text("当前状态 / 修改原因") }, supportingText = { Text("会保存在当前状态与本次变化记录中") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                    Text("分类", style = MaterialTheme.typography.titleSmall)
                    if (draft.kind == "unclassified") Text("当前尚未分类，可以选择下面四类。", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("drive" to "驱力", "sentiment" to "情感", "emotion" to "情绪", "psychology" to "心理").forEach { (kind, label) ->
                            FilterChip(selected = draft.kind == kind, enabled = !saving,
                                onClick = { draft = draft.copy(kind = kind) }, label = { Text(label) })
                        }
                    }
                    Text(if (state.getString("scope") == "global")
                        "当前值和原因在这位助手的各窗口间共用。分类与时间规则也作用于这个状态的各窗口。"
                    else "当前值和原因只修改此会话；分类与时间规则作用于这位助手的同一状态。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("时间涨落规则", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                TextButton(enabled = !saving, onClick = { expandedRules = !expandedRules }) {
                                    Text(if (expandedRules) "收起 ▴" else "展开 ▾")
                                }
                            }
                            if (expandedRules) {
                                if (!hadTimeRules) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("定义新的时间规则", Modifier.weight(1f))
                                        Switch(checked = draft.time != null, enabled = !saving,
                                            onCheckedChange = { draft = draft.copy(time = if (it) StateTimeDraft() else null) })
                                    }
                                    if (draft.time == null) Text("仍为未定义；只改当前值、原因或分类时保留原演化规则。", style = MaterialTheme.typography.bodySmall)
                                }
                                val time = draft.time
                                if (time != null) {
                                    Text("自定义数字支持 0、小数及超过 30 的数值。", style = MaterialTheme.typography.bodySmall)
                                    StateEditDirection("增加", time.increase, !saving) { draft = draft.copy(time = time.copy(increase = it)) }
                                    HorizontalDivider()
                                    StateEditDirection("减少", time.decrease, !saving) { draft = draft.copy(time = time.copy(decrease = it)) }
                                    StateEditNumberField("时间下降最低值", time.floor, !saving,
                                        "由你填写，30 只是示例；不强行抬高低于此值的当前状态。",
                                        { draft = draft.copy(time = time.copy(floor = it)) })
                                    if (time.increase.condition == "not_contacted" || time.decrease.condition == "not_contacted") {
                                        StateEditNumberField("未联系等待（分钟）", time.contactWindow, !saving,
                                            "0 表示无需等待；按最近真实用户消息计算，自动唤醒不算联系。",
                                            { draft = draft.copy(time = time.copy(contactWindow = it)) })
                                    }
                                }
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(enabled = !saving, modifier = Modifier.fillMaxWidth(), onClick = saveClick@ {
                    if (saving) return@saveClick
                    val submission = try { stateEditSubmission(state, draft) }
                    catch (failure: IllegalArgumentException) {
                        error = requireNotNull(failure.message)
                        null
                    }
                    if (submission != null) {
                        saving = true
                        error = ""
                        coroutine.launch {
                            try {
                                save(submission)
                                close()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                AppLogger.e("InternalStatePanel", "用户状态修改保存失败", failure)
                                error = when (failure) {
                                    is IllegalArgumentException, is IllegalStateException ->
                                        failure.message.orEmpty() + "；填写内容仍保留，请核对后再保存。若状态已变化，请取消并重新打开详情。"
                                    else -> "修改未保存，填写内容仍保留，请稍后重试。"
                                }
                            } finally { saving = false }
                        }
                    }
                }) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (saving) "正在保存" else "保存修改")
                }
            }
        }
    }
}

@Composable
private fun StateEditDirection(label: String, value: StateDirectionDraft, enabled: Boolean, change: (StateDirectionDraft) -> Unit) {
    Text(label + "条件", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("always" to "任何", "not_contacted" to "未联系", "custom" to "自定义").forEach { (condition, text) ->
            FilterChip(selected = value.condition == condition, enabled = enabled,
                onClick = { change(value.copy(condition = condition)) }, label = { Text(text) })
        }
    }
    OutlinedTextField(value = value.reason, onValueChange = { change(value.copy(reason = it)) }, enabled = enabled,
        label = { Text(label + "原因") }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4)
    if (value.condition == "custom") Text("自定义条件由模型确认是否成立；修改条件后等待重新确认。", style = MaterialTheme.typography.bodySmall)
    StateEditNumberField(label + "间隔（分钟）", value.interval, enabled,
        "0 或负数暂停这个方向的时间变化。", { change(value.copy(interval = it)) })
    StateEditNumberField("每次" + label + "多少", value.amount, enabled,
        "0 不变化；负数按相反方向变化。", { change(value.copy(amount = it)) })
}

@Composable
private fun StateEditNumberField(label: String, value: String, enabled: Boolean, help: String, change: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value = value, onValueChange = change, enabled = enabled, singleLine = true,
            label = { Text(label) }, supportingText = { Text(help) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().focusRequester(focus), trailingIcon = {
                TextButton(enabled = enabled, onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("选数 ▾") }
            })
        DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
            DropdownMenuItem(text = { Text("自定义数字") }, onClick = {
                menu = false
                focus.requestFocus()
                keyboard?.show()
            })
            (0..30).forEach { number ->
                DropdownMenuItem(text = { Text(number.toString()) }, onClick = { menu = false; change(number.toString()) })
            }
        }
    }
}
