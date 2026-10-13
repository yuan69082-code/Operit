package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoomPolicyTest {
    @Test fun newAndLegacyAssistantsDoNotGrantDecoration() {
        for (data in listOf(JSONObject(), JSONObject().put("proactive_enabled", true))) {
            assertThrows(IllegalStateException::class.java) { RoomPolicy.apply(data, JSONObject().put("title", "新房间"), true, 1) }
        }
    }
    @Test fun cannotUnlockOrSelfGrantThroughDecoration() {
        val data = JSONObject().put("ai_decorate", true)
        assertThrows(IllegalArgumentException::class.java) { RoomPolicy.apply(data, JSONObject().put("locks", JSONArray()), true, 1) }
        assertThrows(IllegalArgumentException::class.java) { RoomPolicy.apply(data, JSONObject().put("ai_diary", true), true, 1) }
    }
    @Test fun lockRejectsWholePatchBeforeAnyFieldChanges() {
        val data = JSONObject().put("ai_decorate", true).put("title", "原名").put("locks", JSONArray(listOf("theme")))
        assertThrows(IllegalStateException::class.java) { RoomPolicy.apply(data, JSONObject().put("title", "新名").put("theme", JSONObject()), true, 1) }
        assertEquals("原名", data.getString("title"))
    }
    @Test fun userCanChangeLockedFieldAndAiCanChangeUnlockedField() {
        val data = JSONObject().put("ai_decorate", true).put("locks", JSONArray(listOf("title")))
        RoomPolicy.apply(data, JSONObject().put("title", "用户改名"), false, 1)
        RoomPolicy.apply(data, JSONObject().put("subtitle", "读到一个有意思的故事"), true, 2)
        assertEquals("用户改名", data.getString("title"))
        assertEquals("assistant", data.getString("updated_by"))
    }
    @Test fun rejectingBadLayoutDoesNotEraseWidgets() {
        val data = JSONObject()
        assertThrows(IllegalArgumentException::class.java) { RoomPolicy.apply(data, JSONObject().put("widgets", JSONArray()), false, 1) }
        assertEquals(5, RoomPolicy.layout(data).length())
    }
    @Test fun reorderAndHideKeepsEveryComponentExactlyOnce() {
        val widgets = RoomPolicy.layout(JSONObject())
        widgets.getJSONObject(0).put("visible", false).put("size", 3)
        val data = JSONObject()
        RoomPolicy.apply(data, JSONObject().put("widgets", widgets), false, 1)
        assertFalse(RoomPolicy.layout(data).getJSONObject(0).getBoolean("visible"))
    }
    @Test fun aliasesAndOtherAssistantsCannotBeDecorationFields() {
        assertThrows(IllegalArgumentException::class.java) { RoomPolicy.authorize(JSONObject(), JSONObject().put("owner", "another"), false) }
    }
}
