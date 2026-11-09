package com.ai.assistance.operit.data.api

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Simulated authorization server and transport, no subscription or paid completion is used. */
class SubscriptionAccountsTest {
    private class Vault : SubscriptionVault {
        val values = mutableMapOf<String, JSONObject>()
        @Synchronized override fun get(channel: String) = values[channel]?.let { JSONObject(it.toString()) }
        @Synchronized override fun put(channel: String, value: JSONObject?) { if (value == null) values.remove(channel) else values[channel] = JSONObject(value.toString()) }
    }
    private fun response(request: Request, code: Int, text: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("simulated").body(text.toResponseBody()).build()
    private fun grok(vault: Vault) { vault.put("grok", JSONObject().put("session", "one").put("access", "old")
        .put("refresh", "refresh").put("expires_at", 1L)) }

    @Test fun concurrentRefreshIsSingleFlightAndLogoutCannotBeUndone(): Unit = runBlocking {
        val vault = Vault(); grok(vault)
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count.incrementAndGet()
            response(chain.request(), 200, """{"access_token":"new","refresh_token":"new-refresh","expires_in":3600}""")
        }.build()
        val accounts = SubscriptionAccounts(vault, client)
        val tokens = (1..20).map { async(Dispatchers.Default) { accounts.token("grok", "one") } }.awaitAll()
        assertEquals(setOf("new"), tokens.toSet()); assertEquals(1, count.get())
        accounts.logout("grok")
        try { accounts.token("grok", "one"); fail("old account must not return") } catch (_: IOException) {}
        assertFalse(accounts.connected("grok"))
    }

    @Test fun refreshFailureDoesNotEraseAccountOrUseAnotherChannel(): Unit = runBlocking {
        val vault = Vault(); grok(vault)
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain -> count.incrementAndGet(); response(chain.request(), 429, "{}") }.build()
        val accounts = SubscriptionAccounts(vault, client)
        accounts.saveKimiKey("coding-plan-key")
        try { accounts.token("grok", "one"); fail("quota must be reported") } catch (e: IOException) { assertTrue(e.message!!.contains("429")) }
        assertEquals("old", vault.get("grok")!!.getString("access")); assertEquals(1, count.get())
        assertTrue(accounts.connected("kimi"))
    }

    @Test fun accountSwitchInvalidatesOldRequestAndQuotaFailureKeepsNewAccount(): Unit = runBlocking {
        val vault = Vault()
        val server = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), 503, "{}") }.build()
        val accounts = SubscriptionAccounts(vault, server)
        accounts.saveKimiKey("first"); val first = accounts.sessionId("kimi")!!
        accounts.saveKimiKey("second")
        try { accounts.token("kimi", first); fail("must bind old request") } catch (_: IOException) {}
        assertEquals("second", accounts.token("kimi", accounts.sessionId("kimi")!!))
        try { accounts.usage("kimi"); fail("must report metadata error") } catch (_: IOException) {}
        assertTrue(accounts.connected("kimi"))
        assertTrue(vault.get("kimi")!!.getString("access") == "second")
    }

    @Test fun transportRotationCancelsExistingCallsIncludingUnreadBody() {
        var current: String? = "one"
        val guard = AccountRequestGuard { current }
        val client = OkHttpClient.Builder().addInterceptor(guard.interceptor("one")).addInterceptor { chain ->
            response(chain.request(), 200, "unread streamed body")
        }.build()
        val call = client.newCall(Request.Builder().url("https://example.invalid/").build())
        val body = call.execute()
        current = "two"; guard.changed()
        assertTrue(call.isCanceled())
        try { body.body!!.string(); fail("old body may not enter new session") } catch (_: IOException) {} finally { body.close() }
        assertThrows(IOException::class.java) { client.newCall(Request.Builder().url("https://example.invalid/").build()).execute() }
    }

    @Test fun quotaIsServerDataWithUnknownsAndCredentialsRemoved(): Unit = runBlocking {
        val vault = Vault()
        val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), 200,
            """{"data":{"remaining":12,"access_token":"never expose","nested":{"secret":"no"}},"api_key":"no"}""") }.build()
        val accounts = SubscriptionAccounts(vault, client); accounts.saveKimiKey("key")
        val result = accounts.usage("kimi")
        assertEquals(12, result.getJSONObject("data").getInt("remaining"))
        assertFalse(result.toString().contains("never expose")); assertFalse(result.toString().contains("secret"))
        assertFalse(result.has("reset_at")); assertFalse(result.has("unlimited"))
    }
}
