package com.ai.assistance.operit.ui.features.chat.voice

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCallRoutePolicyTest {
    private val speaker = VoiceCallRoutePolicy.Device(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val earpiece = VoiceCallRoutePolicy.Device(2, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    private val usb = VoiceCallRoutePolicy.Device(3, AudioDeviceInfo.TYPE_USB_HEADSET)
    private val bluetooth = VoiceCallRoutePolicy.Device(4, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

    @Test fun builtInEarpieceDoesNotSilentlyTakeHandsFreeAudio() {
        assertEquals(1, VoiceCallRoutePolicy.select(listOf(speaker, earpiece), earpiece.id))
    }

    @Test fun pluggingInHeadphonesLeavesSpeaker() {
        assertEquals(3, VoiceCallRoutePolicy.select(listOf(speaker, earpiece, usb), speaker.id))
    }

    @Test fun alreadySelectedBluetoothIsKeptWhenUsbAlsoExists() {
        assertEquals(4, VoiceCallRoutePolicy.select(listOf(speaker, usb, bluetooth), bluetooth.id))
    }

    @Test fun unpluggingHeadphonesReturnsToSpeaker() {
        assertEquals(1, VoiceCallRoutePolicy.select(listOf(speaker, earpiece), usb.id))
    }

    @Test fun noAvailableRouteDoesNotInventADevice() {
        assertNull(VoiceCallRoutePolicy.select(emptyList(), null))
    }
}
