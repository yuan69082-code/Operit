package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.model.ApiProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

class VertexAiEndpointTest {
    @Test fun explicitGlobalLocationIsPreservedForBothRequestModes() {
        for (version in listOf("v1", "v1beta1")) {
            val base = "https://aiplatform.googleapis.com/$version/projects/test-project/locations/global"
            for (suffix in listOf("", "/publishers/google", "/publishers/google/models/")) {
                for (streaming in listOf(false, true)) {
                    val method = if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
                    assertEquals(
                        "$base/publishers/google/models/gemini-4-argon:$method",
                        VertexAiEndpoint.requestUrl("$base$suffix", "gemini-4-argon", streaming).toString()
                    )
                }
            }
        }
    }

    @Test fun explicitRegionalLocationAndNumericProjectArePreserved() {
        val base = "https://us-central1-aiplatform.googleapis.com/v1/projects/123456/locations/us-central1"
        assertEquals(
            "$base/publishers/google/models/gemini-2.5-flash:generateContent",
            VertexAiEndpoint.requestUrl(base, "gemini-2.5-flash", false).toString()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteProjectPathIsRejected() {
        VertexAiEndpoint.requestUrl("https://aiplatform.googleapis.com/v1/projects/test-project", "gemini-4-argon", false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun projectEndpointCannotSupplyCredentialsInQuery() {
        VertexAiEndpoint.requestUrl("https://aiplatform.googleapis.com/v1/projects/test-project/locations/global?key=secret", "gemini-4-argon", false)
    }

    @Test fun defaultProviderUsesVertexPublisherRoute() {
        val endpoint = EndpointCompleter.completeEndpoint(
            ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.VERTEX_AI),
            ApiProviderType.VERTEX_AI
        )
        assertEquals(
            "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-2.5-flash:generateContent",
            VertexAiEndpoint.requestUrl(endpoint, "gemini-2.5-flash", false).toString()
        )
    }

    @Test fun streamingUsesSseAndAcceptsPublisherModelName() {
        assertEquals(
            "https://aiplatform.googleapis.com/v1beta1/publishers/google/models/gemini-2.5-pro:streamGenerateContent?alt=sse",
            VertexAiEndpoint.requestUrl(
                "https://aiplatform.googleapis.com/v1beta1/publishers/google/models/",
                "publishers/google/models/gemini-2.5-pro", true
            ).toString()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidEndpointDoesNotSwitchToAiStudio() {
        VertexAiEndpoint.requestUrl("invalid", "gemini-2.5-flash", false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun developerApiPathIsRejected() {
        VertexAiEndpoint.requestUrl("https://aiplatform.googleapis.com/v1beta/models", "gemini-2.5-flash", false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelCannotInjectQueryParameters() {
        VertexAiEndpoint.requestUrl("https://aiplatform.googleapis.com", "gemini-2.5-flash?key=other", false)
    }
}
