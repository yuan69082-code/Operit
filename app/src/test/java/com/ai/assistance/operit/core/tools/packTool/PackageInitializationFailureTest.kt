package com.ai.assistance.operit.core.tools.packTool

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import com.ai.assistance.operit.core.companion.OperitStorageTestRunner
import com.ai.assistance.operit.core.tools.AIToolHandler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Real catalog initialization/public reads, with only the asset boundary failing. */
@RunWith(OperitStorageTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PackageInitializationFailureTest {
    private fun manager(listFailure: Boolean): PackageManager {
        val app = RuntimeEnvironment.getApplication()
        val assets = Mockito.mock(AssetManager::class.java) { call -> when (call.method.name) {
            "list" -> if (listFailure) throw UnsatisfiedLinkError("catalog loader unavailable") else arrayOf("broken.js")
            "open" -> throw UnsatisfiedLinkError("script runtime unavailable")
            else -> Mockito.RETURNS_DEFAULTS.answer(call)
        } }
        val context = object : ContextWrapper(app) { override fun getAssets() = assets }
        return PackageManager::class.java.getDeclaredConstructor(Context::class.java, AIToolHandler::class.java)
            .apply { isAccessible = true }.newInstance(context, AIToolHandler.getInstance(app))
    }

    @Test(timeout = 30_000) fun catalogLinkageFailureCompletesAllBackgroundWaiters() {
        val manager = manager(true)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reads = (1..2).map { pool.submit(Callable { manager.getAvailablePackages() }) }
            reads.forEach { read ->
                try { read.get(10, TimeUnit.SECONDS); fail("failed catalog cannot report success") }
                catch (error: ExecutionException) {
                    assertTrue(error.cause is IllegalStateException)
                    assertTrue(error.stackTraceToString().contains("catalog loader unavailable"))
                }
            }
        } finally { pool.shutdownNow() }
    }

    @Test(timeout = 30_000) fun brokenOptionalRuntimeIsRecordedWithoutHangingTheCatalog() {
        val manager = manager(false)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit(Callable {
                val packages = manager.getAvailablePackages()
                Triple(packages, manager.getPackageLoadErrors(), manager.getAvailablePackages())
            }).get(10, TimeUnit.SECONDS)
            assertFalse(result.first.containsKey("broken"))
            assertFalse(result.third.containsKey("broken"))
            assertTrue(result.second.getValue("broken").contains("script runtime unavailable"))
            assertTrue(result.second.getValue("broken").contains("broken.js"))
        } finally { pool.shutdownNow() }
    }
}
