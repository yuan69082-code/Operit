package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.data.api.GeminiSubscriptionProtocol
import com.ai.assistance.operit.data.api.SubscriptionAccounts
import com.ai.assistance.operit.data.model.ModelConfigData
import java.io.IOException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject

/** Reuses Gemini's native parts, tool pairing, thought signatures, cancellation and parser.
 * Only the Code Assist envelope/auth differ. No supplier thought text becomes activity notes. */
internal class GeminiSubscriptionProvider(
    private val accounts: SubscriptionAccounts,
    private val session: String,
    private val config: ModelConfigData,
    keys: ApiKeyProvider,
    client: OkHttpClient,
) : GeminiProvider(
    apiEndpoint = GeminiSubscriptionProtocol.ENDPOINT, apiKeyProvider = keys, modelName = config.modelName,
    client = unwrapClient(client), customHeaders = accounts.headers("gemini"),
    enableToolCall = config.enableToolCall,
    thinkingConfigurations = config.thinkingConfigurations, thinkingOptionId = config.thinkingOptionId,
) {
    override suspend fun createRequest(context: Context, requestBody: RequestBody, isStreaming: Boolean, requestId: String): Request {
        val body = Buffer().also(requestBody::writeTo).readUtf8()
        val envelope = JSONObject().put("model", config.modelName).put("project", accounts.geminiProject(session))
            .put("user_prompt_id", requestId).put("request", JSONObject(body))
            // Opt-in credit spending is never requested on behalf of the user.
            .put("enabled_credit_types", JSONArray())
        val method = if (isStreaming) "streamGenerateContent?alt=sse" else "generateContent"
        return Request.Builder().url("${GeminiSubscriptionProtocol.ENDPOINT}:$method")
            .header("Authorization", "Bearer ${accounts.token("gemini", session)}")
            .apply { accounts.headers("gemini").forEach { (name, value) -> header(name, value) } }
            .post(envelope.toString().toRequestBody("application/json".toMediaType())).build()
    }

    companion object {
        private fun unwrapClient(client: OkHttpClient): OkHttpClient = client.newBuilder().apply {
            // Outer interceptor must see responses from both real network and test transports.
            interceptors().add(0, Interceptor { chain ->
                val response = chain.proceed(chain.request())
                if (!response.isSuccessful) return@Interceptor response
                val body = response.body ?: return@Interceptor response
                if (chain.request().url.queryParameter("alt") != "sse") {
                    val text = body.use { unwrap(JSONObject(it.string())) }
                    response.newBuilder().body(text.toResponseBody("application/json".toMediaType())).build()
                } else response.newBuilder().body(object : ResponseBody() {
                    private val converted = EnvelopeSource(body.source()).buffer()
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = converted
                }).build()
            })
        }.build()

        private fun unwrap(data: JSONObject): String {
            if (data.has("error")) throw IOException("Gemini Code Assist 返回流内错误，未重试本轮")
            val response = data.optJSONObject("response") ?: throw IOException("Gemini Code Assist 响应缺少内容封套")
            if (data.has("traceId")) response.put("responseId", data.get("traceId"))
            return response.toString()
        }

        /** Incremental SSE adaptation; history/result bodies are never buffered as a whole. */
        private class EnvelopeSource(private val input: BufferedSource) : Source {
            private val output = Buffer()
            private val event = StringBuilder()
            private var ended = false
            override fun timeout() = input.timeout()
            override fun close() = input.close()
            override fun read(sink: Buffer, byteCount: Long): Long {
                require(byteCount >= 0)
                if (byteCount == 0L) return 0
                while (output.size == 0L && !ended) {
                    if (input.exhausted()) { flush(); ended = true; break }
                    val limit = 16 * 1024 * 1024L
                    val newline = input.indexOf('\n'.code.toByte(), 0L, limit + 1)
                    val line = if (newline == -1L) {
                        if (input.buffer.size > limit) throw IOException("Gemini 单个流事件超过处理大小")
                        // SSE permits the last event to end at EOF without a blank line.
                        input.readUtf8().removeSuffix("\r")
                    } else input.readUtf8LineStrict(limit)
                    when {
                        line.isEmpty() -> flush()
                        line.startsWith("data:") -> {
                            if (event.isNotEmpty()) event.append('\n')
                            event.append(line.substring(5).trimStart())
                            if (event.length > 16 * 1024 * 1024) throw IOException("Gemini 单个流事件超过处理大小")
                        }
                    }
                }
                return if (output.size == 0L) -1 else output.read(sink, minOf(byteCount, output.size))
            }
            private fun flush() {
                if (event.isEmpty()) return
                val text = event.toString(); event.setLength(0)
                if (text == "[DONE]") { ended = true; return }
                val data = try { JSONObject(text) } catch (_: Exception) { throw IOException("Gemini 流事件格式无效") }
                output.writeUtf8("data: ${unwrap(data)}\n\n")
            }
        }
    }
}
