package com.ai.assistance.operit.core.chat

import android.content.Context
import com.ai.assistance.operit.core.companion.CompanionStore
import org.json.JSONArray
import org.json.JSONObject

/** 我聊天时自己记下的感受，按窗口存；压缩时原样归进对应段落，不交给模型代写。 */
internal object FeelingLog {
    private fun key(chatId: String) = "feelings:$chatId"
    private const val SEGMENTS = "segments_enabled"

    fun segmentsEnabled(context: Context) = CompanionStore(context).preferences.getBoolean(SEGMENTS, true)
    fun setSegmentsEnabled(context: Context, enabled: Boolean) {
        CompanionStore(context).preferences.edit().putBoolean(SEGMENTS, enabled).apply()
    }

    fun add(context: Context, chatId: String, text: String): JSONObject {
        val clean = text.trim()
        require(clean.isNotBlank()) { "感受不能为空" }
        val now = System.currentTimeMillis()
        CompanionStore(context).update(key(chatId)) { data ->
            val list = data.optJSONArray("items") ?: JSONArray()
            list.put(JSONObject().put("at", now).put("text", clean))
            // 事件目录只显示节选；原始感受不能因新记录增长而被静默丢弃。
            data.put("items", list)
        }
        return JSONObject().put("saved", true).put("at", now)
    }

    fun since(context: Context, chatId: String, from: Long): List<SegmentedSummary.Feeling> {
        val list = CompanionStore(context).read(key(chatId)).optJSONArray("items") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { SegmentedSummary.Feeling(it.optLong("at"), it.optString("text")) }
        }.filter { it.at >= from && it.text.isNotBlank() }
    }
}
