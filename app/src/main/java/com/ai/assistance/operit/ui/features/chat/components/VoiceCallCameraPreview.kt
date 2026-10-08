package com.ai.assistance.operit.ui.features.chat.components

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
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

    init { surfaceTextureListener = this }

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
        val rotation = (sensorRotation + displayDegrees) % 360
        val sideways = rotation == 90 || rotation == 270
        val rotatedWidth = if (sideways) bufferHeight else bufferWidth
        val rotatedHeight = if (sideways) bufferWidth else bufferHeight
        val scale = max(width.toFloat() / rotatedWidth, height.toFloat() / rotatedHeight)
        setTransform(Matrix().apply {
            setScale(bufferWidth.toFloat() / width, bufferHeight.toFloat() / height)
            postTranslate(-bufferWidth / 2f, -bufferHeight / 2f)
            postRotate(rotation.toFloat())
            postScale(-scale, scale) // Mirror only the local selfie preview.
            postTranslate(width / 2f, height / 2f)
        })
    }
}
