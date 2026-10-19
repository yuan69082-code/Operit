package com.ai.assistance.operit.core.companion

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Source material only. Display blocks never include or rewrite an assistant's own notes. */
internal object ReadingPage {
    fun extract(html: String, url: String): JSONArray {
        val document = Jsoup.parse(html, url)
        document.select("script,style,nav,header,footer,aside,form,button,noscript").remove()
        val root = document.selectFirst("article,main") ?: document.body()
        val blocks = JSONArray()
        val pending = StringBuilder()
        var remaining = 16000
        var images = 0
        val seen = mutableSetOf<String>()
        fun flush() {
            val text = pending.toString().replace(Regex("\\s+"), " ").trim().take(remaining)
            pending.setLength(0)
            if (text.isNotEmpty()) {
                blocks.put(JSONObject().put("type", "text").put("text", text))
                remaining -= text.length
            }
        }
        fun visit(node: Node) {
            if (remaining <= 0) return
            when (node) {
                is TextNode -> pending.append(node.text())
                is Element -> {
                    if (node.normalName() == "img") {
                        flush()
                        val address = imageAddress(node.absUrl("src"))
                        if (remaining > 0 && address != null && images < 12 && seen.add(address)) {
                            blocks.put(JSONObject().put("type", "image").put("url", address).put("alt", node.attr("alt").take(300)))
                            images++
                        }
                    } else {
                        val boundary = node.isBlock || node.normalName() == "br"
                        if (boundary) flush()
                        node.childNodes().forEach { visit(it) }
                        if (boundary) flush()
                    }
                }
            }
        }
        visit(root)
        flush()
        return blocks
    }

    fun text(blocks: JSONArray): String = (0 until blocks.length()).map { blocks.getJSONObject(it) }
        .filter { it.getString("type") == "text" }.joinToString("\n\n") { it.getString("text") }

    /** Reject non-web embeds and credentials; downloads also validate resolved public IP addresses. */
    fun imageAddress(raw: String): String? {
        val address = raw.toHttpUrlOrNull() ?: return null
        if (address.username.isNotEmpty() || address.password.isNotEmpty()) return null
        return address.toString()
    }
}
