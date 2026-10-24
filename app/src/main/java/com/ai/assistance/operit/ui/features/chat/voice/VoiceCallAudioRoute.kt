package com.ai.assistance.operit.ui.features.chat.voice

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/** Own the communication route for the lifetime of continuous microphone capture. */
@Suppress("DEPRECATION")
internal class VoiceCallAudioRoute(private val manager: AudioManager) : AutoCloseable {
    private val previousMode = manager.mode
    private val previousSpeaker = manager.isSpeakerphoneOn
    private var registered = false
    private var closed = false
    private var selectedId: Int? = null
    private val routeError = AtomicReference<Exception?>(null)
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = changed()
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = changed()
    }

    private fun changed() {
        try { update() } catch (error: Exception) { routeError.set(error) }
    }

    fun checkHealthy() { routeError.get()?.let { throw it } }

    fun start() {
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        update()
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        registered = true
    }

    @Synchronized private fun update() {
        if (closed) return
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = manager.availableCommunicationDevices
            val id = VoiceCallRoutePolicy.select(
                devices.map { VoiceCallRoutePolicy.Device(it.id, it.type) },
                manager.communicationDevice?.id,
            ) ?: return
            // Do not keep re-requesting an unchanged route when the microphone is added.
            if (selectedId == id) return
            val selected = devices.first { it.id == id }
            check(manager.setCommunicationDevice(selected)) { "无法启用通话音频输出，请检查耳机连接后重试" }
            selectedId = id
        } else {
            // These platform versions use the speakerphone flag for communication routing.
            val wired = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
            manager.isSpeakerphoneOn = !wired && !manager.isBluetoothScoOn
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try {
            if (registered) manager.unregisterAudioDeviceCallback(callback)
            if (Build.VERSION.SDK_INT >= 31) {
                if (selectedId != null) manager.clearCommunicationDevice()
            } else manager.isSpeakerphoneOn = previousSpeaker
        } finally {
            manager.mode = previousMode
        }
    }
}

internal object VoiceCallRoutePolicy {
    data class Device(val id: Int, val type: Int)

    fun select(devices: List<Device>, currentId: Int?): Int? {
        val external = devices.filter { it.type in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        ) }
        if (external.isNotEmpty()) {
            return external.firstOrNull { it.id == currentId }?.id ?: external.first().id
        }
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.id
    }
}
