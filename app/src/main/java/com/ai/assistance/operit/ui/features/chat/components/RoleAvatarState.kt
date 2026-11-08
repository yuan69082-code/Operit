package com.ai.assistance.operit.ui.features.chat.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One lightweight subscription for all visible bubbles; no Main-thread blocking or per-message card scans. */
@OptIn(ExperimentalCoroutinesApi::class)
private class RoleAvatarState(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val avatars = CharacterCardManager.getInstance(context).characterNamesFlow
        .distinctUntilChanged()
        .flatMapLatest { names ->
            UserPreferencesManager.getInstance(context).getAiAvatarsForCharacterCardsFlow(names.values)
                .map { avatars -> names.mapValues { (_, id) -> avatars[id] } }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    companion object {
        @Volatile private var instance: RoleAvatarState? = null
        fun get(context: Context): RoleAvatarState = instance ?: synchronized(this) {
            instance ?: RoleAvatarState(context.applicationContext).also { instance = it }
        }
    }
}

@Composable
internal fun rememberRoleAvatar(name: String?, defaultAvatar: String?): String? {
    val context = LocalContext.current
    val state = remember(context) { RoleAvatarState.get(context) }
    val avatars by state.avatars.collectAsState()
    // Preserve the existing distinction between an unknown role and a known role without an override.
    return if (name != null && avatars.containsKey(name)) avatars[name] else defaultAvatar
}
