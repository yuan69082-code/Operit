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
            db.execSQL("CREATE TABLE IF NOT EXISTS research_current (scope TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, brief TEXT NOT NULL, revision INTEGER NOT NULL, state TEXT NOT NULL, tab TEXT NOT NULL, assignee TEXT NOT NULL, category TEXT NOT NULL, priority TEXT NOT NULL, updated INTEGER NOT NULL, due INTEGER NOT NULL, search TEXT NOT NULL, PRIMARY KEY(scope,kind,id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS research_page_order ON research_current(scope,kind,priority,updated DESC,id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS research_recent ON research_current(scope,kind,updated DESC,id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS research_due ON research_current(scope,kind,state,due)")
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

    internal fun researchRow(kind: String, id: String): JSONObject? {
        if (!file.exists()) return null
        return db().rawQuery("SELECT payload FROM research_current WHERE scope=? AND kind=? AND id=?", arrayOf(scope, kind, id))
            .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    }

    /** Current record, immutable version and newly written notes commit together. No history in the head. */
    internal fun researchWrite(kind: String, record: JSONObject, imported: Boolean = false, commitNow: Boolean = true): JSONObject {
        require(kind in setOf("task", "source", "entry", "meta"))
        val db = db()
        if (!writing) { db.beginTransaction(); writing = true }
        val row = JSONObject(record.toString())
        val id = row.getString("id")
        val old = researchRow(kind, id)
        val notes = row.optJSONArray("history") ?: JSONArray()
        row.remove("history"); row.remove("_incremental_history")
        for (i in 0 until notes.length()) {
            val note = JSONObject(notes.getJSONObject(i).toString()).put("id", id)
                .put("entry_id", if (imported) "$id:legacy:$i" else java.util.UUID.randomUUID().toString())
            put("research_notes", note)
            if (note.optString("kind") == "批注") row.put("latest_note", JSONObject(notes.getJSONObject(i).toString()))
        }
        val revision = (old?.optLong("_research_revision") ?: 0) + 1
        row.put("_research_revision", revision)
        put("research_versions", JSONObject().put("id", "$kind:$id").put("revision", revision)
            .put("record", row).put("recorded_at", System.currentTimeMillis()).put("legacy_import", imported))
        val brief = JSONObject(row.toString())
        if (kind == "entry") listOf("body", "page_blocks", "summary", "note", "user_note", "receipts").forEach { brief.remove(it) }
        val dispatch = row.optJSONObject("dispatch")
        val state = when (kind) {
            "task" -> if (dispatch?.optString("state") == "running") "running" else if (row.optString("status") in com.ai.assistance.operit.core.tasks.TaskRecords.inactiveStatuses) "inactive" else dispatch?.optString("state").orEmpty()
            "source" -> if (row.optBoolean("enabled")) "enabled" else "disabled"
            else -> row.optString("state")
        }
        val due = when (kind) { "task" -> dispatch?.optLong("at") ?: 0; "source" -> row.optLong("checked") + row.optInt("hours") * 3_600_000L; else -> row.optLong("expires") }
        val search = listOf("title", "detail", "id", "conclusion", "next", "evidence", "origin", "goal", "blocker", "plan", "materials", "address", "progress", "url", "note", "user_note")
            .joinToString("\n") { row.optString(it) }
        // Preserve insertion order for the existing tool list; editing a row must not move it.
        db.execSQL("INSERT OR REPLACE INTO research_current(rowid,scope,kind,id,payload,brief,revision,state,tab,assignee,category,priority,updated,due,search) VALUES((SELECT rowid FROM research_current WHERE scope=? AND kind=? AND id=?),?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(scope, kind, id, scope, kind, id, row.toString(), brief.toString(), revision, state,
                if (kind == "task") com.ai.assistance.operit.core.tasks.TaskRecords.tab(row) else row.optString("status"),
                row.optString("assignee"), row.optString("category"), row.optString("priority"),
                row.optLong("updated", row.optLong("added")), due, search))
        if (commitNow) commit()
        return row
    }

    internal fun researchPage(kind: String, offset: Int, limit: Int = 20, filter: String = "", stage: String = "",
        assignee: String = "*", category: String = "", query: String = "", state: String = "", due: Long? = null, displayOrder: Boolean = false): JSONObject {
        require(offset >= 0 && limit in 1..21)
        val clauses = mutableListOf("scope=?", "kind=?")
        val args = mutableListOf(scope, kind)
        fun match(column: String, value: String) { clauses.add("$column=?"); args.add(value) }
        if (filter == "all") clauses.add("tab!='archived'") else if (filter.isNotBlank()) match("tab", filter)
        // Exact status is retained separately in the payload; use the indexed search of its JSON token only for this optional UI filter.
        if (stage.isNotBlank()) { clauses.add("instr(payload,?)>0"); args.add("\"status\":\"$stage\"") }
        if (assignee != "*") match("assignee", assignee)
        if (category.isNotBlank()) match("category", category)
        if (query.isNotBlank()) { clauses.add("instr(lower(search),lower(?))>0"); args.add(query) }
        when (state) {
            "active_task" -> clauses.add("tab NOT IN ('done','archived')")
            "history" -> clauses.add("state IN ('read','saved')")
            "queue" -> { match("state", "queued"); clauses.add("due>?"); args.add(System.currentTimeMillis().toString()) }
            "retained" -> clauses.add("state NOT IN ('read','saved') AND (state!='queued' OR due<=${System.currentTimeMillis()})")
            else -> if (state.isNotBlank()) match("state", state)
        }
        if (due != null) { clauses.add("due<=?"); args.add(due.toString()) }
        val where = clauses.joinToString(" AND ")
        val total = db().rawQuery("SELECT COUNT(*) FROM research_current WHERE $where", args.toTypedArray()).use { it.moveToFirst(); it.getLong(0) }
        val order = if (due != null) "due,id" else if (kind == "task") {
            if (displayOrder) "priority,updated DESC,id" else "rowid"
        } else if (kind == "source") "rowid" else "updated DESC,id"
        val rows = db().rawQuery("SELECT brief FROM research_current WHERE $where ORDER BY $order LIMIT ? OFFSET ?",
            (args + limit.toString() + offset.toString()).toTypedArray()).use { c -> JSONArray().apply { while (c.moveToNext()) put(JSONObject(c.getString(0))) } }
        return JSONObject().put("items", rows).put("total", total).put("next_offset", if (offset + rows.length() < total) offset + rows.length() else JSONObject.NULL)
    }

    internal fun researchHistory(kind: String, id: String, offset: Int): JSONObject {
        require(offset >= 0)
        val type = if (kind == "task") "research_notes" else "research_versions"
        val lookup = if (kind == "task") id else "$kind:$id"
        val args = arrayOf(scope, type, lookup)
        val total = db().rawQuery("SELECT COUNT(*) FROM history WHERE scope=? AND kind=? AND lookup=?", args).use { it.moveToFirst(); it.getLong(0) }
        val rows = db().rawQuery("SELECT payload FROM history WHERE scope=? AND kind=? AND lookup=? ORDER BY rowid DESC LIMIT 5 OFFSET ?", args + offset.toString())
            .use { c -> JSONArray().apply { while (c.moveToNext()) put(JSONObject(c.getString(0))) } }
        return JSONObject().put("history", rows).put("history_total", total)
            .put("next_history_offset", if (offset + rows.length() < total) offset + rows.length() else JSONObject.NULL)
    }

    internal fun researchDeleteTask(id: String) {
        val db = db(); if (!writing) { db.beginTransaction(); writing = true }
        // Link removal and explicit deletion are one transaction, including their versions.
        // A crash must not leave surviving tasks referring to a deleted task.
        var offset = 0
        while (true) {
            val page = researchPage("task", offset)
            val rows = page.getJSONArray("items")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.getString("id") == id) continue
                val links = row.optJSONArray("linked_ids") ?: continue
                val remaining = (0 until links.length()).map { links.getString(it) }.filter { it != id }
                if (remaining.size != links.length()) researchWrite("task", row.put("linked_ids", JSONArray(remaining)), commitNow = false)
            }
            if (page.isNull("next_offset")) break
            offset = page.getInt("next_offset")
        }
        db.execSQL("DELETE FROM research_current WHERE scope=? AND kind='task' AND id=?", arrayOf(scope, id))
        db.execSQL("DELETE FROM history WHERE scope=? AND ((kind='research_notes' AND lookup=?) OR (kind='research_versions' AND lookup=?))", arrayOf(scope, id, "task:$id"))
        commit()
    }

    internal fun researchMaterials(kind: String, cursor: Long, input: String): List<JSONObject> {
        val state = if (kind == "task") "active_task" else "enabled"
        val first = researchPage(kind, 0, 8, state = state)
        val total = first.getLong("total")
        val rotated = if (total == 0L) emptyList() else researchPage(kind, ((cursor.coerceAtLeast(0) % total * 8) % total).toInt(), 8, state = state).getJSONArray("items").objects()
        val related = InnerMaterialSelection.terms(input).take(3).flatMap { term ->
            researchPage(kind, 0, 2, state = state, query = term).getJSONArray("items").objects()
        }
        return (related + rotated).distinctBy { it.getString("id") }
    }

    /** Replay only a journal already committed by AtomicFile. One SQL transaction, no network. */
    fun applyCategoryJournal(journal: JSONObject) {
        require(journal.getInt("schema") == 1)
        val db = db()
        val id = journal.getString("id")
        val digest = CategoryJournalFingerprint.digest(journal)
        val committedDigest = db.rawQuery("SELECT digest FROM category_commits WHERE scope=? AND id=?", arrayOf(scope, id)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (committedDigest != null) {
            if (committedDigest == digest) return
            // Legacy hashes distinguished 1.0 from 1. JSONObject copies normalize these
            // numbers, so an unrelated save could make an already-committed journal unreadable.
            // Upgrade only after verifying the complete ordered history of this frame;
            // never replay rows or replace current psychological state to repair a checksum.
            check(!committedDigest.startsWith(CategoryJournalFingerprint.PREFIX)) { "事件提交标识冲突" }
            val archived = db.rawQuery(
                "SELECT payload FROM history WHERE scope=? AND kind='category_versions' AND instr(payload,?)>0 ORDER BY rowid",
                arrayOf(scope, JSONObject.quote(id))
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val row = JSONObject(cursor.getString(0))
                        if (row.optString("frame_id") == id) add(CategoryJournalFingerprint.digest(row))
                    }
                }
            }.distinct()
            val rows = journal.getJSONArray("rows")
            val expected = (0 until rows.length()).map { CategoryJournalFingerprint.digest(rows.getJSONObject(it)) }.distinct()
            check(expected.isNotEmpty() && archived == expected) { "事件提交标识冲突：提交内容与已归档版本不一致" }
            if (!writing) { db.beginTransaction(); writing = true }
            db.execSQL("UPDATE category_commits SET digest=? WHERE scope=? AND id=?", arrayOf(digest, scope, id))
            commit()
            return
        }
        if (!writing) { db.beginTransaction(); writing = true }
        val rows = journal.getJSONArray("rows")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val kind = row.getString("storage_kind")
                require(kind in setOf("episode", "focus", "experience_link", "union", "decision", "feedback", "inner_record", "inner_pending", "inner_index", "inner_family", "foundation_change", "inner_time", "dream_process") + SubjectRestDreams.kinds + LifeUnderstandings.kinds)
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
        require(kind in setOf("activity", "activity_part", "activity_contact", "waking_note", "inner_read", "thought", "thought_meta",
            "life_event", "life_activity", "life_run", "dream_process", "dream_step", "message_inbox", "private_time_config", "private_time_plan", "private_time_sleep", "private_time_essence"))
        val db = db()
        if (!writing) { db.beginTransaction(); writing = true }
        val old = categoryRow(kind, id)
        val before = old?.toString()
        val oldVersion = old?.getInt("version") ?: 0
        val row = change(old)
        if (kind.startsWith("private_time_") || kind in setOf("life_event", "message_inbox", "life_activity", "dream_process", "dream_step", "life_run")) {
            // The archive's version lookup uses kind:id. Bind the host-owned kind here,
            // so technical receipts cannot lose their history identity at any entry point.
            require(!row.has("storage_kind") || row.getString("storage_kind") == kind)
            row.put("storage_kind", kind)
        }
        require(row.getString("id") == id && row.getString("parent") == parent)
        if (row.toString() != before) {
            row.put("version", oldVersion + 1)
            // Stream checkpoints replace the durable current checkpoint. They are not new
            // experiences; archiving every growing prefix duplicated the same dream many times.
            fun calendarMeaning(text: String): String = JSONObject(text).apply {
                remove("version"); remove("pending_notice")
                optJSONArray("items")?.let { items -> for (i in 0 until items.length()) {
                    items.getJSONObject(i).remove("notice_keys"); items.getJSONObject(i).remove("started"); items.getJSONObject(i).remove("activity_ids")
                } }
            }.toString()
            val calendarDeliveryOnly = kind == "private_time_plan" && before != null &&
                calendarMeaning(before) == calendarMeaning(row.toString())
            val checkpoint = calendarDeliveryOnly || (kind == "dream_step" && row.optString("status") in setOf("requested", "partial")) ||
                (kind == "dream_process" && row.optString("status") == "running")
            if (!checkpoint) put("category_versions", row)
            if (old != null && (kind.startsWith("private_time_") || kind in setOf("life_event", "message_inbox", "life_activity", "dream_process", "dream_step", "life_run"))) {
                db.execSQL("UPDATE category_current SET parent=?,payload=? WHERE scope=? AND kind=? AND id=?",
                    arrayOf(parent, row.toString(), scope, kind, id))
            } else db.execSQL("INSERT OR REPLACE INTO category_current(scope,kind,id,parent,payload) VALUES(?,?,?,?,?)",
                arrayOf(scope, kind, id, parent, row.toString()))
            if (kind in SubjectRecall.kinds) synchronized(recallCache) { recallCache[file.absolutePath to scope]?.remove(kind) }
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
        val orderedQueue = kind in setOf("life_event", "message_inbox", "life_activity", "dream_process", "dream_step")
        val order = if (privateRecent) "rowid DESC" else if (orderedQueue) "rowid" else "id"
        val index = if (privateRecent || (orderedQueue && actualParent != null)) " INDEXED BY category_recent" else ""
        return db().rawQuery("SELECT payload FROM category_current$index WHERE scope=? AND kind=?$filter ORDER BY $order LIMIT ? OFFSET ?",
            args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }
    }

    fun activityQueuePage(kind: String, parents: List<String>, offset: Int): List<JSONObject> {
        require(parents.isNotEmpty() && offset >= 0)
        if (!file.exists()) return emptyList()
        val placeholders = parents.joinToString(",") { "?" }
        return db().rawQuery("SELECT payload FROM category_current WHERE scope=? AND kind=? AND parent IN ($placeholders) ORDER BY rowid LIMIT 9 OFFSET ?",
            (listOf(scope, kind) + parents + offset.toString()).toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) }
        }
    }

    fun latestCategoryRow(kind: String, parent: String): JSONObject? {
        if (!file.exists()) return null
        return db().rawQuery("SELECT payload FROM category_current INDEXED BY category_recent WHERE scope=? AND kind=? AND parent=? ORDER BY rowid DESC LIMIT 1",
            arrayOf(scope, kind, parent)).use { c -> if (c.moveToFirst()) JSONObject(c.getString(0)) else null }
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

    /** Current heads only, with a bounded disposable index. New versions invalidate it;
     * full source bindings/history never enter the cache or the model material budget. */
    fun relevantSubjectRows(kind: String, input: String, chat: String, limit: Int): List<JSONObject> {
        require(kind in SubjectRecall.kinds && limit in 1..8)
        if (input.isBlank() || !file.exists()) return emptyList()
        val database = db()
        val revision = database.rawQuery("SELECT COUNT(*),COALESCE(MAX(rowid),0) FROM category_current WHERE scope=? AND kind=?",
            arrayOf(scope, kind)).use { c -> c.moveToFirst(); c.getLong(0) to c.getLong(1) }
        val key = file.absolutePath to scope
        val cached = synchronized(recallCache) { recallCache[key]?.get(kind)?.takeIf { it.revision == revision } }
        val ids = if (cached != null) SubjectRecall.ranked(cached.entries.asSequence(), input, chat, limit) else {
            val entries = mutableListOf<SubjectRecall.Entry>()
            var weight = 0L
            var retain = true
            val matches = database.rawQuery("SELECT payload FROM category_current WHERE scope=? AND kind=? ORDER BY id",
                arrayOf(scope, kind)).use { cursor ->
                val stream = sequence {
                    while (cursor.moveToNext()) {
                        val row = JSONObject(cursor.getString(0))
                        if (kind !in setOf("union", "episode", "dream_process") && row.getString("parent") != "private") continue
                        val entry = SubjectRecall.entry(row) ?: continue
                        if (retain) {
                            weight += entry.weight
                            if (weight > INNER_INDEX_CACHE_WEIGHT) { entries.clear(); retain = false }
                            else entries += entry
                        }
                        yield(entry)
                    }
                }
                SubjectRecall.ranked(stream, input, chat, limit)
            }
            synchronized(recallCache) {
                if (retain) {
                    val subject = recallCache.getOrPut(key) { LinkedHashMap(8, 0.75f, true) }
                    subject[kind] = RecallIndex(revision, entries.toList(), weight)
                    // Six small domains must not evict one another merely because they
                    // were read in one request. Keep the old aggregate four-MiB weight
                    // bound, grouped by subject, with an LRU within each subject too.
                    while (subject.values.sumOf { it.weight } > INNER_INDEX_CACHE_WEIGHT) {
                        subject.remove(subject.keys.first())
                    }
                } else recallCache[key]?.remove(kind)
            }
            matches
        }
        return ids.mapNotNull { categoryRow(kind, it) }
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
        private data class RecallIndex(val revision: Pair<Long, Long>, val entries: List<SubjectRecall.Entry>, val weight: Long)
        private val recallCache = object : LinkedHashMap<Pair<String, String>, LinkedHashMap<String, RecallIndex>>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, LinkedHashMap<String, RecallIndex>>?) = size > 4
        }
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
