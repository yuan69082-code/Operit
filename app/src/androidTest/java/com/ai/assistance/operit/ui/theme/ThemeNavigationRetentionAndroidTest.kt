package com.ai.assistance.operit.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.ThemePreferenceValues
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.main.layout.ChatNavigationState
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real outer theme: a new subtree cannot recover rememberSaveable state. */
@RunWith(AndroidJUnit4::class)
class ThemeNavigationRetentionAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directoryWithoutBackgroundToConversationWithBackgroundRetainsNavigation() =
        verifyNavigationRetention(sharedBackground = false)

    @Test fun directoryWithBackgroundToConversationWithoutBackgroundRetainsNavigation() =
        verifyNavigationRetention(sharedBackground = true)

    private fun verifyNavigationRetention(sharedBackground: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = UserPreferencesManager.getInstance(context)
        val active = ActivePromptManager.getInstance(context)
        val prompt = runBlocking { active.getActivePrompt() }
        val originalActive = runBlocking { active.activeThemePreferenceSnapshotFlow.first().values }
        val originalShared = runBlocking { preferences.observeSharedAppearance().first().values }
        val background = File.createTempFile("theme-navigation-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        background.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        fun appearance(enabled: Boolean) = ThemePreferenceValues.defaultVisual()
            .withBoolean("use_background_image", enabled)
            .withString("background_image_uri", background.toURI().toString())
        var currentNavigation: ChatNavigationState? = null
        var currentMarker: Any? = null
        try {
            runBlocking {
                preferences.saveSharedAppearance(appearance(sharedBackground))
                preferences.replaceThemeForPrompt(prompt, appearance(!sharedBackground))
            }
            compose.setContent {
                OperitTheme(sharedOutsideChat = true) {
                    val navigation = rememberSaveable(saver = ChatNavigationState.saver) { ChatNavigationState() }
                    val marker = remember { Any() }
                    val scope = requireNotNull(LocalAppThemeScope.current)
                    val snapshot = LocalThemePreferenceSnapshot.current
                    SideEffect {
                        currentNavigation = navigation
                        currentMarker = marker
                        scope.conversationVisible = !navigation.directoryVisible
                    }
                    Column {
                        Text("background:${snapshot.useBackgroundImage}")
                        if (navigation.directoryVisible) {
                            Button(onClick = navigation::openConversation) { Text("Open conversation") }
                        } else {
                            Text("Conversation")
                            Button(onClick = navigation::showDirectory) { Text("Back to directory") }
                        }
                    }
                }
            }
            compose.onNodeWithText("background:$sharedBackground").assertIsDisplayed()
            val initialNavigation = currentNavigation
            val initialMarker = currentMarker
            repeat(2) {
                compose.onNodeWithText("Open conversation").performClick()
                compose.onNodeWithText("background:${!sharedBackground}").assertIsDisplayed()
                compose.onNodeWithText("Conversation").assertIsDisplayed()
                compose.runOnIdle {
                    assertSame(initialNavigation, currentNavigation)
                    assertSame(initialMarker, currentMarker)
                }
                compose.onNodeWithText("Back to directory").performClick()
                compose.onNodeWithText("background:$sharedBackground").assertIsDisplayed()
                compose.runOnIdle {
                    assertSame(initialNavigation, currentNavigation)
                    assertSame(initialMarker, currentMarker)
                }
            }
        } finally {
            runBlocking {
                preferences.replaceThemeForPrompt(prompt, originalActive)
                preferences.saveSharedAppearance(originalShared)
            }
            background.delete()
        }
    }
}
