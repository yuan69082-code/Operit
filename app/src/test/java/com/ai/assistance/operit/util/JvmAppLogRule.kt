package com.ai.assistance.operit.util

import org.junit.rules.ExternalResource

/** Local JVM tests have no Android logger or application log directory. */
class JvmAppLogRule : ExternalResource() {
    private var systemLog = true
    private var fileLog = true
    override fun before() {
        systemLog = AppLogger.enableSystemLog
        fileLog = AppLogger.enableFileLogging
        AppLogger.enableSystemLog = false
        AppLogger.enableFileLogging = false
    }
    override fun after() {
        AppLogger.enableSystemLog = systemLog
        AppLogger.enableFileLogging = fileLog
    }
}
