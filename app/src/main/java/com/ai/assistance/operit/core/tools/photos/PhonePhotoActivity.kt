package com.ai.assistance.operit.core.tools.photos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.UUID

/** A visible one-shot capture surface. Closing it cancels the tool and releases the camera. */
class PhonePhotoActivity : Activity() {
    private var requestId = ""
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("OperitPhoto")
    private lateinit var workerHandler: Handler
    private lateinit var preview: TextureView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    @Volatile private var closing = false
    private var started = false
    private val expiry = object : Runnable {
        override fun run() {
            if (!PhonePhotoTools.isPending(requestId)) finishPhoto(error = "拍摄请求已取消或超时")
            else main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra("request").orEmpty()
        if (!PhonePhotoTools.isPending(requestId)) { finish(); return }
        worker.start(); workerHandler = Handler(worker.looper)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF171717.toInt()) }
        val label = TextView(this).apply {
            text = if (intent.getBooleanExtra("authorize", false)) "允许 Operit 查看你选择的相册照片" else "AI 正在拍照 · ${if (intent.getStringExtra("camera") == "front") "前置" else "后置"}摄像头"
            setTextColor(0xFFFFFFFF.toInt()); setPadding(24, 24, 24, 24); textSize = 18f
        }
        layout.addView(label)
        preview = TextureView(this)
        layout.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(Button(this).apply { text = "取消"; setOnClickListener { finishPhoto(error = "用户取消拍照或授权") } })
        setContentView(layout)
        main.post(expiry)
        if (intent.getBooleanExtra("authorize", false)) {
            val permissions = when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            requestPermissions(permissions, 10)
        } else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 11)
        } else preparePreview()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (PhonePhotoTools.hasAlbumAccess(this)) finishPhoto(path = "") else finishPhoto(error = "相册权限未授予")
        } else if (requestCode == 11) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) preparePreview()
            else finishPhoto(error = "相机权限未授予")
        }
    }

    private fun preparePreview() {
        if (preview.isAvailable) startCapture(requireNotNull(preview.surfaceTexture))
        else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = startCapture(texture)
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { finishPhoto(error = "相机画面已关闭"); return true }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun startCapture(texture: SurfaceTexture) {
        if (started || closing) return
        started = true
        try {
            val manager = getSystemService(CameraManager::class.java)
            val front = intent.getStringExtra("camera") == "front"
            val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
                ?: error("没有所选摄像头")
            val info = manager.getCameraCharacteristics(id)
            val sizes = requireNotNull(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val jpegSize = sizes.getOutputSizes(ImageFormat.JPEG).sortedBy { it.width.toLong() * it.height }
                .firstOrNull { it.width >= 1280 && it.width <= 2560 } ?: error("摄像头没有支持的照片尺寸")
            val previewSize = sizes.getOutputSizes(SurfaceTexture::class.java).minBy { kotlin.math.abs(it.width - 1280) }
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val target = Surface(texture).also { surface = it }
            val output = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also { reader = it }
            output.setOnImageAvailableListener({ available ->
                try {
                    val image = available.acquireNextImage() ?: return@setOnImageAvailableListener
                    val bytes = image.use { value -> ByteArray(value.planes[0].buffer.remaining()).also { value.planes[0].buffer.get(it) } }
                    if (closing || !PhonePhotoTools.isPending(requestId)) return@setOnImageAvailableListener
                    val folder = File(filesDir, "shared_photos").apply { mkdirs() }
                    val file = File(folder, "capture_${UUID.randomUUID()}.jpg")
                    file.writeBytes(bytes)
                    finishPhoto(path = file.absolutePath)
                } catch (error: Exception) { finishPhoto(error = "照片保存失败：${error.message}") }
            }, workerHandler)
            val displayDegrees = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            val orientation = (requireNotNull(info.get(CameraCharacteristics.SENSOR_ORIENTATION)) + (if (front) displayDegrees else -displayDegrees) + 360) % 360
            val continuousFocus = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closing) { device.close(); return }
                    camera = device
                    try {
                        device.createCaptureSession(listOf(target, output.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value: CameraCaptureSession) {
                                if (closing) { value.close(); return }
                                session = value
                                try {
                                    val repeating = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(target)
                                        if (continuousFocus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }
                                    value.setRepeatingRequest(repeating.build(), null, workerHandler)
                                    workerHandler.postDelayed({
                                        if (!closing && PhonePhotoTools.isPending(requestId)) try {
                                            val photo = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(output.surface); set(CaptureRequest.JPEG_ORIENTATION, orientation)
                                                if (continuousFocus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            }
                                            value.capture(photo.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { finishPhoto(error = "拍照失败：${failure.reason}") }
                                            }, workerHandler)
                                        } catch (error: Exception) { finishPhoto(error = "拍照失败：${error.message}") }
                                    }, 900)
                                } catch (error: Exception) { finishPhoto(error = "预览失败：${error.message}") }
                            }
                            override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); finishPhoto(error = "摄像头配置失败") }
                        }, workerHandler)
                    } catch (error: Exception) { finishPhoto(error = "相机启动失败：${error.message}") }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); finishPhoto(error = "摄像头已断开") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); finishPhoto(error = "相机不可用，错误码 $error；请检查是否被通话占用") }
            }, workerHandler)
        } catch (error: Exception) { finishPhoto(error = "相机启动失败：${error.message}") }
    }

    private fun finishPhoto(path: String? = null, error: String? = null) {
        runOnUiThread {
            if (closing) return@runOnUiThread
            closing = true
            PhonePhotoTools.finishRequest(requestId, path, error)
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        if (started && !closing) finishPhoto(error = "拍照界面离开前台，拍摄已取消")
    }
    override fun onDestroy() {
        closing = true
        main.removeCallbacks(expiry)
        PhonePhotoTools.finishRequest(requestId, error = "拍照或授权已结束")
        session?.close(); camera?.close(); reader?.close(); surface?.release()
        if (::workerHandler.isInitialized) workerHandler.removeCallbacksAndMessages(null)
        worker.quitSafely()
        super.onDestroy()
    }
}
