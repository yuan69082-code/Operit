package com.ai.assistance.operit.ui.features.settings.screens

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

@Composable
internal fun MiniMaxVoiceDesigner(endpoint: String, apiKey: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("设计音色 / 账户音色库") }
    if (!open) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf("") }
    var sample by remember { mutableStateOf("我在这里，慢慢说，我听着。") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var designed by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var audio by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release(); audio?.delete() } }
    suspend fun request(operation: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先填写 MiniMax API Key" }
        val raw = endpoint.ifBlank { "https://api.minimaxi.com/v1/t2a_v2" }.toHttpUrl()
        require(raw.isHttps) { "音色管理需要 HTTPS 地址" }
        val segments = raw.pathSegments.filter { it.isNotBlank() }.toMutableList()
        if (segments.lastOrNull() in listOf("t2a_v2", "voice_design", "get_voice")) segments.removeAt(segments.lastIndex)
        val url = raw.newBuilder().encodedPath("/" + (segments + operation).joinToString("/")).build()
        val client = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            check(response.isSuccessful) { "MiniMax 请求失败 HTTP ${response.code}" }
            val json = JSONObject(requireNotNull(response.body).string())
            val result = json.getJSONObject("base_resp")
            check(result.getInt("status_code") == 0) { result.getString("status_msg") }
            json
        }
    }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLogger.e("VoiceDesigner", "Voice management failed", e); status = e.message.orEmpty() }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("MiniMax 音色工坊") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("使用上方填写的 MiniMax 接口和密钥。生成音色会调用服务商 API。选择后仍需保存语音配置。")
            OutlinedTextField(description, { description = it }, label = { Text("声音描述") })
            OutlinedTextField(sample, { sample = it }, label = { Text("试听文本（最多500字）") })
            TextButton(enabled = !busy && description.isNotBlank() && sample.length in 1..500, onClick = { run {
                val result = request("voice_design", JSONObject().put("prompt", description).put("preview_text", sample))
                designed = result.getString("voice_id")
                val hex = result.getString("trial_audio")
                require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null }) { "试听音频格式无效" }
                val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                player?.release(); player = null; audio?.delete()
                audio = withContext(Dispatchers.IO) { File.createTempFile("voice-design-", ".mp3", context.cacheDir).apply { writeBytes(bytes) } }
                status = "已生成 $designed"
            } }) { Text("生成音色") }
            if (audio != null) TextButton(onClick = { run {
                player?.release()
                player = MediaPlayer().apply { setDataSource(requireNotNull(audio).absolutePath); prepare(); start() }
            } }) { Text("试听") }
            if (designed.isNotBlank()) TextButton(onClick = { onSelect(designed); open = false }) { Text("使用此音色") }
            TextButton(enabled = !busy, onClick = { run {
                val result = request("get_voice", JSONObject().put("voice_type", "all"))
                val loaded = mutableListOf<Pair<String, String>>()
                for (type in listOf("system_voice", "voice_cloning", "voice_generation")) {
                    val rows = result.optJSONArray(type) ?: continue
                    for (i in 0 until rows.length()) {
                        val voice = rows.getJSONObject(i)
                        val id = voice.getString("voice_id")
                        loaded.add(id to voice.optString("voice_name", id))
                    }
                }
                voices = loaded.distinctBy { it.first }; status = "已读取 ${voices.size} 个音色"
            } }) { Text("读取账户音色库") }
            voices.forEach { (id, label) -> TextButton(onClick = { onSelect(id); open = false }) { Text(label) } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("关闭") } })
}
