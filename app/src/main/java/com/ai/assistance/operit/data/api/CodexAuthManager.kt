package com.ai.assistance.operit.data.api

import android.content.Context
import com.ai.assistance.operit.data.preferences.CodexAuthPreferences
import com.ai.assistance.operit.data.preferences.CodexAuthState
import com.ai.assistance.operit.data.preferences.CodexStoredUsageSnapshot
import com.ai.assistance.operit.data.preferences.CodexUsagePreferences
import com.ai.assistance.operit.util.AppLogger
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

class CodexAuthManager private constructor(context: Context) {
    private val preferences = CodexAuthPreferences.getInstance(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val oauthClient = CodexOAuthClient(client = httpClient)
    private val usageClient = CodexUsageClient(client = httpClient)
    private val usagePreferences = CodexUsagePreferences.getInstance(context)
    private val refreshMutex = Mutex()
    private val accountLock = Any()
    private val requestGuard = AccountRequestGuard { preferences.currentState()?.sessionId }
    private var loginAttempt: String? = null

    fun beginLogin(): String = synchronized(accountLock) { java.util.UUID.randomUUID().toString().also { loginAttempt = it } }
    fun cancelLogin(attempt: String) = synchronized(accountLock) { if (loginAttempt == attempt) loginAttempt = null }
    fun boundClient(client: OkHttpClient, session: String): OkHttpClient =
        client.newBuilder().addInterceptor(requestGuard.interceptor(session)).addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code != 401) response else {
                // Only a rejected HTTP request is retried, before streaming or executing tools.
                response.close()
                val current = requireSession(session)
                val updated = kotlinx.coroutines.runBlocking {
                    if (chain.request().header("Authorization") == "Bearer ${current.accessToken}") refreshAccessToken(current)
                    else validState(session)
                }
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${updated.accessToken}")
                    .header("ChatGPT-Account-ID", updated.accountId).build())
            }
        }.build()
    fun requireSession(session: String): CodexAuthState = synchronized(accountLock) {
        requestGuard.check(session)
        requireNotNull(preferences.currentState())
    }

    val authState: StateFlow<CodexAuthState?> = preferences.authState
    val usageSnapshotFlow: Flow<CodexStoredUsageSnapshot?> = usagePreferences.snapshotFlow

    suspend fun saveLoginTokens(tokens: CodexOAuthTokenResponse, attempt: String? = null): CodexAuthState {
        val completeTokens = tokens.requireComplete()
        val accessToken = requireNotNull(completeTokens.accessToken)
        val idToken = requireNotNull(completeTokens.idToken)
        val refreshToken = requireNotNull(completeTokens.refreshToken)
        val accessClaims = CodexOAuthProtocol.parseJwtClaims(accessToken)
            ?: throw IOException("Codex access token is not a valid JWT")
        val idClaims = CodexOAuthProtocol.parseJwtClaims(idToken)
        val accountId = idClaims?.accountId ?: accessClaims.accountId
            ?: throw IOException("Codex token has no ChatGPT account ID")
        val expiresAt = accessClaims.expiresAtMillis
            ?: completeTokens.expiresInSeconds?.let { System.currentTimeMillis() + it * 1000L }
            ?: throw IOException("Codex access token has no expiration")
        val state = CodexAuthState(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = expiresAt,
            accountId = accountId,
            residency = accessClaims.residency ?: idClaims?.residency,
            email = idClaims?.email ?: accessClaims.email,
        )
        synchronized(accountLock) {
            if (attempt != null && loginAttempt != attempt) throw CancellationException("登录已取消或被新登录替换")
            preferences.save(state)
            loginAttempt = null
            requestGuard.changed()
        }
        return state
    }

    suspend fun getValidAccessToken(): String = validState(requireNotNull(preferences.currentState()) { "Codex is not logged in" }.sessionId).accessToken

    suspend fun validState(session: String): CodexAuthState {
        val bound = requireSession(session)
        val current = preferences.currentState()
            ?: throw IOException("Codex is not logged in")
        if (current.expiresAtMillis - System.currentTimeMillis() > REFRESH_WINDOW_MILLIS) {
            return requireSession(bound.sessionId)
        }

        return refreshMutex.withLock {
            val latest = requireSession(session)
            if (latest.expiresAtMillis - System.currentTimeMillis() > REFRESH_WINDOW_MILLIS) {
                latest
            } else {
                refreshLocked(latest)
            }
        }
    }

    suspend fun refreshAccessToken(current: CodexAuthState): CodexAuthState = refreshMutex.withLock {
        val latest = requireSession(current.sessionId)
        if (latest.accessToken != current.accessToken) latest else refreshLocked(latest)
    }

    private suspend fun refreshLocked(current: CodexAuthState): CodexAuthState {
        val response = oauthClient.refreshAccessToken(current.refreshToken)
        val accessToken = response.accessToken
            ?: throw IOException("Codex refresh response has no access token")
        val accessClaims = CodexOAuthProtocol.parseJwtClaims(accessToken)
            ?: throw IOException("Codex refreshed access token is not a valid JWT")
        val idClaims = response.idToken?.let(CodexOAuthProtocol::parseJwtClaims)
        val accountId = idClaims?.accountId ?: accessClaims.accountId ?: current.accountId
        val expiresAt = accessClaims.expiresAtMillis
            ?: response.expiresInSeconds?.let { System.currentTimeMillis() + it * 1000L }
            ?: throw IOException("Codex refreshed access token has no expiration")
        val updated = current.copy(
            accessToken = accessToken,
            refreshToken = response.refreshToken ?: current.refreshToken,
            expiresAtMillis = expiresAt,
            accountId = accountId,
            residency = accessClaims.residency ?: idClaims?.residency ?: current.residency,
            email = idClaims?.email ?: accessClaims.email ?: current.email,
        )
        synchronized(accountLock) {
            requireSession(current.sessionId)
            preferences.save(updated)
        }
        return updated
    }

    suspend fun logout() {
        val current = synchronized(accountLock) {
            val old = preferences.currentState()
            loginAttempt = null
            preferences.clear()
            requestGuard.changed()
            old
        }
        try {
            if (current != null) {
                oauthClient.revokeToken(current.refreshToken, "refresh_token")
            }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to revoke Codex OAuth token", error)
        }
    }

    fun currentAccountId(): String? = preferences.currentState()?.accountId

    fun currentResidency(): String? = preferences.currentState()?.residency

    suspend fun fetchUsage(): Result<CodexUsageSnapshot> {
        return try {
            val session = requireNotNull(preferences.currentState()) { "Codex is not logged in" }.sessionId
            val state = validState(session)
            val result = usageClient.fetch(
                accessToken = state.accessToken,
                accountId = state.accountId,
                residency = state.residency,
            )
            requireSession(session)
            if (result.isSuccess) {
                usagePreferences.save(state.accountId, result.getOrThrow())
            }
            result
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to prepare Codex usage request", error)
            Result.failure(error)
        }
    }

    companion object {
        private const val TAG = "CodexAuthManager"
        private const val REFRESH_WINDOW_MILLIS = 5 * 60 * 1000L

        @Volatile
        private var instance: CodexAuthManager? = null

        fun getInstance(context: Context): CodexAuthManager {
            return instance ?: synchronized(this) {
                instance ?: CodexAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
