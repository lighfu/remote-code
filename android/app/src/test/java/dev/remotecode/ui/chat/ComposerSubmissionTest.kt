package dev.remotecode.ui.chat

import dev.remotecode.data.Entry
import org.junit.Assert.*
import org.junit.Test

class ComposerSubmissionTest {
    @Test fun approvalOrAnotherSendCannotAcknowledgeDraft() {
        val image = Any()
        val send = ComposerSubmission("hello", 4, listOf(image), emptyList<Any>())
        val images = mutableListOf(image)
        val files = mutableListOf<Any>()
        var text = "hello"
        assertFalse(send.hasReceipt(listOf(
            Entry("a", kind = "assistant", clientMessageId = send.clientMessageId),
            Entry("b", kind = "user", text = "hello"),
            Entry("c", kind = "user", text = "hello", clientMessageId = "another-uuid"),
        )))
        assertEquals("hello", text)
        assertEquals(listOf(image), images)
        assertFalse(send.acknowledged)
        assertTrue(send.hasReceipt(listOf(Entry("actual", kind = "user", clientMessageId = send.clientMessageId))))
        send.acknowledge(text, 4, images, files) { text = "" }
        assertEquals("", text)
        assertTrue(images.isEmpty())
        assertTrue(send.acknowledged)
    }

    @Test fun acknowledgementPreservesEditsAndNewAttachments() {
        // A newly picked item may have equal content but is not the sent item.
        data class DraftAttachment(val name: String)
        val sentImage = DraftAttachment("image")
        val sentFile = DraftAttachment("file")
        val newImage = sentImage.copy()
        val newFile = sentFile.copy()
        val text = "same text typed again"
        val send = ComposerSubmission(text, 3, listOf(sentImage), listOf(sentFile))
        val images = mutableListOf(sentImage, newImage)
        val files = mutableListOf(sentFile, newFile)
        send.acknowledge(text, 6, images, files) { fail("Unsent edit was cleared") }
        assertSame(newImage, images.single())
        assertSame(newFile, files.single())
        send.acknowledge(text, 3, images, files) { fail("Late HTTP reply repeated the acknowledgement") }
    }
}
