package com.ai.assistance.operit.core.companion

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.Reader
import java.io.Writer
import java.security.MessageDigest
import java.util.ArrayDeque

/** Only historical rows leave the hot document; definitions and current values never do. */
internal object InternalStateHistory {
    val limits = mapOf("events" to 3000, "experiences" to 600, "semantic_reports" to 300, "versions" to 300, "inner_history" to 40)
    private const val MAX_FIELD_CHARS = 2 * 1024 * 1024

    // Parse one row at a time: parsing the entire old file before trimming caused startup OOMs.
    fun read(input: Reader, archive: (String, JSONObject) -> Unit): JSONObject {
        val result = JSONObject()
        JsonReader(input).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "archived_contacts") { reader.skipValue(); continue }
                val limit = limits[name]
                if (limit == null) {
                    result.put(name, org.json.JSONTokener(JsonParser.parseReader(reader).toString()).nextValue())
                } else {
                    val retained = ArrayDeque<Pair<JSONObject, Int>>()
                    var chars = 0L
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val text = JsonParser.parseReader(reader).toString()
                        val row = JSONObject(text)
                        retained.addLast(row to text.length)
                        chars += text.length
                        while (retained.size > limit || (chars > MAX_FIELD_CHARS && retained.size > 1)) {
                            val old = retained.removeFirst()
                            archive(name, old.first)
                            chars -= old.second
                        }
                    }
                    reader.endArray()
                    result.put(name, JSONArray(retained.map { it.first }))
                }
            }
            reader.endObject()
            check(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected trailing internal-state data" }
        }
        return result
    }

    fun compact(data: JSONObject, archive: (String, JSONObject) -> Unit) {
        limits.forEach { (name, limit) ->
            val rows = data.optJSONArray(name) ?: return@forEach
            var start = rows.length()
            var chars = 0L
            while (start > 0 && rows.length() - start < limit) {
                val size = rows.getJSONObject(start - 1).toString().length
                if (start < rows.length() && chars + size > MAX_FIELD_CHARS) break
                chars += size
                start--
            }
            if (start == 0) return@forEach
            for (i in 0 until start) archive(name, rows.getJSONObject(i))
            data.put(name, JSONArray((start until rows.length()).map { rows.getJSONObject(it) }))
        }
    }

    /** Avoid simultaneously allocating a whole-file String and its UTF-8 byte array. */
    fun write(data: JSONObject, output: Writer) {
        val writer = JsonWriter(output)
        fun value(item: Any?) {
            when {
                item == null || item === JSONObject.NULL -> writer.nullValue()
                item is JSONObject -> {
                    writer.beginObject()
                    item.keys().forEach { key -> writer.name(key); value(item.get(key)) }
                    writer.endObject()
                }
                item is JSONArray -> {
                    writer.beginArray()
                    for (i in 0 until item.length()) value(item.get(i))
                    writer.endArray()
                }
                item is Number -> writer.value(item)
                item is Boolean -> writer.value(item)
                item is String -> writer.value(item)
                else -> error("Unsupported JSON value: ${item.javaClass.name}")
            }
        }
        value(data)
        writer.flush() // AtomicFile owns the stream and performs the final sync/close.
    }
}

/** Archive commits precede hot-file replacement. Content keys make interrupted migration repeatable. */
internal class InternalStateArchive(private val file: File, private val scope: String) : Closeable {
    private var database: SQLiteDatabase? = null
    private var writing = false

    private fun db(): SQLiteDatabase {
        database?.let { return it }
        return SQLiteDatabase.openOrCreateDatabase(file, null).also { db ->
            database = db
            db.execSQL("CREATE TABLE IF NOT EXISTS history (scope TEXT NOT NULL, kind TEXT NOT NULL, hash TEXT NOT NULL, lookup TEXT NOT NULL, payload TEXT NOT NULL, contact_chat TEXT, contact_at INTEGER, PRIMARY KEY(scope,kind,hash))")
            db.execSQL("CREATE INDEX IF NOT EXISTS history_lookup ON history(scope,kind,lookup)")
            db.execSQL("CREATE INDEX IF NOT EXISTS history_contact ON history(scope,contact_chat,contact_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS history_contact_global ON history(scope,contact_at)")
            db.execSQL("CREATE TABLE IF NOT EXISTS category_current (scope TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, parent TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope,kind,id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS category_parent ON category_current(scope,kind,parent,id)")
            // Equal owner/kind/parent ranges are then ordered by SQLite's implicit rowid.
            // Latest receipt pages must not sort every full historical result.
            db.execSQL("CREATE INDEX IF NOT EXISTS category_recent ON category_current(scope,kind,parent)")
            db.execSQL("CREATE TABLE IF NOT EXISTS category_commits (scope TEXT NOT NULL, id TEXT NOT NULL, digest TEXT NOT NULL, PRIMARY KEY(scope,id))")
        }
    }

