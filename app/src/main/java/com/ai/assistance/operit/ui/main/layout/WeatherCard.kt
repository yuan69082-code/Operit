package com.ai.assistance.operit.ui.main.layout

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
internal fun WeatherCard() {
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var weather by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        loading = true
        error = null
        try {
            weather = withContext(Dispatchers.IO) {
                val connection = URL("https://wttr.in/?format=%l:+%C+%t+Feels+%f+Humidity+%h&lang=zh").openConnection() as HttpURLConnection
                connection.connectTimeout = 6000
                connection.readTimeout = 6000
                try {
                    check(connection.responseCode == 200) { "天气服务返回 ${connection.responseCode}" }
                    val value = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                    check(value.isNotBlank() && value.length < 200) { "天气服务未返回有效内容" }
                    value.replace("Feels", "体感").replace("Humidity", "湿度")
                } finally { connection.disconnect() }
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "天气读取失败" } finally { loading = false }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WbSunny, null, tint = MaterialTheme.colorScheme.primary)
                Text("天气", modifier = Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { refresh++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "刷新天气") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            else Text(weather)
            Text("按网络位置查询 · wttr.in", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
