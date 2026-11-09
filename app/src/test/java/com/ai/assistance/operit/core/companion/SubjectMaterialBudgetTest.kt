package com.ai.assistance.operit.core.companion

import android.app.Application
import com.ai.assistance.operit.core.chat.CurrentRequestContext
import com.ai.assistance.operit.core.chat.RuntimeContextSections
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterValueType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubjectMaterialBudgetTest {
    private fun payload() = JSONObject().put("inner_structure", JSONObject()
        .put("identity", JSONArray()).put("selected", JSONArray()).put("pending", JSONArray())
        .put("catalogue", JSONArray()).put("pending_ids", JSONArray()))
        .put("category_events", JSONObject().put("episodes", JSONArray()).put("focus", JSONArray()))
        .put("unions", JSONObject().put("items", JSONArray()))
        .put("retrieved_materials", JSONArray()).put("host_materials", JSONArray())
        .put("states", JSONArray()).put("important_changes", JSONArray()).put("experiences", JSONArray())
        .put("thoughts", JSONArray()).put("candidates", JSONArray()).put("state_catalog", JSONArray())

    @Test fun completeObjectsCompeteUnderOneBudgetWithoutMutatingSource() {
        val source = payload()
        for (i in 0..20) {
            source.getJSONObject("inner_structure").getJSONArray("selected")
                .put(JSONObject().put("id", "inner$i").put("text", "长期偏好".repeat(40)))
            source.getJSONObject("unions").getJSONArray("items")
                .put(JSONObject().put("id", "union$i").put("meaning", "多种理解".repeat(40)))
            source.getJSONArray("retrieved_materials").put(JSONObject().put("id", "memory$i").put("text", "原有材料".repeat(40)))
        }
        val original = source.toString()
        val small = SubjectMaterialBudget.select(source)
        val large = SubjectMaterialBudget.select(source, 16384)
        assertTrue(SubjectMaterialBudget.estimate(small.toString()) + SubjectMaterialBudget.ENVELOPE_RESERVE <= 2048)
        assertTrue(SubjectMaterialBudget.estimate(large.toString()) + SubjectMaterialBudget.ENVELOPE_RESERVE <= 16384)
        assertEquals(original, source.toString())
        assertTrue(small.getJSONObject("material_budget").getJSONObject("omitted_counts").getInt("unions.items") > 0)
        val selected = small.getJSONObject("inner_structure").getJSONArray("selected")
        for (i in 0 until selected.length()) assertEquals("长期偏好".repeat(40), selected.getJSONObject(i).getString("text"))
        assertFalse(small.getJSONObject("material_budget").getBoolean("exact"))
    }

    @Test fun oversizeIdentityIsExplicitlyOmittedAndNeverReplacedOrTruncated() {
        val source = payload()
        source.getJSONObject("inner_structure").getJSONArray("identity").put(
            JSONObject().put("id", "self").put("description", "持续身份".repeat(3000)))
        val selected = SubjectMaterialBudget.select(source)
        assertEquals(1, selected.getJSONObject("material_budget").getJSONObject("omitted_counts").getInt("inner_structure.identity"))
        assertEquals(0, selected.getJSONObject("inner_structure").getJSONArray("identity").length())
        assertEquals(1, source.getJSONObject("inner_structure").getJSONArray("identity").length())
    }

    @Test fun estimatorDistinguishesScriptsAndDoesNotCallLengthTokens() {
        assertTrue(SubjectMaterialBudget.estimate("中文".repeat(100)) > SubjectMaterialBudget.estimate("ab".repeat(100)))
        assertTrue(SubjectMaterialBudget.estimate("🙂".repeat(100)) > 100)
        try { SubjectMaterialBudget.select(payload(), 16385); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun fullWindowCountsInputOnceAndIncludesEnabledOutputReserve() {
        val p = ModelParameter("max", "output", "max_tokens", defaultValue = 500, currentValue = 500,
            isEnabled = true, valueType = ParameterValueType.INT)
        val result = SubjectRequestWindow.check(1800, listOf(p), 2048)
        assertEquals(2300L, result.totalEstimate)
        assertTrue(result.exceeds)
        assertNull(SubjectRequestWindow.check(1800, listOf(p.copy(isEnabled = false)), 2048).outputReserve)
    }

    @Test fun nativeReadProofRejectsForeignTruncatedAndUntrustedResults() {
        val f = InternalStateFrame("f", "role", "chat", 0, 10, setOf("input"), emptyMap(), 1)
        val json = JSONObject().put("item", JSONObject().put("id", "x").put("version", 2)).toString()
        val native = com.ai.assistance.operit.core.tools.InternalStateReadResultData(json, "role", "chat", "inner", true)
        fun members(data: com.ai.assistance.operit.core.tools.ToolResultData, delivered: String = json): Map<String, Int> {
            val original = CurrentRequestContext.stage(listOf(PromptTurn(PromptTurnKind.USER, "取回")),
                RuntimeContextSections(internalStateFrame = f)) + PromptTurn(PromptTurnKind.TOOL_RESULT, delivered, "companion_internal_state")
            return requireNotNull(CurrentRequestContext.internalStateFrame(SubjectToolMaterials.attach(original, 20,
                listOf(com.ai.assistance.operit.data.model.ToolResult("companion_internal_state", true, data))))).interactionMembers
        }
        assertEquals(mapOf("inner:x" to 2), members(native))
        assertTrue(members(native.copy(owner = "other")).isEmpty())
        assertTrue(members(native.copy(chatId = "other-window")).isEmpty())
        assertTrue(members(native.copy(privateContext = false)).isEmpty())
        assertTrue(members(native, json.take(10)).isEmpty())
        assertTrue(members(com.ai.assistance.operit.core.tools.StringResultData(json)).isEmpty())
    }

    @Test fun actualToolContinuationBindsResultOnceAndKeepsFailureText() {
        val f = InternalStateFrame("f", "role", "chat", 0, 10, setOf("input"), emptyMap(), 1)
        val original = CurrentRequestContext.stage(listOf(PromptTurn(PromptTurnKind.USER, "查资料")),
            RuntimeContextSections(internalStateFrame = f)) +
            PromptTurn(PromptTurnKind.TOOL_RESULT, "query_memory failed: offline", toolName = "query_memory")
        val bound = SubjectToolMaterials.attach(original, 20)
        val updated = requireNotNull(CurrentRequestContext.internalStateFrame(bound))
        assertEquals(1, updated.materialSources.size)
        val source = JSONObject(updated.materialSources.values.single())
        assertEquals("query_memory failed: offline", source.getString("text"))
        assertEquals("tool_result", source.getString("type"))
        assertEquals(bound, SubjectToolMaterials.attach(bound, 21))
        assertEquals(1, bound.count { it.content.contains("query_memory failed: offline") })
        assertTrue(updated.eventIds == f.eventIds) // Not a new real-world episode.
        val second = SubjectToolMaterials.attach(bound +
            PromptTurn(PromptTurnKind.TOOL_RESULT, "query_memory failed: offline", toolName = "query_memory"), 22)
        assertEquals(2, requireNotNull(CurrentRequestContext.internalStateFrame(second)).materialSources.size)
        assertEquals(second, SubjectToolMaterials.attach(second, 23))
    }
}