    fun put(kind: String, row: JSONObject) {
        val db = db()
        if (!writing) { db.beginTransaction(); writing = true }
        val payload = row.toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val values = ContentValues().apply {
            put("scope", scope); put("kind", kind); put("hash", hash)
            put("lookup", lookup(kind, row))
            put("payload", payload)
            if (kind == "events" && row.optString("source") == "user_message" &&
                (row.has("contact_at") || row.getString("id").startsWith("message:"))) {
                put("contact_chat", row.getString("chat"))
                put("contact_at", if (row.has("contact_at")) row.getLong("contact_at") else row.getLong("at"))
            }
        }
        // Ignore only the exact already-archived row; other storage failures must abort migration.
        db.execSQL("INSERT OR IGNORE INTO history(scope,kind,hash,lookup,payload,contact_chat,contact_at) VALUES(?,?,?,?,?,?,?)",
            arrayOf(scope, kind, hash, values.getAsString("lookup"), payload,
                values.getAsString("contact_chat"), values.getAsLong("contact_at")))
    }

    fun find(kind: String, id: String): JSONObject? {
        if (!file.exists()) return null
        db().rawQuery("SELECT payload FROM history WHERE scope=? AND kind=? AND lookup=? ORDER BY rowid DESC LIMIT 1",
            arrayOf(scope, kind, id)).use { return if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    }

    /** Replay only a journal already committed by AtomicFile. One SQL transaction, no network. */
    fun applyCategoryJournal(journal: JSONObject) {
        require(journal.getInt("schema") == 1)
        val db = db()
        val id = journal.getString("id")
        val digest = InnerStructure.hash(journal)
        db.rawQuery("SELECT digest FROM category_commits WHERE scope=? AND id=?", arrayOf(scope, id)).use {
            if (it.moveToFirst()) { check(it.getString(0) == digest) { "事件提交标识冲突" }; return }
        }
        if (!writing) { db.beginTransaction(); writing = true }
        val rows = journal.getJSONArray("rows")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val kind = row.getString("storage_kind")
                require(kind in setOf("episode", "focus", "experience_link", "union", "decision", "feedback", "inner_record", "inner_pending", "inner_index", "inner_family") + SubjectRestDreams.kinds)
            put("category_versions", row)
            db.execSQL("INSERT OR REPLACE INTO category_current(scope,kind,id,parent,payload) VALUES(?,?,?,?,?)",
                arrayOf(scope, kind, row.getString("id"), row.getString("parent"), row.toString()))
        }
        db.execSQL("INSERT INTO category_commits(scope,id,digest) VALUES(?,?,?)", arrayOf(scope, id, digest))
        commit()
    }

    fun categoryRow(kind: String, id: String): JSONObject? {
        if (!file.exists()) return null
        return db().rawQuery("SELECT payload FROM category_current WHERE scope=? AND kind=? AND id=?", arrayOf(scope, kind, id))
            .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    }

    /** Activity facts reuse the archive. They are not psychological state changes. */
    fun activityWrite(kind: String, id: String, parent: String, change: (JSONObject?) -> JSONObject): JSONObject {
        require(kind in setOf("activity", "activity_part", "activity_contact", "waking_note", "inner_read", "thought", "thought_meta"))
        val db = db()
        if (!writing) { db.beginTransaction(); writing = true }
        val old = categoryRow(kind, id)
        val before = old?.toString()
        val oldVersion = old?.getInt("version") ?: 0
        val row = change(old)
        require(row.getString("id") == id && row.getString("parent") == parent)
        if (row.toString() != before) {
            row.put("version", oldVersion + 1)
            put("category_versions", row)
            db.execSQL("INSERT OR REPLACE INTO category_current(scope,kind,id,parent,payload) VALUES(?,?,?,?,?)",
                arrayOf(scope, kind, id, parent, row.toString()))
        }
        commit()
        return row
    }

