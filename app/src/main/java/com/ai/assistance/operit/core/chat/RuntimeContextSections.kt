package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.companion.InternalStateFrame

/** Keep host instructions separate from changing observations without omitting either. */
data class RuntimeContextSections(
    val instructions: String = "",
    val state: String = "",
    val internalStateFrame: InternalStateFrame? = null,
) {
    fun asText(): String = instructions + state
}
