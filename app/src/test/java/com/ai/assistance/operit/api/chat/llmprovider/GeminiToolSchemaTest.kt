package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.util.StreamingJsonXmlConverter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiToolSchemaTest {
    @Test fun dynamicPackageParamsUseJsonTextWhileScalarTypesStayIntact() {
        val schema = GeminiToolSchema.parameters(listOf(
            ToolParameterSchema("params", "object", "Package parameters"),
            ToolParameterSchema("enabled", "boolean", "Enabled", false)))
        val properties = schema.getJSONObject("properties")
        assertEquals("string", properties.getJSONObject("params").getString("type"))
        assertEquals("boolean", properties.getJSONObject("enabled").getString("type"))
        assertEquals("params", schema.getJSONArray("required").getString(0))
        assertEquals(1, schema.getJSONArray("required").length())
    }

    @Test fun streamedJsonTextPreservesNestedArgumentsAndEscaping() {
        val params = JSONObject().put("text", "你好 \"quoted\"\n<&>")
            .put("options", JSONObject().put("enabled", true).put("count", 2))
        val input = JSONObject().put("tool_name", "demo:say").put("params", params.toString()).toString()
        val converter = StreamingJsonXmlConverter()
        val xml = buildString {
            input.forEach { c -> converter.feed(c.toString()).forEach { event ->
                append(when (event) {
                    is StreamingJsonXmlConverter.Event.Tag -> event.text
                    is StreamingJsonXmlConverter.Event.Content -> event.text
                })
            } }
        }
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.ByteArrayInputStream("<tool>$xml</tool>".toByteArray()))
        val nodes = document.getElementsByTagName("param")
        val raw = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("name") == "params" }.textContent
        val decoded = JSONObject(raw)
        assertEquals(params.getString("text"), decoded.getString("text"))
        assertTrue(decoded.getJSONObject("options").getBoolean("enabled"))
        assertEquals(2, decoded.getJSONObject("options").getInt("count"))
    }
}
