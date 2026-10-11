package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.group.*
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.preferences.*
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupControls(chatId: String, groupId: String, viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { GroupStore(context) }
    val games = remember(context) { GroupGames(context) }
    val revision by GroupStore.revision.collectAsState()
    val settings = remember(chatId, revision) { store.read(chatId) }
    val draft by viewModel.userMessage.collectAsState()
    var tab by remember(chatId) { mutableStateOf("") }
    var error by remember(chatId) { mutableStateOf<String?>(null) }
    var members by remember(groupId) { mutableStateOf(emptyList<CharacterCard>()) }
    var privateChats by remember(groupId) { mutableStateOf(emptyList<ChatHistory>()) }
    var purpose by remember(chatId) { mutableStateOf(settings.optString("purpose")) }
    var rules by remember(chatId) { mutableStateOf("") }
    var title by remember(chatId) { mutableStateOf("") }
    var publicAction by remember(chatId) { mutableStateOf("") }
    var hiddenAction by remember(chatId) { mutableStateOf("") }
    var selectedCards by remember(chatId) { mutableStateOf(emptySet<Int>()) }
    var turnLimit by remember(chatId) { mutableStateOf(settings.optInt("turn_limit").toString()) }
    var minuteLimit by remember(chatId) { mutableStateOf(settings.optInt("minute_limit").toString()) }
    val core = remember(context) { ChatRuntimeHolder.getInstance(context).getCore(ChatRuntimeSlot.MAIN) }
    fun work(action: suspend () -> Unit) {
        scope.launch {
            try { error = null; action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { AppLogger.e("GroupControls", "Group action failed", failure); error = failure.message }
        }
    }
    LaunchedEffect(groupId, revision) {
        val group = CharacterGroupCardManager.getInstance(context).getCharacterGroupCard(groupId)
        members = group?.members?.sortedBy { it.orderIndex }?.map {
            CharacterCardManager.getInstance(context).getCharacterCard(it.characterCardId)
        }.orEmpty()
        privateChats = ChatHistoryManager.getInstance(context).chatHistoriesFlow.first().filter { it.characterGroupId.isNullOrBlank() }
    }
    LaunchedEffect(draft.text, draft.selection) {
        if (draft.text.take(draft.selection.end).endsWith("@")) tab = "@"
    }
    fun readable(text: String): String {
        var result = text.replace("user", "你")
        members.forEach { result = result.replace(it.id, it.name) }
        return result
    }
    fun play(move: JSONObject) = work {
        games.act(chatId, "user", move)
        selectedCards = emptySet(); publicAction = ""; hiddenAction = ""
        core.getMessageCoordinationDelegate().startGroupDiscussion(chatId)
    }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { tab = "@" }) {
            val selected = store.selected(chatId)
            Text(if (selected.isEmpty()) "@ 全员" else "@ " + members.filter { it.id in selected }.joinToString { it.name })
        }
        TextButton(onClick = { tab = "群设置" }) { Text("群设置") }
        TextButton(onClick = { tab = "成员" }) { Text("成员") }
        TextButton(onClick = { tab = "关系网" }) { Text("关系网") }
        TextButton(onClick = { tab = "游戏" }) { Text("游戏") }
        TextButton(onClick = { tab = "活动" }) { Text(if (settings.optString("status") == "running") settings.optString("speaker", "讨论中") else "活动记录") }
    }
    if (tab.isNotEmpty()) AlertDialog(onDismissRequest = { tab = "" }, title = { Text(tab) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when(tab) {
                "关系网" -> RelationshipEditor(chatId)
                "成员" -> {
                    var available by remember { mutableStateOf(emptyList<CharacterCard>()) }
                    LaunchedEffect(revision) { available = CharacterCardManager.getInstance(context).getAllCharacterCards() }
                    Text("成员沿用各自私聊的身份和记忆。离群后不再接收新群消息；已经参加的经历会保留。游戏中变更成员会结束本局。")
                    members.forEach { member -> Row {
                        Text(member.name, Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { work { core.cancelMessageAndWait(chatId)?.join(); GroupMembership.change(context, groupId, member.id, false) } }) { Text("移出") }
                    } }
                    Text("邀请加入")
                    available.filter { it.id !in members.map { member -> member.id } }.forEach { member ->
                        TextButton(onClick = { work { core.cancelMessageAndWait(chatId)?.join(); GroupMembership.change(context, groupId, member.id, true) } }) { Text("加入 ${member.name}") }
                    }
                    val audit = store.read("assistant_admin_audit").optJSONArray("log") ?: JSONArray()
                    if (audit.length() > 0) Text("最近的主助手管理操作")
                    for (i in audit.length()-1 downTo maxOf(0, audit.length()-10)) {
                        val entry = audit.getJSONObject(i)
                        Text(entry.optString("kind") + " · " + readable(entry.optString("detail")), style = MaterialTheme.typography.bodySmall)
                    }
                }
                "@" -> {
                    Text("未指定时，所有成员各回应一次。可选择多位助手；选择跟随助手身份，不受模型切换影响。")
                    TextButton(onClick = { work { store.update(chatId) { it.put("mentions", JSONArray()) } } }) { Text("@ 所有人") }
                    members.forEach { member ->
                        val selected = store.selected(chatId)
                        Row {
                            Checkbox(checked = member.id in selected, onCheckedChange = { checked -> work {
                                val next = if (checked) selected + member.id else selected - member.id
                                store.update(chatId) { it.put("mentions", JSONArray(next)) }
                            } })
                            Text(member.name, Modifier.padding(top = 12.dp))
                        }
                    }
                    TextButton(onClick = {
                        val cursor = draft.selection.end
                        if (draft.text.take(cursor).endsWith("@")) {
                            val names = members.filter { it.id in store.selected(chatId) }.joinToString(" ") { "@${it.name}" }.ifBlank { "@所有人" }
                            val text = draft.text.take(cursor-1) + names + " " + draft.text.drop(cursor)
                            viewModel.updateUserMessage(TextFieldValue(text, TextRange(cursor-1+names.length+1)))
                        }
                        tab = ""
                    }) { Text("确定") }
                }
                "群设置" -> {
                    Text("成员沿用各自的个人记忆、模型和能力。关联私聊的近期内容自动带入，较早记录按需查看。")
                    members.forEach { member ->
                        Text(member.name, style = MaterialTheme.typography.titleSmall)
                        val choices = privateChats.filter { it.characterCardId == member.id }.sortedByDescending { it.updatedAt }
                        val linked = settings.optJSONObject("links")?.optString(member.id).orEmpty()
                        Text("个人记忆：${member.name} 的独立记忆库")
                        TextButton(onClick = { work { store.update(chatId) {
                            val links = it.optJSONObject("links") ?: JSONObject(); links.remove(member.id); it.put("links", links)
                        } } }) { Text(if (linked.isBlank()) "✓ 延续最近的私聊" else "延续最近的私聊") }
                        choices.forEach { chat ->
                            TextButton(onClick = { work { store.update(chatId) {
                                val links = it.optJSONObject("links") ?: JSONObject(); links.put(member.id, chat.id); it.put("links", links)
                            } } }) { Text((if (linked == chat.id) "✓ " else "") + chat.title) }
                        }
                    }
                    HorizontalDivider()
                    Row { Switch(settings.optBoolean("continuous"), onCheckedChange = { enabled -> work { store.update(chatId) { it.put("continuous", enabled) } } }); Text("持续讨论", Modifier.padding(12.dp)) }
                    OutlinedTextField(purpose, { purpose = it }, label = { Text("讨论目的，可留空自由聊天") })
                    Row { Switch(settings.optBoolean("proactive"), onCheckedChange = { enabled -> work {
                        store.update(chatId) { it.put("proactive", enabled) }
                        if (enabled) members.forEachIndexed { index, member -> store.schedule(chatId, member.id, index+1, "了解群聊近况，决定是否开话题并安排下次醒来") }
                        else members.forEach { store.cancelWake(chatId, it.id) }
                    } }); Text("允许成员主动开话题", Modifier.padding(12.dp)) }
                    Text("首次唤醒在开启后依次安排，此后由各助手决定。活动记录显示下次时间。")
                    OutlinedTextField(turnLimit, { turnLimit = it.filter(Char::isDigit) }, label = { Text("本次最多发言次数，0 为不限") })
                    OutlinedTextField(minuteLimit, { minuteLimit = it.filter(Char::isDigit) }, label = { Text("本次最多分钟，0 为不限") })
                    TextButton(onClick = { work {
                        val turns = turnLimit.toInt(); val minutes = minuteLimit.toInt()
                        require(turns >= 0 && minutes >= 0)
                        store.update(chatId) { it.put("purpose", purpose.take(500)).put("turn_limit", turns).put("minute_limit", minutes) }
                    } }) { Text("保存讨论设置") }
                    FlowRow {
                        TextButton(onClick = { work { core.getMessageCoordinationDelegate().startGroupDiscussion(chatId, purpose = purpose) } }) { Text("开始／继续") }
                        TextButton(onClick = { core.cancelMessage(chatId) }) { Text("暂停") }
                        TextButton(onClick = { core.cancelMessage(chatId); core.getMessageCoordinationDelegate().stopGroup(chatId, false) }) { Text("结束讨论") }
                    }
                }
                "活动" -> {
                    Text("状态：" + when(settings.optString("status")) { "running" -> "讨论中"; "paused" -> "已暂停"; "error" -> "遇到错误"; "ended" -> "已结束"; else -> "空闲" })
                    val wakes = settings.optJSONObject("wakes") ?: JSONObject()
                    members.forEach { member -> wakes.optJSONObject(member.id)?.let { wake ->
                        Text(member.name + "：" + if (wake.optBoolean("paused")) "暂无下次安排" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(wake.optLong("at"))) + "\n" + wake.optString("purpose"))
                    } }
                    val logs = settings.optJSONArray("log") ?: JSONArray()
                    for (i in logs.length()-1 downTo 0) {
                        val item = logs.getJSONObject(i)
                        Text(java.text.DateFormat.getTimeInstance().format(java.util.Date(item.getLong("at"))) + " " + readable(item.optString("actor")) + " · " + item.optString("kind"), style = MaterialTheme.typography.labelMedium)
                        Text(readable(item.optString("detail")), style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
                "游戏" -> {
                    val game = games.state(chatId)
                    if (game == null || game.phase == "ended") {
                        Text("斗地主需要三位玩家；狼人杀需要4–12位，包含你。游戏身份仅在本局生效。")
                        FlowRow {
                            listOf("landlord" to "斗地主", "werewolf" to "狼人杀").forEach { (kind,label) ->
                                TextButton(onClick = { work { games.start(chatId, kind, listOf("user") + members.map { it.id }, ""); core.getMessageCoordinationDelegate().startGroupDiscussion(chatId) } }) { Text(label) }
                            }
                        }
                        OutlinedTextField(title, { title = it }, label = { Text("小游戏名称") })
                        OutlinedTextField(rules, { rules = it }, label = { Text("场景、规则与目标，也可以让 AI 帮你编写") }, minLines = 3)
                        FlowRow {
                            TextButton(onClick = { work { games.template(title, rules) } }) { Text("保存模板") }
                            TextButton(onClick = { work { games.start(chatId, "story", listOf("user") + members.map { it.id }, rules) } }) { Text("情景开局") }
                            TextButton(onClick = { work { games.start(chatId, "custom", listOf("user") + members.map { it.id }, rules) } }) { Text("自制游戏开局") }
                        }
                        games.templates().forEach { (name, text) -> TextButton(onClick = { title = name; rules = text }) { Text(name) } }
                        if (game != null) Text("上一局：${game.winner.ifBlank { "已结束" }}")
                    } else {
                        val own = games.view(chatId, "user")
                        Text("第 ${game.round} 轮 · " + when(game.phase) { "bid" -> "叫分"; "play" -> "出牌"; "night" -> "夜晚"; "discussion" -> "讨论"; "vote" -> "投票"; else -> "进行中" })
                        game.events.takeLast(6).forEach { Text(readable(it), style = MaterialTheme.typography.bodySmall) }
                        val canAct = own.optBoolean("your_action_pending")
                        when(game.kind) {
                            "landlord" -> {
                                Text("轮到：" + readable(game.players[game.turn]))
                                Text("剩余手牌：" + game.hands.entries.joinToString { "${readable(it.key)} ${it.value.size}" })
                                FlowRow { game.hands.getValue("user").forEach { card ->
                                    FilterChip(selected = card in selectedCards, onClick = { selectedCards = if (card in selectedCards) selectedCards - card else selectedCards + card }, label = { Text(GroupGameEngine.label(card)) })
                                } }
                                if (game.phase == "bid") FlowRow { (0..3).forEach { bid -> TextButton(enabled = canAct, onClick = { play(JSONObject().put("bid", bid)) }) { Text(if (bid == 0) "不叫" else "$bid 分") } } }
                                else Row {
                                    TextButton(enabled = canAct && selectedCards.isNotEmpty(), onClick = { play(JSONObject().put("cards", JSONArray(selectedCards.toList()))) }) { Text("出牌") }
                                    TextButton(enabled = canAct, onClick = { play(JSONObject().put("pass", true)) }) { Text("不出") }
                                }
                            }
                            "werewolf" -> {
                                Text("你的身份：${own.optString("your_role")}")
                                Text(readable(own.optString("your_private_findings")))
                                Text("规则：狼人袭击、预言家查验、守卫保护；村民夜间跳过。白天各发言一次再投票，平票无人出局。狼人达到存活人数一半即获胜。")
                                if (game.phase == "discussion") {
                                    OutlinedTextField(publicAction, { publicAction = it }, label = { Text("你的推理") })
                                    TextButton(enabled = canAct, onClick = { play(JSONObject().put("text", publicAction)) }) { Text("提交发言") }
                                } else {
                                    FlowRow { game.alive.forEach { target -> TextButton(enabled = canAct, onClick = { play(JSONObject().put("target", target)) }) { Text(readable(target)) } } }
                                    TextButton(enabled = canAct, onClick = { play(JSONObject().put("skip", true)) }) { Text(if (game.phase == "vote") "弃票" else "跳过") }
                                }
                            }
                            else -> {
                                Text("轮到：" + readable(game.players[game.turn]))
                                Text(readable(own.optString("your_hidden_actions")))
                                OutlinedTextField(publicAction, { publicAction = it }, label = { Text("公开行动") })
                                OutlinedTextField(hiddenAction, { hiddenAction = it }, label = { Text("隐藏行动，仅自己可见") })
                                TextButton(enabled = canAct, onClick = { play(JSONObject().put("text", publicAction).put("hidden", hiddenAction)) }) { Text("提交行动") }
                            }
                        }
                        TextButton(onClick = { work { core.cancelMessage(chatId); games.end(chatId) } }) { Text("结束本局") }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { tab = "" }) { Text("关闭") } })
}
