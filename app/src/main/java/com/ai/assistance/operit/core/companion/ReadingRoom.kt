package com.ai.assistance.operit.core.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Reading source, delivered excerpt and personal note are distinct fields with distinct provenance. */
class ReadingRoom(private val context: Context, private val role: String) {
    private val store = CompanionStore(context)
    private val scope = "reading:$role"
    init { require(role.isNotBlank()) }
    fun sources() = store.read(scope).optJSONArray("sources").objects()
    fun entries(history: Boolean = false): List<JSONObject> {
        val now = System.currentTimeMillis()
        return store.read(scope).optJSONArray("entries").objects().filter {
            if (history) it.optString("state") in setOf("read", "saved") else it.optString("state") == "queued" && it.optLong("expires") > now
        }.sortedByDescending { it.optLong("added") }
    }
    fun saveSource(id: String?, title: String, kind: String, address: String, hours: Int, enabled: Boolean, parent: String? = null): JSONObject {
        require(title.length in 1..160 && hours in 1..168 && kind in setOf("rss", "topic")) { "请填写来源名称、类型和1–168小时的间隔" }
        require(address.length in 2..1500)
        if (kind == "rss") address.toHttpUrl().also { require(it.scheme in setOf("http", "https") && it.username.isEmpty() && it.password.isEmpty()) }
        val sourceId = id ?: UUID.randomUUID().toString()
        lateinit var result: JSONObject
        store.update(scope) { data ->
            val sources = data.optJSONArray("sources").objects().toMutableList()
            val index = sources.indexOfFirst { it.getString("id") == sourceId }
            require(id == null || index >= 0) { "来源不存在" }
            require(index >= 0 || sources.size < 60) { "来源最多60个" }
            result = JSONObject().put("id", sourceId).put("title", title).put("kind", kind).put("address", address)
                .put("hours", hours).put("enabled", enabled).put("checked", if (index < 0) 0L else sources[index].optLong("checked"))
            // 学习进度和延伸来源跟着来源走，编辑开关不丢
            val previous = if (index < 0) null else sources[index]
            if (previous != null && previous.optString("progress").isNotBlank()) result.put("progress", previous.optString("progress")).put("progress_at", previous.optLong("progress_at"))
            val origin = parent?.takeIf { it.isNotBlank() } ?: previous?.optString("parent")?.takeIf { it.isNotBlank() }
            if (origin != null) {
                if (origin.startsWith("chat:")) {
                    // 枝芽：从某天的聊天长出来的来源
                    require(BranchSprout.validOrigin(origin)) { "聊天来源写成 chat:YYYY-MM-DD" }
                    if (kind == "topic") BranchSprout.privacyProblem(address)?.let { throw IllegalArgumentException(it) }
                } else {
                    require(origin != sourceId && sources.any { it.getString("id") == origin }) { "延伸自的来源不存在" }
                }
                result.put("parent", origin)
            }
            if (index < 0) sources.add(result) else sources[index] = result
            data.put("sources", JSONArray(sources))
        }
        AssistantRoom.revision.updateValue(); return result
    }

    fun add(title: String, url: String, summary: String, source: String = "manual"): JSONObject = synchronized(lock) {
        require(title.length in 1..200 && summary.length <= 12000)
        val normalized = url.toHttpUrl().newBuilder().fragment(null).build().toString()
        val id = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
        val existing = store.read("reading-entry:$role:$id")
        if (existing.has("id")) return@synchronized existing
        val now = System.currentTimeMillis()
        val result = JSONObject().put("id", id).put("owner", role).put("title", title).put("url", normalized)
            .put("summary", summary).put("source", source).put("added", now).put("expires", now + 7 * 86_400_000L)
            .put("state", "queued").put("material", "summary").put("note", "")
        store.update("reading-entry:$role:$id") { data -> result.keys().forEach { data.put(it, result.get(it)) } }
        index(result)
        return@synchronized result
    }