    /** Objective operations share the existing archive, never the subjective report reducer.
     * Claim and receipt are short SQL transactions; no executor runs under this transaction. */
    fun actionWrite(id: String, change: (JSONObject?) -> JSONObject): JSONObject {
        val db = db()
        if (!writing) { db.beginTransaction(); writing = true }
        val old = find("action_history", id)
        val before = old?.toString()
        val row = change(old)
        if (row.toString() != before) {
            put("action_history", row)
            val head = JSONObject(row.toString())
            head.getJSONObject("request").remove("parameters")
            head.optJSONObject("receipt")?.let { receipt ->
                receipt.remove("data")
                if (!receipt.isNull("error")) receipt.put("error", receipt.getString("error").take(600))
                val text = receipt.getString("text")
                receipt.put("text", text.take(600)).put("excerpt", text.length > 600)
            }
            head.optJSONObject("declaration")?.optJSONObject("capability")?.remove("source")
            db.execSQL("INSERT OR REPLACE INTO category_current(scope,kind,id,parent,payload) VALUES(?,?,?,?,?)",
                arrayOf(scope, "action", id, row.getString("parent"), head.toString()))
        }
        commit()
        return row
    }

    fun actionPage(parent: String, offset: Int): List<JSONObject> {
        require(offset >= 0)
        if (!file.exists()) return emptyList()
        return db().rawQuery("SELECT payload FROM category_current INDEXED BY category_recent WHERE scope=? AND kind='action' AND parent=? ORDER BY rowid DESC LIMIT 9 OFFSET ?",
            arrayOf(scope, parent, offset.toString())).use { c ->
            buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) }
        }
    }

    fun actionVersions(id: String, offset: Int): List<JSONObject> {
        require(offset >= 0)
        if (!file.exists()) return emptyList()
        return db().rawQuery("SELECT payload FROM history WHERE scope=? AND kind='action_history' AND lookup=? ORDER BY rowid DESC LIMIT 9 OFFSET ?",
            arrayOf(scope, id, offset.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
        }
    }

    fun categoryPage(kind: String, offset: Int, limit: Int, parent: String?): List<JSONObject> {
        require(offset >= 0 && limit in 1..21)
        if (!file.exists()) return emptyList()
        val privateRecent = kind in SubjectRestDreams.kinds || kind in setOf("activity", "activity_part", "waking_note")
        val actualParent = parent ?: if (privateRecent) "private" else null
        val filter = if (actualParent == null) "" else " AND parent=?"
        val args = listOf(scope, kind) + listOfNotNull(actualParent) + listOf(limit.toString(), offset.toString())
        // Recent private generated material must not be permanently hidden behind the oldest
        // IDs. The existing scope/kind/parent/rowid index bounds this read before deserialization.
        val order = if (privateRecent) "rowid DESC" else "id"
        val index = if (privateRecent) " INDEXED BY category_recent" else ""
        return db().rawQuery("SELECT payload FROM category_current$index WHERE scope=? AND kind=?$filter ORDER BY $order LIMIT ? OFFSET ?",
            args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }
    }

    fun categoryVersions(id: String, offset: Int): List<JSONObject> {
        require(offset >= 0)
        if (!file.exists()) return emptyList()
        return db().rawQuery("SELECT payload FROM history WHERE scope=? AND kind='category_versions' AND lookup=? ORDER BY rowid DESC LIMIT 9 OFFSET ?",
            arrayOf(scope, id, offset.toString())).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }
    }

    fun categoryCount(kind: String, parent: String?): Long {
        if (!file.exists()) return 0L
        val filter = if (parent == null) "" else " AND parent=?"
        return db().rawQuery("SELECT COUNT(*) FROM category_current WHERE scope=? AND kind=?$filter",
            (listOf(scope, kind) + listOfNotNull(parent)).toTypedArray()).use { c -> c.moveToFirst(); c.getLong(0) }
    }

    /** Current adopted rows share a compact text index; the archive remains the authority.
     * INSERT OR REPLACE gives an edited row a new rowid, including same-ID retirement. Other
     * category kinds do not invalidate this index. No historical/evidence JSON is retained. */
    fun relevantInnerRows(input: String, parent: String, limit: Int): List<JSONObject> {
        require(parent in setOf("identity", "other") && limit in 1..8)
        val query = InnerMaterialSelection.terms(input)
        if (query.isEmpty() || !file.exists()) return emptyList()
        val db = db()
        val revision = db.rawQuery("SELECT COUNT(*),COALESCE(MAX(rowid),0) FROM category_current WHERE scope=? AND kind='inner_record'",
            arrayOf(scope)).use { c -> c.moveToFirst(); c.getLong(0) to c.getLong(1) }
        val key = file.absolutePath to scope
        val cached = synchronized(innerSearchCache) { innerSearchCache[key]?.takeIf { it.revision == revision } }
        val ids = if (cached != null) InnerMaterialSelection.ranked(cached.entries.asSequence(), query, parent, limit)
        else {
            val entries = mutableListOf<InnerMaterialSelection.Entry>()
            var weight = 0L
            var retain = true
            val matches = db.rawQuery("SELECT payload FROM category_current WHERE scope=? AND kind='inner_record' ORDER BY id",
                arrayOf(scope)).use { cursor ->
                val stream = sequence {
                    while (cursor.moveToNext()) {
                        val row = JSONObject(cursor.getString(0))
                        if (row.optBoolean("closed")) continue
                        val entry = InnerMaterialSelection.entry(row.getJSONObject("value")) ?: continue
                        if (retain) {
                            weight += entry.cacheWeight
                            if (weight > INNER_INDEX_CACHE_WEIGHT) { entries.clear(); retain = false }
                            else entries += entry
                        }
                        yield(entry)
                    }
                }
                InnerMaterialSelection.ranked(stream, query, parent, limit)
            }
            synchronized(innerSearchCache) {
                if (retain) innerSearchCache[key] = InnerSearchIndex(revision, entries.toList())
                else innerSearchCache.remove(key)
            }
            matches
        }
        return ids.mapNotNull { id -> categoryRow("inner_record", id)?.getJSONObject("value") }
    }

    fun page(kind: String, offset: Int, limit: Int, hotIds: Set<String>): List<JSONObject> {
        require(offset >= 0 && limit in 1..21)
        if (!file.exists()) return emptyList()
        val result = mutableListOf<JSONObject>()
        var skipped = 0
        // CursorWindow pages disk rows; do not materialize the archive to implement pagination.
        db().rawQuery("SELECT lookup,payload FROM history WHERE scope=? AND kind=? ORDER BY rowid DESC",
            arrayOf(scope, kind)).use { cursor ->
            while (result.size < limit && cursor.moveToNext()) {
                if (cursor.getString(0) in hotIds) continue
                if (skipped++ < offset) continue
                result.add(JSONObject(cursor.getString(1)))
            }
        }
        return result
    }

    fun latestContact(chat: String?, at: Long): Long? {
        if (!file.exists()) return null
        val filter = if (chat == null) "" else " AND contact_chat=?"
        val args = listOf(scope, at.toString()) + listOfNotNull(chat)
        return db().rawQuery("SELECT MAX(contact_at) FROM history WHERE scope=? AND contact_at<=?$filter",
            args.toTypedArray()).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    fun contactPage(chat: String?, after: Long, until: Long): List<Long> {
        if (!file.exists()) return emptyList()
        val filter = if (chat == null) "" else " AND contact_chat=?"
        val args = listOf(scope, after.toString(), until.toString()) + listOfNotNull(chat)
        return db().rawQuery("SELECT DISTINCT contact_at FROM history WHERE scope=? AND contact_at>? AND contact_at<=?$filter ORDER BY contact_at LIMIT 256",
            args.toTypedArray()).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
    }

    companion object {
        private const val INNER_INDEX_CACHE_WEIGHT = 1_048_576L
        private data class InnerSearchIndex(val revision: Pair<Long, Long>, val entries: List<InnerMaterialSelection.Entry>)
        private val innerSearchCache = object : LinkedHashMap<Pair<String, String>, InnerSearchIndex>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, InnerSearchIndex>?) = size > 4
        }

        fun lookup(kind: String, row: JSONObject): String = when (kind) {
            "semantic_reports" -> row.getString("frame_id")
            "versions" -> "${row.getString("dimension")}:${row.getInt("version")}"
            "category_versions" -> "${row.getString("storage_kind")}:${row.getString("id")}"
            else -> row.optString("id")
        }
    }

    fun commit() {
        if (writing) {
            val db = checkNotNull(database)
            db.setTransactionSuccessful()
            db.endTransaction()
            writing = false
        }
    }

    override fun close() {
        try { if (writing) database?.endTransaction() } finally { database?.close() }
    }
}
