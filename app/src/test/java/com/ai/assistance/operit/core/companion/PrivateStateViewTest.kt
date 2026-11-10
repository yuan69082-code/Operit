package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrivateStateViewTest {
    @Test fun activityReceiptsKeepTheSamePrivateAudience() {
        val receipt = JSONObject().put("storage_kind", "activity_part").put("kind", "operation").put("content",
            JSONObject().put("tool", "companion_internal_state").put("status", "completed").put("receipt", "旧梦原文"))
        assertFalse(PrivateStateView.project(receipt).toString().contains("旧梦原文"))
        receipt.getJSONObject("content").put("private_section", "inner").put("receipt", "完整可见的内在记录")
        assertTrue(PrivateStateView.project(receipt).toString().contains("完整可见的内在记录"))
        receipt.getJSONObject("content").put("private_section", "dream")
        assertFalse(PrivateStateView.project(receipt).toString().contains("完整可见的内在记录"))
        receipt.getJSONObject("content").put("tool", "some_tool").put("receipt",
            JSONObject().put("type", "dream_not_reality").put("text", "旧私密梦全文").toString())
        assertFalse(PrivateStateView.project(receipt).toString().contains("旧私密梦全文"))
        assertTrue(receipt.toString().contains("旧私密梦全文"))
    }
    @Test fun evidenceAndToolDetailDoNotReopenPrivateOriginals() {
        val source = JSONObject().put("type", "dream_not_reality").put("id", "dream-source").put("text", "旧私密梦全文")
        val adopted = JSONObject().put("id", "p").put("content", JSONObject().put("description", "后来采用的认识，条件和例外仍保留"))
            .put("bindings", JSONObject().put("dream-source", source))
        val action = JSONObject().put("request", JSONObject().put("tool", "companion_internal_state").put("parameters",
            JSONArray().put(JSONObject().put("name", "section").put("value", "dream"))))
            .put("receipt", JSONObject().put("status", "completed").put("text", "旧私密梦全文").put("data", "旧私密梦全文"))
        val view = PrivateStateView.project(JSONObject().put("items", JSONArray().put(adopted).put(action)))
        assertFalse(view.toString().contains("旧私密梦全文"))
        assertTrue(view.toString().contains("后来采用的认识，条件和例外仍保留"))
        assertTrue(view.toString().contains("completed"))
        assertTrue(adopted.toString().contains("旧私密梦全文")) // persistent model evidence unchanged
        val oldHead = JSONObject(action.toString())
        oldHead.getJSONObject("request").remove("parameters")
        assertFalse(PrivateStateView.project(oldHead).toString().contains("旧私密梦全文"))
        val publicRead = JSONObject(action.toString())
        publicRead.getJSONObject("request").getJSONArray("parameters").getJSONObject(0).put("value", "inner")
        publicRead.getJSONObject("receipt").put("text", "可公开的实际结果").put("data", "可公开的实际结果")
        assertTrue(PrivateStateView.project(publicRead).toString().contains("可公开的实际结果"))
    }
    @Test fun fullTextAndUserVisibleWakingNotesAreNotSummarized() {
        val exact = "  { \"x\": \"例外\" }\n"
        val row = JSONObject().put("content", JSONObject().put("description", exact))
            .put("inner_source", exact).put("text", "留下的表达<companion_note>原私密随记</companion_note>正文")
        val view = PrivateStateView.project(row)
        assertEquals(exact, view.getJSONObject("content").getString("description"))
        assertEquals(exact, view.getString("inner_source"))
        assertFalse(view.getString("text").contains("原私密随记</"))
        val waking = JSONObject().put("storage_kind", "waking_note").put("content", JSONObject().put("text", "只剩一点感觉，不编完整梦"))
        assertEquals(waking.toString(), PrivateStateView.project(waking).toString())
    }
}
