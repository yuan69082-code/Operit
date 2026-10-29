package com.ai.assistance.operit.core.chat

/** Keep host instructions separate from changing observations without omitting either. */
data class RuntimeContextSections(
    val instructions: String = "",
    val state: String = ""
) {
    fun asText(): String = instructions + state
}
