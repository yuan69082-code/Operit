package com.ai.assistance.operit.core.awareness

import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.core.companion.CompanionRuntime
import com.ai.assistance.operit.core.companion.CompanionStore
import com.ai.assistance.operit.data.preferences.AssistantScope
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

object SoftwareAwareness {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val presence = SoftwarePresence()
    private val presenceLock = Any()
    private var recordingIdentity: Pair<String?, Boolean>? = null
    init {
        scope.launch {
            for (write in writes) try { write() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { AppLogger.e("SoftwareAwareness", "Event recording failed: ${failure.javaClass.simpleName}") }
        }
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        writes.trySend {
            val store = SoftwareAwarenessStore(app)
            store.recover()
            if (store.enabled) record(app, "coverage", "软件进程已启动；上次进程的精确终止时间未知", "system",
                JSONObject().put("previous_close_time", JSONObject.NULL), wake = false)
        }
    }

    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        writes.send { done.complete(Unit) }
        done.await()
    }

    private fun toolRelated() = PhoneAwarenessRuntime.duringToolActivity()
    private fun actor(role: String?): String = when {
        role != null -> "assistant:$role"
        toolRelated() -> "tool_or_user_uncertain"
        else -> "software_change"
    }

    fun assetChanged(context: Context, kind: String, action: String, name: String) {
        val caller = AssistantScope.callerId()
        val isUrgent = kind == "assistant" && action in setOf("新增助手", "删除助手")
        record(context, kind, "$action：${name.take(100)}", actor(caller),
            JSONObject().put("name", name.take(100)).put("action", action), wake = caller == null && !toolRelated(), urgent = isUrgent)
    }

    fun preferencesChanged(context: Context, source: String, before: Map<String, Any?>, after: Map<String, Any?>, role: String?) {
        try {
            if (source == "companion_options" &&
                (before["capabilities_enabled"] != after["capabilities_enabled"] || before["software_role"] != after["software_role"])) {
                configurationChanged(context)
            }
            if (!SoftwareAwarenessStore(context).enabled) return
            val origin = actor(role)
            SoftwareChangePolicy.changes(source, before, after).forEach {
                val isUrgent = it.kind == "model" || (it.kind == "assistant" && it.detail.optString("action") in setOf("created", "deleted"))
                record(context, it.kind, it.summary, origin, it.detail,
                    wake = role == null && !toolRelated() && SoftwareChangePolicy.mayWake(source),
                    urgent = isUrgent)
            }
        } catch (failure: Exception) {
            // A committed setting stays successful even if the observation sink is unavailable.
            AppLogger.e("SoftwareAwareness", "Could not observe settings in $source: ${failure.javaClass.simpleName}")
        }
    }

    fun record(context: Context, kind: String, summary: String, actor: String, detail: JSONObject = JSONObject(), wake: Boolean = true, urgent: Boolean = false) {
        val app = context.applicationContext
        val store = SoftwareAwarenessStore(app)
        if (!store.enabled) return
        val observer = checkNotNull(store.role)
        val revision = store.revision
        val at = System.currentTimeMillis()
        val copy = JSONObject(detail.toString())
        writes.trySend {
            store.record(observer, revision, kind, summary, actor, copy, at, wake, urgent)
            if (wake) CompanionRuntime.signalSoftwareEvent()
        }
    }

    fun configurationChanged(context: Context) = synchronized(presenceLock) {
        val store = SoftwareAwarenessStore(context)
        val identity = store.role to store.enabled
        if (recordingIdentity == identity) return@synchronized
        recordingIdentity = identity
        val wall = System.currentTimeMillis()
        val tick = SystemClock.elapsedRealtime()
        presence.recording(false, wall, tick)
        savePresence(context, presence.recording(store.enabled, wall, tick))
    }

    fun foreground(context: Context, visible: Boolean) = synchronized(presenceLock) {
        configurationChanged(context)
        val wall = System.currentTimeMillis(); val tick = SystemClock.elapsedRealtime()
        savePresence(context, presence.recording(SoftwareAwarenessStore(context).enabled, wall, tick))
        savePresence(context, presence.foreground(visible, wall, tick))
    }

