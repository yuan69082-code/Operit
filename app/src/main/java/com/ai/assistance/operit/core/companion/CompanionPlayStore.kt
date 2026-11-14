package com.ai.assistance.operit.core.companion

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ai.assistance.operit.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Durable playroom data. Coupons, keepsakes and notifications are indexed by assistant, never
 * copied into the chat summary or an unbounded per-turn prompt. Every listing is paged.
 */
class CompanionPlayStore(context: Context) {
    private val app = context.applicationContext
    private val helper: PlayDatabase = synchronized(lock) {
        database ?: PlayDatabase(app).also { database = it }
    }

    private fun roleId(role: String) = role.also { require(it.isNotBlank() && it.length <= 160) }
    private fun validateOffset(offset: Int) = offset.also { require(it in 0..100_000) }
    private fun id() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()

    /** Only the human-facing UI can invoke issuance. There is deliberately no AI issue action. */
    fun createCoupon(role: String, title: String, description: String, uses: Int): JSONObject = synchronized(lock) {
        roleId(role)
        require(title.trim().length in 1..80 && description.trim().length in 1..1500)
        require(uses in 1..100 || uses == -1)
        val key = id()
        helper.writableDatabase.let { db ->
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO coupons (id, role, title, detail, total_uses, remaining, status, created, updated) VALUES (?,?,?,?,?,?,?,?,?)",
                    arrayOf(key, role, title.trim(), description.trim(), uses, uses, "draft", now(), now()))
                notice(db, role, "coupon_draft", key, "园园新创建了一张「${title.trim()}」，暂未赠送给你。")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        wake(role, "coupon_draft", "新建未赠送的情侣券")
        coupon(role, key, true)
    }

