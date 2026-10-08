package com.ai.assistance.operit.ui.features.chat.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.features.chat.voice.VoiceCallRuntime
import com.ai.assistance.operit.ui.floating.FloatingMode
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf

@Composable
fun useFloatingWindowLauncher(
    actualViewModel: ChatViewModel,
    permissionLauncher: ActivityResultLauncher<String>
): () -> Unit {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    return {
        actualViewModel.onFloatingButtonClick(
            FloatingMode.WINDOW,
            permissionLauncher,
            colorScheme,
            typography,
            moveTaskToBackOnReady = true
        )
    }
}

@Composable
fun ChatScreenHeader(
        modifier: Modifier = Modifier,
        actualViewModel: ChatViewModel,
        showChatHistorySelector: Boolean,
        chatHeaderTransparent: Boolean,
        chatHeaderHistoryIconColor: Int?,
        chatHeaderPipIconColor: Int?,
        onCharacterSwitcherClick: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    LaunchedEffect(actualViewModel, context) {
        actualViewModel.moveTaskToBackEvents.collect {
            (context as? android.app.Activity)?.moveTaskToBack(true)
        }
    }

    var voiceCallChatId by remember { mutableStateOf<String?>(null) }
    var voiceCallStartError by remember { mutableStateOf("") }
    var showVoiceCall by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.ai.assistance.operit.ui.features.chat.voice.VoiceCallIncoming.restore(context.applicationContext) }
    val incomingCall = com.ai.assistance.operit.ui.features.chat.voice.VoiceCallIncoming.pending
    val streamingCallChats by actualViewModel.activeStreamingChatIds.collectAsState()
    var answerAnalysis by remember { mutableStateOf(true) }
    fun answerIncoming(analyze: Boolean) {
        val request = com.ai.assistance.operit.ui.features.chat.voice.VoiceCallIncoming.pending ?: return
        try {
            check(request.expiresAt > System.currentTimeMillis()) { "来电已超时" }
            check(!streamingCallChats.contains(request.chatId)) { "来电会话仍在处理，请稍后接听" }
            if (analyze) com.ai.assistance.operit.ui.features.chat.voice.VoiceCallAudioAnalysis(context).requireConfigured()
            VoiceCallRuntime.open(context, actualViewModel, request.chatId, nativeAudio = false,
                audioAnalysis = analyze, callerRoleCardId = request.roleCardId, incoming = true)
            com.ai.assistance.operit.ui.features.chat.voice.VoiceCallIncoming.clear(context)
            showVoiceCall = true
        } catch (error: Exception) {
            com.ai.assistance.operit.util.AppLogger.e("VoiceCall", "Could not answer incoming call", error)
            actualViewModel.showToast(error.message.orEmpty())
        }
    }
    val incomingPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) answerIncoming(answerAnalysis)
        else actualViewModel.showToast(context.getString(R.string.microphone_permission_denied))
    }
    if (incomingCall != null) {
        fun acceptIncoming(analyze: Boolean) {
            answerAnalysis = analyze
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                answerIncoming(analyze)
            } else incomingPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
        AlertDialog(
            onDismissRequest = { com.ai.assistance.operit.ui.features.chat.voice.VoiceCallIncoming.clear(context) },
            title = { Text("${incomingCall.callerName} 来电") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(incomingCall.reason.ifBlank { "想和你说说话" })
                    if (streamingCallChats.contains(incomingCall.chatId)) Text("正在结束当前回复，稍后可以接听。")
                    com.ai.assistance.operit.ui.features.settings.screens.VoiceCallAnalysisSettingsButton()
                    TextButton(onClick = { acceptIncoming(false) }, enabled = !streamingCallChats.contains(incomingCall.chatId)) { Text("使用普通转写接听") }
                }
            },
            confirmButton = { TextButton(onClick = { acceptIncoming(true) }, enabled = !streamingCallChats.contains(incomingCall.chatId)) { Text("接听（音频分析）") } },
            dismissButton = { TextButton(onClick = { com.ai.assistance.operit.ui.features.chat.voice.VoiceCallIncoming.clear(context) }) { Text("拒接") } },
        )
    }
    val runningCall = VoiceCallRuntime.controller
    val callChatId by actualViewModel.currentChatId.collectAsState()
    val callBusy by actualViewModel.currentChatIsLoading.collectAsState()
    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceCallChatId = actualViewModel.currentChatId.value
        else actualViewModel.showToast(context.getString(R.string.microphone_permission_denied))
    }
    if (showVoiceCall && runningCall != null) {
        VoiceCallDialog(runningCall) { showVoiceCall = false }
    }
    voiceCallChatId?.let { boundCallChatId ->
        fun startCall(nativeAudio: Boolean, audioAnalysis: Boolean = false) {
            try {
                if (audioAnalysis) com.ai.assistance.operit.ui.features.chat.voice.VoiceCallAudioAnalysis(context).requireConfigured()
                if (nativeAudio) check(actualViewModel.activeChatModelConfig.value?.enableDirectAudioProcessing == true) {
                    "原生音频通话需要当前聊天模型支持音频并启用直接音频处理。Claude 等文字模型请选择音频分析通话。"
                }
                VoiceCallRuntime.open(context, actualViewModel, boundCallChatId, nativeAudio, audioAnalysis)
                showVoiceCall = true
                voiceCallChatId = null
                voiceCallStartError = ""
            } catch (error: Exception) {
                com.ai.assistance.operit.util.AppLogger.e("VoiceCall", "Could not start call service", error)
                voiceCallStartError = error.message.orEmpty()
            }
        }
        AlertDialog(
            onDismissRequest = { voiceCallChatId = null; voiceCallStartError = "" },
            title = { Text(stringResource(R.string.voice_call_title)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("音频分析通话会先转写说话、语气和环境声，再发给当前聊天模型；Claude 等文字模型也能使用。首次使用请填写分析接口的 API Key。")
                    com.ai.assistance.operit.ui.features.settings.screens.VoiceCallAnalysisSettingsButton()
                    TextButton(onClick = { startCall(true) }) { Text(stringResource(R.string.voice_call_native_audio)) }
                    Text("原生音频仅用于支持音频输入的当前聊天模型；录音期间不显示转写文字。")
                    if (voiceCallStartError.isNotBlank()) Text(voiceCallStartError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = { startCall(false, true) }) { Text("音频分析通话") } },
            dismissButton = { TextButton(onClick = { startCall(false) }) { Text(stringResource(R.string.voice_call_transcription)) } },
        )
    }

    val characterCardManager = remember { CharacterCardManager.getInstance(context) }
    val characterGroupCardManager = remember { CharacterGroupCardManager.getInstance(context) }
    val activePromptManager = remember { ActivePromptManager.getInstance(context) }
    val userPreferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val activePrompt by activePromptManager.activePromptFlow.collectAsState(
        initial = ActivePrompt.CharacterCard(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID)
    )
    val activeCharacterCard by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterCard -> characterCardManager.getCharacterCardFlow(prompt.id)
            is ActivePrompt.CharacterGroup -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeCharacterGroup by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterGroup -> characterGroupCardManager.getCharacterGroupCardFlow(prompt.id)
            is ActivePrompt.CharacterCard -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeCardAvatarUri by remember(activeCharacterCard?.id) {
        activeCharacterCard?.id?.let { userPreferencesManager.getAiAvatarForCharacterCardFlow(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeGroupAvatarUri by remember(activeCharacterGroup?.id) {
        activeCharacterGroup?.id?.let { userPreferencesManager.getAiAvatarForCharacterGroupFlow(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeGroupFallbackMemberCardId = remember(activeCharacterGroup?.members) {
        val sortedMembers = activeCharacterGroup?.members?.sortedBy { it.orderIndex }.orEmpty()
        sortedMembers.firstOrNull()?.characterCardId
    }
    val activeGroupFallbackMemberAvatarUri by remember(activeGroupFallbackMemberCardId) {
        activeGroupFallbackMemberCardId?.let { userPreferencesManager.getAiAvatarForCharacterCardFlow(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeCharacterAvatarUri =
        when (activePrompt) {
            is ActivePrompt.CharacterGroup -> activeGroupAvatarUri ?: activeGroupFallbackMemberAvatarUri
            is ActivePrompt.CharacterCard -> activeCardAvatarUri
        }

    val activeStreamingChatIds by actualViewModel.activeStreamingChatIds.collectAsState()
    val isFloatingMode by actualViewModel.isFloatingMode.collectAsState()
    val currentWindowSize by actualViewModel.currentWindowSize.collectAsState()
    val maxWindowSizeInK by actualViewModel.maxWindowSizeInK.collectAsState()
    val inputTokenCount by actualViewModel.inputTokenCount.collectAsState()
    val outputTokenCount by actualViewModel.outputTokenCount.collectAsState()

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                actualViewModel.launchWindowFloatingModeAfterMicPermissionGranted(
                    colorScheme = colorScheme,
                    typography = typography,
                    moveTaskToBackOnReady = true
                )
            } else {
                actualViewModel.showToast(context.getString(R.string.microphone_permission_denied))
            }
        }


    val launchFloatingWindow = useFloatingWindowLauncher(actualViewModel, permissionLauncher)

    Row(
            modifier =
                    modifier
                            .fillMaxWidth()
                            .background(
                                    if (chatHeaderTransparent) Color.Transparent
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            )
                            .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChatHeader(
                showChatHistorySelector = showChatHistorySelector,
                onToggleChatHistorySelector = { actualViewModel.toggleChatHistorySelector() },
                modifier = Modifier.weight(1f),
                isFloatingMode = isFloatingMode,
                onLaunchFloatingWindow = launchFloatingWindow,
                historyIconColor = chatHeaderHistoryIconColor,
                pipIconColor = chatHeaderPipIconColor,
                runningTaskCount = activeStreamingChatIds.size,
                activeCharacterName = activeCharacterGroup?.name ?: activeCharacterCard?.name ?: "",
                activeCharacterAvatarUri = activeCharacterAvatarUri,
                onCharacterClick = onCharacterSwitcherClick
        )

        Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                enabled = runningCall != null || (callChatId != null && !callBusy && !isFloatingMode),
                onClick = {
                    if (runningCall != null) {
                        showVoiceCall = true
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        voiceCallChatId = callChatId
                    } else {
                        callPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            ) {
                Icon(Icons.Default.Call, contentDescription = stringResource(if (runningCall != null) R.string.voice_call_restore else R.string.voice_call_title), tint = if (runningCall != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            // 统计信息
            val maxWindowSize = (maxWindowSizeInK * 1024).toLong().coerceAtLeast(0L)
            val totalTokenCount = inputTokenCount + outputTokenCount
            val contextUsagePercentage =
                    if (maxWindowSize > 0) {
                        ((currentWindowSize.toDouble() / maxWindowSize.toDouble()) * 100.0)
                            .coerceAtMost(999.0)
                            .toFloat()
                    } else {
                        0f
                    }

            // 使用一个状态来跟踪是否显示详细信息
            val (showDetailedStats, setShowDetailedStats) = remember { mutableStateOf(false) }

            Box {
                // 主要显示（圆环进度）
                val progress = (contextUsagePercentage / 100f).coerceIn(0f, 1f)
                val animatedProgress by animateFloatAsState(targetValue = progress, label = "TokenProgressAnimation")
                val progressColor = when {
                    contextUsagePercentage > 90 -> MaterialTheme.colorScheme.error
                    contextUsagePercentage > 75 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }

                Box(
                    modifier = Modifier
                        .clickable { setShowDetailedStats(!showDetailedStats) }
                        .size(32.dp)
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = animatedProgress,
                        modifier = Modifier.fillMaxSize(),
                        color = progressColor,
                        strokeWidth = 3.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                    Text(
                        text = "${contextUsagePercentage.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = progressColor
                    )
                }

                // 简化的下拉框
                DropdownMenu(
                        expanded = showDetailedStats,
                        onDismissRequest = { setShowDetailedStats(false) },
                        modifier =
                                Modifier.width(IntrinsicSize.Min)
                                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.context_window, currentWindowSize)) },
                        onClick = {},
                        enabled = false
                    )
                    
                    DropdownMenuItem(
                            text = { Text(stringResource(R.string.input_tokens, inputTokenCount)) },
                            onClick = {},
                            enabled = false
                    )
                    DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.output_tokens, outputTokenCount))
                            },
                            onClick = {},
                            enabled = false
                    )
                    DropdownMenuItem(
                            text = {
                                Text(
                                        stringResource(R.string.total_tokens, totalTokenCount),
                                        style =
                                                MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight =
                                                                androidx.compose.ui.text.font
                                                                        .FontWeight.Bold
                                                ),
                                        color = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = {},
                            enabled = false
                    )
                    
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, isHighlighted: Boolean = false) {
    Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
    ) {
        Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
                text = value,
                style =
                        MaterialTheme.typography.labelMedium.copy(
                                fontWeight =
                                        if (isHighlighted)
                                                androidx.compose.ui.text.font.FontWeight.Bold
                                        else androidx.compose.ui.text.font.FontWeight.Normal
                        ),
                color =
                        if (isHighlighted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
        )
    }
}
