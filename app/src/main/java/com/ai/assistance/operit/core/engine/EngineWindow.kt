package com.ai.assistance.operit.core.engine

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Platform facts only. No Engine data, model invocation, or process lifecycle operations. */
class EngineWindow(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "engine-window.json"))
    companion object {
        private val lock = Any()
        private val syncLock = Mutex()
        private val client = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        private val safeId = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")
        fun associated(context: Context, role: String?) = role != null && EngineWindow(context).binding(role) != null
        fun offer(context: Context, role: String?, conversation: String, kind: String, payload: JSONObject) {
            if (role == null) return
            try {
                val store = EngineWindow(context)
                val binding = store.binding(role) ?: return
                if (!EngineWindowPolicy.maySubmit(true, binding.getBoolean("enabled"), store.grants(binding), kind)) return
                store.enqueue(role, conversation, kind, payload)
            } catch (failure: Exception) {
                // Do not turn observation persistence failure into a failed user setting change.
                AppLogger.e("EngineWindow", "Could not retain platform event: ${failure.javaClass.simpleName}")
            }
        }
    }
    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject().put("schema", 1).put("device", UUID.randomUUID().toString())
            .put("url", "").put("token", "").put("bindings", JSONObject()).put("events", JSONArray())
        val data = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        check(data.getInt("schema") == 1) { "不支持的引擎连接记录版本" }
        return data
    }
    private fun write(data: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    fun connection(): JSONObject = synchronized(lock) {
        val data = read()
        JSONObject().put("url", data.getString("url")).put("tokenConfigured", data.getString("token").isNotEmpty())
    }
    fun saveConnection(url: String, token: String) = synchronized(lock) {
        val uri = URI(url.trim())
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in setOf("", "/")) { "请填写电脑共享服务的完整地址，不含路径或账号信息" }
        val data = read()
        // Pending events belong to one bridge; never silently redirect them to another server.
        require(data.getString("url").isEmpty() || data.getString("url") == url.trim().trimEnd('/') ||
            data.getJSONObject("bindings").length() == 0) { "请先解除所有主体关联，再更换电脑服务" }
        require(token.isNotBlank() || data.getString("token").isNotBlank()) { "请填写共享服务 Token" }
        data.put("url", url.trim().trimEnd('/'))
        if (token.isNotBlank()) data.put("token", token)
        write(data)
    }
    fun binding(role: String): JSONObject? = synchronized(lock) {
        read().getJSONObject("bindings").optJSONObject(role)?.let { JSONObject(it.toString()) }
    }
    fun grants(binding: JSONObject): Set<String> {
        val values = binding.getJSONArray("grants")
        return (0 until values.length()).map { values.getString(it) }.toSet()
    }
    fun bind(role: String, subject: String, enabled: Boolean, allowed: Set<String>) = synchronized(lock) {
        require(safeId.matches(role) && safeId.matches(subject)) { "主体或入口标识无效" }
        require(EngineWindowPolicy.grants.containsAll(allowed)) { "事件授权无效" }
        val data = read()
        if (enabled) check(data.getString("url").isNotEmpty() && data.getString("token").isNotEmpty()) { "请先保存电脑连接设置" }
        val bindings = data.getJSONObject("bindings")
        val old = bindings.optJSONObject(role)
        check(old == null || old.getString("subjectId") == subject) { "请先解除原关联；旧事件不会转给另一主体" }
        val bindingId = old?.getString("id") ?: UUID.randomUUID().toString()
        val binding = JSONObject().put("id", bindingId)
            .put("subjectId", subject).put("enabled", enabled).put("grants", JSONArray(allowed.sorted()))
            .put("bridgeKnown", old?.optBoolean("bridgeKnown") == true)
            .put("key", old?.getString("key") ?: "android:${data.getString("device")}:$bindingId")
        bindings.put(role, binding)
        if (enabled || old?.optBoolean("enabled") == true || controls(data).has(binding.getString("key"))) {
            controls(data).put(binding.getString("key"), JSONObject(binding.toString()).put("operation", "binding"))
        }
        val rows = data.getJSONArray("events")
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            if (row.getString("bindingId") == binding.getString("id") &&
                EngineWindowPolicy.grant(row.getString("kind")) !in allowed &&
                row.optString("status") in setOf("local", "awaiting_protocol")) row.put("status", "revoked")
        }
        write(data)
    }
    fun unbind(role: String) = synchronized(lock) {
        val data = read()
        val old = data.getJSONObject("bindings").optJSONObject(role) ?: return@synchronized
        if (old.optBoolean("enabled") || old.optBoolean("bridgeKnown") || controls(data).has(old.getString("key"))) {
            controls(data).put(old.getString("key"), JSONObject().put("operation", "unbind").put("key", old.getString("key")))
        }
        val rows = data.getJSONArray("events")
        repeat(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            if (row.getString("bindingId") == old.getString("id") &&
                row.optString("status") in setOf("local", "awaiting_protocol")) row.put("status", "revoked")
        }
        data.getJSONObject("bindings").remove(role)
        write(data)
    }
    fun enqueue(role: String, conversation: String, kind: String, payload: JSONObject): String = synchronized(lock) {
        require(safeId.matches(conversation)) { "会话标识无效" }
        require(payload.toString().toByteArray(Charsets.UTF_8).size < 120_000) { "事件内容过长，尚未发送" }
        val data = read()
        val binding = data.getJSONObject("bindings").optJSONObject(role) ?: error("没有关联引擎主体")
        check(binding.getBoolean("enabled")) { "软件连接已关闭；没有改用软件模型代答" }
        check(EngineWindowPolicy.grant(kind) in grants(binding)) { "尚未授权向主体提供此类事件" }
        val rows = data.getJSONArray("events")
        check(rows.length() < 3000) { "本机引擎事件记录已满，请先处理记录" }
        val id = UUID.randomUUID().toString()
        rows.put(JSONObject().put("id", id).put("role", role).put("bindingId", binding.getString("id"))
            .put("conversationId", conversation).put("kind", kind).put("payload", JSONObject(payload.toString()))
            .put("occurredAt", Instant.now().toString()).put("status", "local"))
        write(data)
        id
    }
    fun events(role: String): List<JSONObject> = synchronized(lock) {
        val rows = read().getJSONArray("events")
        (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("role") == role }
    }
    private fun controls(data: JSONObject): JSONObject =
        data.optJSONObject("controls") ?: JSONObject().also { data.put("controls", it) }

    fun enabledRoles(): List<String> = synchronized(lock) {
        val bindings = read().getJSONObject("bindings")
        bindings.keys().asSequence().filter { bindings.getJSONObject(it).getBoolean("enabled") }.toList()
    }
    fun hasPendingControls(): Boolean = synchronized(lock) { controls(read()).length() > 0 }
    fun needsBackgroundWork(): Boolean = enabledRoles().isNotEmpty() || hasPendingControls()

    // Revocations are persistent too. They are sent before any further event, including
    // after a restart/offline change. Their transport never starts or stops the Engine.
    private fun flushControls() {
        while (true) {
            val snapshot = synchronized(lock) { read() }
            val pending = controls(snapshot)
            val key = pending.keys().asSequence().firstOrNull() ?: return
            if (snapshot.getString("url").isBlank()) return
            val control = pending.getJSONObject(key)
            val operation = control.getString("operation")
            request(snapshot, operation, JSONObject(control.toString()))
            synchronized(lock) {
                val latest = read()
                if (controls(latest).optJSONObject(key)?.toString() == control.toString()) {
                    controls(latest).remove(key)
                    val bindings = latest.getJSONObject("bindings")
                    bindings.keys().asSequence().forEach { role ->
                        if (bindings.getJSONObject(role).getString("key") == key) {
                            bindings.getJSONObject(role).put("bridgeKnown", true)
                        }
                    }
                    write(latest)
                }
            }
        }
    }
    suspend fun syncControls() = withContext(Dispatchers.IO) { syncLock.withLock { flushControls() } }
    private fun update(id: String, action: (JSONObject) -> Unit) = synchronized(lock) {
        val data = read(); val rows = data.getJSONArray("events")
        repeat(rows.length()) { i -> if (rows.getJSONObject(i).getString("id") == id) action(rows.getJSONObject(i)) }
        write(data)
    }
    private fun request(data: JSONObject, route: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(data.getString("url") + "/api/shared/engine/" + route)
            .header("Authorization", "Bearer " + data.getString("token"))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(req).execute().use { response ->
            val source = checkNotNull(response.body).source()
            require(!source.request(1_048_577)) { "电脑服务返回内容过大" }
            val json = JSONObject(source.readUtf8())
            check(response.isSuccessful) { json.optString("error", "电脑服务拒绝请求") }
            return json
        }
    }
    suspend fun sync(role: String): String = withContext(Dispatchers.IO) {
        syncLock.withLock {
            flushControls()
            val snapshot = synchronized(lock) { read() }
            val selected = snapshot.getJSONObject("bindings").optJSONObject(role) ?: error("没有主体关联")
            check(selected.getBoolean("enabled")) { "软件连接已关闭" }
            val remote = request(snapshot, "binding", JSONObject().put("key", selected.getString("key"))
                .put("subjectId", selected.getString("subjectId")).put("enabled", true).put("grants", selected.getJSONArray("grants")))
            check(remote.getString("subjectId") == selected.getString("subjectId")) { "电脑端主体关联不一致" }
            var count = 0
            for (row in events(role)) {
                if (row.getString("bindingId") != selected.getString("id") || row.optString("status") == "revoked") continue
                val current = binding(role) ?: break
                if (!current.getBoolean("enabled") || current.getString("id") != selected.getString("id")) break
                if (EngineWindowPolicy.grant(row.getString("kind")) !in grants(current)) continue
                val id = row.getString("id")
                if (row.optString("status") in setOf("completed", "rejected", "awaiting_protocol")) continue
                try {
                    // A stable ID makes retrying the bridge receipt safe; Engine delivery is managed there.
                    val result = request(snapshot, "events", JSONObject().put("id", id).put("bindingKey", selected.getString("key"))
                        .put("bindingId", remote.getString("id")).put("conversationId", row.getString("conversationId"))
                        .put("kind", row.getString("kind")).put("occurredAt", row.getString("occurredAt")).put("payload", row.getJSONObject("payload")))
                    check(result.getString("id") == id && result.getString("subjectId") == selected.getString("subjectId")) { "电脑投递回执身份不匹配" }
                    update(id) { local ->
                        if (local.optString("status") != "revoked") {
                            local.put("status", result.getString("status")).put("message", result.getString("message"))
                        }
                    }
                    count++
                } catch (failure: Exception) {
                    update(id) { local -> if (local.optString("status") != "revoked") local.put("message", "电脑接收结果未确认；保留同一事件，稍后重试") }
                    throw failure
                }
            }
            "已核对 $count 条平台记录；正式引擎协议尚未接通"
        }
    }
}
