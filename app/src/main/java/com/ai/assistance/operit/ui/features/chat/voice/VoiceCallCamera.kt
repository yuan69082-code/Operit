package com.ai.assistance.operit.ui.features.chat.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Camera surfaces render continuously; JPEG/MP4 delivery has a separate sampling interval. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallCamera(
    private val context: Context,
    private val scope: CoroutineScope,
    private val video: Boolean,
    private val intervalSeconds: Int,
    private val onMedia: (File, Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("CallCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var job: Job? = null
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var closed = false
    var rotation by mutableStateOf(0)
        private set
    var previewSize by mutableStateOf(Size(640, 480))
        private set
    private var previewSurface: Surface? = null
    private val previewChanged = Channel<Unit>(Channel.CONFLATED)
    private val retiredPreviews = mutableListOf<Pair<Surface, SurfaceTexture>>()
    private val firstFrame = CompletableDeferred<ByteArray>()
    private var clip: File? = null

    @SuppressLint("MissingPermission") // Camera permission is checked before promoting the service.
    fun start() {
        job = scope.launch {
            try {
                val manager = context.getSystemService(CameraManager::class.java)
                val id = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                } ?: error("没有可用的前置摄像头")
                val characteristics = manager.getCameraCharacteristics(id)
                rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: error("无法读取摄像头方向")
                val sizes = checkNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                    .getOutputSizes(ImageFormat.YUV_420_888)
                val size = sizes.filter { it.width <= 640 && it.height <= 480 }.maxByOrNull { it.width * it.height }
                    ?: sizes.minBy { it.width * it.height }
                previewSize = size
                val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
                reader = imageReader
                var lastPreview = 0L
                imageReader.setOnImageAvailableListener({ source ->
                    if (closed) return@setOnImageAvailableListener
                    val image = try { source.acquireLatestImage() } catch (error: IllegalStateException) {
                        if (!closed) scope.launch { onError("摄像头图像队列已关闭"); stop() }
                        null
                    } ?: return@setOnImageAvailableListener
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPreview >= 1000) {
                            lastPreview = now
                            val bytes = jpeg(image)
                            latestJpeg = bytes
                            firstFrame.complete(bytes)
                        }
                    } catch (error: Exception) {
                        com.ai.assistance.operit.util.AppLogger.e("VoiceCallCamera", "Could not prepare camera frame", error)
                        scope.launch { onError("摄像头画面处理失败"); stop() }
                    } finally { image.close() }
                }, handler)
                device = suspendCancellableCoroutine { continuation ->
                    manager.openCamera(id, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (continuation.isActive) {
                                continuation.invokeOnCancellation { camera.close() }
                                continuation.resume(camera)
                            } else camera.close()
                        }
                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("摄像头已断开"))
                            else scope.launch { onError("摄像头已断开"); stop() }
                        }
                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("摄像头错误：$error"))
                            else scope.launch { onError("摄像头错误：$error"); stop() }
                        }
                    }, handler)
                }
                if (video) {
                    while (isActive) recordClip(size, imageReader)
                } else {
                    configure(listOf(imageReader.surface), CameraDevice.TEMPLATE_PREVIEW)
                    sendFrame(firstFrame.await())
                    while (isActive) {
                        select<Unit> {
                            previewChanged.onReceive { configure(listOf(imageReader.surface), CameraDevice.TEMPLATE_PREVIEW) }
                            onTimeout(intervalSeconds * 1000L) { latestJpeg?.let { sendFrame(it) } }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                com.ai.assistance.operit.util.AppLogger.e("VoiceCallCamera", "Camera capture failed", error)
                onError(error.message ?: "摄像头启动失败")
            } finally { release() }
        }
    }

    private suspend fun configure(surfaces: List<android.view.Surface>, template: Int) {
        val camera = checkNotNull(device)
        val outputs = surfaces + listOfNotNull(previewSurface)
        session?.close()
        session = null
        val configured = suspendCancellableCoroutine<CameraCaptureSession> { continuation ->
            camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    if (continuation.isActive) {
                        continuation.invokeOnCancellation { value.close() }
                        continuation.resume(value)
                    } else value.close()
                }
                override fun onConfigureFailed(value: CameraCaptureSession) {
                    value.close()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("摄像头不支持当前采集组合"))
                }
            }, handler)
        }
        session = configured
        // Old view textures are retained until the camera no longer targets them.
        val retired = retiredPreviews.filter { it.first !in outputs }
        retired.forEach { (surface, texture) -> surface.release(); texture.release() }
        retiredPreviews.removeAll(retired.toSet())
        val request = camera.createCaptureRequest(template).apply { outputs.forEach { addTarget(it) } }
        configured.setRepeatingRequest(request.build(), null, handler)
    }

    @Suppress("DEPRECATION")
    private suspend fun recordClip(size: Size, imageReader: ImageReader) {
        val file = newFile(".mp4")
        clip = file
        val mediaRecorder = MediaRecorder().also { recorder = it }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setVideoSize(size.width, size.height)
        mediaRecorder.setVideoFrameRate(30)
        mediaRecorder.setVideoEncodingBitRate(1_200_000)
        mediaRecorder.setOrientationHint(rotation)
        mediaRecorder.setOutputFile(file.absolutePath)
        mediaRecorder.prepare()
        configure(listOf(imageReader.surface, mediaRecorder.surface), CameraDevice.TEMPLATE_RECORD)
        mediaRecorder.start()
        select<Unit> {
            onTimeout(intervalSeconds * 1000L) { }
            previewChanged.onReceive { delay(1000) }
        }
        session?.stopRepeating()
        session?.abortCaptures()
        session?.close()
        session = null
        mediaRecorder.stop()
        mediaRecorder.release()
        recorder = null
        clip = null
        onMedia(file, true)
    }

    private fun newFile(extension: String): File {
        val directory = File(context.cacheDir, "call_camera").apply { mkdirs() }
        return File.createTempFile("camera-", extension, directory)
    }

    private suspend fun sendFrame(bytes: ByteArray) {
        val file = newFile(".jpg")
        var delivered = false
        try {
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            onMedia(file, false)
            delivered = true
        } finally { if (!delivered) file.delete() }
    }

    fun attachPreview(surface: Surface) {
        if (closed) { surface.release(); return }
        previewSurface = surface
        previewChanged.trySend(Unit)
    }

    fun detachPreview(surface: Surface, texture: SurfaceTexture) {
        if (closed) { surface.release(); texture.release(); return }
        if (previewSurface === surface) previewSurface = null
        retiredPreviews.add(surface to texture)
        previewChanged.trySend(Unit)
    }

    private fun jpeg(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        for (planeIndex in 0..2) {
            val plane = image.planes[planeIndex]
            val planeWidth = if (planeIndex == 0) width else width / 2
            val planeHeight = if (planeIndex == 0) height else height / 2
            val offset = plane.buffer.position()
            for (y in 0 until planeHeight) for (x in 0 until planeWidth) {
                val destination = if (planeIndex == 0) y * width + x else width * height + y * width + x * 2 + if (planeIndex == 1) 1 else 0
                nv21[destination] = plane.buffer.get(offset + y * plane.rowStride + x * plane.pixelStride)
            }
        }
        val stream = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 75, stream)
        val bytes = stream.toByteArray()
        if (rotation == 0) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        return try {
            ByteArrayOutputStream().use { output -> rotated.compress(Bitmap.CompressFormat.JPEG, 75, output); output.toByteArray() }
        } finally { if (rotated !== bitmap) rotated.recycle(); bitmap.recycle() }
    }

    fun stop() { job?.cancel(); release() }

    private fun release() {
        closed = true
        session?.close(); session = null
        device?.close(); device = null
        previewSurface?.release(); previewSurface = null
        retiredPreviews.forEach { (surface, texture) -> surface.release(); texture.release() }
        retiredPreviews.clear()
        recorder?.reset(); recorder?.release(); recorder = null
        reader?.close(); reader = null
        clip?.delete(); clip = null
        latestJpeg = null
        thread.quitSafely()
    }
}
