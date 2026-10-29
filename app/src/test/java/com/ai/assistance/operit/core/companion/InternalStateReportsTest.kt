package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InternalStateReportsTest {
    private val hour = 3600000L
    private fun definition(id: String = "existing", kind: String? = null, scope: String = "global") = JSONObject()
        .put("id", id).put("name", id).put("description", "由实际体验形成的测试维度")
        .put("baseline", 50).put("min", 0).put("max", 100).put("maxDelta", 20).put("halfLifeHours", 2)
        .put("scope", scope).put("private", false).put("share", false).put("participates", true)
        .put("triggers", JSONArray().put(JSONObject().put("source", "reading").put("delta", 20)))
        .put("effects", JSONArray()).also { if (kind != null) it.put("kind", kind) }

    private fun data(vararg definitions: JSONObject): JSONObject = JSONObject().also { data ->
        InternalStateEngine.event(data, "input", "user_message", "chat", "本轮原文", 0, applyTriggers = false)
        definitions.forEach { definition ->
            InternalStateEngine.evolve(data, "create", definition.getString("id"), 0, definition,
                null, "已有实际经历", listOf("input"), "chat", 0)
        }
    }

    private fun frame(data: JSONObject, id: String = "frame", at: Long = 0, chat: String = "chat") = InternalStateFrame(
        id, "role", chat, InternalStateEngine.semanticRevision(data), at, setOf("input"),
        InternalStateEngine.definitions(data).associate { it.getString("id") to it.getInt("version") },
        data.getLong("sequence")
    )

    private fun update(id: String = "existing", value: Double = 80.0) = JSONObject()
        .put("id", id).put("value", value).put("reason", "根据本轮原文重新理解当前感受")

    private fun report(frame: InternalStateFrame, updates: List<JSONObject> = emptyList(), creates: List<JSONObject> = emptyList()) = JSONObject()
        .put("frame_id", frame.id).put("updates", JSONArray(updates)).put("create", JSONArray(creates))

    private fun create(definition: JSONObject, value: Double = 88.0) = JSONObject()
        .put("definition", definition).put("value", value).put("reason", "首次体验需要独立状态")

    private fun state(data: JSONObject, id: String = "existing", chat: String = "chat") = InternalStateEngine.snapshot(data, chat, 0)
        .single { it.getString("id") == id }

    @Test fun legacyClassificationDoesNotMigrateNumbersTimesOrTimeRules() {
        val data = data(definition())
        InternalStateEngine.event(data, "read", "reading", "chat", "真实阅读", hour)
        val before = data.toString()
        val snapshot = state(data)
        assertEquals("unclassified", snapshot.getString("kind"))
        assertEquals(70.0, snapshot.getDouble("value"), 0.0)
        assertEquals(before, data.toString())
        val oldDefinition = InternalStateEngine.definitions(data).single()
        assertFalse(oldDefinition.has("kind"))
        assertEquals(2.0, oldDefinition.getDouble("halfLifeHours"), 0.0)
        assertEquals(50.0, oldDefinition.getDouble("baseline"), 0.0)
    }

    @Test fun eventCanRecordNegatedTextWithoutKeywordEffectsAndObjectiveRulesStillWork() {
        val definition = definition().put("triggers", JSONArray()
            .put(JSONObject().put("source", "user_message").put("contains", "生气").put("delta", 20))
            .put(JSONObject().put("source", "reading").put("delta", 3)))
        val data = data(definition)
        val revision = InternalStateEngine.semanticRevision(data)
        InternalStateEngine.event(data, "negated", "user_message", "chat", "我并没有生气", 0, applyTriggers = false)
        assertEquals(50.0, state(data).getDouble("value"), 0.0)
        assertEquals(0, InternalStateEngine.rows(data, "events").last().getJSONArray("changes").length())
        InternalStateEngine.event(data, "reading", "reading", "chat", "真实完成阅读", 0)
        assertEquals(53.0, state(data).getDouble("value"), 0.0)
        InternalStateEngine.advance(data, "chat", hour)
        assertEquals(revision, InternalStateEngine.semanticRevision(data))
    }

    @Test fun definitionAndFirstExperienceCommitTogetherWithoutReplayingInput() {
        val data = data(definition())
        InternalStateEngine.event(data, "read", "reading", "chat", "已有经历", 0)
        val oldInstance = InternalStateEngine.rows(data, "instances").single().toString()
        val frame = frame(data)
        val definition = definition("new_state", "emotion").put("object_ref", "person:a")
        definition.getJSONArray("triggers").put(JSONObject().put("source", "user_message").put("delta", 20))
        val firstExperience = create(definition).put("object_ref", "person:a").put("unresolved", true)
            .put("desire", "希望弄清第一次不安的原因").put("choice", "先观察，并未采取行动")
        val receipt = InternalStateReports.apply(data, frame, report(frame, creates = listOf(firstExperience)), hour)
        assertEquals(88.0, state(data, "new_state").getDouble("value"), 0.0)
        assertEquals(50.0, state(data, "new_state").getDouble("baseline"), 0.0)
        assertEquals("emotion", state(data, "new_state").getString("kind"))
        assertEquals("person:a", state(data, "new_state").getString("object_ref"))
        assertTrue(state(data, "new_state").getBoolean("unresolved"))
        assertEquals("希望弄清第一次不安的原因", state(data, "new_state").getString("desire"))
        assertEquals("先观察，并未采取行动", state(data, "new_state").getString("choice"))
        assertEquals(oldInstance, InternalStateEngine.rows(data, "instances").single { it.getString("dimension") == "existing" }.toString())
        assertEquals(1, InternalStateEngine.rows(data, "events").count { it.getString("id") == "input" })
        assertEquals("state_report", InternalStateEngine.rows(data, "events").last().getString("source"))
        assertEquals("new_state", receipt.getJSONArray("created").getString(0))
        val experience = InternalStateEngine.rows(data, "experiences").single()
        assertEquals("input", experience.getJSONArray("evidence").getString(0))
        assertEquals(frame.id, experience.getString("frame_id"))
        assertEquals(50.0, experience.getDouble("before"), 0.0)
        assertEquals(88.0, experience.getDouble("value"), 0.0)
        assertTrue(experience.getBoolean("unresolved"))
        assertEquals("person:a", experience.getString("object_ref"))
        assertEquals("希望弄清第一次不安的原因", experience.getString("desire"))
        assertEquals("先观察，并未采取行动", experience.getString("choice"))
    }

    @Test fun initialExperienceCanBindItsObjectWhenDefinitionIsNotObjectSpecific() {
        val data = data()
        val firstFrame = frame(data)
        val firstExperience = create(definition("new_state", "emotion")).put("object_ref", "person:a").put("unresolved", true)
        InternalStateReports.apply(data, firstFrame, report(firstFrame, creates = listOf(firstExperience)), 0)
        assertEquals("person:a", state(data, "new_state").getString("object_ref"))
        val next = frame(data, "next")
        val before = data.toString()
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, next, report(next, listOf(update("new_state").put("object_ref", "person:b"))), 0)
        }
        assertEquals(before, data.toString())
    }

    @Test fun invalidInitialExperienceMetadataRejectsWholeBatch() {
        val data = data(definition())
        val frame = frame(data)
        val before = data.toString()
        val bound = definition("new_state", "emotion").put("object_ref", "person:a")
        val invalid = listOf(
            create(bound).put("object_ref", "person:b"),
            create(bound).put("unresolved", "true"),
            create(bound).put("desire", JSONObject().put("unexpected", true)),
            create(bound).put("choice", 1)
        )
        invalid.forEach { firstExperience ->
            assertThrows(IllegalArgumentException::class.java) {
                InternalStateReports.apply(data, frame, report(frame, listOf(update()), listOf(firstExperience)), 0)
            }
            assertEquals(before, data.toString())
        }
        assertFalse(data.has("experiences"))
        assertFalse(data.has("semantic_reports"))
    }

    @Test fun sparseUpdateCanAddressUnselectedDimensionAndIsAbsoluteNotAnotherDelta() {
        val data = data(definition("selected"), definition("outside_attention"))
        InternalStateEngine.event(data, "read", "reading", "chat", "客观事件已计算", 0)
        val selected = InternalStateEngine.rows(data, "instances").single { it.getString("dimension") == "selected" }.toString()
        val frame = frame(data)
        InternalStateReports.apply(data, frame, report(frame, listOf(update("outside_attention", 93.0))), hour)
        assertEquals(93.0, state(data, "outside_attention").getDouble("value"), 0.0)
        assertEquals(selected, InternalStateEngine.rows(data, "instances").single { it.getString("dimension") == "selected" }.toString())
    }

    @Test fun omittedSubjectiveFieldsRemainAndLowerIntensityDoesNotResolveExperience() {
        val data = data(definition())
        val first = frame(data)
        InternalStateReports.apply(data, first, report(first, listOf(update().put("unresolved", true)
            .put("object_ref", "person:a").put("desire", "希望理解原因").put("choice", "先等待"))), 0)
        val second = frame(data, "second")
        InternalStateReports.apply(data, second, report(second, listOf(update(value = 52.0))), 0)
        val state = state(data)
        assertTrue(state.getBoolean("unresolved"))
        assertEquals("希望理解原因", state.getString("desire"))
        assertEquals("先等待", state.getString("choice"))
        assertEquals("person:a", state.getString("object_ref"))
        assertEquals(52.0, state.getDouble("value"), 0.0)
    }

    @Test fun knownObjectCannotBeSilentlyRetargeted() {
        val data = data(definition().put("object_ref", "person:a"))
        val frame = frame(data)
        val before = data.toString()
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame, report(frame, listOf(update().put("object_ref", "person:b"))), 0)
        }
        assertEquals(before, data.toString())
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateEngine.evolve(data, "revise", "existing", 1, definition().put("object_ref", "person:b"),
                null, "不能用另一对象覆盖历史", listOf("input"), "chat", 0)
        }
        assertEquals(before, data.toString())
    }

    @Test fun rejectedBatchDoesNotPartiallyUpdateOrCreate() {
        val data = data(definition())
        val frame = frame(data)
        val before = data.toString()
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame, report(frame, listOf(update()),
                listOf(create(definition("new_state", "emotion"), 101.0))), 0)
        }
        assertEquals(before, data.toString())
        assertFalse(data.has("experiences"))
        assertFalse(data.has("semantic_reports"))
    }

    @Test fun creationRequiresClassificationAndExplicitExistingTimeParameters() {
        val data = data()
        val frame = frame(data)
        val before = data.toString()
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame, report(frame, creates = listOf(create(definition("new_state")))), 0)
        }
        val missingTime = definition("new_state", "drive").also { it.remove("halfLifeHours") }
        assertThrows(JSONException::class.java) {
            InternalStateReports.apply(data, frame, report(frame, creates = listOf(create(missingTime))), 0)
        }
        assertEquals(before, data.toString())
    }

    @Test fun duplicateIdentifiersOrWrongValueTypeCannotMutateData() {
        val data = data(definition())
        val frame = frame(data)
        val before = data.toString()
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame, report(frame, listOf(update(), update(value = 60.0))), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame, report(frame, listOf(update().put("value", "NaN"))), 0)
        }
        assertEquals(before, data.toString())
    }

    @Test fun evidenceMustBeRealAndBoundToThisFrame() {
        val data = data(definition())
        InternalStateEngine.event(data, "not_in_frame", "user_message", "chat", "另一条真实来源", 0, applyTriggers = false)
        val frame = frame(data)
        val before = data.toString()
        for (source in listOf("not_in_frame", "invented")) {
            assertThrows(IllegalArgumentException::class.java) {
                InternalStateReports.apply(data, frame, report(frame, listOf(update().put("evidence", JSONArray().put(source)))), 0)
            }
        }
        val falseFrame = frame.copy(eventIds = setOf("invented"))
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, falseFrame, report(falseFrame, listOf(update())), 0)
        }
        assertEquals(before, data.toString())
    }

    @Test fun staleDefinitionOrConcurrentFrameCannotOverwriteNewerReport() {
        val data = data(definition())
        val first = frame(data)
        val second = first.copy(id = "concurrent")
        val wrongVersion = first.copy(definitionVersions = mapOf("existing" to 999))
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, wrongVersion, report(wrongVersion, listOf(update())), 0)
        }
        InternalStateReports.apply(data, first, report(first, listOf(update())), 0)
        val before = data.toString()
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, second, report(second, listOf(update(value = 10.0))), 0)
        }
        assertEquals(before, data.toString())
        assertEquals(80.0, state(data).getDouble("value"), 0.0)
    }

    @Test fun sameFrameRetryIsIdempotentAndCannotChangeContentOrIdentity() {
        val data = data(definition())
        val frame = frame(data)
        val payload = report(frame, listOf(update()))
        val receipt = InternalStateReports.apply(data, frame, payload, 0)
        val before = data.toString()
        val retry = InternalStateReports.apply(data, frame, JSONObject(payload.toString()), hour)
        assertEquals(receipt.toString(), retry.toString())
        assertEquals(before, data.toString())
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame, report(frame, listOf(update(value = 81.0))), hour)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame.copy(roleId = "another_role"), payload, hour)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame.copy(eventIds = setOf("another_source")), payload, hour)
        }
        assertEquals(before, data.toString())
    }

    @Test fun emptyReportConsumesFrameWithoutInventingStatesOrTimeDefaults() {
        val data = data()
        val frame = frame(data)
        val receipt = InternalStateReports.apply(data, frame, report(frame), hour)
        assertEquals(frame.revision, receipt.getLong("revision"))
        assertTrue(InternalStateEngine.definitions(data).isEmpty())
        assertTrue(InternalStateEngine.rows(data, "instances").isEmpty())
        assertEquals(1, InternalStateEngine.rows(data, "events").size)
        val before = data.toString()
        InternalStateReports.apply(data, frame, report(frame), hour * 2)
        assertEquals(before, data.toString())
    }

    @Test fun anEmptyAcknowledgementDoesNotInvalidateAnotherInFlightReport() {
        val data = data(definition())
        val quietFrame = frame(data, id = "quiet")
        val activeFrame = frame(data, id = "active")
        InternalStateReports.apply(data, quietFrame, report(quietFrame), 0)
        assertEquals(activeFrame.revision, InternalStateEngine.semanticRevision(data))
        InternalStateReports.apply(data, activeFrame, report(activeFrame, listOf(update(value = 65.0))), 0)
        assertEquals(65.0, state(data).getDouble("value"), 0.0)
        val beforeRetry = data.toString()
        InternalStateReports.apply(data, quietFrame, report(quietFrame), 0)
        assertEquals(beforeRetry, data.toString())
    }

    @Test fun anotherChatObjectiveChangeCannotBeOverwrittenByAnOldGlobalReport() {
        val data = data(definition())
        val frame = frame(data)
        InternalStateEngine.event(data, "concurrent_read", "reading", "another_chat", "另一窗口真实阅读", 1)
        val before = data.toString()
        assertEquals(frame.revision, InternalStateEngine.semanticRevision(data))
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame, report(frame, listOf(update(value = 55.0)),
                listOf(create(definition("new_state", "emotion")))), 2)
        }
        assertEquals(before, data.toString())
        assertEquals(70.0, state(data).getDouble("value"), 0.0)
        assertEquals(1, InternalStateEngine.definitions(data).size)
    }

    @Test fun anotherSessionAndUnreportedDimensionsDoNotConflictWithThisReport() {
        val data = data(definition("local", scope = "session"), definition("unreported"))
        val frame = frame(data)
        InternalStateEngine.event(data, "concurrent_read", "reading", "another_chat", "另一窗口真实阅读", 1)
        val unreported = InternalStateEngine.rows(data, "instances").single { it.getString("dimension") == "unreported" }.toString()
        val anotherSession = InternalStateEngine.rows(data, "instances").single {
            it.getString("dimension") == "local" && it.getString("scope") == "another_chat"
        }.toString()
        InternalStateReports.apply(data, frame, report(frame, listOf(update("local"))), 2)
        assertEquals(80.0, state(data, "local").getDouble("value"), 0.0)
        assertEquals(unreported, InternalStateEngine.rows(data, "instances").single { it.getString("dimension") == "unreported" }.toString())
        assertEquals(anotherSession, InternalStateEngine.rows(data, "instances").single {
            it.getString("dimension") == "local" && it.getString("scope") == "another_chat"
        }.toString())
    }

    @Test fun timeProjectionAndFactsWithoutValueChangesDoNotInvalidateFrame() {
        val data = data(definition())
        InternalStateEngine.event(data, "read_before_frame", "reading", "chat", "已观察的阅读", 0)
        val frame = frame(data)
        InternalStateEngine.advance(data, "chat", hour * 2)
        InternalStateEngine.event(data, "later_message", "user_message", "chat", "新事实尚无数值解释", hour * 2, applyTriggers = false)
        assertEquals(60.0, state(data).getDouble("value"), 1e-9)
        InternalStateReports.apply(data, frame, report(frame, listOf(update(value = 75.0))), hour * 2)
        assertEquals(75.0, state(data).getDouble("value"), 0.0)
    }

    @Test fun completedFrameRetryDoesNotEraseAnObjectiveChangeAfterItsCommit() {
        val data = data(definition())
        val frame = frame(data)
        val payload = report(frame, listOf(update()))
        val receipt = InternalStateReports.apply(data, frame, payload, 0)
        InternalStateEngine.event(data, "later_read", "reading", "chat", "提交后的真实阅读", 0)
        assertEquals(100.0, state(data).getDouble("value"), 0.0)
        val before = data.toString()
        assertEquals(receipt.toString(), InternalStateReports.apply(data, frame, payload, hour).toString())
        assertEquals(before, data.toString())
    }

    @Test fun invalidOrReplacedEventBoundaryIsRejected() {
        val data = data(definition())
        val frame = frame(data)
        val before = data.toString()
        for (sequence in listOf(-1L, 0L, data.getLong("sequence") + 1)) {
            val invalid = frame.copy(eventSequence = sequence)
            assertThrows(IllegalArgumentException::class.java) {
                InternalStateReports.apply(data, invalid, report(invalid, listOf(update())), 0)
            }
            assertEquals(before, data.toString())
        }
        val payload = report(frame, listOf(update()))
        InternalStateReports.apply(data, frame, payload, 0)
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame.copy(eventSequence = data.getLong("sequence")), payload, 0)
        }
    }

    @Test fun definitionEvolutionInvalidatesOldFrameButTimeAloneDoesNot() {
        val data = data(definition())
        val frame = frame(data)
        InternalStateEngine.advance(data, "chat", hour)
        assertEquals(frame.revision, InternalStateEngine.semanticRevision(data))
        InternalStateEngine.evolve(data, "revise", "existing", 1, definition().put("halfLifeHours", 4),
            null, "真实经历支持修订速度", listOf("input"), "chat", hour)
        assertEquals(frame.revision + 1, InternalStateEngine.semanticRevision(data))
        val before = data.toString()
        assertThrows(IllegalArgumentException::class.java) {
            InternalStateReports.apply(data, frame, report(frame, listOf(update())), hour)
        }
        assertEquals(before, data.toString())
    }

    @Test fun sessionReportDoesNotChangeAnotherWindow() {
        val data = data(definition(scope = "session"))
        InternalStateEngine.snapshot(data, "another_chat", 0)
        val other = InternalStateEngine.rows(data, "instances").single { it.getString("scope") == "another_chat" }.toString()
        val frame = frame(data)
        InternalStateReports.apply(data, frame, report(frame, listOf(update())), 0)
        assertEquals(80.0, state(data).getDouble("value"), 0.0)
        assertEquals(other, InternalStateEngine.rows(data, "instances").single { it.getString("scope") == "another_chat" }.toString())
    }

    @Test fun publishingDefinitionDoesNotPublishOldPrivateSubjectiveFieldsOrHistory() {
        val privateDefinition = definition(kind = "emotion").put("private", true)
        val data = data()
        val first = frame(data)
        InternalStateReports.apply(data, first, report(first, creates = listOf(create(privateDefinition).put("desire", "仍然保密的愿望")
            .put("choice", "仍然保密的选择").put("unresolved", true))), 0)
        assertTrue(InternalStateEngine.rows(InternalStateEngine.observation(data, "chat", 0, true), "experiences").isEmpty())

        InternalStateEngine.evolve(data, "revise", "existing", 1, definition(kind = "emotion").put("private", false),
            null, "从现在开始公开", listOf("input"), "chat", 0)
        val firstView = InternalStateEngine.observation(data, "chat", 0, false)
        assertFalse(firstView.getJSONArray("states").getJSONObject(0).has("reason"))
        assertTrue(InternalStateEngine.rows(firstView, "experiences").isEmpty())

        val second = frame(data, "public_report")
        InternalStateReports.apply(data, second, report(second, listOf(update(value = 60.0).put("reason", "现在公开的感受"))), 0)
        assertEquals("仍然保密的愿望", state(data).getString("desire"))
        val view = InternalStateEngine.observation(data, "chat", 0, false)
        val visibleState = view.getJSONArray("states").getJSONObject(0)
        assertEquals("现在公开的感受", visibleState.getString("reason"))
        assertFalse(visibleState.has("desire"))
        assertFalse(visibleState.has("choice"))
        assertFalse(visibleState.has("unresolved"))
        val visibleExperience = InternalStateEngine.rows(view, "experiences").single()
        assertEquals("现在公开的感受", visibleExperience.getString("reason"))
        assertFalse(visibleExperience.has("desire"))
        assertFalse(visibleExperience.has("choice"))
        assertFalse(view.toString().contains("仍然保密"))
    }
}
