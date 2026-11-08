package com.ai.assistance.operit.core.companion

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.core.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(OperitStorageTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class InternalStateStorageIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(root: File) = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = root
    }
    private fun filename(scope: String) = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test fun migrationAndInterruptedArchiveCommitPreserveRowsAndAtomicRestart() {
        val root = temporary.newFolder()
        val directory = File(root, "companion").apply { mkdirs() }
        val key = "internal-state:owner"
        val original = JSONObject().put("semantic_revision", 17).put("sequence", 6500)
            .put("definitions", JSONArray()).put("instances", JSONArray().put(JSONObject()
                .put("dimension", "legacy").put("scope", "old-window").put("value", 83).put("at", 22).put("since", 11)))
            .put("unknown_original", JSONObject().put("object", "未完成的旧事项"))
            .put("events", JSONArray((1..6500).map { i -> JSONObject().put("id", "message:c:user:$i")
                .put("sequence", i).put("at", i).put("source", "user_message").put("chat", "c")
                .put("detail", "原文$i").put("changes", JSONArray()) }))
            .put("versions", JSONArray((1..650).map { v -> JSONObject().put("dimension", "legacy").put("version", v)
                .put("after", JSONObject().put("id", "legacy").put("version", v).put("private", true)) }))
        val hot = File(directory, filename(key) + ".json")
        hot.writeText(original.toString()) // Fixture from an existing installation.
        // Simulate power loss after the archive transaction, before replacing the old file.
        InternalStateArchive(File(directory, "internal-state-history.sqlite"), key).use { archive ->
            repeat(100) { archive.put("events", original.getJSONArray("events").getJSONObject(it)) }
            archive.commit()
        }
        val store = CompanionStore(context(root))
        val adopted = store.read(key)
        assertEquals(original.getJSONArray("instances").toString(), adopted.getJSONArray("instances").toString())
        assertEquals(original.getJSONObject("unknown_original").toString(), adopted.getJSONObject("unknown_original").toString())
        assertEquals(17, adopted.getInt("semantic_revision"))
        assertEquals(3000, adopted.getJSONArray("events").length())
        assertEquals(300, adopted.getJSONArray("versions").length())
        assertFalse(adopted.has("archived_contacts"))
        assertEquals("原文1", store.archivedStateRow(key, "events", "message:c:user:1")!!.getString("detail"))
        assertEquals(1, (adopted as InternalStateDocument).history.definition("legacy", 1)!!.getInt("version"))
        val checkpoint = File(directory, filename(key) + ".subject-v0")
        assertTrue(checkpoint.isFile)
        val savedCheckpoint = checkpoint.readBytes()
        assertEquals(0L, SubjectStateBoundary.revision(CompanionStore(context(root)).read(key)))
        assertArrayEquals(savedCheckpoint, checkpoint.readBytes())
        val atomic = AtomicFile(hot)
        atomic.startWrite().use { it.write("interrupted".toByteArray()) } // No finishWrite: emulate lost process.
        val recovered = CompanionStore(context(root)).read(key)
        assertEquals(adopted.getJSONArray("instances").toString(), recovered.getJSONArray("instances").toString())
        assertEquals(0L, SubjectStateBoundary.revision(recovered))
        assertFalse(recovered.getJSONArray("instances").getJSONObject(0).has("reason"))
    }

    @Test fun archiveReadsArePagedAndCurrentReadDoesNotMaterializeContactHistory() {
        val root = temporary.newFolder()
        val directory = File(root, "companion").apply { mkdirs() }
        val key = "internal-state:owner"
        InternalStateArchive(File(directory, "internal-state-history.sqlite"), key).use { archive ->
            repeat(12000) { i -> archive.put("events", JSONObject().put("id", "message:c:user:$i")
                .put("sequence", i + 1).put("source", "user_message").put("chat", "c").put("contact_at", i.toLong()).put("at", i.toLong())) }
            archive.commit()
            assertEquals(256, archive.contactPage(null, -1, 12000).size)
            assertEquals(11999L, archive.latestContact(null, 12000))
        }
        val start = System.nanoTime()
        repeat(10) {
            val current = CompanionStore(context(root)).read(key)
            assertFalse(current.has("archived_contacts"))
            assertTrue(InternalStateEngine.rows(current, "events").isEmpty())
            assertTrue(current.toString().length < 1000)
        }
        // Generous regression ceiling, not a device latency claim.
        assertTrue("bounded snapshot reads exceeded 10 seconds", System.nanoTime() - start < 10_000_000_000L)
    }

    @Test fun failedMutationLeavesAcceptedSnapshotAndGenericWritesAreRejected() {
        val store = CompanionStore(context(temporary.newFolder()))
        store.updateSubject("owner", "internal-state", "user_edit") { it.put("kept", "实际接受") }
        val before = store.read("internal-state:owner").toString()
        assertThrows(java.io.IOException::class.java) {
            store.updateSubject("owner", "internal-state", "state_report") { it.put("kept", "不能保存"); throw java.io.IOException("disk failure") }
        }
        assertEquals(before, store.read("internal-state:owner").toString())
        assertThrows(IllegalStateException::class.java) { store.update("internal-state:owner") { it.put("kept", "绕过入口") } }
    }
}
