package com.ai.assistance.operit.core.experience

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.core.tools.javascript.JsEngine
import com.ai.assistance.operit.data.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Local editable collections. The message is a snapshot; edits never rewrite the source chat. */
class CollectionStore(private val context: Context) {
    private val root = File(context.filesDir, "experience_collections").apply { mkdirs() }
    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id) { "无效收藏ID" }
        return File(root, "$id.json")
    }
    fun list(): List<JSONObject> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.extension == "json" }.map { read(it.nameWithoutExtension) }
            .sortedByDescending { it.getLong("updatedAt") }
    }
    fun read(id: String): JSONObject = synchronized(lock) {
        JSONObject(AtomicFile(file(id)).openRead().bufferedReader().use { it.readText() })
    }
    fun create(role: String, chat: String, message: ChatMessage): String = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        write(JSONObject().put("id", id).put("role", role).put("chat", chat).put("message", message.timestamp)
            .put("title", message.content.lineSequence().firstOrNull().orEmpty().take(60).ifBlank { "收藏" })
            .put("tags", "").put("content", message.content).put("files", JSONObject())
            .put("toolEnabled", false).put("toolState", JSONObject()).put("previewState", JSONObject())
            .put("createdAt", System.currentTimeMillis()))
        id
    }
    fun edit(id: String, title: String, tags: String, content: String, expectedVersion: Long): JSONObject = synchronized(lock) {
        val item = read(id); check(item.getLong("version") == expectedVersion) { "收藏已被修改，请重新打开" }
        require(title.isNotBlank() && title.length <= 200)
        write(item.put("title", title).put("tags", tags).put("content", content))
    }
    fun promote(id: String): JSONObject = synchronized(lock) {
        val item = read(id)
        check(item.getJSONObject("files").length() == 0) { "已是项目" }
        val files = item.getJSONObject("files")
        val blocks = Regex("```([^\\n]*)\\n([\\s\\S]*?)```").findAll(item.getString("content")).toList()
        blocks.forEachIndexed { i, match ->
            val ext = when (match.groupValues[1].trim().lowercase()) {
                "html" -> "html"; "css" -> "css"; "js", "javascript" -> "js"; "json" -> "json"; else -> "txt"
            }
            val name = if (i == 0 && ext == "html") "index.html" else "snippet-${i + 1}.$ext"
            files.put(name, match.groupValues[2])
        }
        if (blocks.isEmpty()) files.put("README.md", item.getString("content"))
        write(item)
    }
    fun saveFile(id: String, path: String, content: String, expectedVersion: Long): JSONObject = synchronized(lock) {
        validatePath(path)
        val item = read(id); check(item.getLong("version") == expectedVersion) { "项目已被修改，请重新打开" }
        item.getJSONObject("files").put(path, content)
        write(item)
    }
    fun enableTool(id: String, enabled: Boolean): JSONObject = synchronized(lock) {
        val item = read(id)
        if (enabled) require(item.getJSONObject("files").has("tool.js")) { "请先添加 tool.js" }
        write(item.put("toolEnabled", enabled))
    }
    fun importFiles(id: String, files: JSONObject, expectedVersion: Long): JSONObject = synchronized(lock) {
        for (path in files.keys()) { validatePath(path); require(files.get(path) is String) { "项目文件必须是文本" } }
        val item = read(id)
        check(item.getLong("version") == expectedVersion) { "项目已变化，请重新打开后导入" }
        // Importing executable source revokes prior consent until the user enables it again.
        write(item.put("files", files).put("toolEnabled", false))
    }
    fun savePreview(id: String, snapshot: String) = synchronized(lock) {
        require(snapshot.length <= 1_000_000) { "预览状态过大" }
        val item = read(id)
        if (item.getJSONObject("previewState").toString() != snapshot) write(item.put("previewState", JSONObject(snapshot)))
    }
    fun execute(id: String, role: String, payload: JSONObject): JSONObject {
        val before = read(id)
        check(before.getString("role") == role && before.getBoolean("toolEnabled")) { "此助手未获准运行该收藏工具" }
        val script = before.getJSONObject("files").getString("tool.js")
        val engine = JsEngine(context)
        val result = try {
            engine.executeScriptCode(
                script = "const state = JSON.parse(params.savedState); const input = JSON.parse(params.input);\n" +
                    "const result = await (async function(input, state) {\n$script\n})(input, state);\n" +
                    "return JSON.stringify({result: result === undefined ? null : result, state});",
                params = mapOf("savedState" to before.getJSONObject("toolState").toString(), "input" to payload.toString()),
                timeoutSec = 30,
            )
        } finally { engine.destroy() }
        val text = result.toString()
        check(!text.startsWith("Error:", ignoreCase = true)) { text }
        val response = JSONObject(text)
        return synchronized(lock) {
            val item = read(id)
            check(item.getJSONObject("files").toString() == before.getJSONObject("files").toString() && item.getJSONObject("toolState").toString() == before.getJSONObject("toolState").toString() && item.getBoolean("toolEnabled")) { "执行期间项目已修改，未覆盖状态；副作用可能已发生，请先检查" }
            write(item.put("toolState", response.getJSONObject("state")))
            response
        }
    }
    fun references(id: String, query: String = ""): JSONArray = synchronized(lock) {
        val item = read(id)
        val rows = item.optJSONArray("references") ?: JSONArray()
        val result = JSONArray()
        for (i in 0 until rows.length()) {
            val ref = rows.getJSONObject(i)
            if (query.isBlank() || (ref.getString("title") + "\n" + ref.getString("content")).contains(query, ignoreCase = true)) result.put(ref)
        }
        result
    }
    fun addReference(id: String, sourceId: String, path: String?, expectedVersion: Long): JSONObject = synchronized(lock) {
        val item = read(id); val source = read(sourceId)
        check(item.getLong("version") == expectedVersion) { "项目已变化，请重新打开" }
        check(item.getString("role") == source.getString("role")) { "不能引用其他助手的收藏" }
        if (path != null) validatePath(path)
        val content = if (path == null) source.getString("content") else source.getJSONObject("files").getString(path)
        val ref = JSONObject().put("id", UUID.randomUUID().toString())
            .put("title", if (path == null) source.getString("title") else path).put("content", content)
            .put("sourceId", sourceId).put("sourcePath", path ?: JSONObject.NULL)
            .put("sourceVersion", source.getLong("version")).put("chat", source.getString("chat"))
            .put("message", source.getLong("message")).put("capturedAt", System.currentTimeMillis())
        val refs = item.optJSONArray("references") ?: JSONArray()
        refs.put(ref); write(item.put("references", refs))
    }
    fun removeReference(id: String, referenceId: String, expectedVersion: Long): JSONObject = synchronized(lock) {
        val item = read(id); check(item.getLong("version") == expectedVersion) { "项目已变化，请重新打开" }
        val refs = references(id); val kept = JSONArray()
        for (i in 0 until refs.length()) if (refs.getJSONObject(i).getString("id") != referenceId) kept.put(refs.getJSONObject(i))
        write(item.put("references", kept))
    }
    fun promoteReference(id: String, referenceId: String, path: String, expectedVersion: Long): JSONObject = synchronized(lock) {
        validatePath(path)
        val item = read(id); check(item.getLong("version") == expectedVersion) { "项目已变化，请重新打开" }
        check(!item.getJSONObject("files").has(path)) { "目标文件已存在，请填写新路径，避免覆盖" }
        val refs = references(id)
        val ref = (0 until refs.length()).map { refs.getJSONObject(it) }.single { it.getString("id") == referenceId }
        item.getJSONObject("files").put(path, ref.getString("content"))
        write(item)
    }
    fun exportWorkspace(id: String, expectedVersion: Long): JSONObject = synchronized(lock) {
        val item = read(id); check(item.getLong("version") == expectedVersion) { "项目已变化，请重新打开" }
        check(item.getJSONObject("files").length() > 0) { "请先添加项目文件" }
        val directory = File(context.filesDir, "collection_workspaces/$id/" + UUID.randomUUID().toString())
        check(directory.mkdirs()) { "无法创建工作区" }
        try {
            val files = item.getJSONObject("files")
            for (path in files.keys()) {
                validatePath(path)
                val file = File(directory, path); file.parentFile!!.mkdirs(); file.writeText(files.getString(path))
            }
            write(item.put("workspace", JSONObject().put("path", directory.absolutePath)
                .put("baseFiles", JSONObject(files.toString())).put("exportedAt", System.currentTimeMillis())))
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }
    fun pullWorkspace(id: String, expectedVersion: Long): JSONObject = synchronized(lock) {
        val item = read(id); check(item.getLong("version") == expectedVersion) { "项目已变化，请重新打开" }
        val binding = item.getJSONObject("workspace")
        val directory = File(binding.getString("path"))
        val ownedRoot = File(context.filesDir, "collection_workspaces/$id").canonicalFile
        check(directory.canonicalFile.parentFile == ownedRoot && directory.isDirectory) { "工作区已移走或不可访问" }
        check(WorkspaceTransfer.sameFiles(item.getJSONObject("files"), binding.getJSONObject("baseFiles"))) {
            "收藏文件与导出时不同，停止回收以免覆盖。请导出收藏的新副本，再手动合并两边修改。"
        }
        val files = WorkspaceTransfer.read(directory)
        // Replacing executable text revokes the old approval. The original directory remains intact.
        binding.put("baseFiles", JSONObject(files.toString())).put("pulledAt", System.currentTimeMillis())
        write(item.put("files", files).put("toolEnabled", false))
    }
    fun delete(id: String) = synchronized(lock) { AtomicFile(file(id)).delete(); revision.value++ }
    private fun write(item: JSONObject): JSONObject {
        item.put("updatedAt", System.currentTimeMillis()).put("version", item.optLong("version") + 1)
        val bytes = item.toString().toByteArray()
        require(bytes.size <= 4_000_000) { "单个收藏项目不能超过4MB" }
        val atomic = AtomicFile(file(item.getString("id")))
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        revision.value++
        return item
    }
    companion object {
        private val lock = Any()
        val revision = MutableStateFlow(0L)
        fun validatePath(path: String) {
            require(path.isNotBlank() && path.length <= 200 && !path.startsWith('/') &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
                path.none { it == '\\' || it == '\u0000' || it == ':' }) { "请使用项目内相对路径" }
        }
    }
}
