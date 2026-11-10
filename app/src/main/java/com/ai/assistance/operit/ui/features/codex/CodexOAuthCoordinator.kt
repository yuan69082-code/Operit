package com.ai.assistance.operit.ui.features.codex

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.data.api.CodexAuthManager
import com.ai.assistance.operit.data.api.CodexOAuthClient
import com.ai.assistance.operit.data.api.CodexOAuthProtocol
import com.ai.assistance.operit.data.api.CodexPkceCodes
import com.ai.assistance.operit.data.preferences.CodexAuthState
import java.io.IOException

internal data class CodexOAuthLoginSession(
    internal val callbackServer: CodexOAuthLoopbackCallbackServer,
    internal val pkce: CodexPkceCodes,
    internal val state: String,
    val authorizationUrl: String,
    val expiresAt: Long,
    val attempt: String,
) {
    val redirectUri: String
        get() = callbackServer.redirectUri
}

internal class CodexOAuthCoordinator(context: Context) {
    private val authManager = CodexAuthManager.getInstance(context)
    private val oauthClient = CodexOAuthClient(
        client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
    )

    suspend fun startLogin(): CodexOAuthLoginSession {
        val callbackServer = CodexOAuthLoopbackCallbackServer.open()
        val pkce = CodexOAuthProtocol.generatePkce()
        val state = CodexOAuthProtocol.generateState()
        val expiresAt = System.currentTimeMillis() + CodexOAuthProtocol.OAUTH_TIMEOUT_MILLIS
        return CodexOAuthLoginSession(
            callbackServer = callbackServer,
            pkce = pkce,
            state = state,
            authorizationUrl = CodexOAuthProtocol.buildAuthorizationUrl(
                redirectUri = callbackServer.redirectUri,
                pkce = pkce,
                state = state,
            ),
            expiresAt = expiresAt,
            attempt = authManager.beginLogin(),
        )
    }

    suspend fun completeLogin(
        session: CodexOAuthLoginSession,
        callbackUri: Uri,
    ): CodexAuthState {
        val callbackState = callbackUri.getQueryParameter("state")
        if (callbackState != session.state) {
            throw IOException("Codex OAuth state does not match")
        }

        val error = callbackUri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val description = callbackUri.getQueryParameter("error_description")
            throw IOException(description ?: error)
        }

        val code = callbackUri.getQueryParameter("code")
            ?: throw IOException("Codex OAuth callback has no authorization code")
        val tokens = oauthClient.exchangeAuthorizationCode(
            code = code,
            redirectUri = session.redirectUri,
            verifier = session.pkce.verifier,
        )
        if (System.currentTimeMillis() >= session.expiresAt) throw IOException("Codex 登录已超时")
        return authManager.saveLoginTokens(tokens, session.attempt)
    }

    suspend fun logout() {
        authManager.logout()
    }

    fun cancel(session: CodexOAuthLoginSession) {
        authManager.cancelLogin(session.attempt)
        session.callbackServer.close()
    }
}
