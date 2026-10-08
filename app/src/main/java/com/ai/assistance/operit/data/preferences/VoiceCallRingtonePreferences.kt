package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.net.Uri
import android.provider.Settings

class VoiceCallRingtonePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("voice_call_ringtone", Context.MODE_PRIVATE)
    val selectedUri: String? get() = prefs.getString("uri", null)
    val displayName: String? get() = prefs.getString("name", null)
    val playbackUri: Uri get() = selectedUri?.let(Uri::parse) ?: Settings.System.DEFAULT_RINGTONE_URI

    fun select(uri: Uri, name: String) {
        prefs.edit().putString("uri", uri.toString()).putString("name", name).apply()
    }

    fun useSystemRingtone() { prefs.edit().clear().apply() }
}
