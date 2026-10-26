package com.ai.assistance.operit.core.experience

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Read only the explicitly exported project directory; never silently skip unsupported data. */
object WorkspaceTransfer {
    fun sameFiles(a: JSONObject, b: JSONObject): Boolean =
        a.length() == b.length() && a.keys().asSequence().all { b.has(it) && a.getString(it) == b.getString(it) }
    fun read(directory: File): JSONObject {
        val root = directory.canonicalFile
        val files = JSONObject(); var bytes = 0L; var count = 0
        fun visit(folder: File) {
            val children = requireNotNull(folder.listFiles()) { "无法读取工作区目录" }
            for (file in children) {
                check(file.canonicalFile == file.absoluteFile) { "工作区包含符号链接，请移除链接后重试" }
                val path = file.relativeTo(root).invariantSeparatorsPath
                CollectionStore.validatePath(path)
                if (file.isDirectory) { visit(file); continue }
                check(file.isFile) { "工作区包含非普通文件" }
                count++; require(count <= 1000) { "项目文件超过1000个" }
                val raw = file.inputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        bytes += n; require(bytes <= 3_500_000) { "项目文件总量超过3.5MB" }; output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
                require('\u0000' !in text) { "暂不支持二进制项目文件：$path" }
                files.put(path, text)
            }
        }
        visit(root)
        return files
    }
}
