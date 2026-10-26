package com.ai.assistance.operit.core.experience

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.*
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipFile

/** Reads the documented Kelivo ZIP data shapes; original archive remains available for audit. */
class KelivoImport(private val context: Context) {
    data class Plan(val archive: File, val digest: String, val settings: JSONObject, val chats: JSONObject, val summary: String)
    private fun setting(settings: JSONObject, key: String): Any? {
        val value = settings.opt(key)
        return if (value is String && value.trim().startsWith("[")) JSONArray(value)
        else if (value is String && value.trim().startsWith("{")) JSONObject(value) else value
    }
    suspend fun inspect(uri: Uri): Plan = withContext(Dispatchers.IO) {
        val archive = File.createTempFile("kelivo-", ".zip", context.cacheDir)
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> archive.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 256L * 1024 * 1024) { "备份超过256MB" }; output.write(buffer, 0, n) }
            } }
            val digest = MessageDigest.getInstance("SHA-256").let { hash -> archive.inputStream().use { input ->
                val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }; hash.digest().joinToString("") { "%02x".format(it) } }
            ZipFile(archive).use { zip ->
                fun read(name: String): JSONObject {
                    val entry = requireNotNull(zip.getEntry(name)) { "缺少 $name，未识别为Kelivo备份" }
                    require(entry.size in 0..20_000_000) { "$name 过大" }
                    return JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                }
                val settings = read("settings.json"); val chats = read("chats.json")
                val roles = setting(settings, "assistants_v1") as? JSONArray ?: JSONArray()
                val roleIds = (0 until roles.length()).map { roles.getJSONObject(it).getString("id") }.toSet()
                val conversations = chats.getJSONArray("conversations")
                for (i in 0 until conversations.length()) require(conversations.getJSONObject(i).getString("assistantId") in roleIds) { "存在找不到所属助手的会话，请先修复原备份" }
                Plan(archive, digest, settings, chats, "助手 ${roles.length()} 位；会话 ${conversations.length()} 个；消息 ${chats.getJSONArray("messages").length()} 条。\n导入会新建助手与模型配置，不覆盖已有数据。附件、背景和原备份会保留。MCP导入为关闭状态，需自行启用。\nKelivo专用界面布局、工具执行历史、预设消息模板不转换；原始字段保存在备份中。")
            }
        } catch (e: Exception) { archive.delete(); throw e }
    }
    suspend fun apply(plan: Plan): String = withContext(Dispatchers.IO) {
        val ledger = context.getSharedPreferences("kelivo_import_ledger", Context.MODE_PRIVATE)
        check(!ledger.getBoolean(plan.digest, false)) { "这份备份已经导入" }
        val root = File(context.filesDir, "kelivo_imports/${plan.digest}").apply { mkdirs() }
        plan.archive.copyTo(File(root, "original.zip"), overwrite = true)
        val assets = mutableMapOf<String, File>()
        ZipFile(plan.archive).use { zip ->
            var total = 0L
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                CollectionStore.validatePath(entry.name)
                require(entry.size >= 0 && entry.size <= 64L * 1024 * 1024) { "备份条目过大" }
                total += entry.size; require(total <= 512L * 1024 * 1024) { "解压数据过大" }
                val file = File(root, "archive/${entry.name}"); file.parentFile!!.mkdirs()
                zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }; assets[entry.name] = file
            }
        }
        val warnings = mutableListOf<String>()
        val models = ModelConfigManager(context)
        val configIds = mutableMapOf<String, String>()
        val providers = setting(plan.settings, "provider_configs_v1") as? JSONObject ?: JSONObject()
        for (key in providers.keys()) {
            val source = providers.getJSONObject(key)
            val name = "Kelivo ${source.optString("name", key)} [${plan.digest.take(8)}]"
            val existing = models.getAllConfigSummaries().singleOrNull { it.name == name }
            val id = existing?.id ?: models.createConfig(name)
            val modelNames = source.optJSONArray("models") ?: JSONArray()
            val model = (0 until modelNames.length()).map { modelNames.getString(it) }.joinToString(",")
            val declared = source.optString("providerType").lowercase()
            val provider = when {
                source.optBoolean("useResponseApi") -> ApiProviderType.OPENAI_RESPONSES_GENERIC
                declared in setOf("gemini", "google") -> ApiProviderType.GEMINI_GENERIC
                declared in setOf("anthropic", "claude") -> ApiProviderType.ANTHROPIC_GENERIC
                else -> ApiProviderType.OPENAI_GENERIC
            }
            var endpoint = source.getString("baseUrl").trimEnd('/')
            val path = source.optString("chatPath")
            if (path.isNotBlank() && !endpoint.endsWith(path)) endpoint += "/" + path.trimStart('/')
            if (path.isBlank() && provider == ApiProviderType.OPENAI_GENERIC && !endpoint.endsWith("/chat/completions")) endpoint += "/chat/completions"
            if (path.isBlank() && provider == ApiProviderType.ANTHROPIC_GENERIC && !endpoint.endsWith("/messages")) endpoint += "/messages"
            if (path.isBlank() && provider == ApiProviderType.OPENAI_RESPONSES_GENERIC && !endpoint.endsWith("/responses")) endpoint += "/responses"
            models.saveModelConfig(ModelConfigData(id = id, name = name, apiKey = source.optString("apiKey"), apiEndpoint = endpoint, modelName = model, apiProviderType = provider))
            configIds[key] = id
            if (source.has("apiKeys")) warnings.add("${source.optString("name", key)} 的多密钥池需在模型设置检查；仅导入主密钥")
        }
        val cards = CharacterCardManager.getInstance(context)
        val roleIds = mutableMapOf<String, String>()
        val assistants = setting(plan.settings, "assistants_v1") as? JSONArray ?: JSONArray()
        for (i in 0 until assistants.length()) {
            val source = assistants.getJSONObject(i); val oldId = source.getString("id")
            val marker = "kelivo:${plan.digest}:$oldId"
            val existing = cards.getAllCharacterCards().singleOrNull { it.marks == marker }
            val id = existing?.id ?: cards.createCharacterCard(CharacterCard(id = "", name = source.getString("name"), characterSetting = source.optString("systemPrompt"), marks = marker, description = "Kelivo 导入"))
            roleIds[oldId] = id
            configIds[source.optString("chatModelProvider")]?.let { config ->
                val model = requireNotNull(models.getModelConfig(config))
                val requested = source.optString("chatModelId")
                val index = model.modelName.split(',').indexOf(requested)
                if (index >= 0) cards.selectChatModel(id, config, index)
                else warnings.add("${source.getString("name")} 的模型绑定请在设置中选择")
            }
            val background = source.optString("background").trimStart('/')
            assets[background]?.let { image ->
                val target = ActivePrompt.CharacterCard(id)
                val current = ThemeStudio(context).current(target)
                ActivePromptManager.getInstance(context).commitThemeDraft(target, current.withString("background_image_uri", Uri.fromFile(image).toString()).withBoolean("use_background_image", true))
            }
            val worldbook = JSONArray()
            val books = setting(plan.settings, "world_books_v1") as? JSONArray ?: JSONArray()
            val activeBooks = setting(plan.settings, "world_books_active_ids_by_assistant_v1") as? JSONObject
            val selectedBooks = if (activeBooks?.has(oldId) == true) activeBooks.optJSONArray(oldId) else activeBooks?.optJSONArray("__global__")
            val selectedIds = if (selectedBooks == null) emptySet() else (0 until selectedBooks.length()).map { selectedBooks.getString(it) }.toSet()
            for (b in 0 until books.length()) {
                val book = books.getJSONObject(b)
                if (book.getString("id") !in selectedIds || !book.optBoolean("enabled", true)) continue
                val entries = book.optJSONArray("entries") ?: continue
                for (e in 0 until entries.length()) {
                    val entry = entries.getJSONObject(e)
                    if (!entry.optBoolean("enabled", true) || entry.optString("content").isBlank()) continue
                    val keys = when (val raw = entry.opt("keywords")) {
                        is JSONArray -> (0 until raw.length()).map { raw.getString(it) }
                        is String -> raw.split(Regex("[\\n,，]+"))
                        else -> emptyList()
                    }.filter { it.isNotBlank() }
                    val always = entry.optBoolean("constantActive")
                    if (!always && keys.isEmpty()) continue
                    val pattern = keys.joinToString("|") { if (entry.optBoolean("useRegex")) it else Regex.escape(it) }
                    worldbook.put(JSONObject().put("pattern", (if (entry.optBoolean("caseSensitive")) "" else "(?i)") + pattern)
                        .put("text", entry.getString("content")).put("always", always).put("depth", entry.optInt("scanDepth", 6).coerceIn(1, 100)).put("priority", entry.optInt("priority")))
                }
            }
            if (worldbook.length() > 0 && RoleRules(context).read(id).length() == 0) RoleRules(context).save(id, JSONObject().put("worldbook", worldbook))
            val memories = setting(plan.settings, "assistant_memories_v1") as? JSONArray ?: JSONArray()
            val library = com.ai.assistance.operit.core.companion.CompanionStore(context)
            for (m in 0 until memories.length()) {
                val memory = memories.getJSONObject(m)
                if (memory.optString("assistantId") != oldId || memory.optString("content").isBlank()) continue
                val sourceTag = "kelivo:${plan.digest}:memory:$m"
                if (library.entries("role:$id").any { it.optString("title") == sourceTag }) continue
                val text = memory.getString("content")
                if (text.length <= 12000) library.saveEntry("role:$id", null, sourceTag, "Kelivo记忆", "knowledge", text, null)
                else warnings.add("${source.getString("name")} 的超长记忆 $m 保留在原备份中，未转换为资料")
            }
            if (source.has("regexRules")) warnings.add("${source.getString("name")} 的Kelivo文字替换规则需在角色规则面板检查后重建")
        }
        val messages = plan.chats.getJSONArray("messages")
        val byChat = (0 until messages.length()).map { messages.getJSONObject(it) }.groupBy { it.getString("conversationId") }
        val history = ChatHistoryManager.getInstance(context)
        val conversations = plan.chats.getJSONArray("conversations")
        var imported = 0
        for (i in 0 until conversations.length()) {
            val source = conversations.getJSONObject(i)
            val oldId = source.getString("id")
            val id = UUID.nameUUIDFromBytes((plan.digest + oldId).toByteArray()).toString()
            if (history.getChatMetadata(id) != null) continue
            val role = requireNotNull(roleIds[source.getString("assistantId")])
            val order = source.optJSONArray("messageIds")
            val ranks = if (order == null) emptyMap() else (0 until order.length()).associate { order.getString(it) to it }
            var stamp = 0L
            val converted = byChat[oldId].orEmpty().sortedWith(compareBy<JSONObject> { ranks[it.getString("id")] ?: Int.MAX_VALUE }.thenBy { timestamp(it.opt("timestamp")) }).map { row ->
                val sender = when (row.getString("role")) { "user" -> "user"; "assistant" -> "ai"; "system" -> "system"; else -> error("不支持的消息类型：${row.getString("role")}") }
                stamp = maxOf(timestamp(row.opt("timestamp")), stamp + 1)
                var content = row.optString("content")
                assets.forEach { (path, file) -> if (path.startsWith("upload/")) content = content.replace(path, Uri.fromFile(file).toString()) }
                val reasoning = row.optString("reasoningText")
                if (reasoning.isNotBlank()) content = "<think>$reasoning</think>\n$content"
                ChatMessage(sender = sender, content = content, timestamp = stamp, roleName = if (sender == "ai") cards.getCharacterCard(role).name else "",
                    modelName = row.optString("modelId"), provider = row.optString("providerId"), inputTokens = row.optLong("promptTokens"), outputTokens = row.optLong("completionTokens"))
            }
            val created = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp(source.opt("createdAt"))), ZoneId.systemDefault())
            history.saveChatHistory(ChatHistory(id = id, title = source.optString("title", "Kelivo 对话"), messages = converted,
                createdAt = created, updatedAt = created, characterCardId = role, characterCardName = cards.getCharacterCard(role).name, pinned = source.optBoolean("isPinned")))
            imported++
        }
        val mcp = setting(plan.settings, "mcp_servers_v1") as? JSONArray ?: JSONArray()
        val servers = JSONObject()
        for (i in 0 until mcp.length()) {
            val server = mcp.getJSONObject(i); val transport = server.optString("transport")
            if (transport !in setOf("http", "streamable-http", "sse")) { warnings.add("MCP ${server.optString("name")} 的传输方式未转换"); continue }
            servers.put("kelivo_${plan.digest.take(8)}_$i", JSONObject().put("type", if (transport == "sse") "sse" else "streamable_http")
                .put("url", server.getString("url")).put("headers", server.optJSONObject("headers") ?: JSONObject()).put("disabled", true))
        }
        if (servers.length() > 0) com.ai.assistance.operit.data.mcp.MCPLocalServer.getInstance(context).mergeConfigFromJson(JSONObject().put("mcpServers", servers).toString()).getOrThrow()
        check(ledger.edit().putBoolean(plan.digest, true).commit())
        "导入完成：${roleIds.size} 位助手、$imported 个会话、${configIds.size} 个模型配置、${servers.length()} 个关闭状态的MCP。\n原备份已保留。\n" + warnings.joinToString("\n")
    }
    companion object {
        fun timestamp(value: Any?): Long {
            if (value is Number) return value.toLong().let { if (it < 10_000_000_000L) it * 1000 else it }
            if (value is String) { value.toLongOrNull()?.let { return timestamp(it) }; return Instant.parse(value).toEpochMilli() }
            throw IllegalArgumentException("备份缺少有效时间戳")
        }
    }
}
