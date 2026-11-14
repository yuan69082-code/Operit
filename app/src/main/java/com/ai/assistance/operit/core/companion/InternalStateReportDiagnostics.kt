package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Stable, safe diagnostics: neither exception messages nor model-authored field names escape. */
internal class InternalStateReportIssue(
    val stage: String,
    val path: String,
    val rule: String
) : IllegalArgumentException("ISR_${rule}: ${path}") {
    val code: String get() = "ISR_${rule}"
    val summary: String get() = "${code} · ${path} · ${stage}"
}

internal object InternalStateReportDiagnostics {
    private val sections = setOf("frame_id", "updates", "create", "revise", "foundation", "inner",
        "episodes", "unions", "decisions", "action_feedback", "rest", "dream", "dream_understanding")
    private val arrays = setOf("updates", "create", "revise", "foundation", "inner", "episodes", "unions", "decisions",
        "action_feedback", "dream_understanding")
    private val objects = setOf("rest", "dream")

    /** Validate only the shared structural protocol. Domain-specific rules remain authoritative. */
    fun validate(report: JSONObject) {
        val unknown = report.keys().asSequence().any { it !in sections }
        if (unknown) throw InternalStateReportIssue("root", "$", "UNKNOWN_FIELD")
        string(report, "frame_id", "frame_id")
        for (field in arrays) if (field in setOf("updates", "create") || report.has(field)) {
            array(report, field, field)
            val rows = report.getJSONArray(field)
            for (i in 0 until rows.length()) {
                val path = "$field[$i]"
                val entry = objectAt(rows, i, path)
                when (field) {
                    "updates" -> {
                        string(entry, "id", "$path.id")
                        if (entry.has("value")) number(entry, "value", "$path.value")
                        string(entry, "reason", "$path.reason")
                        evidence(entry, path)
                        if (entry.has("unresolved")) boolean(entry, "unresolved", "$path.unresolved")
                    }
                    "create" -> {
                        objectField(entry, "definition", "$path.definition")
                        string(entry, "reason", "$path.reason")
                        number(entry, "value", "$path.value")
                        evidence(entry, path)
                    }
                    "revise" -> {
                        string(entry, "id", "$path.id")
                        integer(entry, "expected_version", "$path.expected_version")
                        objectField(entry, "changes", "$path.changes")
                        string(entry, "reason", "$path.reason")
                        evidence(entry, path)
                    }
                    "foundation" -> {
                        string(entry, "id", "$path.id")
                        integer(entry, "expected_version", "$path.expected_version")
                        number(entry, "value", "$path.value")
                        array(entry, "evidence", "$path.evidence")
                        evidence(entry, path)
                        if (entry.has("reason")) stringOrEmpty(entry, "reason", "$path.reason")
                        if (entry.has("objects")) stringArray(entry, "objects", "$path.objects")
                        if (entry.has("goals")) stringArray(entry, "goals", "$path.goals")
                    }
                    "inner" -> {
                        string(entry, "op", "$path.op")
                        string(entry, "id", "$path.id")
                        if (entry.optString("op") == "propose") {
                            objectField(entry, "record", "$path.record")
                            integer(entry, "expected_version", "$path.expected_version")
                            string(entry, "origin", "$path.origin")
                            evidence(entry, path, true)
                        }
                    }
                    "episodes" -> {
                        string(entry, "op", "$path.op")
                        string(entry, "id", "$path.id")
                        integer(entry, "expected_version", "$path.expected_version")
                        if (entry.optString("op") == "appraise") {
                            string(entry, "category_ref", "$path.category_ref")
                            number(entry, "intensity", "$path.intensity")
                            stringArray(entry, "evidence", "$path.evidence")
                        }
                    }
                    "unions" -> {
                        string(entry, "id", "$path.id")
                        integer(entry, "expected_version", "$path.expected_version")
                        array(entry, "members", "$path.members")
                        evidence(entry, path, true)
                    }
                    "decisions", "action_feedback" -> {
                        string(entry, "id", "$path.id")
                        integer(entry, "expected_version", "$path.expected_version")
                        string(entry, "meaning", "$path.meaning")
                        stringArray(entry, "evidence", "$path.evidence")
                    }
                    "dream_understanding" -> {
                        string(entry, "id", "$path.id")
                        integer(entry, "expected_version", "$path.expected_version")
                    }
                }
            }
        }
        for (field in objects) if (report.has(field)) objectField(report, field, field)
    }

