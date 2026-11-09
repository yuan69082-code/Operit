package com.ai.assistance.operit.core.companion

import android.content.Context
import androidx.core.util.AtomicFile
import com.ai.assistance.operit.core.tools.calculator.ExpressionParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A versioned artifact adapter for the EXISTING calculator, not a new script platform.
 * Immutable source files are executable artifacts, not memory or another action ledger. */
internal class SoftwareCapabilityArtifacts(context: Context, private val role: String, chat: String) {
    private val root: File
    init {
        val state = InternalStateStore(context)
        check(state.enabled(role)) { "当前助手未开启内部状态，未执行软件能力" }
        require(!state.recipientScope(role, chat)) { "私人软件能力只在本助手私聊使用" }
        SubjectStateBoundary.authorize(role, com.ai.assistance.operit.data.preferences.AssistantScope.callerId())
        root = File(context.filesDir, "companion/capability-artifacts/" + InnerStructure.hash(role)).apply { mkdirs() }
    }
    private fun path(version: String): File {
        require(version.matches(Regex("[0-9a-f]{64}")))
        return File(root, "$version.json")
    }
    private fun names(source: String): Set<String> =
        Regex("\\$\\{([a-z][a-z0-9_]{0,23})}").findAll(source).map { it.groupValues[1] }.toSet()

    private fun expression(source: String, inputs: JSONObject): String {
        require(source.length in 1..2048)
        val names = names(source)
        require(names.size <= 16 && inputs.keys().asSequence().toSet() == names)
        val expanded = Regex("\\$\\{([a-z][a-z0-9_]{0,23})}").replace(source) { match ->
            val number = inputs.get(match.groupValues[1])
            require(number is Number && number.toDouble().isFinite())
            "(${number.toDouble()})"
        }
        // The existing calculator also has mutable variables/functions. This adapter only
        // exposes pure arithmetic so one assistant cannot read another calculator's globals.
        require(expanded.matches(Regex("[0-9eE+*/().%\\s-]+")))
        require(expanded.count { it == '(' } <= 32)
        return expanded
    }

    private fun artifact(id: String, source: String, previous: String?, requestId: String): JSONObject {
        require(id.length in 1..80)
        if (previous != null) require(get(previous).getString("id") == id)
        return JSONObject().put("runtime", "operit-calculator-arithmetic/v1").put("owner", role)
            .put("id", id).put("source", source).put("previous_version", previous ?: JSONObject.NULL)
            .put("build_request_id", requestId).put("inputs", JSONArray(names(source).sorted()))
    }

    private fun installed(artifact: JSONObject) = JSONObject().put("id", artifact.getString("id"))
        .put("version", artifact.getString("version")).put("stage", "installed")
        .put("artifact", path(artifact.getString("version")).name).put("inputs", artifact.getJSONArray("inputs"))
        .put("runtime", artifact.getString("runtime")).put("execution", "not_run")

    /** Verify this exact build's immutable output; never build again to answer recovery. */
    fun recoverBuild(id: String, source: String, previous: String?, requestId: String): JSONObject? {
        val expected = artifact(id, source, previous, requestId)
        val version = InnerStructure.hash(expected)
        return synchronized(locks.computeIfAbsent(root.absolutePath) { Any() }) {
            if (!path(version).exists()) null else installed(get(version))
        }
    }

    fun build(id: String, source: String, previous: String?): JSONObject {
        val requestId = requireNotNull(com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager.currentToolRuntimeContext()?.hostInvocationId)
        val probe = JSONObject()
        names(source).forEach { probe.put(it, 0) }
        ExpressionParser(expression(source, probe)).parse() // Syntax only: never run a build.
        val artifact = artifact(id, source, previous, requestId)
        val version = InnerStructure.hash(artifact)
        val file = path(version)
        synchronized(locks.computeIfAbsent(root.absolutePath) { Any() }) {
            val atomic = AtomicFile(file)
            if (file.exists()) check(get(version).toString() == artifact.put("version", version).toString())
            else {
                artifact.put("version", version)
                val out = atomic.startWrite()
                try { out.write(artifact.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
                catch (failure: Throwable) { atomic.failWrite(out); throw failure }
            }
        }
        return installed(artifact)
    }

    fun get(version: String): JSONObject {
        val row = JSONObject(AtomicFile(path(version)).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(row.getString("owner") == role && row.getString("version") == version)
        val content = JSONObject(row.toString()).apply { remove("version") }
        require(InnerStructure.hash(content) == version) { "能力产物校验失败" }
        return row
    }

    fun run(version: String, inputs: JSONObject): JSONObject {
        val artifact = get(version)
        val result = ExpressionParser(expression(artifact.getString("source"), inputs)).parse().evaluate()
        require(result.isFinite()) { "计算未产生有限结果" }
        return JSONObject().put("id", artifact.getString("id")).put("version", version)
            .put("runtime", artifact.getString("runtime")).put("stage", "executed")
            .put("inputs", inputs).put("output", result).put("meaning", "technical_result_not_subject_satisfaction")
    }
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}
