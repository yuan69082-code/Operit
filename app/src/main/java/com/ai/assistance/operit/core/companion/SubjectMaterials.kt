package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.model.CharacterCardMemoryProfileBindingMode
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.MemoryRepository
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Reads the existing memory space. Results are source material, never new real-world events. */
internal object SubjectMaterials {
    suspend fun read(context: Context, role: String, input: String): List<JSONObject> {
        val card = CharacterCardManager.getInstance(context).getCharacterCard(role)
        if (CharacterCardMemoryProfileBindingMode.normalize(card.memoryProfileBindingMode) !=
            CharacterCardMemoryProfileBindingMode.FIXED_PROFILE || card.memoryProfileId.isNullOrBlank()) return emptyList()
        val profile = requireNotNull(card.memoryProfileId)
        val data = InternalStateStore(context).read(role)
        val episodes = CategoryEvents.material(data, false).getJSONArray("episodes")
        val attention = buildList {
            for (i in 0 until episodes.length()) {
                val c = episodes.getJSONObject(i).getJSONObject("content")
                add(c.optString("understanding"))
                add(c.optJSONArray("objects")?.toString().orEmpty())
            }
            InnerStructure.records(data).filter { it.getJSONObject("content").getString("status") == "active" }
                .take(8).forEach { add(it.getJSONObject("content").optString("kind")) }
        }.joinToString(" ")
        fun terms(text: String) = com.ai.assistance.operit.util.TextSegmenter.segment(text)
            .map { it.trim() }.filter { it.length >= 2 }.distinct().take(3)
        val terms = (terms(input) + terms(attention)).distinct()
        return try {
            MemoryRepository(context, profile).subjectMaterials(terms, data.optLong("material_query_cursor")).map { row ->
                val text = row.content.take(1200)
                JSONObject().put("id", "material:memory:$profile:${row.uuid}:${row.updatedAt.time}")
                    .put("type", "memory_record").put("author", JSONObject.NULL).put("owner", role)
                    .put("at", row.updatedAt.time).put("source", row.source).put("title", row.title.take(160))
                    .put("text", text).put("excerpt", text.length < row.content.length)
                    .put("locator", JSONObject().put("tool", "query_memory").put("profile", profile)
                        .put("uuid", row.uuid).put("memory_id", row.id))
                    .put("status", "retrieved").put("not_new_occurrence", true)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            com.ai.assistance.operit.util.AppLogger.w("SubjectMaterials", "Memory material query failed: ${e.javaClass.simpleName}")
            listOf(JSONObject().put("id", "material:query_failure").put("type", "retrieval_status")
                .put("text", "Memory query unavailable; no result obtained").put("status", "failed")
                .put("locator", JSONObject().put("profile", profile)))
        }
    }
}
