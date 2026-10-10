package com.ai.assistance.operit.core.tools.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ImagePoolManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

object PhonePhotoTools {
    val names = setOf("phone_photos")
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    fun prompts() = listOf(ToolPrompt(name = "phone_photos",
        description = "手机图片：action=capture|list|view|send|authorize。capture 打开可见相机并自动拍一张，camera=front/back；list 分页列出已授权相册元信息，可用 album 筛相册。列表不代表看过照片，须 view 真正查看。view 传 source 与 direct_image=true 给支持看图的当前模型，或 intent 使用已配置的识图模型。send 为相册照片、拍摄照片或本地图片生成可显示图片，必须将返回的 Markdown 原样放进给用户的回复，不能只说已发送。不向其他应用或联系人发送。authorize 可重新选择相册权限范围。",
        parametersStructured = listOf(
            param("action", "capture/list/view/send/authorize", true), param("camera", "front 或 back，默认 back"),
            param("source", "list/capture 返回的 content URI 或可读取本地图片绝对路径"),
            param("offset", "分页起点，默认0"), param("limit", "每页1至20，默认12"), param("album", "相册名，可选"),
            param("direct_image", "true 表示当前模型支持看图，返回真实图片输入"), param("intent", "交给独立识图模型的问题"))))
    private fun param(name: String, description: String, required: Boolean = false) =
        ToolParameterSchema(name = name, type = "string", description = description, required = required)

    fun hasAlbumAccess(context: Context): Boolean =
        context.checkSelfPermission(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

    fun finishRequest(id: String, path: String? = null, error: String? = null) {
        val request = pending[id] ?: return
        if (error != null) request.completeExceptionally(IllegalStateException(error)) else request.complete(path.orEmpty())
    }
    fun isPending(id: String) = pending[id]?.isActive == true

    private suspend fun openCameraOrPermission(context: Context, camera: String?, authorize: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()
        pending[id] = result
        try {
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(context, PhonePhotoActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("request", id)
                    .putExtra("camera", camera).putExtra("authorize", authorize))
            }
            return withTimeout(90_000) { result.await() }
        } finally { pending.remove(id) }
    }

    fun register(handler: AIToolHandler, context: Context) {
        handler.registerTool(name = "phone_photos", descriptionGenerator = { "手机相机与相册" }, executor = { tool ->
            runBlocking(Dispatchers.IO) {
                try {
                    fun arg(name: String) = tool.parameters.firstOrNull { it.name == name }?.value
                    val action = arg("action") ?: error("缺少 action")
                    val content = when (action) {
                        "authorize" -> { openCameraOrPermission(context, null, true); "相册授权已更新，可调用 list 查看系统允许访问的照片。" }
                        "capture" -> {
                            val camera = arg("camera") ?: "back"
                            require(camera in setOf("front", "back")) { "camera 须为 front 或 back" }
                            val path = openCameraOrPermission(context, camera)
                            JSONObject().put("source", path).put("captured", true).put("next", "用 view 查看或 send 发给用户").toString()
                        }
                        "list" -> {
                            if (!hasAlbumAccess(context)) openCameraOrPermission(context, null, true)
                            val offset = integer(arg("offset"), 0, 0..100_000)
                            val limit = integer(arg("limit"), 12, 1..20)
                            list(context, offset, limit, arg("album")).toString()
                        }
                        "view", "send" -> {
                            val source = arg("source")?.takeIf { it.isNotBlank() } ?: error("缺少 source")
                            val file = materialize(context, source)
                            if (action == "send") {
                                "图片已准备，请把下面这一行原样放进回复，用户才会收到图片：\n\n![图片](${Uri.fromFile(file)})"
                            } else if (arg("direct_image") == "true") {
                                val imageId = ImagePoolManager.addImage(file.absolutePath)
                                check(imageId != "error") { "图片读取失败" }
                                "<link type=\"image\" id=\"$imageId\"></link>"
                            } else {
                                val intent = arg("intent")?.takeIf { it.isNotBlank() } ?: error("查看图片需 direct_image=true 或提供 intent 调用识图模型")
                                EnhancedAIService.getInstance(context).analyzeImageWithIntent(file.absolutePath, intent)
                            }
                        }
                        else -> error("未知图片操作")
                    }
                    ToolResult(toolName = tool.name, success = true, result = StringResultData(content))
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    AppLogger.e("PhonePhotoTools", "Photo operation failed", error)
                    ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = error.message.orEmpty())
                }
            }
        })
    }

    internal fun integer(text: String?, default: Int, range: IntRange): Int {
        val value = if (text == null) default else text.toIntOrNull() ?: error("分页参数须为整数")
        require(value in range) { "分页参数超出范围" }
        return value
    }

    private fun list(context: Context, offset: Int, limit: Int, album: String?): JSONObject {
        check(hasAlbumAccess(context)) { "未授予相册访问权限" }
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED)
        val rows = JSONArray()
        val selection = album?.let { "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" }
        val args = album?.let { arrayOf(it) }
        val cursor = context.contentResolver.query(uri, columns, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC") ?: error("相册查询失败")
        cursor.use {
            if (it.moveToPosition(offset)) do {
                rows.put(JSONObject().put("source", ContentUris.withAppendedId(uri, it.getLong(0)).toString())
                    .put("name", it.getString(1)).put("album", it.getString(2)).put("date_seconds", it.getLong(3)))
            } while (rows.length() < limit && it.moveToNext())
            return JSONObject().put("photos", rows).put("visible_total", it.count)
                .put("next_offset", if (offset + rows.length() < it.count) offset + rows.length() else JSONObject.NULL)
                .put("scope", "只列出当前系统授权可见图片；这是元信息，尚未查看图片内容")
        }
    }

    private fun materialize(context: Context, source: String): File {
        val uri = Uri.parse(source)
        require(uri.scheme == "content" || uri.scheme == "file" || uri.scheme == null) { "source 须为本地图片路径或 content URI" }
        val dir = File(context.filesDir, "shared_photos").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        try {
            val input = if (uri.scheme == "content") context.contentResolver.openInputStream(uri)
                else File(if (uri.scheme == "file") requireNotNull(uri.path) else source).inputStream()
            requireNotNull(input) { "图片不可读取，权限可能已经撤销" }.use { stream ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 30 * 1024 * 1024) { "图片超过30MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "文件不是可读取图片" }
            val extension = when (bounds.outMimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/bmp" -> "bmp"
                "image/heif", "image/heic" -> "heic"
                else -> error("不支持的图片类型：${bounds.outMimeType}")
            }
            val named = File(dir, "${file.nameWithoutExtension}.$extension")
            if (named != file) check(file.renameTo(named)) { "保存图片失败" }
            return named
        } catch (error: Exception) { file.delete(); throw error }
    }
}