    fun giftCoupon(role: String, couponId: String): JSONObject = synchronized(lock) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val old = couponRow(db, role, couponId)
            check(old.getString("status") == "draft") { "只有尚未送出的券可以赠送" }
            db.execSQL("UPDATE coupons SET status='gifted', updated=? WHERE role=? AND id=?",
                arrayOf(now(), role, couponId))
            notice(db, role, "coupon_gift", couponId, "园园赠送给你一张「${old.getString("title")}」，已放入你的券夹。")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        wake(role, "coupon_gift", "收到情侣券")
        coupon(role, couponId, true)
    }

    fun requestCoupon(role: String, couponId: String): JSONObject = synchronized(lock) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val old = couponRow(db, role, couponId)
            check(old.getString("status") == "gifted" && old.getInt("remaining") != 0) { "这张券目前不能兑换" }
            db.execSQL("UPDATE coupons SET status='requested', updated=? WHERE role=? AND id=?",
                arrayOf(now(), role, couponId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        coupon(role, couponId, false)
    }

    /** A human confirmation, not an automatic deduction on the AI's request. */
    fun decideRequest(role: String, couponId: String, completed: Boolean): JSONObject = synchronized(lock) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val old = couponRow(db, role, couponId)
            check(old.getString("status") == "requested") { "没有待确认的兑换请求" }
            val remaining = old.getInt("remaining")
            val next = if (completed && remaining > 0) remaining - 1 else remaining
            val status = if (next == 0) "used" else "gifted"
            db.execSQL("UPDATE coupons SET status=?, remaining=?, updated=? WHERE role=? AND id=?",
                arrayOf(status, next, now(), role, couponId))
            notice(db, role, "coupon_result", couponId,
                if (completed) "「${old.getString("title")}」本次兑换已由园园确认完成。" else
                    "「${old.getString("title")}」本次兑换暂未完成，券仍可继续使用。")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        coupon(role, couponId, true)
    }

    fun cancelRequest(role: String, couponId: String): JSONObject = synchronized(lock) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            check(couponRow(db, role, couponId).getString("status") == "requested")
            db.execSQL("UPDATE coupons SET status='gifted', updated=? WHERE role=? AND id=?",
                arrayOf(now(), role, couponId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        coupon(role, couponId, false)
    }

    fun coupon(role: String, couponId: String, human: Boolean): JSONObject = synchronized(lock) {
        val item = couponRow(helper.readableDatabase, roleId(role), couponId)
        check(human || item.getString("status") != "draft") { "券尚未送出；通知不授予使用权或查看完整内容" }
        item
    }

    fun coupons(role: String, offset: Int = 0, human: Boolean = true): JSONObject = synchronized(lock) {
        queryPage("SELECT id,role,title,detail,total_uses,remaining,status,created,updated FROM coupons WHERE role=? " +
            (if (human) "" else "AND status!='draft' ") + "ORDER BY created DESC LIMIT 20 OFFSET ?",
            arrayOf(roleId(role), validateOffset(offset).toString()), offset)
    }

    /** An AI can create its own keepsakes; all contents live in storage, not in a chat prompt. */
    fun createItem(role: String, title: String, content: String, visibility: String): JSONObject = synchronized(lock) {
        roleId(role)
        require(title.trim().length in 1..100 && content.length in 1..12000)
        require(visibility in setOf("shared", "private"))
        val key = id()
        helper.writableDatabase.execSQL(
            "INSERT INTO items (id,role,title,body,visibility,created,updated) VALUES (?,?,?,?,?,?,?)",
            arrayOf(key, role, title.trim(), content, visibility, now(), now()))
        item(role, key, true)
    }

    fun changeVisibility(role: String, itemId: String, visibility: String): JSONObject = synchronized(lock) {
        require(visibility in setOf("shared", "private"))
        item(role, itemId, true)
        helper.writableDatabase.execSQL("UPDATE items SET visibility=?,updated=? WHERE role=? AND id=?",
            arrayOf(visibility, now(), role, itemId))
        item(role, itemId, true)
    }

    fun item(role: String, itemId: String, ai: Boolean): JSONObject = synchronized(lock) {
        val where = if (ai) "" else " AND visibility='shared'"
        one("SELECT * FROM items WHERE role=? AND id=?$where", arrayOf(roleId(role), itemId), "物品不存在或不可查看")
    }

    fun items(role: String, offset: Int = 0, ai: Boolean = false): JSONObject = synchronized(lock) {
        queryPage("SELECT id,role,title,visibility,created,updated FROM items WHERE role=? " +
            (if (ai) "" else "AND visibility='shared' ") + "ORDER BY created DESC LIMIT 20 OFFSET ?",
            arrayOf(roleId(role), validateOffset(offset).toString()), offset)
    }

    /** AI-owned notification preference; users/software limits always override wake requests. */
    fun preferences(role: String): JSONObject = synchronized(lock) {
        val modes = JSONObject()
        for (kind in categories) modes.put(kind, mode(roleId(role), kind))
        modes
    }

    fun setPreference(role: String, category: String, next: String): JSONObject = synchronized(lock) {
        require(category in categories && next in setOf("wake", "next", "archive"))
        helper.writableDatabase.execSQL("INSERT OR REPLACE INTO preferences (role,category,mode) VALUES (?,?,?)",
            arrayOf(roleId(role), category, next))
        preferences(role)
    }

    fun notices(role: String, offset: Int = 0): JSONObject = synchronized(lock) {
        queryPage("SELECT id,role,category,reference,body,status,created FROM notices WHERE role=? ORDER BY id DESC LIMIT 20 OFFSET ?",
            arrayOf(roleId(role), validateOffset(offset).toString()), offset)
    }

    fun markRead(role: String, noticeId: Long): JSONObject = synchronized(lock) {
        val notice = one("SELECT * FROM notices WHERE role=? AND id=?", arrayOf(roleId(role), noticeId.toString()), "通知不存在")
        helper.writableDatabase.execSQL("UPDATE notices SET status='read' WHERE role=? AND id=?",
            arrayOf(role, noticeId))
        notice.put("status", "read")
    }

    /**
     * One-shot bounded delivery on an actual model request (not UI preview / token estimate).
     * Surfaced is NOT read: the AI may explicitly mark read, and failed turns remain inspectable.
     */
    fun takeForContext(role: String): JSONArray = synchronized(lock) {
        val db = helper.writableDatabase
        val rows = JSONArray()
        db.beginTransaction()
        try {
            db.rawQuery("SELECT id,category,body,reference FROM notices WHERE role=? AND status='new' AND category NOT IN " +
                "(SELECT category FROM preferences WHERE role=? AND mode='archive') ORDER BY id LIMIT 3",
                arrayOf(roleId(role), role)).use { c ->
                while (c.moveToNext()) {
                    val key = c.getLong(0)
                    rows.put(JSONObject().put("id", key).put("category", c.getString(1))
                        .put("body", c.getString(2).take(180)).put("reference", c.getString(3)))
                    db.execSQL("UPDATE notices SET status='surfaced' WHERE id=? AND role=?",
                        arrayOf(key, role))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        rows
    }

    data class Wake(val role: String, val chat: String, val ids: List<Long>) {
        val triggerId: String get() = "playroom:" + ids.joinToString(":")
        val prompt: String get() = "AI 通知中心有${ids.size}条新的允许唤醒的通知。请阅读本次真实通知，再决定要不要回应；不必强制联系用户，也不要编造未发生的事情。"
    }

    /** Lease at most three wake notices, only for the user-enabled proactive assistant. */
    fun claimWake(): Wake? = synchronized(lock) {
        val options = CompanionStore(app)
        if (!options.proactiveEnabled) return@synchronized null
        val role = options.proactiveRole ?: return@synchronized null
        val chat = AssistantWindowRouter.chat(app, role) ?: return@synchronized null
        val db = helper.writableDatabase
        val ids = mutableListOf<Long>()
        db.beginTransaction()
        try {
            val last = meta(db, role, "wake_last")?.toLongOrNull() ?: 0L
            if (now() - last < 30_000) {
                db.setTransactionSuccessful()
                return@synchronized null
            }
            // Explicit AI preference wins. Only a gifted coupon defaults to wake.
            db.rawQuery(
                "SELECT n.id FROM notices n LEFT JOIN preferences p ON p.role=n.role AND p.category=n.category " +
                    "WHERE n.role=? AND n.status='new' AND n.wake_state='pending' " +
                    "AND COALESCE(p.mode,CASE WHEN n.category='coupon_gift' THEN 'wake' ELSE 'next' END)='wake' " +
                    "ORDER BY n.id LIMIT 3",
                arrayOf(role)
            ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
            if (ids.isNotEmpty()) {
                ids.forEach { key -> db.execSQL("UPDATE notices SET wake_state='running' WHERE id=?", arrayOf(key)) }
                db.execSQL("INSERT OR REPLACE INTO meta (role,key,value) VALUES (?,?,?)",
                    arrayOf(role, "wake_last", now().toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (ids.isEmpty()) null else Wake(role, chat, ids)
    }

    fun finishWake(work: Wake, outcome: String) = synchronized(lock) {
        require(outcome in setOf("handled", "interrupted", "failed"))
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            work.ids.forEach { key ->
                db.execSQL("UPDATE notices SET wake_state=? WHERE role=? AND id=? AND wake_state='running'",
                    arrayOf(outcome, work.role, key))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun recoverWake() = synchronized(lock) {
        // Never replay an interrupted model turn automatically; the inbox is still inspectable.
        helper.writableDatabase.execSQL("UPDATE notices SET wake_state='interrupted' WHERE wake_state='running'")
    }

    /** Called only with an actual installed app version, never a repository commit. */
    fun observeInstalledVersion(role: String) = synchronized(lock) {
        val key = "installed_version"
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val old = meta(db, roleId(role), key)
            if (old != null && old != BuildConfig.VERSION_NAME) {
                notice(db, role, "updates", BuildConfig.VERSION_NAME,
                    "Operit 已更新至 ${BuildConfig.VERSION_NAME}。可以查看这次实际安装版本的新功能与修复说明。")
            }
            if (old != BuildConfig.VERSION_NAME)
                db.execSQL("INSERT OR REPLACE INTO meta (role,key,value) VALUES (?,?,?)",
                    arrayOf(role, key, BuildConfig.VERSION_NAME))
            if (meta(db, role, "game_catalog") == null) {
                notice(db, role, "games", "coupons-v1", "情侣游戏屋已加入恋爱券铺，园园可以亲自制作和赠送兑换券。")
                db.execSQL("INSERT OR REPLACE INTO meta (role,key,value) VALUES (?,?,?)",
                    arrayOf(role, "game_catalog", "coupons-v1"))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun mode(role: String, category: String): String =
        helper.readableDatabase.rawQuery("SELECT mode FROM preferences WHERE role=? AND category=?",
            arrayOf(role, category)).use { c ->
            if (c.moveToFirst()) c.getString(0) else if (category == "coupon_gift") "wake" else "next"
        }

    private fun wake(role: String, category: String, description: String) {
        // Reuse the existing serialized wake pipeline. A user-disabled proactive channel stays off.
        val options = CompanionStore(app)
        if (mode(role, category) == "wake" && options.proactiveEnabled && options.proactiveRole == role)
            CompanionRuntime.signalPlayroomEvent()
    }

    private fun notice(db: SQLiteDatabase, role: String, category: String, reference: String, body: String) {
        require(category in categories || category == "coupon_result")
        db.execSQL("INSERT INTO notices (role,category,reference,body,status,created) VALUES (?,?,?,?,?,?)",
            arrayOf(role, category, reference, body.take(350), "new", now()))
    }

    private fun meta(db: SQLiteDatabase, role: String, key: String) =
        db.rawQuery("SELECT value FROM meta WHERE role=? AND key=?", arrayOf(role, key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun couponRow(db: SQLiteDatabase, role: String, couponId: String): JSONObject =
        queryOne(db, "SELECT * FROM coupons WHERE role=? AND id=?", arrayOf(role, couponId), "券不存在")

    private fun one(sql: String, args: Array<String>, missing: String): JSONObject =
        queryOne(helper.readableDatabase, sql, args, missing)

    private fun queryOne(db: SQLiteDatabase, sql: String, args: Array<String>, missing: String): JSONObject =
        db.rawQuery(sql, args).use { c -> check(c.moveToFirst()) { missing }; asJson(c) }

    private fun queryPage(sql: String, args: Array<String>, offset: Int): JSONObject {
        val rows = JSONArray()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) rows.put(asJson(c))
        }
        return JSONObject().put("rows", rows).put("offset", offset)
            .put("next_offset", if (rows.length() == 20) offset + 20 else JSONObject.NULL)
    }

    private fun asJson(cursor: Cursor): JSONObject = JSONObject().also { output ->
        for (index in 0 until cursor.columnCount) {
            val value: Any = when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                else -> cursor.getString(index)
            }
            output.put(cursor.getColumnName(index), value)
        }
    }

    private class PlayDatabase(context: Context): SQLiteOpenHelper(context, "companion_playroom.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE coupons (id TEXT PRIMARY KEY, role TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, total_uses INTEGER NOT NULL, remaining INTEGER NOT NULL, status TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX coupon_role_date ON coupons(role,created DESC)")
            db.execSQL("CREATE TABLE items (id TEXT PRIMARY KEY, role TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, visibility TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX item_role_date ON items(role,created DESC)")
            db.execSQL("CREATE TABLE notices (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, category TEXT NOT NULL, reference TEXT NOT NULL, body TEXT NOT NULL, status TEXT NOT NULL, wake_state TEXT NOT NULL DEFAULT 'pending', created INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX notice_role_new ON notices(role,status,id)")
            db.execSQL("CREATE TABLE preferences (role TEXT NOT NULL, category TEXT NOT NULL, mode TEXT NOT NULL, PRIMARY KEY(role,category))")
            db.execSQL("CREATE TABLE meta (role TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(role,key))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("情侣游戏屋数据需要显式迁移，禁止静默丢弃")
        }
    }

    companion object {
        private val lock = Any()
        private var database: PlayDatabase? = null
        val categories = setOf("coupon_draft", "coupon_gift", "coupon_result", "games", "updates", "gifts", "study")
    }
}
