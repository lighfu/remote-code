package dev.remotecode.ui.chat

import org.junit.Assert.*
import org.junit.Test

class DraftMediaGalleryTest {
    @Test fun opensTheTappedItemInPickerOrderWithoutChangingTheDraft() {
        val draft = mutableListOf("image", null, "video", "image")
        val gallery = requireNotNull(DraftMediaGallery.open(draft, 3))
        assertEquals(listOf("image", "video", "image"), gallery.items)
        assertEquals(2, gallery.initialPage)
        assertEquals(1, DraftMediaGallery.open(draft, 2)?.initialPage)
        assertEquals(listOf("image", null, "video", "image"), draft)
        draft.removeAt(0)
        assertEquals(listOf("image", "video", "image"), gallery.items)
    }

    @Test fun nonMediaAndOutOfRangeItemsCannotOpenAPage() {
        val draft = listOf("image", null)
        assertNull(DraftMediaGallery.open(draft, 1))
        assertNull(DraftMediaGallery.open(draft, -1))
        assertNull(DraftMediaGallery.open(draft, 2))
    }
}