    /** Never surface JSONException.message or a user-provided unknown key as a path. */
    fun issue(failure: Exception, stage: String): InternalStateReportIssue = when (failure) {
        is InternalStateReportIssue -> failure
        is InternalStateReportRejected ->
            InternalStateReportIssue("contract", failure.reason.name.lowercase(), failure.reason.name)
        is JSONException -> InternalStateReportIssue(stage, stage, "JSON_SHAPE")
        is IllegalArgumentException -> InternalStateReportIssue(stage, stage, "SEMANTIC_RULE")
        else -> InternalStateReportIssue(stage, stage, "INTERNAL")
    }

    inline fun <T> stage(name: String, body: () -> T): T {
        try { return body() }
        catch (known: InternalStateReportIssue) { throw known }
        catch (known: InternalStateReportRejected) { throw known }
        catch (failure: Exception) {
            if (failure is JSONException || failure is IllegalArgumentException) throw issue(failure, name)
            throw failure
        }
    }

    private fun objectAt(values: JSONArray, index: Int, path: String): JSONObject =
        (values.opt(index) as? JSONObject) ?: throw InternalStateReportIssue("schema", path, "EXPECTED_OBJECT")

    private fun objectField(parent: JSONObject, key: String, path: String): JSONObject =
        (parent.opt(key) as? JSONObject) ?: throw InternalStateReportIssue("schema", path, "EXPECTED_OBJECT")

    private fun array(parent: JSONObject, key: String, path: String): JSONArray =
        (parent.opt(key) as? JSONArray) ?: throw InternalStateReportIssue("schema", path, "EXPECTED_ARRAY")

    private fun string(parent: JSONObject, key: String, path: String) {
        if (parent.opt(key) !is String || parent.getString(key).isBlank())
            throw InternalStateReportIssue("schema", path, "REQUIRED_STRING")
    }

    private fun stringOrEmpty(parent: JSONObject, key: String, path: String) {
        if (parent.opt(key) !is String)
            throw InternalStateReportIssue("schema", path, "EXPECTED_STRING")
    }

    private fun boolean(parent: JSONObject, key: String, path: String) {
        if (parent.opt(key) !is Boolean)
            throw InternalStateReportIssue("schema", path, "EXPECTED_BOOLEAN")
    }

    private fun number(parent: JSONObject, key: String, path: String) {
        val n = parent.opt(key)
        if (n !is Number || !n.toDouble().isFinite())
            throw InternalStateReportIssue("schema", path, "FINITE_NUMBER")
    }

    private fun integer(parent: JSONObject, key: String, path: String) {
        val n = parent.opt(key)
        if (n !is Number || !n.toDouble().isFinite() || n.toDouble() != n.toInt().toDouble() || n.toInt() < 0)
            throw InternalStateReportIssue("schema", path, "VERSION_NUMBER")
    }

    private fun stringArray(parent: JSONObject, key: String, path: String) {
        val a = array(parent, key, path)
        for (i in 0 until a.length()) {
            if (a.opt(i) !is String) throw InternalStateReportIssue("schema", "$path[$i]", "EXPECTED_STRING")
        }
    }

    private fun evidence(parent: JSONObject, path: String, required: Boolean = false) {
        if (required || parent.has("evidence")) stringArray(parent, "evidence", "$path.evidence")
    }
}
