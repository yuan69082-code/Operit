package com.ai.assistance.operit.core.tools.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class McpSelectionAndStateTest {
    private val selected = setOf("ai-toy", "ob", "hy", "xc")
    private val installed = selected + "termux"
    private val tool = McpRuntimeTool("lookup", "Lookup", "{}")

    @Test fun `unchecked globally enabled termux is excluded from startup`() {
        assertEquals(selected, McpSelectionPolicy.startupIds(installed, selected) { true }.toSet())
    }

    @Test fun `assistant with no selection starts nothing`() {
        assertTrue(McpSelectionPolicy.startupIds(installed, emptySet()) { true }.isEmpty())
    }

    @Test fun `missing and globally disabled services are excluded`() {
        assertEquals(listOf("xc"), McpSelectionPolicy.startupIds(installed, setOf("xc", "ob", "missing")) { it != "ob" })
    }

    @Test fun `switching assistant changes scope without changing shared connection state`() {
        val states = installed.associateWith { McpRuntimeState(connected = true, tools = listOf(tool)) }
        assertEquals(4, McpSelectionPolicy.readyCount(selected, states))
        assertEquals(1, McpSelectionPolicy.readyCount(setOf("termux"), states))
        assertEquals(0, McpSelectionPolicy.readyCount(emptySet(), states))
        assertEquals(installed, states.keys)
    }

    @Test fun `connected without discovered tools is not ready`() {
        assertFalse(McpRuntimeState(connected = true).ready)
        assertFalse(McpRuntimeState(tools = listOf(tool)).ready)
        assertFalse(McpRuntimeState(connected = true, tools = listOf(tool), error = "failed").ready)
    }

    @Test fun `successful empty discovery is distinguishable from failure`() = runBlocking {
        val fixture = Fixture()
        fixture.session.connect()
        assertFalse(fixture.latest.ready)
        fixture.session.listTools()
        assertTrue(fixture.latest.ready)
        assertEquals(emptyList<McpRuntimeTool>(), fixture.latest.tools)
    }

    @Test fun `tool list failure clears old tool readiness then recovery replaces tools`() = runBlocking {
        val fixture = Fixture()
        fixture.remote.tools = listOf(tool)
        fixture.session.connect()
        fixture.session.listTools()
        fixture.remote.discoveryFailure = IllegalStateException("tools/list failed")
        try {
            fixture.session.listTools()
            fail("Expected discovery error")
        } catch (_: IllegalStateException) { }
        assertFalse(fixture.latest.ready)
        assertNull(fixture.latest.tools)
        assertEquals("tools/list failed", fixture.latest.error)
        fixture.remote.discoveryFailure = null
        fixture.remote.tools = listOf(tool.copy(name = "new_tool"))
        fixture.session.listTools()
        assertTrue(fixture.latest.ready)
        assertEquals(listOf("new_tool"), fixture.latest.tools!!.map { it.name })
        assertNull(fixture.latest.error)
    }

    @Test fun `discovery cancellation propagates instead of reporting failure or ready`() = runBlocking {
        val fixture = Fixture()
        fixture.session.connect()
        fixture.remote.discoveryFailure = CancellationException("assistant changed")
        try {
            fixture.session.listTools()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(fixture.latest.ready)
        assertFalse(fixture.latest.discovering)
        assertNull(fixture.latest.error)
    }

    @Test fun `transport call failure invalidates online state`() = runBlocking {
        val fixture = Fixture()
        fixture.session.connect()
        fixture.session.listTools()
        fixture.remote.callFailure = IllegalStateException("connection closed")
        try {
            fixture.session.callTool("lookup", emptyMap())
            fail("Expected transport failure")
        } catch (_: IllegalStateException) { }
        assertFalse(fixture.latest.connected)
        assertFalse(fixture.latest.ready)
        assertEquals("connection closed", fixture.latest.error)
    }

    @Test fun `tool business error does not mark the transport disconnected`() = runBlocking {
        val fixture = Fixture()
        fixture.session.connect()
        fixture.session.listTools()
        fixture.remote.callResult = McpRuntimeCallResult(false, null, "invalid arguments")
        assertFalse(fixture.session.callTool("lookup", emptyMap()).success)
        assertTrue(fixture.latest.ready)
    }

    @Test fun `closed session no longer contributes to online count`() = runBlocking {
        val fixture = Fixture()
        fixture.session.connect()
        fixture.session.listTools()
        fixture.session.close()
        assertEquals(0, McpSelectionPolicy.readyCount(setOf("xc"), mapOf("xc" to fixture.latest)))
        assertNull(fixture.latest.tools)
    }

    @Test fun `connection exception is preserved for the page and restart log`() = runBlocking {
        val fixture = Fixture()
        fixture.remote.connectFailure = IllegalStateException("HTTP 401")
        try {
            fixture.session.connect()
            fail("Expected connection error")
        } catch (_: IllegalStateException) { }
        assertEquals("HTTP 401", fixture.latest.error)
        assertFalse(fixture.latest.ready)
    }

    private class Fixture {
        val remote = FakeSession()
        var latest = McpRuntimeState()
        val session = TrackedMcpRuntimeSession(remote) { latest = it }
    }

    private class FakeSession : McpRuntimeSession {
        var connected = false
        var tools = emptyList<McpRuntimeTool>()
        var discoveryFailure: Exception? = null
        var connectFailure: Exception? = null
        var callFailure: Exception? = null
        var callResult = McpRuntimeCallResult(true, null)

        override suspend fun connect(): Boolean {
            connectFailure?.let { throw it }
            connected = true
            return true
        }
        override fun isConnected() = connected
        override suspend fun isActive() = connected
        override suspend fun listTools(): List<McpRuntimeTool> {
            discoveryFailure?.let { throw it }
            return tools
        }
        override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpRuntimeCallResult {
            callFailure?.let { throw it }
            return callResult
        }
        override suspend fun close() { connected = false }
    }
}
