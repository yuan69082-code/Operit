package com.ai.assistance.operit.core.companion

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

internal object StudyImport {
    data class Starter(val id: String, val title: String, val description: String) {
        val source get() = "https://www.gutenberg.org/ebooks/$id"
    }
    val starters = listOf(
        Starter("25362", "搜神记", "中文古文 · 中国神怪传说"),
        Starter("51828", "聊斋志异", "中文古文 · 志怪、惊悚与爱情"),
        Starter("25192", "浮生六记", "中文古文 · 夫妻情趣、日常温情与人生悲欢"),
        Starter("32242", "A Wonder Book for Girls & Boys", "英文原著 · 霍桑改写的希腊神话"),
        Starter("28497", "Myths of the Norsemen", "英文原著 · 北欧神话")
    )
    fun starter(context: Context, role: String, starter: Starter): JSONObject {
        val store = StudyStore(context, role)
        store.books().firstOrNull { it.optString("source") == starter.source }?.let { return it }
        val text = java.util.zip.GZIPInputStream(context.assets.open("study-starter/${starter.id}.txt.gz"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }.replace("\r\n", "\n")
        val start = Regex("(?m)^\\*\\*\\* START OF .+ \\*\\*\\*$").find(text) ?: error("书籍起始标记损坏")
        val end = Regex("(?m)^\\*\\*\\* END OF .+ \\*\\*\\*$").find(text) ?: error("书籍结束标记损坏")
        val body = text.substring(start.range.last + 1, end.range.first).trim()
        val headings = Regex("(?m)^(?:第[一二三四五六七八九十百〇零0-9]+[卷回章][^\\n]{0,60}|CHAPTER [IVXLCDM]+:[^\\n]{1,100})$").findAll(body).toList()
        val sections = mutableListOf<Pair<String, String>>()
        if (headings.isEmpty()) sections.add("正文" to body)
        else {
            if (headings.first().range.first > 0) sections.add("前言" to body.substring(0, headings.first().range.first))
            headings.forEachIndexed { i, heading -> sections.add(heading.value.trim() to body.substring(heading.range.first, if (i == headings.lastIndex) body.length else headings[i + 1].range.first)) }
        }
        sections.add("来源与许可" to (starter.source + "\n\n" + text.substring(0, start.range.first) + text.substring(end.range.first)))
        val chapters = sections.filter { it.second.isNotBlank() }.flatMap { (title, body) ->
            StudyPolicy.split(body).mapIndexed { i, part -> (if (i == 0) title else "$title · 续${i + 1}") to part }
        }
        return save(store, starter.title, "daily", starter.source, chapters)
    }
    private fun htmlText(html: String): String {
        val document = Jsoup.parse(html)
        document.select("script,style,nav,header,footer,aside,form,button,noscript").remove()
        val root = document.selectFirst("article,main") ?: document.body()
        root.select("p,div,br,h1,h2,h3,h4,li,blockquote").forEach { it.before("\n\n") }
        return root.wholeText().trim()
    }
    private fun save(store: StudyStore, title: String, category: String, source: String, chapters: List<Pair<String, String>>): JSONObject {
        require(chapters.isNotEmpty() && chapters.size <= 2000) { "没有可读章节，或章节数超过2000" }
        val book = store.create(title, "reading", category, "source", source)
        for ((name, body) in chapters) store.write(book.getString("id"), null, null, name.take(200), body, JSONArray(), "source", false)
        return store.book(book.getString("id"))
    }
    suspend fun url(context: Context, role: String, title: String, category: String, url: String): JSONObject {
        val (resolved, html) = ReadingRoom(context, role).download(url)
        val body = htmlText(html)
        return save(StudyStore(context, role), title, category, resolved, StudyPolicy.split(body).mapIndexed { i, text -> "第${i + 1}节" to text })
    }
    fun file(context: Context, role: String, title: String, category: String, uri: Uri, epub: Boolean, encoding: String = "UTF-8"): JSONObject {
        val temporary = File.createTempFile("study-import-", ".data", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                temporary.outputStream().use { out ->
                    val buffer = ByteArray(8192); var size = 0
                    while (true) { val n = input.read(buffer); if (n < 0) break; size += n; require(size <= 30 * 1024 * 1024) { "文件超过30MB，请拆分后导入" }; out.write(buffer, 0, n) }
                }
            }
            val chapters = if (epub) readEpub(temporary) else {
                val bytes = temporary.readBytes()
                val charset = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) Charsets.UTF_16LE
                    else if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) Charsets.UTF_16BE else java.nio.charset.Charset.forName(encoding)
                val decoder = charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                val text = try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
                    catch (failure: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("TXT 编码不匹配，请选择正确编码后重新导入", failure) }
                StudyPolicy.split(text).mapIndexed { i, part -> "第${i + 1}节" to part }
            }
            return save(StudyStore(context, role), title, category, "本地导入", chapters)
        } finally { temporary.delete() }
    }
    private fun readEpub(file: File): List<Pair<String, String>> = ZipFile(file).use { zip ->
        var total = 0
        fun read(path: String): String {
            val entry = zip.getEntry(path) ?: error("EPUB 缺少 $path")
            require(entry.size <= 10 * 1024 * 1024) { "EPUB 单个章节过大" }
            val out = java.io.ByteArrayOutputStream()
            zip.getInputStream(entry).use { input -> val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 60 * 1024 * 1024 && out.size() + n <= 10 * 1024 * 1024) { "EPUB 解压内容过大" }; out.write(buffer, 0, n) }
            }
            return out.toString("UTF-8")
        }
        val container = Jsoup.parse(read("META-INF/container.xml"), "", Parser.xmlParser())
        val opf = container.selectFirst("rootfile")?.attr("full-path") ?: error("EPUB 缺少目录")
        val document = Jsoup.parse(read(opf), "", Parser.xmlParser())
        val manifest = document.select("manifest > item").associate { it.attr("id") to it.attr("href") }
        val base = java.net.URI("https", "epub.invalid", "/$opf", null)
        document.select("spine > itemref").filter { it.attr("linear") != "no" }.flatMapIndexed { chapter, item ->
            val href = manifest[item.attr("idref")] ?: error("EPUB 章节索引损坏")
            val address = base.resolve(href).normalize()
            require(address.host == "epub.invalid")
            val path = address.path.removePrefix("/")
            val html = read(path)
            val title = Jsoup.parse(html).selectFirst("h1,h2,title")?.text().orEmpty().ifBlank { "第${chapter + 1}章" }
            val body = htmlText(html)
            if (body.isBlank()) emptyList() else StudyPolicy.split(body).mapIndexed { part, text -> (if (part == 0) title else "$title · ${part + 1}") to text }
        }
    }
}
