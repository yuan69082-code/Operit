package com.ai.assistance.operit.core.group

import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.stream.SharedStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.share
import com.ai.assistance.operit.util.stream.stream
import kotlinx.coroutines.CoroutineScope

object GroupGamePrivacy {
    fun publicText(text: String): String = ChatUtils.removeThinkingContent(text)
        .replace(Regex("<(${ChatMarkupRegex.TOOL_TAG_NAME_REGEX_SOURCE})\\b[^>]*name\\s*=\\s*[\"'](?:group_game|group_chat|assistant_admin|assistant_relationship|companion_todo|companion_library|companion_state|phone_awareness)[\"'][^>]*>[\\s\\S]*?(?:</\\1>|$)", RegexOption.IGNORE_CASE), "[工具内部操作不在群公共上下文中展开]")
        .replace(Regex("<(${ChatMarkupRegex.TOOL_RESULT_TAG_NAME_REGEX_SOURCE})\\b[^>]*name\\s*=\\s*[\"'](?:group_chat|assistant_admin|assistant_relationship|companion_todo|companion_library|companion_state|phone_awareness)[\"'][^>]*>[\\s\\S]*?(?:</\\1>|$)", RegexOption.IGNORE_CASE), "[此助手查询的私人资料不在群公共上下文中展开]")

    // The provider/tool loop runs normally upstream. Publish only after secret tool arguments
    // and reasoning have been removed, so neither streaming UI nor persisted history exposes them.
    fun publicDisplay(upstream: Stream<String>, scope: CoroutineScope): SharedStream<String> = stream<String> {
        val result = StringBuilder()
        upstream.collect { result.append(it) }
        emit(publicText(result.toString()))
    }.share(scope, replay = 1)
}
