package com.ai.assistance.operit.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Base64
import com.ai.assistance.operit.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File

/** Read only on explicit export; JVM handlers cannot record native crashes or system kills. */
internal object ProcessExitDiagnostics {
    fun capture(context: Context): File {
        val file = File(context.cacheDir, "process-exits.txt")
        file.bufferedWriter().use { writer ->
            writer.appendLine("Export version=${BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT}")
            writer.appendLine("Historical exits; timestamps identify the affected launch, not the export version.")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                writer.appendLine("Process exit history requires Android 11 or newer.")
                return@use
            }
            try {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                writer.appendLine("records=${exits.size}")
                var traceCount = 0
                for (exit in exits) {
                    writer.appendLine("timeMillis=${exit.timestamp} process=${exit.processName} pid=${exit.pid}")
                    writer.appendLine("reason=${exit.reason} statusOrSignal=${exit.status} importance=${exit.importance}")
                    writer.appendLine("pssKb=${exit.pss} rssKb=${exit.rss} description=${exit.description}")
                    val native = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    val anr = exit.reason == ApplicationExitInfo.REASON_ANR
                    if (traceCount >= 3 || (!native && !anr)) continue
                    if (native && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) continue
                    try {
                        val stream = exit.traceInputStream
                        if (stream == null) {
                            writer.appendLine("trace=unavailable")
                            continue
                        }
                        traceCount++
                        // Native traces are protobuf, not text. Preserve their original bytes
                        // in base64 so they can be decoded without asking for another export.
                        val limit = 512 * 1024
                        val output = ByteArrayOutputStream()
                        stream.use { input ->
                            val buffer = ByteArray(8192)
                            while (output.size() <= limit) {
                                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                                if (count < 0) break
                                if (count == 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        val bytes = output.toByteArray()
                        val truncated = bytes.size > limit
                        val visible = if (truncated) bytes.copyOf(limit) else bytes
                        writer.appendLine("traceFormat=${if (native) "tombstone-protobuf-base64" else "anr-text"} truncated=$truncated")
                        writer.appendLine(if (native) Base64.encodeToString(visible, Base64.NO_WRAP) else visible.toString(Charsets.UTF_8))
                        writer.appendLine("endTrace")
                    } catch (error: Exception) {
                        writer.appendLine("traceReadError=${error.javaClass.simpleName}: ${error.message}")
                    }
                }
            } catch (error: Exception) {
                writer.appendLine("exitHistoryReadError=${error.javaClass.simpleName}: ${error.message}")
            }
        }
        return file
    }
}
