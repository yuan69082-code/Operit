package com.ai.assistance.operit.util

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.ai.assistance.operit.BuildConfig
import java.io.File
import java.io.FileOutputStream
import com.ai.assistance.operit.ui.error.CrashReportActivity
import kotlin.system.exitProcess

class GlobalExceptionHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        val stackTrace = ThrowableTextFormatter.format(ex)

        // The crash activity may never appear. Persist before launching it or exiting;
        // AppLogger's asynchronous queue cannot guarantee a write before process death.
        try {
            val file = File(context.filesDir, "logs/last-crash.txt")
            file.parentFile?.mkdirs()
            val report = "timeMillis=${System.currentTimeMillis()} version=${BuildConfig.VERSION_NAME} " +
                "pid=${Process.myPid()} thread=${thread.name}\n$stackTrace"
            FileOutputStream(file).use { output ->
                output.write(report.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        } catch (failure: Exception) {
            Log.e("GlobalExceptionHandler", "Could not persist crash report", failure)
        }
        CrashRecoveryState.markPendingCrashReportLaunch(context)

        val intent =
                Intent(context, CrashReportActivity::class.java).apply {
                    putExtra(CrashReportActivity.EXTRA_STACK_TRACE, stackTrace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
        context.startActivity(intent)

        // 终止当前进程
        exitProcess(1)
    }
}
