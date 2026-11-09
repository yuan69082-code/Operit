package com.ai.assistance.operit.data.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.ai.assistance.operit.BuildConfig
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Subscription credentials are independent of assistant identity. Only a reference is stored
 * in ModelConfigData. This vault is device-local and excluded from application exports. */
internal interface SubscriptionVault {
    fun get(channel: String): JSONObject?
    fun put(channel: String, value: JSONObject?)
}

private class DeviceSubscriptionVault(context: Context) : SubscriptionVault {
    private val file = AtomicFile(File(context.noBackupFilesDir, "subscription-accounts.aes"))
    private val alias = "operit-subscription-accounts"
    private fun key(): java.security.Key {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.getKey(alias, null)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.readFully()
        require(bytes.size > 28) { "订阅凭据文件损坏；请退出该账号并重新授权" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized override fun get(channel: String): JSONObject? = read().optJSONObject(channel)
    @Synchronized override fun put(channel: String, value: JSONObject?) {
        val data = read()
        if (value == null) data.remove(channel) else data.put(channel, value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
}

data class SubscriptionChannel(val id: String, val title: String, val endpoint: String?,
    val documentation: String, val accountPage: String, val limitation: String)

object SubscriptionChannels {
    val entries = listOf(
        SubscriptionChannel("codex", "ChatGPT / Codex", null,
            "https://developers.openai.com/codex/auth/", "https://chatgpt.com",
            "沿用现有 Codex 登录。会员额度和另购 credits 分开；本机不能更改服务端额外计费设置。"),
        SubscriptionChannel("claude", "Claude 会员", null,
            "https://code.claude.com/docs/en/legal-and-compliance", "https://claude.ai",
            "官方目前不允许第三方应用直接使用 Claude 会员 OAuth。普通 Claude API 另行计费，不作为会员接入。"),
        SubscriptionChannel("kimi", "Kimi Code", "https://api.kimi.com/coding/v1/chat/completions",
            "https://www.kimi.com/help/kimi-code/membership-guide", "https://www.kimi.com/code/console",
            "使用 Kimi Code 控制台创建的编程套餐 Key，普通聊天会员或开放平台 Key 不等同于此套餐。权益面向编程；非编程产品调用不在该权益说明范围。服务端加油包与额度以账号页面为准，本机不能限制另购 credits 的消耗。"),
        SubscriptionChannel("grok", "Grok 订阅授权", "https://api.x.ai/v1/responses",
            "https://docs.x.ai/build/enterprise", "https://grok.com",
            "通过 xAI 设备授权登录，实际可用模型和套餐由服务端决定。服务端可能启用额外付费；Operit 无法关闭该设置，不会切换普通 API 或其他账号。"),
        SubscriptionChannel("gemini", "Gemini 会员", null,
            "https://geminicli.com/docs/resources/tos-privacy/", "https://gemini.google.com",
            "官方限制第三方直接使用 Gemini CLI 的 Code Assist OAuth。Google AI 会员不等于 Gemini 按量 API 授权，当前不能直接接入此会员。"),
        SubscriptionChannel("glm", "GLM Coding Plan", null,
            "https://docs.bigmodel.cn/cn/coding-plan/tool/others", "https://bigmodel.cn",
            "官方套餐仅限指定工具，当前列表未包含 Operit。普通 API 单独收费，不能当成套餐额度；需服务方开放此应用后接入。")
    )
    fun get(id: String) = entries.first { it.id == id }
}

data class DeviceAuthorization(val attempt: String, val userCode: String, val url: String,
    internal val deviceCode: String, val expiresAt: Long, internal val intervalSeconds: Long)

/** Wire adaptation only, no imported Kelivo source. Refreshes merge within an account;
 * changing account invalidates in-flight calls and authorization attempts. */
class SubscriptionAccounts internal constructor(private val vault: SubscriptionVault, private val client: OkHttpClient) {
    private val lock = Any()
    private val refreshMutex = Mutex()
    private val guards = mutableMapOf<String, AccountRequestGuard>()
    private var loginAttempt: String? = null
    private val snapshots = mutableMapOf<String, JSONObject>()
    private fun guard(channel: String) = synchronized(lock) {
        guards.getOrPut(channel) { AccountRequestGuard { sessionId(channel) } }
    }
    fun sessionId(channel: String): String? = synchronized(lock) {
        vault.get(channel)?.optString("session")?.takeIf { it.isNotEmpty() }
    }
    fun connected(channel: String) = sessionId(channel) != null
    fun publicAccount(channel: String): String? = synchronized(lock) { vault.get(channel)?.optString("label") }
    private fun state(channel: String, session: String): JSONObject = synchronized(lock) {
        val state = vault.get(channel)
        if (state == null || state.getString("session") != session) throw IOException("订阅账号已退出或切换，请重新发送")
        state
    }
    private fun save(channel: String, state: JSONObject) {
        synchronized(lock) { vault.put(channel, state); snapshots.remove(channel) }
        guard(channel).changed()
    }
    fun saveKimiKey(key: String) {
        require(key.isNotBlank() && key.none { it.isWhitespace() }) { "请填写控制台创建的完整 Kimi Code Key" }
        save("kimi", JSONObject().put("session", UUID.randomUUID().toString()).put("access", key)
            .put("label", "Kimi Code 凭据").put("created_at", System.currentTimeMillis()))
    }
    fun logout(channel: String) {
        synchronized(lock) { loginAttempt = null; vault.put(channel, null); snapshots.remove(channel) }
        guard(channel).changed()
    }
    fun cancelLogin(attempt: String) = synchronized(lock) { if (loginAttempt == attempt) loginAttempt = null }
    suspend fun beginGrok(): DeviceAuthorization {
        val attempt = UUID.randomUUID().toString()
        synchronized(lock) { loginAttempt = attempt }
        val result = json(Request.Builder().url("$ISSUER/oauth2/device/code").post(form(
            "client_id" to GROK_CLIENT, "scope" to "openid profile email offline_access grok-cli:access api:access")).build())
        checkAttempt(attempt)
        val url = result.optString("verification_uri_complete").ifBlank { result.getString("verification_uri") }
        require(url.toHttpUrl().let { it.isHttps && it.host == "auth.x.ai" }) { "授权地址与 xAI 不一致" }
        return DeviceAuthorization(attempt, result.getString("user_code"), url, result.getString("device_code"),
            System.currentTimeMillis() + result.getLong("expires_in") * 1000, result.optLong("interval", 5).coerceAtLeast(1))
    }
    private fun checkAttempt(attempt: String) = synchronized(lock) {
        if (loginAttempt != attempt) throw kotlinx.coroutines.CancellationException("授权已取消")
    }
    suspend fun awaitGrok(auth: DeviceAuthorization) {
        var interval = auth.intervalSeconds
        while (System.currentTimeMillis() < auth.expiresAt) {
            checkAttempt(auth.attempt)
            delay(interval * 1000)
            val response = raw(Request.Builder().url("$ISSUER/oauth2/token").post(form(
                "client_id" to GROK_CLIENT, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to auth.deviceCode)).build())
            checkAttempt(auth.attempt)
            val data = JSONObject(response.second)
            if (response.first in 200..299) {
                val saved = credentials(data, null).put("session", UUID.randomUUID().toString())
                    .put("label", "Grok 授权账号").put("created_at", System.currentTimeMillis())
                synchronized(lock) {
                    checkAttempt(auth.attempt)
                    vault.put("grok", saved); loginAttempt = null; snapshots.remove("grok")
                }
                guard("grok").changed()
                return
            }
            when (data.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> throw IOException("你已拒绝这次授权")
                "expired_token" -> throw IOException("授权已过期，请重新登录")
                else -> throw IOException("Grok 授权失败（HTTP ${response.first}），未保存账号")
            }
        }
        cancelLogin(auth.attempt)
        throw IOException("授权等待超时，请重新登录")
    }
    private fun credentials(data: JSONObject, old: JSONObject?): JSONObject {
        require(data.getString("access_token").isNotBlank()) { "授权没有返回访问凭据" }
        val expires = data.getLong("expires_in")
        require(expires > 0) { "授权没有有效期限" }
        val refresh = data.optString("refresh_token").ifBlank { old?.optString("refresh").orEmpty() }
        require(refresh.isNotBlank()) { "授权没有返回续期凭据" }
        return (old?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put("access", data.getString("access_token")).put("refresh", refresh)
            .put("expires_at", System.currentTimeMillis() + expires * 1000)
    }
    suspend fun token(channel: String, session: String): String {
        val current = state(channel, session)
        if (channel == "kimi" || current.getLong("expires_at") - System.currentTimeMillis() > 300_000) return current.getString("access")
        return refresh(channel, session, current.getString("access"))
    }
    private suspend fun refresh(channel: String, session: String, rejected: String): String = refreshMutex.withLock {
        val current = state(channel, session)
        if (current.getString("access") != rejected) return@withLock current.getString("access")
        require(channel == "grok")
        val result = json(Request.Builder().url("$ISSUER/oauth2/token").post(form(
            "client_id" to GROK_CLIENT, "grant_type" to "refresh_token", "refresh_token" to current.getString("refresh"))).build())
        val updated = credentials(result, current)
        synchronized(lock) { state(channel, session); vault.put(channel, updated) }
        updated.getString("access")
    }
    fun boundClient(channel: String, session: String, base: OkHttpClient): OkHttpClient =
        base.newBuilder().followRedirects(false).followSslRedirects(false)
            .addInterceptor(guard(channel).interceptor(session)).addInterceptor { chain ->
                val original = chain.request()
                val endpoint = SubscriptionChannels.get(channel).endpoint ?: error("订阅渠道未开放")
                val approved = endpoint.toHttpUrl()
                require(original.url.host == approved.host && original.url.isHttps) { "订阅凭据不能发送到其他地址" }
                val response = chain.proceed(original)
                if (channel != "grok" || response.code != 401) response else {
                    response.close()
                    val rejected = original.header("Authorization").orEmpty().removePrefix("Bearer ")
                    val refreshed = kotlinx.coroutines.runBlocking { refresh(channel, session, rejected) }
                    chain.proceed(original.newBuilder().header("Authorization", "Bearer $refreshed").build())
                }
            }.build()
    fun headers(channel: String): Map<String, String> = buildMap {
        put("User-Agent", "Operit/${BuildConfig.VERSION_NAME}")
        if (channel == "grok") put("X-XAI-Token-Auth", "xai-grok-cli")
    }
    /** Metadata calls never consume a generated completion or execute tools. Failed quota
     * lookup leaves the account usable and does not infer an entitlement or balance. */
    suspend fun models(channel: String): List<JSONObject> {
        val session = sessionId(channel) ?: throw IOException("请先连接订阅账号")
        val endpoint = if (channel == "grok") "https://cli-chat-proxy.grok.com/v1/models-v2" else "https://api.kimi.com/coding/v1/models"
        val result = authorizedJson(channel, session, endpoint)
        val array = result.optJSONArray("data") ?: result.optJSONArray("models")
            ?: throw IOException("服务未返回可识别的模型目录")
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            JSONObject().put("id", row.optString("id").ifBlank { row.getString("slug") })
                .put("context_window", row.opt("context_window") ?: JSONObject.NULL)
                .put("max_output_tokens", row.opt("max_output_tokens") ?: JSONObject.NULL)
        }
    }
    suspend fun usage(channel: String): JSONObject {
        val session = sessionId(channel) ?: throw IOException("请先连接订阅账号")
        val endpoint = if (channel == "grok") "https://cli-chat-proxy.grok.com/v1/billing?format=credits" else "https://api.kimi.com/coding/v1/usages"
        val result = authorizedJson(channel, session, endpoint)
        // Retain server fields as a quota response, not fabricated percentages. Only explicitly
        // selected public quota fields are exposed; arbitrary server credentials are excluded.
        val visible = JSONObject().put("queried_at", System.currentTimeMillis()).put("channel", channel)
        for (name in listOf("usage", "limits", "limit", "reset_at", "remaining", "used", "total", "plan", "plan_type",
            "weekly", "daily", "credits", "subscription", "billing_enabled", "extra_usage_enabled", "data")) {
            if (result.has(name)) visible.put(name, redactQuota(result.get(name)))
        }
        state(channel, session)
        synchronized(lock) { snapshots[channel] = visible }
        return visible
    }
    private fun redactQuota(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().also { output -> value.keys().forEach { key ->
            if (!Regex("token|secret|key|authorization|cookie|email", RegexOption.IGNORE_CASE).containsMatchIn(key))
                output.put(key, redactQuota(value.get(key)))
        } }
        is JSONArray -> JSONArray().also { out -> (0 until value.length()).forEach { out.put(redactQuota(value.get(it))) } }
        else -> value
    }
    private suspend fun authorizedJson(channel: String, session: String, endpoint: String): JSONObject {
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer ${token(channel, session)}")
        headers(channel).forEach { (name, value) -> request.header(name, value) }
        val result = json(request.build())
        state(channel, session)
        return result
    }
    private suspend fun json(request: Request): JSONObject {
        val (status, body) = raw(request)
        if (status !in 200..299) throw IOException(when (status) {
            401 -> "账号授权失效或凭据被拒绝（401），请重新连接"
            403 -> "服务端未向此账号开放该能力（403）"
            429 -> "服务端额度或频率限制（429），不会切换收费通道"
            else -> "订阅服务请求失败（HTTP $status）"
        })
        return JSONObject(body)
    }
    private suspend fun raw(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request.newBuilder().header("User-Agent", "Operit/${BuildConfig.VERSION_NAME}").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val source = it.body?.source() ?: throw IOException("订阅服务没有返回内容")
                        source.request(512 * 1024L + 1)
                        if (source.buffer.size > 512 * 1024) throw IOException("订阅服务响应超过元数据大小限制")
                        it.code to source.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    private fun form(vararg values: Pair<String, String>) = FormBody.Builder().apply { values.forEach { add(it.first, it.second) } }.build()
    companion object {
        private const val ISSUER = "https://auth.x.ai"
        private const val GROK_CLIENT = "b1a00492-073a-47ea-816f-4c329264a828"
        @Volatile private var instance: SubscriptionAccounts? = null
        fun get(context: Context): SubscriptionAccounts = instance ?: synchronized(this) {
            instance ?: SubscriptionAccounts(DeviceSubscriptionVault(context.applicationContext), OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build()).also { instance = it }
        }
    }
}
