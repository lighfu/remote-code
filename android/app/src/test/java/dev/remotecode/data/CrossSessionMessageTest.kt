package dev.remotecode.data

import org.junit.Assert.*
import org.junit.Test

class CrossSessionMessageTest {
    @Test fun readsWindowsPeerEnvelopeAndKeepsBodyLiteral() {
        val text = """<cross-session-message from="uds:\\.\pipe\LOCAL\cc-msg-test" from-session="local_abc" from-name="作業 &amp; テスト" from-mode="prompting">
            |  <code> &amp; text
            |</cross-session-message>""".trimMargin()
        val peer = parseCrossSessionMessage(text)!!
        assertEquals("作業 & テスト", peer.senderName)
        assertEquals("local_abc", peer.senderSessionId)
        assertEquals("  <code> &amp; text", peer.text)
        assertTrue(peer.senderAddress!!.startsWith("uds:"))
    }

    @Test fun rejectsExamplesMalformedAndDuplicateAttributes() {
        for (text in listOf(
            "example <cross-session-message from=\"uds:test\">hello</cross-session-message>",
            "<cross-session-message>hello</cross-session-message>",
            "<cross-session-message from=\"a\" from=\"b\">hello</cross-session-message>",
            "<cross-session-message from=\"a\" broken>hello</cross-session-message>",
        )) assertNull(text, parseCrossSessionMessage(text))
    }

    @Test fun structuredMetadataWorksAndHumanInputIsNotReclassified() {
        val raw = "<cross-session-message from=\"uds:test\">body</cross-session-message>"
        assertNull(Entry("human", kind = "user", text = raw).crossSessionMessage())
        assertNull(Entry("human-pc", kind = "user", text = raw, title = "PC から", messageOrigin = "human").crossSessionMessage())
        assertEquals("body", Entry("old", kind = "user", text = raw, title = "PC から").crossSessionMessage()!!.text)
        val entry = Entry("peer", kind = "user", text = "exact body", crossSession = CrossSessionMessage(senderName = "Worker"))
        assertEquals("exact body", entry.crossSessionMessage()!!.text)
    }

    @Test fun englishOriginTitlesSupportLegacyRecordsWithoutReclassifyingHumanExamples() {
        val raw = "<cross-session-message from=\"uds:test\">body</cross-session-message>"
        for (title in listOf("From PC", "From another screen", "Request from main session", "PC から", "本体からの依頼")) {
            assertEquals("body", Entry("peer-$title", kind = "user", text = raw, title = title).crossSessionMessage()!!.text)
            for (origin in listOf("human", "other")) {
                assertNull(Entry("human-$title", kind = "user", text = raw, title = title, messageOrigin = origin).crossSessionMessage())
            }
        }
        assertNull(Entry("custom", kind = "user", text = raw, title = "Custom label").crossSessionMessage())
    }
}