    private fun index(entry: JSONObject) {
        store.update(scope) { data ->
            val old = data.optJSONArray("entries").objects().filter { it.getString("id") != entry.getString("id") }
            val brief = JSONObject(entry.toString()).also { it.remove("body"); it.remove("summary"); it.remove("note"); it.remove("receipts") }
            val all = old + brief
            // Only the pending queue expires; read materials and notes remain on disk and in history.
            val history = all.filter { it.optString("state") in setOf("read", "saved") }
            val queue = all.filter { it.optString("state") == "queued" && it.optLong("expires") > System.currentTimeMillis() }.takeLast(200)
            data.put("entries", JSONArray(history + queue))
        }
        AssistantRoom.revision.updateValue()
    }
    fun entry(id: String): JSONObject = store.read("reading-entry:$role:$id").also { check(it.has("id")) { "资料不存在或不属于当前助手" } }
    fun open(id: String, part: String, ai: Boolean): JSONObject = synchronized(lock) {
        require(part in setOf("summary", "body"))
        val data = entry(id)
        check(data.has(part)) { "尚未取得正文，请先 fetch；当前只有来源摘要" }
        if (ai) {
            store.update("reading-entry:$role:$id") {
                val level = if (part == "summary" && it.optString("delivered") == "partial") "partial"
                    else if (part == "summary") "summary" else it.getString("material")
                it.put("delivered", level).put("delivered_at", System.currentTimeMillis())
                if (it.optString("state") != "saved") it.put("state", "read")
            }.also { index(it) }
        }
        JSONObject().put("id", id).put("owner", role).put("title", data.getString("title")).put("url", data.getString("url"))
            .put("level", if (part == "summary") "summary" else data.getString("material")).put("content", data.getString(part))
            .put("note", data.optString("note")).put("fetched_at", data.optLong("fetched"))
    }
    fun note(id: String, text: String, ai: Boolean): JSONObject = synchronized(lock) {
        require(text.length <= 6000)
        val data = entry(id)
        if (ai) check(data.has("delivered_at")) { "先打开实际材料，再记录自己的随记" }
        store.update("reading-entry:$role:$id") {
            // User annotations are separate from the assistant's own reading reflection.
            it.put(if (ai) "note" else "user_note", text).put("state", "saved").put("noted_at", System.currentTimeMillis())
        }.also { index(it) }
    }
    fun skip(id: String) = synchronized(lock) {
        check(entry(id).optString("state") == "queued") { "已读材料与随记保留在历史中，无需从待读移开" }
        store.update("reading-entry:$role:$id") { it.put("state", "skipped") }.also { index(it) }
    }
    /** 自己写的学习进度：学到哪了、下一步想看什么。覆盖写，只保留最新一份。 */
    fun progress(id: String, text: String): JSONObject {
        require(text.isNotBlank() && text.length <= 2000) { "进度须为1–2000字" }
        lateinit var result: JSONObject
        store.update(scope) { data ->
            val sources = data.optJSONArray("sources").objects()
            val source = sources.firstOrNull { it.getString("id") == id } ?: error("来源不存在")
            source.put("progress", text.trim()).put("progress_at", System.currentTimeMillis())
            result = source
            data.put("sources", JSONArray(sources))
        }
        AssistantRoom.revision.updateValue(); return result
    }
    fun checked(id: String) {
        store.update(scope) { data ->
            val sources = data.optJSONArray("sources").objects()
            val source = sources.firstOrNull { it.getString("id") == id } ?: error("来源不存在")
            source.put("checked", System.currentTimeMillis())
            data.put("sources", JSONArray(sources))
        }
    }
    fun due() = sources().filter { it.optBoolean("enabled") && it.optLong("checked") + it.getInt("hours") * 3_600_000L <= System.currentTimeMillis() }

