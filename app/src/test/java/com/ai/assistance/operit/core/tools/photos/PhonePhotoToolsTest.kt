package com.ai.assistance.operit.core.tools.photos

import org.junit.Assert.*
import org.junit.Test

class PhonePhotoToolsTest {
    @Test fun paginationIsBoundedAndNeverSilentlyChangesInvalidArguments() {
        assertEquals(12, PhonePhotoTools.integer(null, 12, 1..20))
        assertEquals(20, PhonePhotoTools.integer("20", 12, 1..20))
        listOf("0", "-1", "21", "x", "999999999999").forEach {
            try { PhonePhotoTools.integer(it, 12, 1..20); fail("Invalid page accepted") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
    }
}
