package com.ai.assistance.operit.core.companion

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CompanionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(root: File): CompanionStore {
        val context = mock<Context>()
        val preferences = mock<SharedPreferences>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(root)
        whenever(context.getSharedPreferences("companion_options", Context.MODE_PRIVATE)).thenReturn(preferences)
        return CompanionStore(context)
    }

    @Test fun roleLibraryPersistsAcrossWindowsWithoutLeakingIntoAnotherRole() {
        val root = temporary.newFolder()
        val first = store(root)
        val role = first.scope("window-one", "role-one")
        val saved = first.saveEntry(role, null, "音频笔记", "知识/声音", "knowledge", "保留这句话", null)
        val reopened = store(root)
        assertEquals(saved.getString("id"), reopened.entries(reopened.scope("window-two", "role-one")).single().getString("id"))
        assertTrue(reopened.entries(reopened.scope("window-one", "role-two")).isEmpty())
        assertTrue(reopened.entries(reopened.scope("window-one", null)).isEmpty())
        assertEquals(listOf("知识/声音"), reopened.categories(role))
    }

    @Test fun libraryOwnsFileCopyAndDeletingEntryPreservesOriginal() {
        val original = temporary.newFile("conversation.txt").apply { writeText("原始文件") }
        val library = store(temporary.newFolder())
        val saved = library.saveEntry("role:r", null, "聊天文件", "文件", "file", "", original.path)
        val owned = File(saved.getString("file"))
        assertNotEquals(original.canonicalPath, owned.canonicalPath)
        original.writeText("原文件后来修改了")
        assertEquals("原始文件", owned.readText())
        library.deleteEntry("role:r", saved.getString("id"))
        assertFalse(owned.exists())
        assertEquals("原文件后来修改了", original.readText())
        assertTrue(library.entries("role:r").isEmpty())
    }

    @Test fun failedMemoryUpdateKeepsPreviouslyProtectedText() {
        val root = temporary.newFolder()
        val memory = store(root)
        memory.setMemory("chat", "pin", "保留", "逐字原文")
        memory.setMemory("chat", "draft", "", "AI 写的摘要")
        assertThrows(IllegalArgumentException::class.java) {
            memory.setMemory("chat", "pin", "过大", "字".repeat(16001))
        }
        val reopened = store(root)
        assertEquals("逐字原文", reopened.read("summary:chat").getJSONObject("pins").getString("保留"))
        assertFalse(reopened.read("summary:chat").getJSONObject("pins").has("过大"))
        assertTrue(reopened.memoryContext("chat").contains("AI 写的摘要"))
        assertEquals("", reopened.memoryContext("another-chat"))
        reopened.setMemory("chat", "unpin", "保留", "")
        assertFalse(reopened.memoryContext("chat").contains("逐字原文"))
    }

    @Test fun updatingEntryMovesCategoryWithoutLosingItsIdOrFile() {
        val library = store(temporary.newFolder())
        val original = temporary.newFile("skill.txt").apply { writeText("步骤") }
        val saved = library.saveEntry("role:r", null, "技巧", "未分类", "file", "", original.path)
        val moved = library.saveEntry("role:r", saved.getString("id"), "整理后的技巧", "能力/交流", "skill", "补充知识", null)
        assertEquals(saved.getString("file"), moved.getString("file"))
        assertEquals(saved.getString("id"), library.entries("role:r").single().getString("id"))
        assertEquals("能力/交流", library.entries("role:r").single().getString("category"))
    }
}
