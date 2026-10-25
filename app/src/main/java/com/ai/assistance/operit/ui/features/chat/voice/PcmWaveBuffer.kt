package com.ai.assistance.operit.ui.features.chat.voice

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Disk-backed PCM: a long press must not retain the entire recording in heap memory. */
internal class PcmWaveBuffer(private val directory: File) {
    private var file: File? = null
    private var output: RandomAccessFile? = null
    private var bytes = 0L
    fun size(): Long = bytes
    fun write(data: ByteArray) {
        if (output == null) {
            check(directory.isDirectory || directory.mkdirs())
            file = File.createTempFile("voice-", ".wav", directory)
            output = RandomAccessFile(checkNotNull(file), "rw").apply { write(ByteArray(44)) }
        }
        // WAV uses unsigned 32-bit sizes; this is a format boundary, not a recording timer.
        check(bytes + data.size < 0xfffffff0L) { "录音超过 WAV 文件大小限制，已保留录音" }
        checkNotNull(output).write(data)
        bytes += data.size
    }
    fun finish(): File {
        val stream = checkNotNull(output) { "没有录到音频" }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt((bytes + 36).toInt()).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes.toInt()).array()
        stream.seek(0); stream.write(header); stream.fd.sync(); stream.close()
        val result = checkNotNull(file)
        output = null; file = null; bytes = 0
        return result
    }
    fun reset() {
        output?.close(); file?.delete()
        output = null; file = null; bytes = 0
    }
}
