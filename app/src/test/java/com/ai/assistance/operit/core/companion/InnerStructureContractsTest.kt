package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure contract tests complement the real host/storage integration tests. */
class InnerStructureContractsTest {
    private fun data() = JSONObject().put("sequence", 1).put("semantic_revision", 0)
        .put("events", JSONArray().put(JSONObject().put("id", "fact").put("sequence", 1).put("at", 10)
            .put("source", "assistant_reply").put("detail", "我此前明确选择坦诚").put("chat", "old")))
    private fun frame(data: JSONObject, id: String, time: Long) = InternalStateFrame(
        id, "owner", "chat", 0, time, setOf("fact"), emptyMap(), 1,
        innerVersions = InnerStructure.versions(data), innerPending = InnerStructure.pendingBindings(data),
        innerSources = mapOf("config:role_prompt" to "初始参考"), innerObjects = setOf("other", "user"))
    private fun content(id: String, family: String = "value") = JSONObject().put("id", id).put("family", family)
        .put("kind", "独立类别$id").put("description", "采用的坦诚").put("status", "active")
    private fun propose(id: String, record: JSONObject, expected: Int = 0) = JSONObject().put("op", "propose")
        .put("id", id).put("record", record).put("expected_version", expected).put("origin", "subject_development")
        .put("evidence", JSONArray().put("fact")).put("reason", "这是真实过去的来源")
    private fun adopt(data: JSONObject, id: String): JSONObject {
        val p = InnerStructure.pending(data).single { it.getString("id") == id }
        return JSONObject().put("op", "adopt").put("id", id).put("reason", "明确采用")
            .put("development", JSONObject().put("account", "这次只采用该条具体认识")
                .put("before", p.get("before")).put("after", p.getJSONObject("record"))
                .put("anchors", JSONArray().put(JSONObject().put("ref", "fact").put("quote", "此前明确选择坦诚")
                    .put("interpretation", "过去的明确选择支持此变化"))))
    }

    @Test fun priorDevelopmentUsesExactScopeAndRetirementDoesNotReinstateTemplate() {
        val d = data()
        InnerStructure.apply(d, frame(d, "first", 20), JSONArray().put(propose("p", content("v"))), 21)
        InnerStructure.apply(d, frame(d, "second", 30), JSONArray().put(adopt(d, "p")), 31)
        assertEquals("subject_development", InnerStructure.records(d).single().getString("origin"))
        val revised = content("v").put("description", "重新考虑这一条").put("status", "retired")
        InnerStructure.apply(d, frame(d, "third", 40), JSONArray().put(propose("p2", revised, 1)), 41)
        InnerStructure.apply(d, frame(d, "fourth", 50), JSONArray().put(adopt(d, "p2")), 51)
        assertTrue(InnerStructure.guard(d).contains("持续身份已建立"))
        assertTrue(InnerStructure.identity(d).isEmpty())
        assertEquals(2, InnerStructure.records(d).single().getInt("version"))
        assertEquals(4, d.getJSONArray("inner_history").length())
    }

    @Test fun forgedOrChangedEvidenceCannotAdoptEvenWithMatchingVersion() {
        val d = data()
        InnerStructure.apply(d, frame(d, "first", 20), JSONArray().put(propose("p", content("v"))), 21)
        val a = adopt(d, "p")
        d.getJSONArray("events").getJSONObject(0).put("detail", "更改了来源")
        assertThrows(IllegalArgumentException::class.java) {
            InnerStructure.apply(d, frame(d, "second", 30), JSONArray().put(a), 31)
        }
        assertTrue(InnerStructure.records(d).isEmpty())
        assertEquals(1, InnerStructure.pending(d).size)
    }

    @Test fun noSilentReclassificationOrDanglingDesireIsAccepted() {
        val d = data()
        InnerStructure.apply(d, frame(d, "first", 20), JSONArray().put(propose("p", content("v"))), 21)
        InnerStructure.apply(d, frame(d, "second", 30), JSONArray().put(adopt(d, "p")), 31)
        assertThrows(IllegalArgumentException::class.java) {
            InnerStructure.apply(d, frame(d, "third", 40), JSONArray().put(propose("bad", content("v", "emotion")
                .put("unresolved", false).put("current_state", "现在平静"), 1)), 41)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InnerStructure.apply(d, frame(d, "fourth", 50), JSONArray().put(propose("bad2", content("d", "desire")
                .put("parent_ref", "missing").put("object_ref", "具体对象"))), 51)
        }
        assertEquals("value", InnerStructure.records(d).single().getJSONObject("content").getString("family"))
    }

