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
    val limits = mapOf("events" to 3000, "experiences" to 600, "semantic_reports" to 300, "versions" to 300)
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
        fun lookup(kind: String, row: JSONObject): String = when (kind) {
            "semantic_reports" -> row.getString("frame_id")
            "versions" -> "${row.getString("dimension")}:${row.getInt("version")}"
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
