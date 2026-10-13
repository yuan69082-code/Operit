package com.ai.assistance.operit.core.tools.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory evidence only. Persisted tool schemas are not evidence of a live connection. */
data class McpRuntimeState(
    val connected: Boolean = false,
    val discovering: Boolean = false,
    val tools: List<McpRuntimeTool>? = null,
    val error: String? = null
) {
    val ready: Boolean get() = connected && !discovering && tools != null && error == null
}

object McpSelectionPolicy {
    fun startupIds(installed: Set<String>, selected: Set<String>, enabled: (String) -> Boolean): List<String> =
        installed.filter { it in selected && enabled(it) }.sorted()

    fun readyCount(selected: Set<String>, states: Map<String, McpRuntimeState>): Int =
        selected.count { states[it]?.ready == true }
}

/** All consumers (startup, management page and AI calls) publish to the same state source. */
internal class TrackedMcpRuntimeSession(
    private val delegate: McpRuntimeSession,
    private val onState: (McpRuntimeState) -> Unit
) : McpRuntimeSession {
    private val state = MutableStateFlow(McpRuntimeState())
    private val discoveryMutex = Mutex()

    private fun update(change: (McpRuntimeState) -> McpRuntimeState) {
        state.update(change)
        onState(state.value)
    }

    override suspend fun connect(): Boolean {
        try {
            val connected = delegate.connect()
            update { if (connected) it.copy(connected = true, error = null)
                else McpRuntimeState(error = "MCP connection failed") }
            return connected
        } catch (e: CancellationException) {
            update { McpRuntimeState() }
            throw e
        } catch (e: Exception) {
            update { McpRuntimeState(error = e.message ?: e.javaClass.simpleName) }
            throw e
        }
    }

    override fun isConnected(): Boolean = state.value.connected && delegate.isConnected()

    override suspend fun isActive(): Boolean {
        val active = delegate.isActive()
        if (!active) update { McpRuntimeState() }
        return active
    }

    override suspend fun listTools(): List<McpRuntimeTool> = discoveryMutex.withLock {
        update { it.copy(discovering = true, error = null) }
        try {
            val tools = delegate.listTools()
            update { McpRuntimeState(connected = true, tools = tools) }
            tools
        } catch (e: CancellationException) {
            update { it.copy(discovering = false, tools = null) }
            throw e
        } catch (e: Exception) {
            update { McpRuntimeState(connected = delegate.isConnected(), error = e.message ?: e.javaClass.simpleName) }
            throw e
        }
    }

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpRuntimeCallResult {
        try {
            // A tool's business error is still a response from a working transport.
            return delegate.callTool(name, arguments).also {
                update { state -> state.copy(connected = true) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update { McpRuntimeState(error = e.message ?: e.javaClass.simpleName) }
            throw e
        }
    }

    override suspend fun close() {
        try { delegate.close() } finally { update { McpRuntimeState() } }
    }
}
