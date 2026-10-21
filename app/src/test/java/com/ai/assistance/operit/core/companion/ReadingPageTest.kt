package com.ai.assistance.operit.core.companion

import org.junit.Assert.*
import org.junit.Test

class ReadingPageTest {
    @Test fun keepsParagraphsAndImagesInSourceOrder() {
        val blocks = ReadingPage.extract("""<header>导航</header><article><p>第一段<strong>强调</strong>。</p><figure><img src="../sky.jpg" alt="星空"><figcaption>原图说明</figcaption></figure><p>第二段。</p><script>不可执行</script></article>""", "https://example.com/posts/story")
        assertEquals(4, blocks.length())
        assertEquals("第一段强调。", blocks.getJSONObject(0).getString("text"))
        assertEquals("https://example.com/sky.jpg", blocks.getJSONObject(1).getString("url"))
        assertEquals("星空", blocks.getJSONObject(1).getString("alt"))
        assertEquals("第一段强调。\n\n原图说明\n\n第二段。", ReadingPage.text(blocks))
    }

    @Test fun oldStylePlainPagesStillHaveReadableText() {
        val blocks = ReadingPage.extract("<body>旧网页文字<br>下一行</body>", "https://example.com")
        assertEquals("旧网页文字\n\n下一行", ReadingPage.text(blocks))
    }

    @Test fun rejectsNonWebAndCredentialImagesAndDeduplicates() {
        val blocks = ReadingPage.extract("""<article><p>正文</p><img src="data:image/png;base64,bad"><img src="file:///private"><img src="https://user:secret@example.com/a"><img src="/a.jpg"><img src="/a.jpg"></article>""", "https://example.com")
        assertEquals(blocks.toString(), 2, blocks.length())
        assertEquals("https://example.com/a.jpg", blocks.getJSONObject(1).getString("url"))
        assertNull(ReadingPage.imageAddress("javascript:alert(1)"))
    }

    @Test fun limitsSourceSizeAndImageCount() {
        val images = (1..30).joinToString("") { "<img src='/image$it.jpg'>" }
        val blocks = ReadingPage.extract("<article>$images<p>${"字".repeat(20000)}</p><p>超过正文范围</p></article>", "https://example.com")
        assertEquals(13, blocks.length())
        assertEquals(16000, ReadingPage.text(blocks).length)
        assertFalse(ReadingPage.text(blocks).contains("超过正文范围"))
    }

    @Test fun paragraphSeparatorsAreIncludedInTheTextBudget() {
        val html = (1..600).joinToString("") { "<p>${"段".repeat(100)}</p>" }
        val blocks = ReadingPage.extract("<article>$html</article>", "https://example.com")
        assertTrue(ReadingPage.text(blocks).length <= 16000)
        val tiny = ReadingPage.extract("<article>${"<p>字</p>".repeat(2000)}</article>", "https://example.com")
        assertTrue(tiny.length() <= 256)
    }
}
