package com.ai.assistance.operit.core.companion

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object RoomPolicy {
    val widgets = linkedMapOf("diary" to "日记", "todo" to "私人打算", "reading" to "阅读角", "collection" to "收藏与资料", "dates" to "纪念日")
    val fields = linkedMapOf("title" to "房间名字", "subtitle" to "房间寄语", "cover" to "封面", "widgets" to "组件布局", "dates" to "纪念日", "theme" to "聊天外观")
    fun locked(data: JSONObject, field: String) = data.optJSONArray("locks")?.let { array -> (0 until array.length()).any { array.getString(it) == field } } == true
    fun authorize(data: JSONObject, patch: JSONObject, ai: Boolean) {
        require(patch.keys().asSequence().all { it in fields }) { "包含不支持的房间设置" }
        if (ai) {
            check(data.optBoolean("ai_decorate", false)) { "用户尚未允许你布置自己的房间" }
            check(patch.keys().asSequence().none { locked(data, it) }) { "这项设置已被用户锁定" }
        }
    }
    fun layout(data: JSONObject): JSONArray = data.optJSONArray("widgets") ?: JSONArray(widgets.keys.map {
        JSONObject().put("id", it).put("visible", true).put("size", 1)
    })
    fun validate(patch: JSONObject) {
        if (patch.has("widgets")) require(patch.get("widgets") is JSONArray)
        if (patch.has("dates")) require(patch.get("dates") is JSONArray)
        if (patch.has("theme")) require(patch.get("theme") is JSONObject)
        for (key in listOf("title", "subtitle", "cover")) if (patch.has(key)) require(patch.getString(key).length <= if (key == "cover") 2000 else 300) { "房间文字过长" }
        patch.optJSONArray("widgets")?.let { array ->
            val ids = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                require(item.getInt("size") in 1..3) { "组件尺寸须为1–3" }
                item.getBoolean("visible")
                item.getString("id")
            }
            require(ids.size == widgets.size && ids.toSet() == widgets.keys) { "布局须恰好包含全部组件，每项一次" }
        }
        patch.optJSONArray("dates")?.let { array ->
            require(array.length() <= 100) { "纪念日最多100项" }
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                require(item.getString("title").length in 1..120)
                LocalDate.parse(item.getString("date"))
            }
        }
    }
    fun apply(data: JSONObject, patch: JSONObject, ai: Boolean, now: Long) {
        authorize(data, patch, ai); validate(patch)
        patch.keys().forEach { data.put(it, patch.get(it)) }
        data.put("updated", now).put("updated_by", if (ai) "assistant" else "user")
    }
}
