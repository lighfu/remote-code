package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectMarkdownTest {
    @Test fun automaticPcImagesRejectNetworkSharesAndKeepLocalLiteralNames() {
        assertEquals("C:/work/a b%20.png", projectMarkdownLocalImagePath("file:///C:/work/a%20b%2520.png"))
        assertEquals("/work/image.png", projectMarkdownLocalImagePath("file:///work/image.png"))
        assertNull(projectMarkdownLocalImagePath("file://server/share/image.png"))
        assertNull(projectMarkdownLocalImagePath("file:////server/share/image.png"))
        assertNull(projectMarkdownLocalImagePath("https://example.com/image.png"))
    }
    @Test fun markdownKindsIgnoreCaseAndParentNames() {
        assertTrue(isProjectMarkdown("C:\\work\\README.MD"))
        assertTrue(isProjectMarkdown("/docs/readme"))
        assertTrue(isProjectMarkdown("notes.markdown"))
        assertFalse(isProjectMarkdown("notes.md/file.json"))
    }

    @Test fun relativeTargetsUseTheMarkdownDirectoryAndPreserveEncodedLiteralNames() {
        assertEquals("file:///C:/work/assets/a%20b.png", resolveProjectMarkdownLink("C:\\work\\docs\\README.md", "../assets/a%20b.png"))
        assertEquals("file:///C:/work/docs/a%2520b%23name.kt#L12", resolveProjectMarkdownLink("C:\\work\\docs\\README.md", "a%2520b%23name.kt#L12"))
        assertEquals("file:///work/docs/a+b.md", resolveProjectMarkdownLink("/work/docs/README.md", "a+b.md"))
        assertEquals("file:///work/next.md", resolveProjectMarkdownLink("/work/docs/README.md", "../next.md"))
    }

    @Test fun absoluteWindowsUnixAndUncPathsStayAbsolute() {
        assertEquals("file:///D:/code/main.kt", resolveProjectMarkdownLink("C:/work/README.md", "D:\\code\\main.kt"))
        assertEquals("file:///tmp/next.md", resolveProjectMarkdownLink("/work/README.md", "/tmp/next.md"))
        assertEquals("file://server/share/a.png", resolveProjectMarkdownLink("C:/work/README.md", "\\\\server\\share\\a.png"))
        assertEquals("file:///C:/images/a.png", resolveProjectMarkdownLink("C:/work/README.md", "/images/a.png"))
    }

    @Test fun webLinksRemainWebAndExecutableSchemesAreRejected() {
        assertEquals("https://example.com/doc", resolveProjectMarkdownLink("C:/work/README.md", "https://example.com/doc"))
        assertEquals("https://example.com/a.png", resolveProjectMarkdownLink("C:/work/README.md", "//example.com/a.png"))
        assertNull(resolveProjectMarkdownLink("C:/work/README.md", "javascript:alert(1)"))
        assertNull(resolveProjectMarkdownLink("C:/work/README.md", "data:text/html,hello"))
        assertEquals("日本語-heading", projectMarkdownAnchor("#日本語 Heading!"))
    }
}
