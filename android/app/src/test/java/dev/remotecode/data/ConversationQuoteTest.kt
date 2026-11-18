package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationQuoteTest {
    @Test fun selectedTextBecomesAMultilineQuote() {
        assertEquals("> 最初の行\n> \n> English 🧩\n\n", appendConversationQuote("", "最初の行\n\nEnglish 🧩"))
    }

    @Test fun existingDraftAndCodeWhitespaceArePreserved() {
        assertEquals("依頼  \n\n>   val x = 1\n> \treturn x\n\n", appendConversationQuote("依頼  ", "  val x = 1\n\treturn x"))
        assertEquals("Draft\n\n> quoted\n\n", appendConversationQuote("Draft\n\n", "quoted"))
        assertEquals("Draft\n\n> quoted\n\n", appendConversationQuote("Draft\n", "quoted"))
    }

    @Test fun blankSelectionLeavesDraftUntouched() {
        assertEquals("未送信  \n", appendConversationQuote("未送信  \n", " \n\t"))
    }
}
