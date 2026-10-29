package com.claudeinline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResponseParserTest {
    private fun target(text: String, kind: TargetKind = TargetKind.SELECTION) =
        EditTarget(kind, 0, text.length, text, "test")

    private fun parse(raw: String, targetText: String = "old") = ResponseParser.parse(raw, target(targetText))

    private fun failure(raw: String, targetText: String = "old"): ClaudeEditException {
        try {
            parse(raw, targetText)
        } catch (e: ClaudeEditException) {
            return e
        }
        fail("expected ClaudeEditException for: $raw")
        throw AssertionError()
    }

    @Test
    fun oneLineKeepsIndentationWithoutNewlines() {
        val response = parse("<replacement>    return a + b</replacement>", "    return a - b")
        assertEquals("    return a + b", response.replacement)
        assertEquals(emptyList<String>(), response.imports)
    }

    @Test
    fun stripsOneNewlineAfterOpenAndBeforeClose() {
        assertEquals("    x = 1\n    y = 2", parse("<replacement>\n    x = 1\n    y = 2\n</replacement>").replacement)
    }

    @Test
    fun stripsOnlyOneNewlineEachSide() {
        assertEquals("\n  a\n", parse("<replacement>\n\n  a\n\n</replacement>", "x\n").replacement)
    }

    @Test
    fun neverStripsIndentationOfFirstLine() {
        val raw = "<replacement>\n        deep = 1\n    shallow = 2\n</replacement>"
        assertEquals("        deep = 1\n    shallow = 2", parse(raw).replacement)
    }

    @Test
    fun preservesTabs() {
        assertEquals("\tif x:\n\t\tpass", parse("<replacement>\n\tif x:\n\t\tpass\n</replacement>").replacement)
    }

    @Test
    fun matchesTrailingNewlineOfTarget() {
        assertEquals("b = 2\n", parse("<replacement>b = 2</replacement>", "a = 1\n").replacement)
        assertEquals("b = 2\n\n", parse("<replacement>b = 2</replacement>", "a = 1\n\n").replacement)
        assertEquals("b = 2\n", parse("<replacement>b = 2\n\n\n</replacement>", "a = 1\n").replacement)
    }

    @Test
    fun dropsTrailingNewlinesWhenTargetHasNone() {
        assertEquals("b = 2", parse("<replacement>b = 2\n</replacement>", "a = 1").replacement)
        assertEquals("b = 2", parse("<replacement>b = 2\n\n\n</replacement>", "a = 1").replacement)
    }

    @Test
    fun emptyReplacementIsAllowed() {
        assertEquals("", parse("<replacement></replacement>", "x").replacement)
        assertEquals("\n", parse("<replacement></replacement>", "x\n").replacement)
    }

    @Test
    fun noisyTextBeforeAndAfterIsIgnored() {
        val raw = "Sure, here you go!\n\n<replacement>y = 2</replacement>\n\nLet me know if you need more."
        assertEquals("y = 2", parse(raw).replacement)
    }

    @Test
    fun tagsInsideAnOuterFenceWin() {
        assertEquals("y = 2", parse("```xml\n<replacement>y = 2</replacement>\n```").replacement)
    }

    @Test
    fun lastCloseTagWins() {
        val raw = "<replacement>x = '</replacement>'\nprint(x)</replacement>\nthanks"
        assertEquals("x = '</replacement>'\nprint(x)", parse(raw).replacement)
    }

    @Test
    fun wholeFencedContentInsideTagsIsUnwrapped() {
        val raw = "<replacement>\n```python\ndef f():\n    pass\n```\n</replacement>"
        assertEquals("def f():\n    pass", parse(raw).replacement)
    }

    @Test
    fun fencedContentKeepsIndentationInside() {
        val raw = "<replacement>```\n    return 1\n```</replacement>"
        assertEquals("    return 1", parse(raw).replacement)
    }

    @Test
    fun partialFenceInsideContentIsKept() {
        val raw = "<replacement>text\n```\ncode\n```\nmore</replacement>"
        assertEquals("text\n```\ncode\n```\nmore", parse(raw).replacement)
    }

    @Test
    fun fenceFallbackWithoutTags() {
        val raw = "Here is the change:\n```python\n    return a + b\n```\nDone."
        assertEquals("    return a + b", parse(raw, "    return a - b").replacement)
    }

    @Test
    fun fenceFallbackUsesFirstBlockAndKeepsBlankLines() {
        val raw = "```\nfirst\n\n  second\n```\nand\n```\nother\n```"
        assertEquals("first\n\n  second", parse(raw).replacement)
    }

    @Test
    fun fenceFallbackAppliesTrailingNewlineMatching() {
        assertEquals("new\n", parse("```\nnew\n```", "old\n").replacement)
    }

    @Test
    fun errorTagThrowsWithReason() {
        assertEquals("Cannot do that", failure("<error>Cannot do that</error>").message)
    }

    @Test
    fun errorTagIsTrimmedAndFoundAmongNoise() {
        assertEquals("too vague", failure("Sorry.\n<error>\n  too vague \n</error>\nBye").message)
    }

    @Test
    fun errorTagBeatsFence() {
        assertEquals("nope", failure("```\ncode\n```\n<error>nope</error>").message)
    }

    @Test
    fun errorTagInsideReplacementIsJustCode() {
        val raw = "<replacement>print('<error>x</error>')</replacement>"
        assertEquals("print('<error>x</error>')", parse(raw).replacement)
    }

    @Test
    fun missingTagThrowsWithSnippet() {
        val e = failure("I think you should rename the variable.\n\nBest regards")
        assertTrue(e.message, e.message!!.startsWith("Claude did not return a replacement"))
        assertTrue(e.message, e.message!!.contains("rename the variable. Best regards"))
    }

    @Test
    fun longMissingTagOutputIsTruncated() {
        val e = failure("word ".repeat(500))
        assertTrue(e.message!!.length < 300)
        assertTrue(e.message!!.endsWith("..."))
    }

    @Test
    fun emptyOutputThrows() {
        assertTrue(failure("").message!!.startsWith("Claude did not return a replacement"))
        assertTrue(failure("  \n ").message!!.startsWith("Claude did not return a replacement"))
    }

    @Test
    fun unclosedReplacementThrows() {
        val e = failure("<replacement>def f():\n    pass")
        assertTrue(e.message!!.startsWith("Claude did not return a replacement"))
    }

    @Test
    fun crlfAndCrAreConvertedToLf() {
        assertEquals("a\nb", parse("<replacement>a\r\nb\r\n</replacement>").replacement)
        assertEquals("a\nb\nc", parse("<replacement>a\rb\r\nc</replacement>").replacement)
    }

    @Test
    fun crlfAroundTagsIsStripped() {
        assertEquals("x = 1", parse("<replacement>\r\nx = 1\r\n</replacement>").replacement)
    }

    @Test
    fun importsAreFilteredAndTrimmed() {
        val raw = "<replacement>x</replacement>\n<imports>\nimport os\n  from a import b  \n\n" +
            "not an import\n# import hidden\nimport sys\n</imports>"
        assertEquals(listOf("import os", "from a import b", "import sys"), parse(raw).imports)
    }

    @Test
    fun importsBeforeReplacementAreFound() {
        val raw = "<imports>import json</imports>\n<replacement>data = json.loads(s)</replacement>"
        val response = parse(raw)
        assertEquals("data = json.loads(s)", response.replacement)
        assertEquals(listOf("import json"), response.imports)
    }

    @Test
    fun importsMentionedInsideReplacementAreIgnored() {
        val raw = "<replacement>s = '<imports>import os</imports>'</replacement>"
        assertEquals(emptyList<String>(), parse(raw).imports)
    }

    @Test
    fun importsWithFencesInsideAreCleaned() {
        val raw = "<replacement>x</replacement><imports>\n```python\nimport re\n```\n</imports>"
        assertEquals(listOf("import re"), parse(raw).imports)
    }

    @Test
    fun importsWithoutCloseTagAreIgnored() {
        assertEquals(emptyList<String>(), parse("<replacement>x</replacement><imports>\nimport os").imports)
    }

    @Test
    fun importsAlongsideFenceFallback() {
        val raw = "```\nx = 1\n```\n<imports>\nimport os\n</imports>"
        val response = parse(raw)
        assertEquals("x = 1", response.replacement)
        assertEquals(listOf("import os"), response.imports)
    }

    @Test
    fun noImportsBlockGivesEmptyList() {
        assertEquals(emptyList<String>(), parse("<replacement>x</replacement>").imports)
    }
}
