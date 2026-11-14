package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.CompanionPlayStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The playroom is deliberately a view over durable per-assistant data, not prompt text. */
@Composable
fun CompanionPlayPanel(role: String, section: String) {
    val context = LocalContext.current
    val play = remember(role) { CompanionPlayStore(context) }
    val coroutine = rememberCoroutineScope()
    var revision by remember(role) { mutableIntStateOf(0) }
    var rows by remember(role, section) { mutableStateOf(JSONArray()) }
    var extra by remember(role, section) { mutableStateOf(JSONArray()) }
    var prefs by remember(role) { mutableStateOf(JSONObject()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var title by remember(role) { mutableStateOf("") }
    var details by remember(role) { mutableStateOf("") }
    var uses by remember(role) { mutableIntStateOf(1) }
    var editor by remember(role) { mutableStateOf(false) }

    LaunchedEffect(role, section, revision) {
        try {
            val result = withContext(Dispatchers.IO) {
                play.observeInstalledVersion(role)
                when (section) {
                    "情侣游戏屋" -> Triple(play.coupons(role), JSONArray(), JSONObject())
                    "储物柜" -> Triple(play.items(role), play.coupons(role, human = false).getJSONArray("rows"), JSONObject())
                    else -> Triple(play.notices(role), JSONArray(), play.preferences(role))
                }
            }
            rows = result.first.getJSONArray("rows")
            extra = result.second
            prefs = result.third
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message.orEmpty() }
    }

    fun apply(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        coroutine.launch {
            try {
                error = ""
                withContext(Dispatchers.IO) { action() }
                revision++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        when (section) {
            "情侣游戏屋" -> {
                Text("恋爱券铺", style = MaterialTheme.typography.headlineSmall)
                Text("你负责写券和决定送出时机；他可以提出想要什么券，不能代替你发行。")
                Button(onClick = { editor = !editor }) {
                    Icon(if (editor) Icons.Default.Close else Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (editor) "收起制券" else "制作新券")
                }
                if (editor) OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("券名") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = details, onValueChange = { details = it },
                            label = { Text("兑换说明") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("可用次数")
                            Spacer(Modifier.width(10.dp))
                            listOf(1 to "1次", 3 to "3次", -1 to "不限").forEach { (number, label) ->
                                FilterChip(selected = uses == number, onClick = { uses = number },
                                    label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                            }
                        }
                        Button(enabled = !busy && title.trim().isNotBlank() && details.trim().isNotBlank(),
                            onClick = { apply {
                                play.createCoupon(role, title, details, uses)
                                title = ""; details = ""; editor = false
                            } }) { Text("创建并暂存（同时通知他）") }
                    }
                }
                Text("我的券包与赠送记录", style = MaterialTheme.typography.titleMedium)
                if (rows.length() == 0) Text("这里还没有券。第一张由你来写。")
                for (index in 0 until rows.length()) {
                    val coupon = rows.getJSONObject(index)
                    val id = coupon.getString("id")
                    val status = coupon.getString("status")
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("🎟 ${coupon.getString("title")}", style = MaterialTheme.typography.titleMedium)
                            Text(coupon.getString("detail"), style = MaterialTheme.typography.bodySmall)
                            Text("状态：${couponStatus(status)} · 剩余：${if (coupon.getInt("remaining") == -1) "不限" else coupon.getInt("remaining")}",
                                style = MaterialTheme.typography.labelMedium)
                            when (status) {
                                "draft" -> Button(enabled = !busy, onClick = { apply { play.giftCoupon(role, id) } }) { Text("赠送给他") }
                                "requested" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !busy, onClick = { apply { play.decideRequest(role, id, true) } }) { Text("确认已完成") }
                                    OutlinedButton(enabled = !busy, onClick = { apply { play.decideRequest(role, id, false) } }) { Text("暂未完成") }
                                }
                                "used" -> Text("已用完，可在纪念记录中保留券面。")
                            }
                        }
                    }
                }
                Text("更多玩法以后可以加入这个游戏屋；现在不会额外创建积分、等级或重复记忆系统。",
                    style = MaterialTheme.typography.bodySmall)
            }

            "储物柜" -> {
                Text("AI 专属储物柜", style = MaterialTheme.typography.headlineSmall)
                Text("由他决定收藏什么、写什么、哪些物品公开。私人抽屉不会在这个界面展示。")
                Text("公开收藏", style = MaterialTheme.typography.titleMedium)
                if (rows.length() == 0) Text("他还没有公开展示的收藏品。")
                for (index in 0 until rows.length()) {
                    val item = rows.getJSONObject(index)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.getString("title"), style = MaterialTheme.typography.titleMedium)
                            Text("AI 自己保存的物品 · 已公开", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("他的券夹", style = MaterialTheme.typography.titleMedium)
                if (extra.length() == 0) Text("尚未收到兑换券。")
                for (index in 0 until extra.length()) {
                    val coupon = extra.getJSONObject(index)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("🎟 ${coupon.getString("title")}", style = MaterialTheme.typography.titleMedium)
                            Text("${couponStatus(coupon.getString("status"))} · 剩余 ${if (coupon.getInt("remaining") == -1) "不限" else coupon.getInt("remaining")} 次",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            "通知中心" -> {
                Text("AI 通知中心", style = MaterialTheme.typography.headlineSmall)
                Text("通知由软件真实事件产生。接收偏好由 AI 自己管理，界面只展示当前设置。")
                Text("他的通知偏好", style = MaterialTheme.typography.titleMedium)
                val categories = mapOf("coupon_draft" to "你新建但未赠送的券", "coupon_gift" to "已赠送的券",
                    "coupon_result" to "兑换结果", "games" to "新增游戏", "updates" to "软件更新",
                    "gifts" to "礼物", "study" to "书房")
                for ((category, label) in categories) {
                    Text("$label：${when (prefs.optString(category, "next")) {
                        "wake" -> "允许主动唤醒"
                        "archive" -> "仅存档"
                        else -> "下次阅读"
                    }}")
                }
                HorizontalDivider()
                Text("历史通知（每次最多展示20条）", style = MaterialTheme.typography.titleMedium)
                if (rows.length() == 0) Text("暂时没有通知。")
                for (index in 0 until rows.length()) {
                    val notice = rows.getJSONObject(index)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(notice.getString("body"), style = MaterialTheme.typography.bodyMedium)
                            Text(when (notice.getString("status")) {
                                "new" -> "未送达AI本轮"
                                "surfaced" -> "已进入一次模型请求，未标记已读"
                                "read" -> "AI已标记已读"
                                else -> "已存档"
                            }, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

private fun couponStatus(status: String) = when (status) {
    "draft" -> "未赠送"
    "gifted" -> "已赠送"
    "requested" -> "待确认兑换"
    "used" -> "已用完·纪念"
    else -> status
}
