package com.ai.assistance.operit.ui.features.settings.screens

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.companion.InternalStateStore
import com.ai.assistance.operit.core.companion.AssistantWindowRouter
import com.ai.assistance.operit.core.companion.OperitStorageTestRunner
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Actual Android Compose + storage, with a new test assistant and no model/network call.
 * Native graphics in Robolectric is not a physical phone or real-model acceptance test. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateInnerStateNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test(timeout = 120_000) fun installedCategoriesAndPagedSurvivalAreRealNativeViews() {
        val context = RuntimeEnvironment.getApplication()
        val role = runBlocking { CharacterCardManager.getInstance(context).createCharacterCard(CharacterCard("", "原生页面验证助手")) }
        val chat = ChatHistory(title = "原生页面验证", messages = emptyList(), characterCardId = role)
        runBlocking { ChatHistoryManager.getInstance(context).saveChatHistory(chat) }
        AssistantWindowRouter.selected(context, chat)
        context.getSharedPreferences("companion_options", Context.MODE_PRIVATE).edit()
            .putBoolean("internal_state:$role", true).commit()
        val states = InternalStateStore(context)
        states.snapshot(role, chat.id, false)
        val prior = states.read(role).optLong("semantic_revision")
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { InternalStatePanel(role) }
                }
            }
        }
        try {
            compose.waitUntil(30_000) { compose.onAllNodesWithText("亲近").fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            println(compose.onRoot().printToString())
            runCatching { screenshot("native-current-load-failure") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        compose.onNodeWithText("亲近").assertExists()
        screenshot("native-current-installed")
        compose.onNodeWithText("需要").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("生").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("下一页")[0].performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("求生欲").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("求生欲").performScrollTo().assertIsDisplayed()
        screenshot("native-needs-page-two-survival")
        org.junit.Assert.assertEquals("Opening and paging must not adopt or create a new experience", prior,
            states.read(role).optLong("semantic_revision"))
        org.junit.Assert.assertFalse(states.read(role).getJSONObject("foundation_state").getJSONObject("survival").getBoolean("formed"))
    }

    private fun screenshot(name: String) {
        // This Compose version waits for a hardware frame-commit callback which Robolectric
        // does not produce. PixelCopy's native-graphics shadow captures the actual View tree
        // synchronously instead (AndroidX fix bcef06da), without changing production Compose.
        val image = compose.runOnIdle {
            val window = compose.activity.window
            val view = window.decorView
            org.junit.Assert.assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            var result = -1
            PixelCopy.request(window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
            org.junit.Assert.assertEquals(PixelCopy.SUCCESS, result)
            bitmap
        }
        val output = File("build/reports/native-private-state/$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use { org.junit.Assert.assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        org.junit.Assert.assertTrue(output.length() > 1000)
    }
}
