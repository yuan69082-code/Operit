package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.data.api.SubscriptionAccounts
import com.ai.assistance.operit.data.api.SubscriptionChannels
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import okhttp3.OkHttpClient

/** The normal service factory uses the existing streaming/tool adapters. The assistant,
 * history, state version and tool executor are unchanged when this transport is selected. */
internal object SubscriptionProviderFactory {
    fun create(context: Context, config: ModelConfigData, client: OkHttpClient): AIService {
        val accounts = SubscriptionAccounts.get(context)
        return create(accounts, config, client)
    }
    internal fun create(accounts: SubscriptionAccounts, config: ModelConfigData, client: OkHttpClient): AIService {
        val channel = config.subscriptionChannel
        require(channel in SubscriptionChannels.directChannels) { "该订阅没有开放直接接入" }
        val session = requireNotNull(accounts.sessionId(channel)) { "请在模型配置中连接订阅账号" }
        val endpoint = requireNotNull(SubscriptionChannels.get(channel).endpoint)
        val keys = object : ApiKeyProvider {
            override suspend fun getApiKey() = accounts.token(channel, session)
            override suspend fun getCandidateKeyCount(): Int = if (accounts.sessionId(channel) == session) 1 else 0
        }
        val bound = accounts.boundClient(channel, session, client)
        val headers = accounts.headers(channel)
        if (channel == "gemini") return GeminiSubscriptionProvider(accounts, session, config, keys, bound)
        return if (channel == "grok") OpenAIResponsesProvider(
            responsesApiEndpoint = endpoint, apiKeyProvider = keys, modelName = config.modelName,
            client = bound, customHeaders = headers, responsesProviderType = ApiProviderType.OPENAI_RESPONSES_GENERIC,
            supportsVision = config.enableDirectImageProcessing, enableToolCall = config.enableToolCall,
            thinkingConfigurations = config.thinkingConfigurations, thinkingOptionId = config.thinkingOptionId
        ) else OpenAIProvider(
            apiEndpoint = endpoint, apiKeyProvider = keys, modelName = config.modelName,
            client = bound, customHeaders = headers, providerType = ApiProviderType.OPENAI_GENERIC,
            supportsVision = config.enableDirectImageProcessing, enableToolCall = config.enableToolCall,
            thinkingConfigurations = config.thinkingConfigurations, thinkingOptionId = config.thinkingOptionId
        )
    }
}
