package com.ai.assistance.operit.core.experience

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.ui.theme.RenderInspection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class CollectionLinksTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun withStore(block: (CollectionStore) -> Unit) {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(temporary.root)
        // JVM Android stubs cannot implement AtomicFile. Replace only the platform I/O boundary.
        Mockito.mockConstruction(AtomicFile::class.java) { atomic, construction ->
            val file = construction.arguments()[0] as File
            whenever(atomic.startWrite()).thenAnswer { FileOutputStream(file) }
            whenever(atomic.openRead()).thenAnswer { FileInputStream(file) }
            doAnswer { (it.arguments[0] as FileOutputStream).close(); null }.whenever(atomic).finishWrite(any())
            doAnswer { file.delete(); null }.whenever(atomic).delete()
        }.use { block(CollectionStore(context)) }
    }
    private fun create(store: CollectionStore, owner: String, text: String) =
        store.create(owner, "chat", ChatMessage(sender = "user", content = text, timestamp = 10L))

    @Test fun referenceSurvivesSourceDeletionAndPromotesWithoutOverwrite() = withStore { store ->
        val project = create(store, "a", "project"); val source = create(store, "a", "reference body")
        var item = store.addReference(project, source, null, store.read(project).getLong("version"))
        val reference = store.references(project).getJSONObject(0).getString("id")
        store.delete(source)
        assertEquals(1, store.references(project, "BODY").length())
        item = store.promoteReference(project, reference, "docs/notes.md", item.getLong("version"))
        assertEquals("reference body", item.getJSONObject("files").getString("docs/notes.md"))
        assertThrows(IllegalStateException::class.java) { store.promoteReference(project, reference, "docs/notes.md", item.getLong("version")) }
    }
    @Test fun foreignOwnerAndStaleVersionCannotChangeReferences() = withStore { store ->
        val project = create(store, "a", "project"); val foreign = create(store, "b", "private")
        val version = store.read(project).getLong("version")
        assertThrows(IllegalStateException::class.java) { store.addReference(project, foreign, null, version) }
        assertThrows(IllegalStateException::class.java) { store.addReference(project, project, null, version - 1) }
        assertEquals(0, store.references(project).length())
    }
    @Test fun workspaceRoundTripRejectsConcurrentProjectChanges() = withStore { store ->
        val id = create(store, "a", "project")
        var item = store.saveFile(id, "index.html", "before", store.read(id).getLong("version"))
        item = store.exportWorkspace(id, item.getLong("version"))
        val dir = File(item.getJSONObject("workspace").getString("path"))
        File(dir, "index.html").writeText("disk edit")
        item = store.pullWorkspace(id, item.getLong("version"))
        assertEquals("disk edit", item.getJSONObject("files").getString("index.html"))
        item = store.saveFile(id, "index.html", "app edit", item.getLong("version"))
        File(dir, "index.html").writeText("second disk edit")
        assertThrows(IllegalStateException::class.java) { store.pullWorkspace(id, item.getLong("version")) }
        assertEquals("app edit", store.read(id).getJSONObject("files").getString("index.html"))
        assertEquals("second disk edit", File(dir, "index.html").readText())
    }
    @Test fun binaryFilesCannotSilentlyCorruptProjectText() {
        val dir = temporary.newFolder(); File(dir, "image.bin").writeBytes(byteArrayOf(0, -1))
        assertThrows(Exception::class.java) { WorkspaceTransfer.read(dir) }
    }
    @Test fun liveInspectionSeparatesOwnersAndRemovesDisposedNodes() {
        RenderInspection.register("test-a", "a") { JSONObject().put("target", "send_button") }
        RenderInspection.register("test-b", "b") { JSONObject().put("target", "attachment") }
        try {
            assertEquals("send_button", RenderInspection.inspect("a").getJSONArray("nodes").getJSONObject(0).getString("target"))
            RenderInspection.remove("test-a")
            assertEquals(0, RenderInspection.inspect("a").getJSONArray("nodes").length())
        } finally { RenderInspection.remove("test-a"); RenderInspection.remove("test-b") }
    }
}
