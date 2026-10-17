package com.ai.assistance.operit.data.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalUpdateManifestTest {
    @Test
    fun `shared build 8 updates standalone build 118 using Android version code`() {
        val release = PersonalUpdateManifest.parse(
            """{"version":"1.12.2-pc.1+8","version_code":5300008,"android":"https://github.com/example/app-debug.apk","protocol":1}"""
        )
        assertEquals("1.12.2-pc.1+8", release.version)
        assertTrue(release.isNewerThan(5200118))
        assertFalse(release.isNewerThan(5300008))
        assertFalse(release.isNewerThan(5300009))
    }

    @Test
    fun `old standalone 114 cannot replace installed shared build 8`() {
        val release = PersonalUpdateManifest(
            version = "1.12.2-pc.1+114",
            versionCode = 5200114
        )
        assertFalse(release.isNewerThan(5300008))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing usable version code is rejected`() {
        PersonalUpdateManifest.parse(
            """{"version":"1.12.2-pc.1+9","version_code":0,"android":"https://github.com/example/app-debug.apk"}"""
        )
    }
}
