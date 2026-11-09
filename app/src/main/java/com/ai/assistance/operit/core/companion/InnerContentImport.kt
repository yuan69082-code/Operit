package com.ai.assistance.operit.core.companion

import com.google.gson.internal.Streams
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Reader

/** UI and file import use the same candidate submission, one durable receipt per item.
 * JSON arrays and JSONL are streamed; batch size is not a limit on saved content. */
internal object InnerContentImport {
    suspend fun read(reader: Reader, store: InternalStateStore, role: String, chat: String,
                     original: String?, source: String?, currentSubmission: Boolean = false,
                     progress: suspend (Long) -> Unit): Long = withContext(Dispatchers.IO) {
        var count = 0L
        JsonReader(reader).use { json ->
            json.isLenient = true
            val array = json.peek() == JsonToken.BEGIN_ARRAY
            if (array) json.beginArray()
            while (if (array) json.hasNext() else json.peek() != JsonToken.END_DOCUMENT) {
                val parsed = Streams.parse(json)
                require(parsed.isJsonObject) { "第 ${count + 1} 项须为完整内容对象；此前成功提交的内容已保留" }
                val row = JSONObject(parsed.toString())
                val content = row.optJSONObject("record") ?: row
                val provenance = row.optJSONObject("provenance")
                val raw = provenance?.optString("original")?.takeIf { it.isNotBlank() }
                    ?: if (currentSubmission) content.toString() else original
                val label = provenance?.optString("source")?.takeIf { it.isNotBlank() }
                    ?: if (currentSubmission) "用户本次导入提出的完整变化" else source
                val formedAt = provenance?.optLong("formed_at")?.takeIf { it > 0 }
                val digest = InnerStructure.hash(JSONObject().put("record", content).put("original", raw ?: JSONObject.NULL)
                    .put("source", label ?: JSONObject.NULL).put("formed_at", formedAt ?: JSONObject.NULL))
                store.submitEstablishedContent(role, chat, "file:$digest", content, raw, label, formedAt)
                count++
                progress(count)
            }
            if (array) { json.endArray(); require(json.peek() == JsonToken.END_DOCUMENT) { "数组后还有未处理内容" } }
        }
        count
    }

    fun record(family: String, kind: String, description: String, objectRef: String,
               context: String, exceptions: String, extra: JSONObject = JSONObject(), id: String? = null): JSONObject {
        require(family in InnerStructure.families && kind.isNotBlank() && description.isNotBlank())
        val row = JSONObject(extra.toString()).put("family", family).put("kind", kind)
            .put("description", description).put("status", "active").put("context", context).put("exceptions", exceptions)
        if (objectRef.isNotBlank()) row.put("object_ref", objectRef)
        else row.remove("object_ref")
        row.put("id", id ?: InnerStructure.hash(row))
        return row
    }
}
