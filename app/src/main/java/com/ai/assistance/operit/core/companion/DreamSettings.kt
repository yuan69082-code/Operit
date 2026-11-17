package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.preferences.AssistantScope
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import org.json.JSONObject

/** User runtime configuration, independent of personality and never writable by dream text. */
internal class DreamSettings(private val context: Context) {
    data class Binding(val configId: String, val index: Int) {
        fun json() = JSONObject().put("config_id", configId).put("model_index", index)
    }
    fun read(role: String): Binding? {
        SubjectStateBoundary.authorize(role, AssistantScope.callerId())
        val row = AssistantRoom(context, role).read().optJSONObject("dream_environment_model") ?: return null
        return Binding(row.getString("config_id"), row.getInt("model_index"))
    }
    suspend fun save(role: String, binding: Binding?) {
        SubjectStateBoundary.authorize(role, AssistantScope.callerId())
        if (binding != null) {
            val config = requireNotNull(ModelConfigManager(context).getModelConfig(binding.configId)) { "所选模型配置不存在" }
            require(binding.index in getModelList(config.modelName).indices) { "所选模型已变更，请重新选择" }
        }
        CompanionStore(context).update("room:$role") {
            if (binding == null) it.remove("dream_environment_model") else it.put("dream_environment_model", binding.json())
        }
    }
}
