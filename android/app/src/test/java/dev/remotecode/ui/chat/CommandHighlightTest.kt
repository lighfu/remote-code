package dev.remotecode.ui.chat

import org.junit.Assert.*
import org.junit.Test

class CommandHighlightTest {
    private fun spans(line: CodeLine, ink: CodeInk) = line.tokens.filter { it.ink == ink }.map { line.text.substring(it.start, it.end) }

    @Test fun bashSeparatesAssignmentsCommandsFlagsVariablesAndComments() {
        val line = highlightCode("S=folder ffmpeg -ss 4.5 -i \"\${S}/clip.mp4\" | head -n 3 # frames", codeLanguage("x.sh")).single()
        assertTrue(spans(line, CodeInk.Variable).containsAll(listOf("S", "\${S}")))
        assertEquals(listOf("ffmpeg", "head"), spans(line, CodeInk.Command))
        assertEquals(listOf("-ss", "-i", "-n"), spans(line, CodeInk.Flag))
        assertEquals(listOf("# frames"), spans(line, CodeInk.Comment))
        assertTrue(spans(line, CodeInk.Number).contains("4.5"))
    }

    @Test fun powershellTreatsLiteralQuotesAndEnvVariablesCorrectly() {
        val line = highlightCode("Get-ChildItem \$env:TEMP -Filter '*.jpg' | Select-Object Name # list", codeLanguage("x.ps1")).single()
        assertEquals(listOf("Get-ChildItem", "Select-Object"), spans(line, CodeInk.Command))
        assertEquals(listOf("\$env:TEMP"), spans(line, CodeInk.Variable))
        assertEquals(listOf("-Filter"), spans(line, CodeInk.Flag))
        val quoted = highlightCode("Write-Output 'literal \$name # text' \"hello \$name\"", codeLanguage("x.ps1")).single()
        assertEquals(listOf("\$name"), spans(quoted, CodeInk.Variable))
        assertTrue(spans(quoted, CodeInk.Comment).isEmpty())
        assertTrue(spans(quoted, CodeInk.String).any { it.contains("# text") })
    }

    @Test fun multilinePowerShellCommentsAndHereStringsRemainReadable() {
        val lines = highlightCode("<# start\nstill comment #> Get-Item .\n\$code = @\"\nclass Example {}\n\"@\nWrite-Output \$code", codeLanguage("x.ps1"))
        assertEquals(CodeInk.Comment, lines[1].tokens.first().ink)
        assertTrue(spans(lines[1], CodeInk.Command).contains("Get-Item"))
        assertEquals(listOf("class Example {}"), spans(lines[3], CodeInk.String))
        assertEquals(listOf("Write-Output"), spans(lines[5], CodeInk.Command))
    }

    @Test fun commandLanguageAndOutputDetectionAvoidColoringOrdinaryLogsAsCode() {
        assertEquals("Shell", commandCodeLanguage("Bash", "echo \$HOME").label)
        assertEquals("PowerShell", commandCodeLanguage("command", "pwsh.exe -Command Get-Item .").label)
        assertEquals("PowerShell", commandCodeLanguage("command", "Write-Output \$env:TEMP").label)
        assertEquals("JSON", outputCodeLanguage("{\"ok\":true,\"value\":12}").label)
        assertEquals("JSON", outputCodeLanguage("[{\"id\":1}]").label)
        assertEquals("JSON", outputCodeLanguage(" { } ").label)
        assertEquals("JSON", outputCodeLanguage(" [ ] ").label)
        assertEquals("テキスト", outputCodeLanguage("[画像]").label)
        assertEquals("テキスト", outputCodeLanguage("width=1748\nheight=1372").label)
    }

    @Test fun pythonHereDocumentsHighlightTheEmbeddedCodeAndResumeShellCommands() {
        val lines = highlightCode("python - <<'PY'\ndef hello():\n    return 42\nPY\necho done", codeLanguage("x.sh"))
        assertTrue(spans(lines[1], CodeInk.Keyword).contains("def"))
        assertTrue(spans(lines[2], CodeInk.Keyword).contains("return"))
        assertEquals(listOf("PY"), spans(lines[3], CodeInk.Operator))
        assertTrue(spans(lines[4], CodeInk.Keyword).contains("echo"))
    }

    @Test fun ansiColorsArePreservedWhileControlSequencesAreRemoved() {
        val value = terminalText("before \u001b[1;31mred\u001b[0m after \u001b[38;2;12;34;56mRGB\u001b[0m\r\n")
        assertEquals("before red after RGB\n", value.text)
        assertFalse(value.text.contains('\u001b'))
        assertTrue(value.spans.first().bold)
        assertEquals("red", value.text.substring(value.spans.first().start, value.spans.first().end))
        assertEquals(0x0C2238, value.spans.last().foreground)
        val link = terminalText("\u001b]8;;https://example.test\u0007label\u001b]8;;\u001b\\\u001b[2K")
        assertEquals("label", link.text)
    }

    @Test fun malformedAndLongInputCannotProduceOutOfBoundsSpans() {
        val input = "echo \"" + "value ".repeat(5_000) + "\${unfinished"
        val line = highlightCode(input, codeLanguage("x.sh")).single()
        assertEquals(input, line.text)
        assertTrue(line.tokens.all { it.start >= 0 && it.end in it.start..input.length })
        assertEquals("text", terminalText("text\u001b[38;2;").text)
    }
}
