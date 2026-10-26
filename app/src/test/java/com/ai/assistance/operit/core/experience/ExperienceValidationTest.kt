package com.ai.assistance.operit.core.experience

import com.ai.assistance.operit.data.preferences.ThemePreferenceValues
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExperienceValidationTest {
    @Test fun partialThemePatchPreservesUnrelatedSettings() {
        val original = ThemePreferenceValues.defaultVisual().withBoolean("show_message_timestamp", true)
        val changed = ThemeStudio.patch(original, JSONObject().put("ints", JSONObject().put("custom_primary_color", -1234)))
        assertEquals(true, changed.boolean("show_message_timestamp"))
        assertEquals(-1234, changed.int("custom_primary_color"))
        assertEquals(original.strings, changed.strings)
    }
    @Test fun themeRoundTripPreservesSignedColorsAndNativeFields() {
        val original = ThemePreferenceValues.defaultVisual().withInt("bubble_user_text_color", -16777216)
        assertEquals(original, ThemeStudio.decode(ThemeStudio.encode(original)))
    }
    @Test fun unknownThemeFieldsCannotSilentlyPretendToRender() {
        assertThrows(IllegalArgumentException::class.java) {
            ThemeStudio.patch(ThemePreferenceValues.defaultVisual(), JSONObject().put("booleans", JSONObject().put("invented_glow", true)))
        }
    }
    @Test fun invalidWorldbookExpressionIsRejectedBeforeSaving() {
        assertThrows(IllegalArgumentException::class.java) {
            RoleRules.validate(JSONObject().put("worldbook", JSONArray().put(JSONObject().put("pattern", "[").put("text", "资料"))))
        }
    }
    @Test fun alwaysOnWorldbookDoesNotRequireARegex() {
        RoleRules.validate(JSONObject().put("worldbook", JSONArray().put(JSONObject().put("always", true).put("text", "资料"))))
    }
    @Test fun proseRulesPreserveToolArgumentsAndCode() {
        val rules = JSONArray().put(JSONObject().put("pattern", "cat").put("replacement", "dog").put("direction", "both"))
        val original = "cat <tool name=\"x\"><param>cat</param></tool> cat "+"```"+"js\ncat\n"+"```"
        val result = TextTransforms.apply(original, rules, "output")
        assertTrue(result.startsWith("dog <tool name=\"x\"><param>cat</param></tool> dog"))
        assertTrue(result.endsWith("```"+"js\ncat\n"+"```"))
    }
    @Test fun inputOnlyRuleDoesNotChangeOutput() {
        val rules = JSONArray().put(JSONObject().put("pattern", "x").put("replacement", "y").put("direction", "input"))
        assertEquals("x", TextTransforms.apply("x", rules, "output"))
    }
    @Test fun projectTraversalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CollectionStore.validatePath("../outside") }
        CollectionStore.validatePath("src/main.js")
    }
    @Test fun kelivoDatesAcceptEpochAndIsoWithoutInventedTimes() {
        assertEquals(1700000000000L, KelivoImport.timestamp(1700000000L))
        assertEquals(1700000000000L, KelivoImport.timestamp("2023-11-14T22:13:20Z"))
        assertThrows(IllegalArgumentException::class.java) { KelivoImport.timestamp(null) }
    }
}