    @Test fun materialBudgetPreservesExactIdentityAndRotatesLosslessOtherRecords() {
        val d = data()
        val records = InnerStructure.root(d).getJSONObject("records")
        // Stress fixture for bounded selection, not a claim of normal adoption coverage.
        repeat(128) { index ->
            val c = content("r$index", if (index < 4) "personality" else "feeling")
                .put("description", "完整内容".repeat(110))
            if (index >= 4) c.put("current_state", "待理解").put("unresolved", true)
            records.put("r$index", JSONObject().put("id", "r$index").put("content", c).put("version", 1)
                .put("origin", "subject_development").put("source", "subject_adoption").put("evidence", JSONObject()))
        }
        val first = InnerStructure.material(d, "", 0)
        val later = InnerStructure.material(d, "", 16)
        fun fragments(material: JSONObject) = material.getJSONArray("record_fragments").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
        }
        fun selectedCount(material: JSONObject, identity: Boolean): Int =
            material.getJSONArray(if (identity) "identity" else "selected").length() + fragments(material).count {
                (records.getJSONObject(it.getString("record_id")).getJSONObject("content").getString("family") == "personality") == identity
            }
        assertEquals(8, selectedCount(first, false))
        assertEquals(8, selectedCount(later, false))
        assertEquals(4, selectedCount(first, true))
        assertEquals(128, first.getInt("record_count"))
        assertNotEquals(first.getJSONArray("catalogue").toString(), later.getJSONArray("catalogue").toString())
        // A bounded page must provide original fragments, never silently replace a long
        // adopted record with a summary. Reassembling all continuations recovers every
        // character, including provenance and version, without changing persisted content.
        for (initial in fragments(first) + fragments(later)) {
            val saved = records.getJSONObject(initial.getString("record_id"))
            val original = InnerStructure.serialized(saved)
            val assembled = StringBuilder()
            var part = initial
            while (true) {
                assertEquals(InnerStructure.hash(saved), part.getString("hash"))
                assertEquals(saved.getInt("version"), part.getInt("version"))
                assertEquals(assembled.length, part.getInt("offset"))
                assertEquals(original.length, part.getInt("total"))
                assertTrue(part.getString("text").length <= InnerReadProgress.CHUNK_CHARS)
                assembled.append(part.getString("text"))
                if (part.isNull("next_offset")) break
                part = InnerReadProgress.recordFragment(d, saved, part.getInt("next_offset"))
            }
            assertEquals(original, assembled.toString())
            assertEquals(original, InnerStructure.serialized(records.getJSONObject(saved.getString("id"))))
        }
        for (i in 0 until first.getJSONArray("selected").length()) {
            val row = first.getJSONArray("selected").getJSONObject(i)
            val saved = records.getJSONObject(row.getString("id"))
            assertEquals(saved.getJSONObject("content").toString(), row.getJSONObject("content").toString())
            assertEquals(saved.getInt("version"), row.getInt("version"))
            assertEquals(saved.getString("origin"), row.getString("origin"))
            assertEquals(0, row.getJSONArray("evidence_ids").length())
        }
    }

    @Test fun migrationAddsOnlySchemaAndPreservesMissingProvenance() {
        val original = JSONObject().put("definitions", JSONArray().put(JSONObject().put("id", "legacy").put("kind", "psychology")))
            .put("instances", JSONArray().put(JSONObject().put("dimension", "legacy").put("value", 83).put("scope", "window-a")))
            .put("legacy_extra", "尚未完成")
        val before = JSONObject(original.toString())
        InnerStructure.root(original)
        assertEquals(before.getJSONArray("definitions").toString(), original.getJSONArray("definitions").toString())
        assertEquals(before.getJSONArray("instances").toString(), original.getJSONArray("instances").toString())
        assertEquals("尚未完成", original.getString("legacy_extra"))
        assertFalse(original.getJSONArray("instances").getJSONObject(0).has("source"))
        val adopted = InnerStructure.hash(original)
        InnerStructure.root(original)
        assertEquals(adopted, InnerStructure.hash(original))
        assertTrue(InnerStructure.records(original).isEmpty())
        assertTrue(InnerStructure.pending(original).isEmpty())
        val future = JSONObject(original.toString())
        future.getJSONObject("inner_structure").put("schema", 2)
        val savedFuture = future.toString()
        assertThrows(IllegalArgumentException::class.java) { InnerStructure.root(future) }
        assertThrows(IllegalArgumentException::class.java) { InnerStructure.records(future) }
        assertEquals(savedFuture, future.toString())
        val malformed = JSONObject(original.toString()).put("inner_structure", "unrecognized original data")
        val savedMalformed = malformed.toString()
        assertThrows(org.json.JSONException::class.java) { InnerStructure.root(malformed) }
        assertEquals(savedMalformed, malformed.toString())
    }
}