    fun view(context: Context, id: String, chat: String?, role: String?, name: String?, group: String?) = synchronized(presenceLock) {
        val value = chat?.let { SoftwarePresence.View(it, role.orEmpty(), name.orEmpty(), group.orEmpty()) }
        savePresence(context, presence.view(id, value, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
    }

    private fun savePresence(context: Context, events: List<SoftwarePresence.Event>) {
        val observer = SoftwareAwarenessStore(context).role
        events.forEach { event ->
            // 她点开别的助手的私聊：不管档位，立刻叫醒观察助手
            val otherAssistant = event.kind == "chat_enter" && !toolRelated() && !observer.isNullOrBlank() &&
                event.view?.group.isNullOrBlank() && !event.view?.role.isNullOrBlank() && event.view?.role != observer
            val description = when (event.kind) {
                "app_foreground" -> "软件进入前台"
                "app_background" -> "软件离开前台"
                "chat_enter" -> "打开聊天页面：${event.view?.name}"
                else -> "离开聊天页面：${event.view?.name}"
            }
            record(context, event.kind, description, if (toolRelated()) "tool_or_user_uncertain" else "user_navigation",
                presenceDetail(event), wake = otherAssistant, urgent = otherAssistant)
        }
    }

    private fun presenceDetail(event: SoftwarePresence.Event): JSONObject = JSONObject()
        .put("chat_id", event.view?.chat).put("assistant_id", event.view?.role).put("group_id", event.view?.group)
        .put("name", event.view?.name).put("entered_at", event.entered).put("observed_duration_ms", event.duration)

    fun userMessage(context: Context, chat: String, role: String?, kind: String = "message_sent", proxy: Boolean = false) {
        record(context, kind, if (kind == "message_sent") "向助手发送了消息" else "向群聊发送了消息",
            if (proxy || toolRelated()) "proxy_or_tool_message" else "user_message",
            JSONObject().put("chat_id", chat).put("assistant_id", role),
            wake = !proxy && !toolRelated() && (kind != "message_sent" || role != SoftwareAwarenessStore(context).role))
    }

    suspend fun authorize(context: Context, chat: String, role: String?) {
        val store = SoftwareAwarenessStore(context)
        check(store.enabled && role != null && role == store.role) { "软件感知未开启，或你不是用户指定的观察助手。" }
        val target = ChatHistoryManager.getInstance(context).getChatMetadata(chat)
        check(target != null && SoftwareEventPolicy.mayRead(store.enabled, store.role, role, target.characterCardId, target.characterGroupId)) {
            "软件观察记录仅供指定助手在自己的私聊使用，不能在群聊共享。"
        }
        check(CharacterCardManager.getInstance(context).getAllCharacterCards().any { it.id == role }) { "观察助手已不存在。" }
    }

    suspend fun query(context: Context, chat: String, role: String?, action: String, after: Long, kind: String?, assistant: String?): JSONObject {
        authorize(context, chat, role)
        val revision = SoftwareAwarenessStore(context).revision
        flush()
        val store = SoftwareAwarenessStore(context)
        val rows = store.rows()
        val result = when (action) {
            "status" -> JSONObject().put("observer", store.role).put("wake_enabled", store.wakeEnabled)
                .put("retention_days", 14).put("max_events", 3000)
                .put("first_sequence", rows.firstOrNull()?.optLong("sequence"))
                .put("latest_sequence", rows.lastOrNull()?.optLong("sequence"))
                .put("live_visits", JSONArray(synchronized(presenceLock) {
                    presence.active(System.currentTimeMillis(), SystemClock.elapsedRealtime()).map(::presenceDetail)
                })).put("limits", "停留是前台可见时长，不证明注意力；退出/杀进程会造成覆盖缺口。不记录密钥和聊天正文。")
            "events" -> {
                require(after >= 0)
                val selected = rows.filter { it.getLong("sequence") > after &&
                    (kind.isNullOrBlank() || it.optString("kind") == kind) &&
                    (assistant.isNullOrBlank() || it.getJSONObject("detail").optString("assistant_id") == assistant) }
                val page = selected.take(20)
                JSONObject().put("events", JSONArray(page)).put("has_more", selected.size > page.size)
                    .put("next_after", page.lastOrNull()?.getLong("sequence") ?: after)
            }
            "usage" -> JSONObject().put("assistants", JSONArray(rows.filter { it.optString("kind") in setOf("chat_leave", "message_sent") }
                .groupBy { it.getJSONObject("detail").optString("assistant_id") }.map { (id, events) ->
                    JSONObject().put("assistant_id", id).put("observed_duration_ms",
                        events.sumOf { it.getJSONObject("detail").optLong("observed_duration_ms") })
                        .put("messages_sent", events.count { it.optString("kind") == "message_sent" && it.optString("actor") == "user_message" })
                })).put("window", "仅统计当前保留日志，不是永久总计；多窗口可重叠")
            "models" -> JSONObject().put("models", JSONArray(ModelConfigManager(context).getAllConfigSummaries().map {
                JSONObject().put("id", it.id).put("name", it.name).put("model", it.modelName).put("provider", it.apiProviderType.name)
            }))
            else -> error("action 须为 status、events、usage 或 models")
        }
        authorize(context, chat, role)
        check(store.revision == revision) { "观察授权已变更，请重新查询。" }
        return result
    }

    suspend fun prompt(context: Context, chat: String, role: String?, actualSend: Boolean): String {
        val store = SoftwareAwarenessStore(context)
        val revision = store.revision
        if (!store.enabled || role != store.role) return ""
        val target = ChatHistoryManager.getInstance(context).getChatMetadata(chat)
        if (target == null || !target.characterGroupId.isNullOrBlank() || target.characterCardId != role) return ""
        if (actualSend) flush()
        val companion = CompanionStore(context)
        val receipt = "software_receipt:$role:$chat"
        val cursor = companion.read(receipt).optLong("injected_through")
        val rows = store.rows().filter { it.getLong("sequence") > cursor }
        if (!store.enabled || role != store.role || store.revision != revision) return ""
        if (actualSend && rows.isNotEmpty()) companion.update(receipt) { it.put("injected_through", rows.last().getLong("sequence")) }
        return "你是用户指定的软件观察助手。software_awareness 可查询软件设置变更、模型增删、助手变动、前后台及聊天访问/发送记录；只有你在私聊可用。观察他人不等于自己经历。\n" +
            if (rows.isEmpty()) "" else "自上次提供后共${rows.size}条记录，以下最多8条简报。更多用 events after=$cursor 查询；时间与来源须保留。事件不是用户新发言；软件变化不能一律断言是用户操作。\n" +
                JSONArray(rows.takeLast(8).map { row -> JSONObject().put("sequence", row.getLong("sequence"))
                    .put("at", row.getLong("at")).put("kind", row.getString("kind")).put("summary", row.getString("summary"))
                    .put("actor", row.getString("actor")) }) + "\n"
    }
}
