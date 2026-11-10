package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject

/** Schema-1 value compatibility over the existing transactional archive. Page sizes bound
 * IO, never the number of saved beliefs. Persisted legacy documents migrate before exposure. */
internal class InnerRows(private val data: JSONObject, private val pending: Boolean,
                         private val frame: InternalStateFrame? = null) {
    private val root = InnerStructure.root(data)
    private val name = if (pending) "pending" else "records"
    private val kind = if (pending) "inner_pending" else "inner_record"
    private val archived = root.optString("storage") == "archive_v1"
    private val port get() = requireNotNull((data as? InternalStateDocument)?.history)
    private fun legacy() = root.getJSONObject(name)
    private fun live(row: JSONObject?) = row?.takeUnless { it.optBoolean("closed") }?.getJSONObject("value")
    fun optJSONObject(id: String): JSONObject? = if (archived) live(CategoryEvents.row(data, kind, id)) else legacy().optJSONObject(id)
    fun getJSONObject(id: String): JSONObject = requireNotNull(optJSONObject(id)) { "内在记录不存在: $id" }
    fun has(id: String) = optJSONObject(id) != null
    fun used(id: String) = if (archived) CategoryEvents.row(data, kind, id) != null else has(id)
    fun familyPage(family: String, offset: Int, limit: Int = 8): List<JSONObject> {
        require(!pending && family in InnerStructure.families && offset >= 0 && limit in 1..21)
        return if (archived) port.categoryPage("inner_family", offset, limit, family)
            .mapNotNull { optJSONObject(it.getString("id")) }
        else allLegacy().filter { it.getJSONObject("content").getString("family") == family }.drop(offset).take(limit)
    }
    fun familyCount(family: String): Long = if (archived) port.categoryCount("inner_family", family)
        else allLegacy().count { it.getJSONObject("content").getString("family") == family }.toLong()
    fun count(parent: String? = null): Long = if (archived) port.categoryCount(kind, parent ?: if (pending) "open" else null)
        else allLegacy().count { parent == null || parentOf(it, pending) == parent }.toLong()
    private fun allLegacy() = legacy().keys().asSequence().map { legacy().getJSONObject(it) }.toList()
    fun page(offset: Int, limit: Int = 8, parent: String? = null): List<JSONObject> {
        require(offset >= 0 && limit in 1..21)
        return if (archived) port.categoryPage(kind, offset, limit, parent ?: if (pending) "open" else null).mapNotNull(::live)
        else allLegacy().filter { parent == null || parentOf(it, pending) == parent }.drop(offset).take(limit)
    }
    fun rotating(cursor: Long, parent: String? = null): List<JSONObject> {
        val count = count(parent)
        if (count == 0L) return emptyList()
        val start = Math.floorMod(cursor, count).toInt()
        val first = page(start, 8, parent)
        return if (first.size == 8 || start == 0) first else first + page(0, minOf(8 - first.size, start), parent)
    }
    fun relevant(input: String, parent: String): List<JSONObject> {
        require(!pending && parent in setOf("identity", "other"))
        if (input.isBlank()) return emptyList()
        if (archived) return port.relevantInnerRows(input, parent, 8)
        val ids = InnerMaterialSelection.ranked(allLegacy().asSequence().mapNotNull(InnerMaterialSelection::entry),
            InnerMaterialSelection.terms(input), parent, 8)
        return ids.map(::getJSONObject)
    }
    fun put(id: String, value: JSONObject) {
        if (!archived) { legacy().put(id, value); return }
        val f = requireNotNull(frame)
        if (!pending) {
            optJSONObject(id)?.let { indexes(it.getJSONObject("content")) }?.forEach { index(it, id, true, f) }
            indexes(value.getJSONObject("content")).forEach { index(it, id, false, f) }
            CategoryEvents.append(data, f, envelope("inner_family", id, JSONObject().put("target", id),
                value.getJSONObject("content").getString("family"), f.id))
        }
        CategoryEvents.append(data, f, envelope(kind, id, value, parentOf(value, pending), f.id))
    }
    fun remove(id: String) {
        if (!archived) { legacy().remove(id); return }
        val f = requireNotNull(frame)
        CategoryEvents.append(data, f, envelope(kind, id, getJSONObject(id), "closed", f.id).put("closed", true))
    }
    private fun index(key: String, id: String, closed: Boolean, f: InternalStateFrame) {
        CategoryEvents.append(data, f, envelope("inner_index", key, JSONObject().put("target", id), "index", f.id).put("closed", closed))
    }
    fun conflict(content: JSONObject): Boolean {
        val id = content.getString("id")
        val keys = indexes(content)
        if (!archived) return allLegacy().any { it.getString("id") != id && indexes(it.getJSONObject("content")).any(keys::contains) }
        return keys.any { key ->
            val row = CategoryEvents.row(data, "inner_index", key)
            row != null && !row.optBoolean("closed") && row.getJSONObject("value").getString("target") != id
        }
    }
    companion object {
        fun parentOf(value: JSONObject, pending: Boolean): String = if (pending) "open" else {
            val c = value.getJSONObject("content")
            if (c.getString("family") in InnerStructure.identityFamilies && c.getString("status") == "active") "identity" else "other"
        }
        fun indexes(c: JSONObject): List<String> = buildList {
            if (c.has("semantic_rule")) add("rule:" + c.getString("semantic_rule"))
            if (c.getString("family") == "relation") add("relation:" + c.getString("object_ref"))
            if (c.getString("family") in setOf("drive", "need") && c.getString("status") == "active")
                add("category:" + InnerStructure.hash(JSONArray(listOf("family", "kind", "parent_ref", "object_ref", "context").map { c.optString(it) })))
        }.map(InnerStructure::hash)
        fun envelope(kind: String, id: String, value: JSONObject, parent: String, commit: String) = JSONObject()
            .put("storage_kind", kind).put("id", id).put("parent", parent).put("value", JSONObject(value.toString()))
            .put("version", value.optInt("version", 1)).put("frame_id", commit)
        /** Archive first, clear snapshot after commit. Interrupted repeats verify the same
         * digest; migration never invents a new psychological source or formation date. */
        fun migrate(data: JSONObject, archive: InternalStateArchive): Boolean {
            val root = data.optJSONObject("inner_structure") ?: return false
            require(root.getInt("schema") == InnerStructure.SCHEMA)
            if (root.optString("storage") == "archive_v1") {
                if (root.optInt("family_index") == 1) return false
                // Upgrade the secondary directory page by page. Only IDs are indexed; the
                // authority stays in inner_record. A crash repeats identical journals safely.
                var offset = 0
                while (true) {
                    val page = archive.categoryPage("inner_record", offset, 20, null)
                    if (page.isEmpty()) break
                    val rows = JSONArray(page.map { r -> envelope("inner_family", r.getString("id"),
                        JSONObject().put("target", r.getString("id")), r.getJSONObject("value").getJSONObject("content").getString("family"), "family-index-v1") })
                    archive.applyCategoryJournal(JSONObject().put("schema", 1).put("id", "family-index-v1:$offset:" + InnerStructure.hash(rows)).put("rows", rows))
                    offset += page.size
                }
                root.put("family_index", 1)
                return true
            }
            val commit = "inner-storage:" + InnerStructure.hash(root)
            val rows = JSONArray()
            listOf("records", "pending").forEach { name ->
                val values = root.getJSONObject(name)
                values.keys().forEach { id ->
                    val value = values.getJSONObject(id)
                    rows.put(envelope(if (name == "pending") "inner_pending" else "inner_record", id, value, parentOf(value, name == "pending"), commit))
                    if (name == "records") {
                        rows.put(envelope("inner_family", id, JSONObject().put("target", id), value.getJSONObject("content").getString("family"), commit))
                        indexes(value.getJSONObject("content")).forEach { key ->
                            rows.put(envelope("inner_index", key, JSONObject().put("target", id), "index", commit))
                        }
                    }
                }
            }
            archive.applyCategoryJournal(JSONObject().put("schema", 1).put("id", commit).put("rows", rows))
            root.remove("records"); root.remove("pending")
            root.put("storage", "archive_v1").put("storage_migration", commit).put("family_index", 1)
            return true
        }
    }
}
