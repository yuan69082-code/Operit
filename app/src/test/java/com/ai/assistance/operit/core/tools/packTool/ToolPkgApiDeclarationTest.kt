package com.ai.assistance.operit.core.tools.packTool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolPkgApiCompatibilityTest {
    @Test
    fun `blank declared API version is legacy version`() {
        assertEquals(
            ToolPkgApiVersion.parse("1.0.0"),
            ToolPkgApiCompatibility.parseDeclaredApiVersion(" ")
        )
    }

    @Test
    fun `unsupported declared API version reports host support`() {
        expectIllegalArgument("Supported ToolPkg API versions: 1.0.0") {
            ToolPkgApiCompatibility.requireSupported(
                apiVersion = "1.0.1",
                operitVersion = "1.12.1+3"
            )
        }
    }

    @Test
    fun `shared release version supports tool package APIs without startup failure`() {
        assertEquals(
            "1.0.0, 1.0.1",
            ToolPkgApiCompatibility.supportedApiVersionText("1.12.2-pc.1+118")
        )
        assertEquals(
            ToolPkgApiVersion.parse("1.0.1"),
            ToolPkgApiCompatibility.requireSupported("1.0.1", "1.12.2-pc.1+118")
        )
    }

    @Test
    fun `channel suffix preserves build compatibility threshold`() {
        for (suffix in listOf("", "-pc.1", "-nightly")) {
            assertEquals(
                "1.0.0",
                ToolPkgApiCompatibility.supportedApiVersionText("1.12.1${suffix}+3")
            )
            assertEquals(
                "1.0.0, 1.0.1",
                ToolPkgApiCompatibility.supportedApiVersionText("1.12.1${suffix}+4")
            )
            assertEquals(
                "1.0.0",
                ToolPkgApiCompatibility.supportedApiVersionText("1.12.1${suffix}")
            )
        }
    }

    @Test
    fun `invalid host version is still rejected`() {
        for (version in listOf("1.12", "1.12.2-", "1.12.2-pc..1+118", "1.12.2-pc.1+oops")) {
            expectIllegalArgument("Operit version is not supported") {
                ToolPkgApiCompatibility.supportedApiVersionText(version)
            }
        }
    }

    private fun expectIllegalArgument(
        messageFragment: String,
        block: () -> Unit
    ) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains(messageFragment))
        }
    }
}
