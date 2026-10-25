package com.ai.assistance.operit.ui.features.chat.voice

import android.content.Context
import android.util.Base64
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.LocalTerminalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Runs the requested Python stack on the phone's Linux environment, never a remote server. */
class VoiceLocalAnalysis(private val context: Context) {
    suspend fun analyze(archive: String, onStage: (String) -> Unit): String = withContext(Dispatchers.IO) {
        check(TerminalManager.getInstance(context).initializeEnvironment()) {
            "本地声学分析需要 Operit 终端：请在终端设置完成 Linux / Python 环境安装后重试。原录音已保存。"
        }
        // Select the local provider explicitly; a user's SSH terminal setting must not upload audio.
        val terminal = LocalTerminalProvider(context)
        try {
            suspend fun run(command: String): String {
                val result = terminal.executeHiddenCommand(command, "voice-acoustics", 3_600_000L)
                check(result.isOk && result.exitCode == 0) {
                    "本地分析未完成，请检查终端 Python、存储权限和模型下载。" + (result.error + " " + result.output).takeLast(600)
                }
                return result.output
            }
            fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
            val source = quote("/sdcard/$archive")
            val script = Base64.encodeToString(context.assets.open("voice_analysis/analyze.py").use { it.readBytes() }, Base64.NO_WRAP)
            onStage("发送中 · 检查本地分析依赖（首次可能安装）")
            run("mkdir -p ~/.operit/voice-analysis && " +
                "(test -x ~/.operit/voice-analysis/venv/bin/python || python3 -m venv ~/.operit/voice-analysis/venv) && " +
                "(test -f ~/.operit/voice-analysis/.deps-1.2.1-0.11.0 || (" +
                "~/.operit/voice-analysis/venv/bin/python -m pip install --disable-pip-version-check faster-whisper==1.2.1 librosa==0.11.0 && " +
                "touch ~/.operit/voice-analysis/.deps-1.2.1-0.11.0)) && " +
                "printf %s '${script}' | base64 -d > ~/.operit/voice-analysis/analyze.py")
            val command = "~/.operit/voice-analysis/venv/bin/python ~/.operit/voice-analysis/analyze.py "
            onStage("发送中 · 本地 Whisper small 词时间（只用已缓存模型）")
            run("HF_HUB_OFFLINE=1 " + command + "transcribe " + source)
            onStage("发送中 · 本地音量与音高（每 0.1 秒）")
            run(command + "features " + source)
            onStage("发送中 · 对齐词时间与停顿")
            val output = run(command + "align " + source)
            val jsonLine = output.lineSequence().lastOrNull { it.trim().startsWith("{\"context\":") }
            val summary = JSONObject(checkNotNull(jsonLine) { "本地分析没有返回完整结果" }).getString("context")
            return@withContext "\n【本地声学测量】Whisper 的词级边界是估计值，不保证逐字准确；词间间隔可能包含未识别声音，不等于静音。" +
                "音量为数字录音 dBFS（不是声压级），音高为有声帧平均 Hz。这里的词不作身份判断，也不替代原模型的声源判断和转写。\n" + summary
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { terminal.disconnect() }
        }
    }
}
