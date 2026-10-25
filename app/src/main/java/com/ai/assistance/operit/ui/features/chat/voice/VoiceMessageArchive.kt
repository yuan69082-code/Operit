package com.ai.assistance.operit.ui.features.chat.voice

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Keep the exact WAV bytes independently of transcription success and chat lifecycle. */
object VoiceMessageArchive {
    suspend fun save(context: Context, source: File): String = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT).format(Date())
        val name = "voice_${stamp}_${UUID.randomUUID().toString().take(8)}.wav"
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/Operit/voice_messages/"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
                "无法创建语音存档，原始录音仍保留在 ${source.absolutePath}"
            }
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { output -> source.inputStream().use { it.copyTo(output) } }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Operit/voice_messages")
            check(folder.isDirectory || folder.mkdirs()) { "无法创建语音存档目录" }
            source.copyTo(File(folder, name), overwrite = false)
        }
        "$relative$name"
    }
}
