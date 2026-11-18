package dev.remotecode.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachedFileTextTest {
    private val bucket = "e415cac0-6226-4960-b80f-282d67b5004d"
    private val path = "C:/Remote Code/data/attachments/$bucket/file/資料.txt"
    private val header = "添付ファイル（PC に保存済み）:"

    @Test
    fun extractsFileOnlyMessageWithUnicodeSpacesEscapedBracketsAndZeroBytes() {
        val parsed = attachedFileText("$header\n- [資料\\[完成版\\].txt](<$path>) (0 bytes)")
        assertEquals("", parsed.text)
        assertEquals(listOf(AttachedFileReference("資料[完成版].txt", path, 0)), parsed.files)
    }

    @Test
    fun extractsOnlyCompleteFinalSuffixAndKeepsLiteralUserFormatting() {
        val body = "**そのままの文章**\n\n$header\nこれは説明です"
        val unix = "/tmp/data/attachments/$bucket/file/notes.md"
        val parsed = attachedFileText("$body\n\n$header\n- [資料.txt](<$path>) (12 bytes)\n- [notes.md](<$unix>) (345 bytes)")
        assertEquals(body, parsed.text)
        assertEquals(listOf(path, unix), parsed.files.map { it.path })
        assertEquals(listOf(12L, 345L), parsed.files.map { it.size })
    }

    @Test
    fun malformedOrUnrelatedSuffixNeverRemovesUserText() {
        listOf(
            "$header\n- [file](<https://example.invalid/file>) (3 bytes)",
            "$header\n- [file](<C:/ordinary-file.txt>) (3 bytes)",
            "$header\n- [file](<$path>) (-1 bytes)",
            "$header\n- [file](<$path>) (3 bytes)\nextra text",
            "a normal [link](<C:/file.txt>)",
        ).forEach { assertEquals(AttachedFileText(it), attachedFileText(it)) }
    }

    @Test
    fun bracketLikeTextInFileNameDoesNotEndTheLabelEarly() {
        val parsed = attachedFileText("$header\n- [a\\](<b.txt](<$path>) (42 bytes)")
        assertEquals("a](<b.txt", parsed.files.single().name)
    }

    @Test
    fun recognizesEnglishHeaderAndKeepsEarlierLegacyTextUntouched() {
        val body = "Draft\r\n\r\n$header\r\nNot an attachment list"
        val parsed = attachedFileText("$body\r\n\r\nAttachments (saved on PC):\r\n- [資料.txt](<$path>) (12 bytes)\r\n")
        assertEquals(body.replace("\r\n", "\n"), parsed.text)
        assertEquals(listOf(AttachedFileReference("資料.txt", path, 12)), parsed.files)
        val invalid = "Attachments (saved on PC):\n- [file](<C:/ordinary-file.txt>) (3 bytes)"
        assertEquals(AttachedFileText(invalid), attachedFileText(invalid))
    }

    @Test
    fun picksTheLastCompleteHeaderAcrossBothServerVersions() {
        val prefix = "Attachments (saved on PC):\nEarlier text"
        val parsed = attachedFileText("$prefix\n\n$header\n- [資料.txt](<$path>) (1 bytes)")
        assertEquals(prefix, parsed.text)
        assertEquals(1L, parsed.files.single().size)
    }
}
