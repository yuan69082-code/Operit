package com.ai.assistance.operit.core.companion

import android.content.Context
import com.ai.assistance.operit.core.awareness.PhoneAwarenessStore
import com.ai.assistance.operit.core.awareness.SoftwareAwarenessStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 关注档位：盯多紧由观察助手自己定，随时可改。
 * 软件只给一条底线：出现硬事实时默认盯紧，想往下降必须写理由，理由会摆在下一次醒来的账上。
 */
object AttentionGear {
    private const val KEY = "attention_gear"
    private val order = listOf("loose", "normal", "tight")
    private val names = mapOf("loose" to "放松", "normal" to "平常", "tight" to "盯紧")
    private const val NORMAL_MS = 20 * 60_000L
    // 盯紧时切应用也攒 3 分钟再打包，解锁仍立刻叫
    private const val TIGHT_PHONE_MS = 3 * 60_000L
    private const val LOOSE_MS = 180 * 60_000L
    private const val GOODNIGHT_WINDOW_MS = 6 * 3_600_000L
    private val goodnight = Regex("晚安|睡了|睡觉|去睡|good\\s*night", RegexOption.IGNORE_CASE)
    private val attachment = Regex("<attachment\\b[\\s\\S]*?</attachment>", RegexOption.IGNORE_CASE)

    data class State(val level: String, val until: Long, val reason: String,
                     val floors: Map<String, String>, val effective: String)

    // 事件循环每两秒检查一次，档位短暂缓存，避免反复读文件
    @Volatile private var cache: Triple<String, Long, State>? = null

    /** 记下她真实发来的话里有没有晚安；附件和唤醒提示不算。 */
    fun noteUserText(context: Context, chatId: String, text: String) {
        if (text.startsWith("[软件主动唤醒")) return
        val body = text.replace(attachment, "").take(2000)
        if (!goodnight.containsMatchIn(body)) return
        CompanionStore(context).update("$KEY:night:$chatId") { it.put("goodnight_at", System.currentTimeMillis()) }
        cache = null
    }

    fun state(context: Context, chatId: String?, roleId: String?): State {
        val now = System.currentTimeMillis()
        val key = "$chatId/$roleId"
        cache?.let { (k, at, s) -> if (k == key && now - at in 0..15_000) return s }
        val store = CompanionStore(context)
        val raw = store.read(KEY)
        val until = raw.optLong("until")
        val expired = until in 1..now
        val level = if (expired) "normal" else raw.optString("level", "normal").takeIf { it in order } ?: "normal"
        val reason = if (expired) "" else raw.optString("reason")
        val floors = if (chatId == null) emptyMap() else floors(context, store, chatId, roleId, now)
        val atSet = raw.optJSONArray("floors_at_set")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        // 降档理由只覆盖写理由时已经存在的底线；之后冒出新的硬事实，还是回到盯紧
        val honored = reason.isNotBlank() && atSet.containsAll(floors.keys)
        val effective = if (floors.isNotEmpty() && level != "tight" && !honored) "tight" else level
        return State(level, if (expired) 0 else until, reason, floors, effective).also { cache = Triple(key, now, it) }
    }

    private fun floors(context: Context, store: CompanionStore, chatId: String, roleId: String?, now: Long): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val ledger = store.read("ledger:$chatId")
        ledger.optString("open").trim().takeIf { it.isNotEmpty() }?.let { out["open"] = "账上还有没了结的事：${it.take(80)}" }
        ledger.optInt("unanswered").takeIf { it >= 2 }?.let { out["unanswered"] = "她已经连着${it}次没回我" }
        val night = store.read("$KEY:night:$chatId").optLong("goodnight_at")
        if (night > 0 && now - night in 0..GOODNIGHT_WINDOW_MS) out["goodnight"] = "她${(now - night) / 60_000}分钟前说过晚安/要睡了"
        if (store.emotionEnabled) {
            val text = XcEmotionBridge.receipt(context, store.scope(chatId, roleId)).optJSONObject("snapshot")
                ?.optString("additionalContext").orEmpty()
            val pleasure = Regex("愉悦=(-?[0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            val arousal = Regex("唤醒=(-?[0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            val recent = Regex("最近一次波动来自「([a-z_]+)」").find(text)?.groupValues?.get(1)
            if (recent in setOf("conflict", "slighted", "loss") || (pleasure != null && pleasure < 0.35 && (arousal ?: 0.0) > 0.5))
                out["xc"] = "XC 里我的情绪在冲突/焦躁这一侧"
        }
        return out
    }

    fun set(context: Context, chatId: String, roleId: String?, level: String, hours: Double?, reason: String?): JSONObject {
        require(level in order) { "level 须为 tight/normal/loose（盯紧/平常/放松）" }
        val phone = PhoneAwarenessStore(context)
        val software = SoftwareAwarenessStore(context)
        check(!roleId.isNullOrBlank() && (roleId == phone.role || roleId == software.role)) { "只有被指定的观察助手能调关注档位" }
        require(hours == null || hours in 0.1..72.0) { "hours 须在 0.1–72 之间" }
        cache = null
        val current = state(context, chatId, roleId)
        val note = reason?.trim()?.take(300).orEmpty()
        require(level == "tight" || current.floors.isEmpty() || note.isNotEmpty()) {
            "底线还在：${current.floors.values.joinToString("；")}。要降到${names[level]}，必须在 reason 写清为什么。"
        }
        val now = System.currentTimeMillis()
        val store = CompanionStore(context)
        store.update(KEY) {
            it.put("level", level).put("until", hours?.let { h -> now + (h * 3_600_000).toLong() } ?: 0L)
                .put("reason", note).put("set_at", now).put("floors_at_set", JSONArray(current.floors.keys.toList()))
        }
        cache = null
        store.log(chatId, "关注档位", "${names[level]}${hours?.let { "，${it}小时" }.orEmpty()}${if (note.isNotEmpty()) "；理由：$note" else ""}")
        return JSONObject().put("result", describe(context, chatId, roleId))
    }

    fun describe(context: Context, chatId: String, roleId: String?): String {
        val s = state(context, chatId, roleId)
        return buildString {
            append("关注档位：").append(names[s.effective])
            if (s.effective != s.level) append("（我设的是${names[s.level]}，底线把它抬到了盯紧）")
            if (s.until > 0) append("，到").append(java.time.Instant.ofEpochMilli(s.until).atZone(java.time.ZoneId.systemDefault())
                .toLocalTime().withSecond(0).withNano(0)).append("自动回平常")
            if (s.floors.isNotEmpty()) append("\n底线：").append(s.floors.values.joinToString("；"))
            if (s.reason.isNotBlank()) append("\n上一次的我降档时写的理由：").append(s.reason).append("（觉得不对就改回去）")
        }
    }

    /** 手机事件：只有盯紧时才按原间隔、解锁/切应用立即叫醒；其他档只存不叫，到点打包。 */
    fun phonePolicy(context: Context, chatId: String, roleId: String, base: Long): Pair<Long, Boolean> =
        when (state(context, chatId, roleId).effective) {
            "tight" -> maxOf(base, TIGHT_PHONE_MS) to true
            "normal" -> maxOf(base, NORMAL_MS) to false
            else -> maxOf(base, LOOSE_MS) to false
        }

    /** 软件事件：加助手、换模型这类紧急事不受档位影响，其余按档位间隔。 */
    fun softwareInterval(context: Context, chatId: String, roleId: String, base: Long): Long =
        when (state(context, chatId, roleId).effective) {
            "tight" -> base
            "normal" -> maxOf(base, NORMAL_MS)
            else -> maxOf(base, LOOSE_MS)
        }
}
