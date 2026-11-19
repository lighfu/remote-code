package dev.remotecode.ui.chat

import dev.remotecode.data.Entry
import org.junit.Assert.*
import org.junit.Test

class ComposerSubmissionTest {
    @Test fun approvalOrAnotherSendCannotAcknowledgeDraft() {
        val image = Any()
        val send = ComposerSubmission("hello", 4, listOf(image))
        val images = mutableListOf(image)
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
        send.acknowledge(text, 4, images) { text = "" }
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
        val send = ComposerSubmission(text, 3, listOf(sentImage, sentFile))
        val attachments = mutableListOf(sentImage, sentFile, newFile, newImage)
        send.acknowledge(text, 6, attachments) { fail("Unsent edit was cleared") }
        assertEquals(2, attachments.size)
        assertSame(newFile, attachments[0])
        assertSame(newImage, attachments[1])
        send.acknowledge(text, 3, attachments) { fail("Late HTTP reply repeated the acknowledgement") }
    }
}
