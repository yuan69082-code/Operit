package com.ai.assistance.operit.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.ai.assistance.operit.util.StickerProtocol
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Copy original bytes, never re-encode animated images. Temporary files are removed on failure. */
object StickerImageStorage {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    fun importImage(context: Context, source: String): File {
        val temp = File.createTempFile("sticker_", ".tmp", context.cacheDir)
        try {
            val uri = Uri.parse(source)
            temp.outputStream().use { output ->
                when (uri.scheme) {
                    "https", "http" -> client.newCall(Request.Builder().url(source).build()).execute().use { response ->
                        check(response.isSuccessful) { "图片下载失败：HTTP ${response.code}" }
                        val body = requireNotNull(response.body) { "图片内容为空" }
                        require(body.contentLength() <= StickerProtocol.MAX_BYTES) { "表情包不能超过20MB" }
                        body.byteStream().use { StickerProtocol.copyBounded(it, output) }
                    }
                    "content" -> requireNotNull(context.contentResolver.openInputStream(uri)) { "图片权限已失效" }
                        .use { StickerProtocol.copyBounded(it, output) }
                    "file", null -> {
                        val path = if (uri.scheme == "file") requireNotNull(uri.path) else source
                        if (path.startsWith("/android_asset/")) {
                            context.assets.open(path.removePrefix("/android_asset/")).use { StickerProtocol.copyBounded(it, output) }
                        } else File(path).inputStream().use { StickerProtocol.copyBounded(it, output) }
                    }
                    else -> error("请选择本地图片或图片直链")
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0 &&
                options.outWidth.toLong() * options.outHeight <= 40_000_000) { "图片无效或尺寸过大" }
            val extension = when (options.outMimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> error("支持 JPG、PNG、GIF、WebP 表情包")
            }
            val image = File(context.cacheDir, "sticker_${UUID.randomUUID()}.$extension")
            check(temp.renameTo(image)) { "图片保存失败" }
            return image
        } finally { temp.delete() }
    }

    /** Chat history must survive deleting/resetting a collection. */
    fun snapshot(context: Context, source: File): File {
        return StickerProtocol.snapshot(File(context.filesDir, "sent_stickers"), source)
    }
}
