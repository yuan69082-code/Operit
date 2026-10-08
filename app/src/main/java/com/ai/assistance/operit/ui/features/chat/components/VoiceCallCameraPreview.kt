package com.ai.assistance.operit.ui.features.chat.components

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallCamera
import kotlin.math.max

/** Camera2 renders straight into the view; model sampling never controls display FPS. */
@Composable
fun VoiceCallCameraPreview(camera: VoiceCallCamera, modifier: Modifier = Modifier) {
    val size = camera.previewSize
    val rotation = camera.rotation
    key(camera) {
        AndroidView(
            factory = { CallCameraTexture(it, camera) },
            modifier = modifier,
            update = { view -> view.updateGeometry(size.width, size.height, rotation) },
            onRelease = { it.releasePreview() },
        )
    }
}

private class CallCameraTexture(context: Context, private val camera: VoiceCallCamera) : TextureView(context), TextureView.SurfaceTextureListener {
    private var output: Surface? = null
    private var ownedTexture: SurfaceTexture? = null
    private var bufferWidth = 640
    private var bufferHeight = 480
    private var sensorRotation = 0
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (display?.displayId == displayId) transformPreview()
        }
    }

    init { surfaceTextureListener = this }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun onDetachedFromWindow() {
        displayManager.unregisterDisplayListener(displayListener)
        super.onDetachedFromWindow()
    }

    fun updateGeometry(width: Int, height: Int, rotation: Int) {
        bufferWidth = width
        bufferHeight = height
        sensorRotation = rotation
        ownedTexture?.setDefaultBufferSize(width, height)
        transformPreview()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        ownedTexture = texture
        texture.setDefaultBufferSize(bufferWidth, bufferHeight)
        output = Surface(texture).also { camera.attachPreview(it) }
        transformPreview()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { transformPreview() }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releasePreview()
        return false // The camera releases it after the old capture session is retired.
    }

    fun releasePreview() {
        val surface = output ?: return
        val texture = ownedTexture ?: return
        output = null
        ownedTexture = null
        camera.detachPreview(surface, texture)
    }

    private fun transformPreview() {
        if (width <= 0 || height <= 0) return
        val displayDegrees = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // TextureView already applies sensor orientation and front-camera mirroring.
        // Undo its nonuniform stretch, then compensate only for display rotation.
        val sensorSideways = sensorRotation % 180 != 0
        val naturalWidth = if (sensorSideways) bufferHeight else bufferWidth
        val naturalHeight = if (sensorSideways) bufferWidth else bufferHeight
        val displaySideways = displayDegrees % 180 != 0
        val rotatedWidth = if (displaySideways) naturalHeight else naturalWidth
        val rotatedHeight = if (displaySideways) naturalWidth else naturalHeight
        val scale = max(width.toFloat() / rotatedWidth, height.toFloat() / rotatedHeight)
        setTransform(Matrix().apply {
            setScale(scale * naturalWidth / width, scale * naturalHeight / height, width / 2f, height / 2f)
            postRotate(-displayDegrees.toFloat(), width / 2f, height / 2f)
        })
    }
}
