package com.ai.assistance.operit.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerVersionTest {
    @Test
    fun `shared release suffix keeps the patch number`() {
        // Before the fix "2-pc" parsed as 0, so 1.12.2-pc.1 looked older than 1.12.1.
        assertTrue(UpdateManager.compareVersions("1.12.2-pc.1+120", "1.12.1+999") > 0)
        assertEquals(0, UpdateManager.compareVersions("1.12.2-pc.1+5", "1.12.2+5"))
    }

    @Test
    fun `newer personal build of shared release is offered over plain build`() {
        assertTrue(UpdateManager.compareVersions("v1.12.2-pc.1+121", "1.12.2+120") > 0)
        assertTrue(UpdateManager.compareVersions("1.12.2+119", "1.12.2-pc.1+120") < 0)
    }
}
