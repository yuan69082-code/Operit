package com.ai.assistance.operit.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** ML Kit locates text lines; PP-OCRv5 reads the original pixels, never the ML Kit transcript. */
internal object ChineseScreenOcr {
    private const val MODEL = "models/ocr/ch_PP-OCRv5_rec_mobile_5825fc7e.onnx"
    private const val HEIGHT = 48
    private val mutex = Mutex()
    private val detector by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private var model: Model? = null

    private class Model(context: Context) {
        val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
        // A single session serves all callers; the mutex bounds peak tensor memory.
        val session: OrtSession = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            val file = AssetCopyUtils.copyAssetToCache(context, MODEL, overwrite = true)
            environment.createSession(file.absolutePath, options)
        }
        val inputName: String = session.inputNames.single()
        val labels: List<String> = listOf("") +
            requireNotNull(session.metadata.customMetadata["character"]) { "OCR model has no dictionary" }
                .trimEnd('\n', '\r').split('\n').map { it.removeSuffix("\r") } + listOf(" ")
    }

    suspend fun recognize(context: Context, bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val started = System.nanoTime()
            currentCoroutineContext().ensureActive()
            // Wait for ML Kit's task to finish before cancellation can release the input bitmap.
            val located: Text = suspendCoroutine { continuation ->
                detector.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            currentCoroutineContext().ensureActive()
            val regions = located.textBlocks.flatMap { block -> block.lines.mapNotNull { line ->
                line.boundingBox?.let { bounds ->
                    // Handwritten strokes need vertical context; a tight glyph crop changed
                    // “字” into “宇” in the supplied sample. Keep a quarter-line border.
                    val marginX = maxOf(2, bounds.height() / 10)
                    val marginY = maxOf(4, bounds.height() / 4)
                    OcrLineRegion(
                        (bounds.left - marginX).coerceIn(0, bitmap.width),
                        (bounds.top - marginY).coerceIn(0, bitmap.height),
                        (bounds.right + marginX).coerceIn(0, bitmap.width),
                        (bounds.bottom + marginY).coerceIn(0, bitmap.height)
                    )
                }
            } }
            val ordered = PpOcrPostprocessing.readingOrder(regions)
            if (ordered.isEmpty()) return@withLock ""
            val engine = model ?: Model(context.applicationContext).also { model = it }
            val lines = Array(ordered.size) { "" }
            // Recognize one line at its own width. Padding a line to a batch partner's width
            // changed a character in the sample; single-line tensors also bound peak memory.
            for ((index, region) in ordered.withIndex()) {
                currentCoroutineContext().ensureActive()
                val width = (HEIGHT * region.width.toDouble() / region.height).toInt().coerceIn(320, 2048)
                val plane = HEIGHT * width
                val input = FloatArray(3 * plane)
                fillInput(bitmap, region, width, input, 0)
                OnnxTensor.createTensor(engine.environment, FloatBuffer.wrap(input),
                    longArrayOf(1, 3, HEIGHT.toLong(), width.toLong())).use { tensor ->
                    engine.session.run(mapOf(engine.inputName to tensor)).use { outputs ->
                        val output = outputs[0] as OnnxTensor
                        val shape = (output.info as TensorInfo).shape
                        require(shape.size == 3 && shape[0] == 1L && shape[2] == engine.labels.size.toLong()) {
                            "OCR output does not match its dictionary"
                        }
                        val steps = shape[1].toInt()
                        val scores = output.floatBuffer
                        lines[index] = PpOcrPostprocessing.decode(scores, 0, steps, engine.labels)
                    }
                }
            }
            AppLogger.d("ChineseScreenOcr", "lines=${lines.size} elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
            lines.filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    private fun fillInput(source: Bitmap, region: OcrLineRegion, width: Int, input: FloatArray, offset: Int) {
        val crop = Bitmap.createBitmap(source, region.left, region.top, region.width, region.height)
        try {
            val resizedWidth = ceil(HEIGHT * region.width.toDouble() / region.height).toInt().coerceIn(1, width)
            val resized = Bitmap.createScaledBitmap(crop, resizedWidth, HEIGHT, true)
            try {
                val pixels = IntArray(resizedWidth * HEIGHT)
                resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, HEIGHT)
                val plane = width * HEIGHT
                for (y in 0 until HEIGHT) for (x in 0 until resizedWidth) {
                    val pixel = pixels[y * resizedWidth + x]
                    val position = offset + y * width + x
                    // The published PP-OCRv5 model uses BGR in [-1, 1], not Android's RGB order.
                    input[position] = ((pixel and 255) / 255f - 0.5f) / 0.5f
                    input[position + plane] = (((pixel ushr 8) and 255) / 255f - 0.5f) / 0.5f
                    input[position + 2 * plane] = (((pixel ushr 16) and 255) / 255f - 0.5f) / 0.5f
                }
            } finally {
                if (resized !== crop) resized.recycle()
            }
        } finally {
            if (crop !== source) crop.recycle()
        }
    }
}
