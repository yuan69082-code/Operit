package com.ai.assistance.operit.data.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Wire protocol reference: google-gemini/gemini-cli code_assist/oauth2.ts.
 * The installed-app client values are public in Google's source, not account secrets.
 * User credentials stay in SubscriptionVault; no credential is accepted through chat. */
data class GeminiAuthorization internal constructor(
    val attempt: String, val url: String, val expiresAt: Long,
    internal val verifier: String, internal val state: String,
)

internal object GeminiSubscriptionProtocol {
    const val ENDPOINT = "https://cloudcode-pa.googleapis.com/v1internal"
    const val TOKEN = "https://oauth2.googleapis.com/token"
    const val CLIENT = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
    private const val INSTALLED_CLIENT_VALUE = "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"
    private const val REDIRECT = "https://codeassist.google.com/authcode"
    private val random = SecureRandom()
    private fun nonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    fun begin(now: Long): GeminiAuthorization {
        val verifier = nonce()
        val state = nonce()
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val url = "https://accounts.google.com/o/oauth2/v2/auth".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT).addQueryParameter("redirect_uri", REDIRECT)
            .addQueryParameter("response_type", "code").addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent select_account")
            .addQueryParameter("scope", "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile")
            .addQueryParameter("state", state).addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256").build().toString()
        return GeminiAuthorization(nonce(), url, now + 600_000, verifier, state)
    }

    fun exchange(auth: GeminiAuthorization, input: String, now: Long): FormBody {
        require(now < auth.expiresAt) { "Google 授权已超时，请重新登录" }
        val text = input.trim()
        val code = if (text.startsWith("https://")) {
            val callback = text.toHttpUrl()
            require(callback.host == "codeassist.google.com" && callback.encodedPath == "/authcode") { "授权回调地址不匹配" }
            require(callback.queryParameter("state") == auth.state) { "授权会话不匹配，请使用本次授权码" }
            requireNotNull(callback.queryParameter("code")) { "回调缺少授权码" }
        } else text
        require(code.isNotBlank() && code.none(Char::isWhitespace)) { "请填写 Google 授权页面提供的授权码" }
        return credentials().add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", REDIRECT).add("code_verifier", auth.verifier).build()
    }
    fun refresh(token: String): FormBody = credentials().add("grant_type", "refresh_token").add("refresh_token", token).build()
    private fun credentials() = FormBody.Builder().add("client_id", CLIENT).add("client_secret", INSTALLED_CLIENT_VALUE)
}
