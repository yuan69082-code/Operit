package com.ai.assistance.operit.core.companion

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DreamShelfTest {
    private fun dream() = JSONObject().put("storage_kind", "dream").put("id", "dream-a").put("version", 1)
        .put("owner", "role-a").put("at", 100L).put("content", JSONObject().put("title", "海上的书房")
            .put("content", "完整梦境不自动进上下文。".repeat(1000)))

    @Test fun indexContainsDateAndRetrievalButNotDreamBody() {
        val d = dream()
        val before = d.toString()
        val index = DreamShelf.index(d, 200)
        assertEquals("dream_index", index.getString("type"))
        assertTrue(index.getString("text").contains("date"))
        assertFalse(index.toString().contains("完整梦境"))
        assertTrue(index.getString("retrieval").contains("id=dream-a"))
        assertEquals(before, d.toString())
    }

    @Test fun chosenRetentionExpiresIntoArchiveWithoutErasingTheDreamOrEmotion() {
        val d = dream()
        val review = DreamShelf.review(d, JSONObject().put("id", "understanding-a").put("meaning", "值得保留两天")
            .put("retention", "active").put("retain_until", 172800100L).put("keywords", JSONArray().put("月亮")), 100)!!
        d.put("dream_review", review)
        assertFalse(DreamShelf.archived(d, 172800099L))
        assertTrue(DreamShelf.archived(d, 172800100L))
        assertTrue(d.getJSONObject("content").getString("content").contains("完整梦境"))
        assertFalse(d.has("emotion"))
        assertTrue(DreamShelf.index(d, 172800100L).getString("text").contains("月亮"))
    }
}
