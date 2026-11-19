package com.ai.assistance.operit.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class AppLoggerRotationTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun sizeRotationRetainsPreviousRunsAndBoundsStorage() {
        val current = File(folder.root, "operit.log")
        current.writeText("earlier launch")
        AppLogger.rotateLog(current)
        assertEquals("earlier launch", current.readText())
        for (i in 1..10) {
            RandomAccessFile(current, "rw").use { it.setLength(4L * 1024 * 1024); it.writeUTF("run-$i") }
            AppLogger.rotateLog(current)
        }
        assertFalse(current.exists())
        assertEquals(8, folder.root.listFiles()!!.size)
        assertEquals("run-10", RandomAccessFile(File(folder.root, "operit.log.1"), "r").use { it.readUTF() })
        assertEquals("run-3", RandomAccessFile(File(folder.root, "operit.log.8"), "r").use { it.readUTF() })
    }
}
