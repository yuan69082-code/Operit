package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ToolParameterSchema
import org.json.JSONArray
import org.json.JSONObject

internal object GeminiToolSchema {
    fun parameters(params: List<ToolParameterSchema>): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        params.forEach { param ->
            // Shared schemas have no nested properties/items. A bare OBJECT describes none
            // of the package's dynamic arguments. Carry JSON text, as the executor already
            // accepts, instead of asking the model to populate an unspecified object.
            val jsonText = param.type.lowercase() in setOf("object", "array")
            properties.put(param.name, JSONObject()
                .put("type", if (jsonText) "string" else param.type)
                .put("description", param.description + if (jsonText)
                    " Supply a JSON-encoded ${param.type} as a string. Include all arguments required by the target tool documentation, preserving nested values. Do not omit required fields or send an empty object when arguments are required." else "")
                .also { param.default?.let { value -> it.put("default", value) } })
            if (param.required) required.put(param.name)
        }
        return JSONObject().put("type", "object").put("properties", properties)
            .also { if (required.length() > 0) it.put("required", required) }
    }
}