    private fun authorizeNetwork(ai: Boolean, chat: String) {
        if (!ai || !CompanionRuntime.isExplorationWake(chat)) return
        val exploration = ExplorationStore(context).read(role)
        check(ExplorationPolicy.enabled(exploration) && "web" in ExplorationPolicy.choices(exploration)) { "本轮探索未授权上网" }
    }
    suspend fun refresh(id: String, ai: Boolean, chat: String): JSONObject = withContext(Dispatchers.IO) {
        authorizeNetwork(ai, chat)
        val source = sources().firstOrNull { it.getString("id") == id } ?: error("来源不存在")
        check(source.optBoolean("enabled") && source.getString("kind") == "rss") { "此来源已暂停或是需要通过搜索工具探索的话题" }
        val (url, xml) = download(source.getString("address"))
        currentCoroutineContext().ensureActive()
        authorizeNetwork(ai, chat)
        val feed = Jsoup.parse(xml, url, Parser.xmlParser())
        check(feed.select("rss, feed, RDF").isNotEmpty()) { "该地址没有返回 RSS 或 Atom" }
        var count = 0
        feed.select("item, entry").take(20).forEach { item ->
            val link = item.selectFirst("link[href]")?.attr("href") ?: item.selectFirst("link")?.text().orEmpty()
            val resolved = url.toHttpUrl().resolve(link)?.toString()
            val title = item.selectFirst("title")?.text().orEmpty().take(200)
            if (resolved != null && title.isNotBlank()) {
                val summary = Jsoup.parse(item.selectFirst("description, summary, content")?.text().orEmpty()).text().take(12000)
                add(title, resolved, summary, source.getString("id")); count++
            }
        }
        checked(id)
        JSONObject().put("items_seen", count).put("source", id).put("message", "新条目已去重放入待读；未自动发消息，也未标记助手已读")
    }
    suspend fun fetch(id: String, ai: Boolean, chat: String): JSONObject = withContext(Dispatchers.IO) {
        authorizeNetwork(ai, chat)
        val entry = entry(id)
        val (url, html) = download(entry.getString("url"))
        currentCoroutineContext().ensureActive()
        authorizeNetwork(ai, chat)
        val document = Jsoup.parse(html, url)
        document.select("script,style,nav,header,footer,aside").remove()
        val body = document.selectFirst("article,main")?.text() ?: document.body().text()
        check(body.isNotBlank()) { "网页未提供可读取的正文" }
        synchronized(lock) {
            store.update("reading-entry:$role:$id") {
                // A parsed page may omit dynamic/paywalled content; never label the original work fully read.
                it.put("body", body.take(16000)).put("material", "partial").put("fetched", System.currentTimeMillis()).put("resolved_url", url)
            }
            open(id, "body", ai)
        }
    }
    private fun download(address: String): Pair<String, String> {
        var url = address.toHttpUrl()
        repeat(5) {
            require(url.username.isEmpty() && url.password.isEmpty()) { "来源地址不能带账号密码" }
            client.newCall(Request.Builder().url(url).header("User-Agent", "VioLife/Reading").build()).execute().use { response ->
                if (response.code in 300..399) {
                    url = url.resolve(requireNotNull(response.header("Location"))) ?: error("无效的跳转地址")
                } else {
                    check(response.isSuccessful) { "来源返回 HTTP ${response.code}" }
                    val body = requireNotNull(response.body)
                    val type = body.contentType()?.toString().orEmpty()
                    check(type.contains("text") || type.contains("xml") || type.contains("html")) { "此来源类型不支持直接阅读：$type" }
                    val bytes = body.byteStream().use { it.readBytesLimited(1_000_000) }
                    return url.toString() to bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                }
            }
        }
        error("来源跳转次数过多")
    }
    companion object {
        private val lock = Any()
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    InetAddress.getAllByName(hostname).toList().also { addresses ->
                        require(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress }) { "阅读角仅连接公开来源" }
                    }
            }).callTimeout(15, TimeUnit.SECONDS).build()
    }
}

private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break; require(out.size() + count <= max) { "来源超过1MB，请使用正常网页工具按需阅读" }; out.write(buffer, 0, count) }
    return out.toByteArray()
}
private fun kotlinx.coroutines.flow.MutableStateFlow<Long>.updateValue() { synchronized(this) { value += 1 } }
