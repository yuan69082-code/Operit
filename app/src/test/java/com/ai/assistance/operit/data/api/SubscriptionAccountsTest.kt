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

    @Test fun googleAuthorizationUsesPkceAndStoresOnlyAfterEligibility(): Unit = runBlocking {
        val vault = Vault()
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); paths.add(request.url.encodedPath)
            val payload = when (request.url.host) {
                "oauth2.googleapis.com" -> {
                    val form = request.body as FormBody
                    assertEquals("authorization_code", form.value((0 until form.size).single { form.name(it) == "grant_type" }))
                    assertTrue((0 until form.size).any { form.name(it) == "code_verifier" && form.value(it).length >= 43 })
                    """{"access_token":"google-token","refresh_token":"google-refresh","expires_in":3600}"""
                }
                else -> {
                    assertEquals("Bearer google-token", request.header("Authorization"))
                    assertNull(vault.get("gemini"))
                    """{"currentTier":{"id":"standard-tier"},"paidTier":{"id":"g1-pro-tier"},"cloudaicompanionProject":"provided-project"}"""
                }
            }
            response(request, 200, payload)
        }.build()
        val accounts = SubscriptionAccounts(vault, client)
        val auth = accounts.beginGemini()
        assertTrue(auth.url.contains("code_challenge_method=S256"))
        accounts.completeGemini(auth, "code-from-google")
        assertEquals(listOf("/token", "/v1internal:loadCodeAssist"), paths)
        val session = accounts.sessionId("gemini")!!
        assertEquals("provided-project", accounts.geminiProject(session))
        assertEquals("google-token", accounts.token("gemini", session))
    }

    @Test fun googleCancellationExpiryAndStateMismatchNeverPersistCredentials(): Unit = runBlocking {
        val vault = Vault(); val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain -> calls.incrementAndGet(); response(chain.request(), 500, "{}") }.build()
        val accounts = SubscriptionAccounts(vault, client)
        val cancelled = accounts.beginGemini(); accounts.cancelLogin(cancelled.attempt)
        try { accounts.completeGemini(cancelled, "code"); fail("cancelled") } catch (_: CancellationException) {}
        val expired = accounts.beginGemini().copy(expiresAt = 1L)
        try { accounts.completeGemini(expired, "code"); fail("expired") } catch (_: IllegalArgumentException) {}
        val current = accounts.beginGemini()
        try { accounts.completeGemini(current, "https://codeassist.google.com/authcode?code=c&state=foreign"); fail("foreign") } catch (_: IllegalArgumentException) {}
        assertEquals(0, calls.get()); assertFalse(accounts.connected("gemini"))
    }

    @Test fun googleConcurrentRefreshAndQuotaKeepProjectAndAccountScope(): Unit = runBlocking {
        val vault = Vault(); val refreshes = AtomicInteger()
        vault.put("gemini", JSONObject().put("session", "one").put("access", "old").put("refresh", "r")
            .put("project", "p").put("expires_at", 1L))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.host == "oauth2.googleapis.com") {
                refreshes.incrementAndGet(); response(chain.request(), 200, """{"access_token":"new","expires_in":3600}""")
            } else response(chain.request(), 200, """{"buckets":[{"modelId":"service-model","remainingFraction":0.4,"resetTime":"server-time"}]}""")
        }.build()
        val accounts = SubscriptionAccounts(vault, client)
        assertEquals(setOf("new"), (1..20).map { async(Dispatchers.Default) { accounts.token("gemini", "one") } }.awaitAll().toSet())
        assertEquals(1, refreshes.get()); assertEquals("p", accounts.geminiProject("one"))
        assertEquals("service-model", accounts.models("gemini").single().getString("id"))
        val usage = accounts.usage("gemini")
        assertEquals(0.4, usage.getJSONArray("buckets").getJSONObject(0).getDouble("remainingFraction"), 0.0001)
        accounts.logout("gemini")
        try { accounts.token("gemini", "one"); fail("logout") } catch (_: IOException) {}
    }

    @Test fun glmCannotSendPlanKeyToPaidEndpointAndQuotaFailureDoesNotDisableChat(): Unit = runBlocking {
        val vault = Vault(); val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertEquals("/api/monitor/usage/quota/limit", chain.request().url.encodedPath)
            assertEquals("plan-key", chain.request().header("Authorization"))
            response(chain.request(), 503, "{}")
        }.build()
        val accounts = SubscriptionAccounts(vault, client); accounts.savePlanKey("glm", "plan-key")
        val session = accounts.sessionId("glm")!!
        val bound = accounts.boundClient("glm", session, client)
        assertThrows(IllegalArgumentException::class.java) { bound.newCall(Request.Builder()
            .url("https://open.bigmodel.cn/api/paas/v4/chat/completions").header("Authorization", "Bearer plan-key").build()).execute() }
        assertEquals(0, calls.get())
        try { accounts.usage("glm"); fail("must report unavailable metadata") } catch (_: IOException) {}
        assertEquals(1, calls.get())
        assertEquals("plan-key", accounts.token("glm", session))
    }

    @Test fun glmQuotaRetainsServerWindowsAndTokenCountsButNotCredentials(): Unit = runBlocking {
        val vault = Vault()
        val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), 200,
            """{"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","usage":1000,"remaining":70,"nextResetTime":1234}],"token_limit":1000,"accessToken":"private"}}""") }.build()
        val accounts = SubscriptionAccounts(vault, client); accounts.savePlanKey("glm", "plan-key")
        val result = accounts.usage("glm").getJSONObject("data")
        assertEquals(1000, result.getInt("token_limit"))
        assertEquals(1234, result.getJSONArray("limits").getJSONObject(0).getInt("nextResetTime"))
        assertFalse(result.has("accessToken")); assertFalse(result.has("unlimited"))
    }

    @Test fun googleTransportRejectsOtherCloudMethodsAndDoesNotRetryRateLimits(): Unit = runBlocking {
        val vault = Vault(); val calls = AtomicInteger()
        vault.put("gemini", JSONObject().put("session", "one").put("access", "a").put("refresh", "r")
            .put("project", "p").put("expires_at", System.currentTimeMillis() + 3_600_000))
        val server = OkHttpClient.Builder().addInterceptor { chain -> calls.incrementAndGet(); response(chain.request(), 429, "{}") }.build()
        val bound = SubscriptionAccounts(vault, server).boundClient("gemini", "one", server)
        assertThrows(IllegalArgumentException::class.java) { bound.newCall(Request.Builder()
            .url("https://cloudcode-pa.googleapis.com/v1internal:unrelated").build()).execute() }
        assertEquals(0, calls.get())
        bound.newCall(Request.Builder().url("https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse").build())
            .execute().use { assertEquals(429, it.code) }
        assertEquals(1, calls.get())
    }

    @Test fun googleMetadataParseFailureNeverLeaksTokenResponse(): Unit = runBlocking {
        val vault = Vault()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, "{access_token: SECRET-RESPONSE, broken")
        }.build()
        val accounts = SubscriptionAccounts(vault, client)
        try { accounts.completeGemini(accounts.beginGemini(), "code"); fail("malformed authorization") }
        catch (e: IOException) { assertFalse(e.toString().contains("SECRET-RESPONSE")); assertNull(e.cause) }
        assertFalse(accounts.connected("gemini"))
    }

    @Test fun googleRejectedAccessRefreshesOnceOnSameSessionAndPreservesRequest(): Unit = runBlocking {
        val vault = Vault(); val refreshes = AtomicInteger(); val attempts = AtomicInteger()
        vault.put("gemini", JSONObject().put("session", "one").put("access", "old").put("refresh", "r")
            .put("project", "p").put("expires_at", System.currentTimeMillis() + 3_600_000))
        val server = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host == "oauth2.googleapis.com") {
                refreshes.incrementAndGet(); response(request, 200, """{"access_token":"new","expires_in":3600}""")
            } else {
                attempts.incrementAndGet()
                assertEquals("same-request", request.header("X-Test-Request"))
                response(request, if (request.header("Authorization") == "Bearer old") 401 else 200, "{}")
            }
        }.build()
        val accounts = SubscriptionAccounts(vault, server)
        accounts.boundClient("gemini", "one", server).newCall(Request.Builder()
            .url("https://cloudcode-pa.googleapis.com/v1internal:generateContent")
            .header("Authorization", "Bearer old").header("X-Test-Request", "same-request").build())
            .execute().use { assertEquals(200, it.code) }
        assertEquals(1, refreshes.get()); assertEquals(2, attempts.get())
        assertEquals("p", accounts.geminiProject("one"))
    }
}
