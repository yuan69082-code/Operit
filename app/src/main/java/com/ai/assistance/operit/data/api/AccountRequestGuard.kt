package com.ai.assistance.operit.data.api

import java.io.IOException
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** A request belongs to the account session that created it, including refresh and retries.
 * Rotation cancels its transport; no request may silently move to the newly logged-in user. */
internal class AccountRequestGuard(private val current: () -> String?) {
    private val calls = mutableMapOf<Call, String>()
    @Synchronized fun check(session: String) {
        if (current() != session) throw IOException("账号已退出或切换，请重新发送本次请求")
    }
    @Synchronized fun changed() {
        calls.filterValues { it != current() }.keys.toList().forEach { it.cancel(); calls.remove(it) }
    }
    @Synchronized private fun start(call: Call, session: String) { check(session); calls[call] = session }
    @Synchronized private fun finish(call: Call) { calls.remove(call) }
    fun interceptor(session: String) = Interceptor { chain ->
        val call = chain.call()
        start(call, session)
        try {
            val response = chain.proceed(chain.request())
            try { check(session) } catch (e: IOException) { response.close(); throw e }
            val body = response.body
            if (body == null) { finish(call); response } else response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                private val input = object : ForwardingSource(body.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        check(session)
                        return super.read(sink, byteCount).also { if (it == -1L) finish(call) }
                    }
                    override fun close() { try { super.close() } finally { finish(call) } }
                }.buffer()
                override fun source(): BufferedSource = input
            }).build()
        } catch (e: Exception) { finish(call); throw e }
    }
}
