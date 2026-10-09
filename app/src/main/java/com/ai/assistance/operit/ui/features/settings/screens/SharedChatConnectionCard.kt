package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R

/** An explicit shared entry keeps existing local chats intact during the desktop rollout. */
@Composable
fun SharedChatConnectionCard() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("shared_chat_connection", 0) }
    var address by remember { mutableStateOf(preferences.getString("url", "").orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.shared_chat_title))
            Text(stringResource(R.string.shared_chat_description))
            OutlinedTextField(value = address, onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.shared_chat_address)) })
            Button(onClick = {
                val uri = Uri.parse(address.trim())
                if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
                    Toast.makeText(context, R.string.shared_chat_invalid_address, Toast.LENGTH_SHORT).show()
                } else {
                    preferences.edit().putString("url", address.trim()).apply()
                    context.startActivity(Intent(context, SharedChatActivity::class.java).putExtra("url", uri.toString()))
                }
            }) { Text(stringResource(R.string.shared_chat_open)) }
        }
    }
}
