package com.ai.assistance.operit.util

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Durable chat images use a small explicit marker; ordinary user Markdown stays plain text. */
object StickerProtocol {
    const val MAX_BYTES = 20L * 1024 * 1024
    val pattern = Regex("""!\[sticker:([^\]\r\n]{1,40})\]\((file://[^\s)]+)\)""")

    fun validCategory(value: String): Boolean =
        value.length in 1..40 && value.matches(Regex("[\\p{L}\\p{N}_ -]+")) &&
            value == value.trim()

    fun markdown(category: String, uri: String): String {
        require(validCategory(category)) { "分类须为1至40字，可使用中英文、数字、空格、下划线或短横线" }
        require(uri.startsWith("file://") && uri.none { it.isWhitespace() || it == ')' }) { "图片地址无效" }
        return "![sticker:$category]($uri)"
    }

    fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            total += size
            require(total <= MAX_BYTES) { "表情包不能超过20MB" }
            output.write(buffer, 0, size)
        }
        require(total > 0) { "图片文件为空" }
    }

    fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                hash.update(buffer, 0, size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun pageOffset(value: String?): Int {
        if (value == null) return 0
        return requireNotNull(value.toIntOrNull()?.takeIf { it >= 0 }) { "offset 须为非负整数" }
    }

    /** Preserve sent bytes independently of the editable collection; repeated sends reuse the file. */
    @Synchronized
    fun snapshot(directory: File, source: File): File {
        require(source.isFile) { "表情包文件不存在" }
        require(source.extension in setOf("jpg", "jpeg", "png", "gif", "webp"))
        directory.mkdirs()
        val target = File(directory, "${digest(source)}.${source.extension}")
        if (!target.exists()) {
            val temporary = File(directory, "${UUID.randomUUID()}.tmp")
            try {
                source.inputStream().use { input ->
                    temporary.outputStream().use { output -> copyBounded(input, output) }
                }
                check(temporary.renameTo(target)) { "表情包发送副本保存失败" }
            } finally { temporary.delete() }
        }
        return target
    }
}
