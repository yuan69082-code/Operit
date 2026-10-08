package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.VoiceCallRingtonePreferences
import com.ai.assistance.operit.util.AppLogger

@Composable
fun VoiceCallRingtoneSettingsButton() {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }) { Text(stringResource(R.string.voice_call_ringtone_settings)) }
    if (show) VoiceCallRingtoneSettingsDialog { show = false }
}

@Composable
private fun VoiceCallRingtoneSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { VoiceCallRingtonePreferences(context) }
    var selectedName by remember { mutableStateOf(prefs.displayName) }
    var error by remember { mutableStateOf("") }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }.orEmpty().ifBlank { context.getString(R.string.voice_call_ringtone_custom) }
                prefs.select(uri, name)
                selectedName = name
                error = ""
            } catch (failure: Exception) {
                AppLogger.e("VoiceCall", "Could not select call ringtone", failure)
                error = context.getString(R.string.voice_call_ringtone_select_error)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_call_ringtone_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selectedName ?: stringResource(R.string.voice_call_ringtone_system))
                Text(stringResource(R.string.voice_call_ringtone_hint))
                Button(onClick = { choose.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.voice_call_ringtone_choose))
                }
                TextButton(onClick = { prefs.useSystemRingtone(); selectedName = null; error = "" }) {
                    Text(stringResource(R.string.voice_call_ringtone_use_system))
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_call_ringtone_done)) } },
    )
}
