package com.ai.assistance.operit.core.companion

import org.json.JSONObject

/** Host boundary, not a new personality/reducer or a second subject identity. */
internal object SubjectStateBoundary {
    const val FIELD = "subject_boundary"
    const val SCHEMA = 1
    private val domains = setOf("internal-state", "thoughts", "attention")

    fun scope(role: String, domain: String): String {
        require(role.isNotBlank() && !role.startsWith("group:") && !role.startsWith("unassigned:")) {
            "状态必须归属现有独立助手"
        }
        require(domain in domains)
        return "$domain:$role"
    }

    fun owner(key: String): String? = key.substringBefore(':').takeIf { it in domains }
        ?.let { key.substringAfter(':').also { role -> scope(role, it) } }

    fun authorize(owner: String, caller: String?) {
        require(caller == null || caller == owner) { "不能代替其他助手读写内部状态" }
    }

    /** Adopts only file ownership. Missing psychological provenance stays missing. */
    fun adopt(data: JSONObject, role: String, domain: String): Boolean {
        scope(role, domain)
        val boundary = data.optJSONObject(FIELD)
        if (boundary != null) {
            check(boundary.getInt("schema") == SCHEMA) { "不支持的主体存储版本" }
            check(boundary.getString("owner") == role && boundary.getString("domain") == domain) { "主体存储归属冲突" }
            check(boundary.getLong("revision") >= 0)
            return false
        }
        data.put(FIELD, JSONObject().put("schema", SCHEMA).put("owner", role).put("domain", domain)
            .put("revision", 0L).put("adoption", "existing_assistant_scope")
            .put("legacy_provenance", "unchanged_or_unknown"))
        return true
    }

    fun revision(data: JSONObject): Long = data.getJSONObject(FIELD).getLong("revision")

    fun committed(data: JSONObject, before: JSONObject, source: String) {
        require(source.isNotBlank())
        // Same accepted report/event is a no-op, including its host version.
        if (same(data, before)) return
        val previous = before.getJSONObject(FIELD)
        check(same(data.getJSONObject(FIELD), previous)) { "业务写入不能修改主体边界" }
        val revision = previous.getLong("revision")
        check(revision < Long.MAX_VALUE)
        data.getJSONObject(FIELD).put("revision", revision + 1).put("last_source", source)
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.length() == b.length() &&
            a.keys().asSequence().all { b.has(it) && same(a.get(it), b.get(it)) }
        a is org.json.JSONArray && b is org.json.JSONArray -> a.length() == b.length() &&
            (0 until a.length()).all { same(a.get(it), b.get(it)) }
        else -> a == b
    }
}
