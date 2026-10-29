package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternalStateDisclosureTest {
    private fun definition(id: String = "state", privateState: Boolean = false, share: Boolean = false) = JSONObject()
        .put("id", id).put("name", id).put("description", "测试中已有的定义")
        .put("baseline", 50).put("min", 0).put("max", 100).put("maxDelta", 20).put("halfLifeHours", 2)
        .put("scope", "global").put("private", privateState).put("share", share).put("participates", true)
        .put("triggers", JSONArray()).put("effects", JSONArray())

    private fun data(vararg definitions: JSONObject) = JSONObject().also { data ->
        InternalStateEngine.event(data, "input", "user_message", "chat", "真实输入", 0, applyTriggers = false)
        definitions.forEach { definition ->
            InternalStateEngine.evolve(data, "create", definition.getString("id"), 0, definition,
                null, "已有真实来源", listOf("input"), "chat", 0)
        }
    }

    private fun apply(data: JSONObject, vararg updates: JSONObject) {
        val revision = InternalStateEngine.semanticRevision(data)
        val frame = InternalStateFrame("frame:$revision", "role", "chat", revision, 0, setOf("input"),
            InternalStateEngine.definitions(data).associate { it.getString("id") to it.getInt("version") }, data.getLong("sequence"))
        InternalStateReports.apply(data, frame, JSONObject().put("frame_id", frame.id)
            .put("updates", JSONArray(updates.toList())).put("create", JSONArray()), 0)
    }

    private fun update(id: String = "state", reason: String = "现在的感受") = JSONObject()
        .put("id", id).put("value", 70).put("reason", reason)

    private fun revise(data: JSONObject, definition: JSONObject) {
        val id = definition.getString("id")
        val version = InternalStateEngine.definitions(data).single { it.getString("id") == id }.getInt("version")
        InternalStateEngine.evolve(data, "revise", id, version, definition, null,
            "从现在起修改公开意愿", listOf("input"), "chat", 0)
    }

    @Test fun publicDefinitionDoesNotRelabelInheritedPrivateFieldsForTheModel() {
        val data = data(definition(privateState = true))
        apply(data, update(reason = "之前的私密解释").put("object_ref", "person:a").put("desire", "之前的私密愿望")
            .put("choice", "之前的私密选择").put("unresolved", true))
        revise(data, definition())
        apply(data, update(reason = "这次可以公开的解释"))
        val before = data.toString()

        val snapshot = InternalStateEngine.snapshot(data, "chat", 0).single()
        assertFalse(snapshot.getBoolean("private"))
        assertEquals("之前的私密愿望", snapshot.getString("desire"))
        val fields = snapshot.getJSONObject("disclosure").getJSONObject("fields")
        assertFalse(fields.getJSONObject("reason").getBoolean("private"))
        for (field in listOf("object_ref", "desire", "choice", "unresolved")) {
            assertTrue(fields.getJSONObject(field).getBoolean("private"))
            assertFalse(fields.getJSONObject(field).getBoolean("share"))
            assertTrue(fields.getJSONObject(field).getBoolean("provenance_verified"))
            assertEquals(1, fields.getJSONObject(field).getInt("origin_version"))
        }

        val experiences = InternalStateEngine.rows(data, "experiences")
        assertTrue(InternalStateEngine.experienceDisclosure(data, experiences.first()).getBoolean("private"))
        val latest = InternalStateEngine.experienceDisclosure(data, experiences.last())
        assertFalse(latest.getBoolean("private"))
        assertTrue(latest.getJSONObject("fields").getJSONObject("desire").getBoolean("private"))
        assertFalse(latest.getJSONObject("fields").getJSONObject("reason").getBoolean("private"))
        assertEquals(before, data.toString())

        val uiState = InternalStateEngine.observation(data, "chat", 0, false).getJSONArray("states").getJSONObject(0)
        assertFalse(uiState.has("disclosure"))
        assertFalse(uiState.has("desire"))
        assertEquals("这次可以公开的解释", uiState.getString("reason"))
    }

    @Test fun currentAndHistoricalSharingMustBothAllowThePrivateField() {
        val data = data(definition(privateState = true, share = true))
        apply(data, update().put("desire", "当时愿意在本机分享"))
        revise(data, definition())
        val first = InternalStateEngine.snapshot(data, "chat", 0).single()
            .getJSONObject("disclosure").getJSONObject("fields").getJSONObject("desire")
        assertTrue(first.getBoolean("private"))
        assertTrue(first.getBoolean("share"))

        revise(data, definition(privateState = true, share = false))
        val second = InternalStateEngine.snapshot(data, "chat", 0).single()
            .getJSONObject("disclosure").getJSONObject("fields").getJSONObject("desire")
        assertTrue(second.getBoolean("private"))
        assertFalse(second.getBoolean("share"))
    }

    @Test fun definitionBoundObjectHasVerifiedDisclosureWithoutInventingAnExperience() {
        val data = data(definition().put("object_ref", "person:a"))
        val before = data.toString()
        val field = InternalStateEngine.snapshot(data, "chat", 0).single()
            .getJSONObject("disclosure").getJSONObject("fields").getJSONObject("object_ref")
        assertTrue(field.getBoolean("provenance_verified"))
        assertFalse(field.getBoolean("private"))
        assertEquals(1, field.getInt("origin_version"))
        assertEquals(before, data.toString())
        assertFalse(data.has("experiences"))
    }

    @Test fun unknownFieldProvenanceIsExplicitAndNeverAssumedPublic() {
        val data = data(definition())
        InternalStateEngine.rows(data, "instances").single().put("reason", "没有版本出处的旧字段")
        val before = data.toString()
        val snapshot = InternalStateEngine.snapshot(data, "chat", 0).single()
        val field = snapshot.getJSONObject("disclosure").getJSONObject("fields").getJSONObject("reason")
        assertEquals("没有版本出处的旧字段", snapshot.getString("reason"))
        assertFalse(field.getBoolean("provenance_verified"))
        assertTrue(field.getBoolean("private"))
        assertFalse(field.getBoolean("share"))
        assertTrue(field.isNull("origin_version"))
        assertEquals("unclassified", snapshot.getString("kind"))
        assertEquals(before, data.toString())
    }

    @Test fun historicalEventKeepsPrivateOriginEvenAfterScopeProjectionAndPublication() {
        val data = data(definition("secret", privateState = true), definition("public"))
        apply(data, update("secret"), update("public"))
        val event = JSONObject(InternalStateEngine.rows(data, "events").last().toString())
        val privateChange = InternalStateEngine.rows(event, "changes").single { it.getString("dimension") == "secret" }
        revise(data, definition("secret"))
        val before = data.toString()

        assertTrue(InternalStateEngine.changeDisclosure(data, privateChange).getBoolean("private"))
        val disclosure = InternalStateEngine.eventDisclosure(data, event)
        val projected = JSONObject(event.toString()).put("changes", JSONArray(InternalStateEngine.rows(event, "changes")
            .filter { it.getString("dimension") == "public" })).put("disclosure", disclosure)
        assertTrue(disclosure.getBoolean("private"))
        assertFalse(disclosure.getBoolean("share"))
        assertEquals(2, disclosure.getJSONArray("changes").length())
        assertEquals(1, projected.getJSONArray("changes").length())
        assertTrue(projected.getJSONObject("disclosure").getBoolean("private"))
        assertEquals(before, data.toString())
    }
}
