package com.ai.assistance.operit.core.group

import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.stream.SharedStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.share
import com.ai.assistance.operit.util.stream.stream
import kotlinx.coroutines.CoroutineScope

object GroupGamePrivacy {
    // Older releases persisted these redaction notices as chat text. Hide only the exact
    // generated notices; the original stored message is not rewritten.
    fun withoutInternalNotices(text: String): String = text
        .replace("[工具内部操作不在群公共上下文中展开]", "")
        .replace("[此助手查询的私人资料不在群公共上下文中展开]", "")

    fun publicText(text: String): String = withoutInternalNotices(ChatUtils.removeThinkingContent(text))
        .replace(Regex("<(${ChatMarkupRegex.TOOL_TAG_NAME_REGEX_SOURCE})\\b[^>]*name\\s*=\\s*[\"'](?:group_game|group_chat|assistant_admin|assistant_relationship|companion_todo|companion_explore|companion_library|companion_state|phone_awareness|software_awareness|companion_capabilities|companion_room|companion_diary|companion_reading)[\"'][^>]*>[\\s\\S]*?(?:</\\1>|$)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<(${ChatMarkupRegex.TOOL_RESULT_TAG_NAME_REGEX_SOURCE})\\b[^>]*name\\s*=\\s*[\"'](?:group_chat|assistant_admin|assistant_relationship|companion_todo|companion_explore|companion_library|companion_state|phone_awareness|software_awareness|companion_capabilities|companion_room|companion_diary|companion_reading)[\"'][^>]*>[\\s\\S]*?(?:</\\1>|$)", RegexOption.IGNORE_CASE), "")

    // The provider/tool loop runs normally upstream. Publish only after secret tool arguments
    // and reasoning have been removed, so neither streaming UI nor persisted history exposes them.
    fun publicDisplay(upstream: Stream<String>, scope: CoroutineScope): SharedStream<String> = stream<String> {
        val result = StringBuilder()
        upstream.collect { result.append(it) }
        emit(publicText(result.toString()))
    }.share(scope, replay = 1)
}
