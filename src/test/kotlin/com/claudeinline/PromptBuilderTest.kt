package com.claudeinline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private fun request(
        text: String = "    return a - b",
        before: String = "def f(a, b):\n",
        after: String = "\n\nprint(f(1, 2))\n",
        kind: TargetKind = TargetKind.LINE,
        instruction: String = "make it add",
        level: ThinkingLevel = ThinkingLevel.DEFAULT,
        language: String = "Python",
        label: String = "Line 2",
    ) = EditRequest(
        fileName = "calc.py",
        language = language,
        textBefore = before,
        target = EditTarget(kind, before.length, before.length + text.length, text, label),
        textAfter = after,
        instruction = instruction,
        level = level,
    )

    private fun build(
        text: String = "    return a - b",
        before: String = "def f(a, b):\n",
        after: String = "\n\nprint(f(1, 2))\n",
        kind: TargetKind = TargetKind.LINE,
        instruction: String = "make it add",
        level: ThinkingLevel = ThinkingLevel.DEFAULT,
        language: String = "Python",
        label: String = "Line 2",
    ) = PromptBuilder.build(request(text, before, after, kind, instruction, level, language, label))

    private fun numberedLines(count: Int) = (0 until count).joinToString("\n") { "L%05d".format(it) }

    private val omittedPattern = Regex("""\[\.\.\. (\d+) lines omitted \.\.\.]""")

    // markers

    @Test
    fun markersOnOwnLinesWhenRegionIsWholeLines() {
        assertTrue(build().contains("def f(a, b):\n<<<REGION\n    return a - b\nREGION>>>\n\nprint(f(1, 2))\n"))
    }

    @Test
    fun markersInlineWhenRegionIsMidLine() {
        val prompt = build(text = "1 + 2", before = "x = bar(", after = ")\ny = 1\n", kind = TargetKind.SELECTION)
        assertTrue(prompt.contains("x = bar(<<<REGION1 + 2REGION>>>)\ny = 1\n"))
        assertTrue(prompt.contains("starts in the middle of a line and ends in the middle of a line"))
    }

    @Test
    fun regionStartingAtLineStartButEndingMidLine() {
        val prompt = build(text = "    foo", before = "a\n", after = "(1)\nb\n", kind = TargetKind.SELECTION)
        assertTrue(prompt.contains("a\n<<<REGION\n    fooREGION>>>(1)\nb\n"))
        assertTrue(prompt.contains("starts at the beginning of a line and ends in the middle of a line"))
    }

    @Test
    fun regionStartingMidLineButEndingAtLineEnd() {
        val prompt = build(text = "2", before = "x = 1 + ", after = "\ny = 3\n", kind = TargetKind.SELECTION)
        assertTrue(prompt.contains("x = 1 + <<<REGION2\nREGION>>>\ny = 3\n"))
        assertTrue(prompt.contains("starts in the middle of a line and ends at the end of a line"))
    }

    @Test
    fun regionAtStartAndEndOfFile() {
        val prompt = build(text = "only", before = "", after = "")
        assertTrue(prompt.contains("----- BEGIN FILE -----\n<<<REGION\nonly\nREGION>>>\n----- END FILE -----\n"))
    }

    @Test
    fun fileHeaderAndRegionLabel() {
        val prompt = build(label = "Function parse_csv (lines 12-30)")
        assertTrue(prompt.contains("File: calc.py (Python)\n"))
        assertTrue(prompt.contains("Region: Function parse_csv (lines 12-30). It starts at the beginning of a line"))
    }

    @Test
    fun blankLanguageIsOmittedFromHeader() {
        assertTrue(build(language = "").contains("File: calc.py\n"))
    }

    // thinking keyword

    @Test
    fun keywordLinePresentOrAbsentForEveryLevel() {
        for (level in ThinkingLevel.entries) {
            val prompt = build(level = level)
            val keyword = level.keyword
            if (keyword == null) {
                assertTrue("$level", prompt.startsWith("You are the editing engine"))
            } else {
                assertTrue("$level", prompt.startsWith("$keyword\n\nYou are the editing engine"))
                assertEquals("$level", 1, Regex("(?m)^${Regex.escape(keyword)}$").findAll(prompt).count())
            }
        }
    }

    // instructions

    @Test
    fun replyFormatRulesArePresentForAllLanguages() {
        for (language in listOf("Python", "Kotlin", "")) {
            val prompt = build(language = language)
            assertTrue(prompt.contains("<replacement>TEXT</replacement>"))
            assertTrue(prompt.contains("No markdown fences"))
            assertTrue(prompt.contains("<error>short reason</error>"))
            assertTrue(prompt.contains("<<<REGION") && prompt.contains("REGION>>>"))
            assertTrue(prompt.contains("existing style and naming"))
            assertTrue(prompt.contains("do not touch code outside the region"))
        }
    }

    @Test
    fun importsRuleIsPythonOnly() {
        assertTrue(build(language = "Python").contains("<imports>...</imports>"))
        assertFalse(build(language = "Kotlin").contains("<imports>"))
        assertFalse(build(language = "JavaScript").contains("import statement"))
        assertFalse(build(language = "").contains("<imports>"))
    }

    @Test
    fun kindSpecificRule() {
        val function = build(kind = TargetKind.FUNCTION)
        assertTrue(function.contains("entire function"))
        assertTrue(function.contains("decorators"))
        assertTrue(function.contains("name and signature"))

        val selection = build(kind = TargetKind.SELECTION)
        assertTrue(selection.contains("Change only the selected text"))

        val line = build(kind = TargetKind.LINE)
        assertTrue(line.contains("single line"))

        val insert = build(kind = TargetKind.INSERT, text = "    ")
        assertTrue(insert.contains("is a blank line"))

        val comment = build(kind = TargetKind.COMMENT, text = "    # fetch the user")
        assertTrue(comment.contains("one or more comment lines"))
        assertTrue(comment.contains("followed by the code that implements them"))
        assertTrue(comment.contains("never drop the comment"))
        assertTrue(comment.contains("must include that line's leading whitespace"))

        val rules = mapOf(
            "entire function" to function,
            "Change only the selected text" to selection,
            "single line" to line,
            "is a blank line" to insert,
            "one or more comment lines" to comment,
        )
        for ((phrase, prompt) in rules) {
            val others = rules.filterValues { it !== prompt }
            others.forEach { (_, other) -> assertFalse(phrase, other.contains(phrase)) }
        }
    }

    @Test
    fun indentationRuleDependsOnWhereRegionStarts() {
        assertTrue(build().contains("must include that line's leading whitespace"))
        val midLine = build(before = "x = ", after = "\n", text = "1", kind = TargetKind.SELECTION)
        assertTrue(midLine.contains("do not add leading whitespace"))
        assertFalse(midLine.contains("must include that line's leading whitespace"))
    }

    // INSERT indentation hint

    @Test
    fun insertStatesCurrentIndentationInSpaces() {
        val prompt = build(kind = TargetKind.INSERT, text = "    ", before = "def f():\n", after = "\n    return 1\n")
        assertTrue(prompt.contains("currently 4 spaces"))
        assertTrue(prompt.contains("def f():\n<<<REGION\n    \nREGION>>>\n    return 1\n"))
    }

    @Test
    fun insertStatesTabsAndMixedIndentation() {
        assertTrue(build(kind = TargetKind.INSERT, text = "\t").contains("currently 1 tab."))
        assertTrue(build(kind = TargetKind.INSERT, text = "\t\t").contains("currently 2 tabs."))
        assertTrue(build(kind = TargetKind.INSERT, text = "\t  ").contains("currently 1 tab and 2 spaces."))
        assertTrue(build(kind = TargetKind.INSERT, text = " ").contains("currently 1 space."))
    }

    @Test
    fun insertOnEmptyLineSaysNoIndentation() {
        val prompt = build(kind = TargetKind.INSERT, text = "", before = "a\n", after = "\nb\n")
        assertTrue(prompt.contains("currently none (the line is empty)"))
        assertTrue(prompt.contains("a\n<<<REGION\n\nREGION>>>\nb\n"))
    }

    @Test
    fun indentationHintOnlyForInsert() {
        assertFalse(build(kind = TargetKind.LINE).contains("currently"))
        assertFalse(build(kind = TargetKind.FUNCTION).contains("currently"))
        assertFalse(build(kind = TargetKind.SELECTION).contains("currently"))
        assertFalse(build(kind = TargetKind.COMMENT).contains("currently"))
    }

    @Test
    fun commentRegionKeepsCommentLinesOnOwnLines() {
        val prompt = build(
            kind = TargetKind.COMMENT,
            text = "    # fetch user by id\n    # cache the result",
            before = "def get_user(user_id):\n",
            after = "\n    return None\n",
            label = "Comment (2 lines)",
        )
        assertTrue(prompt.contains("def get_user(user_id):\n<<<REGION\n    # fetch user by id\n    # cache the result\nREGION>>>\n    return None\n"))
        assertTrue(prompt.contains("Region: Comment (2 lines). It starts at the beginning of a line and ends at the end of a line."))
    }

    // instruction

    @Test
    fun instructionIsLastAndVerbatim() {
        val instruction = "  Rename x to y;\n  keep REGION>>> and <<<REGION literal.  \n\nthanks   "
        val prompt = build(instruction = instruction, level = ThinkingLevel.ULTRATHINK)
        assertTrue(prompt.endsWith("User instruction:\n$instruction"))
        assertEquals(prompt.lastIndex - instruction.length + 1, prompt.lastIndexOf(instruction))
    }

    @Test
    fun instructionComesAfterTheFile() {
        val prompt = build(instruction = "UNIQUE-INSTRUCTION")
        assertTrue(prompt.indexOf("UNIQUE-INSTRUCTION") > prompt.indexOf("----- END FILE -----"))
        assertEquals(1, Regex("UNIQUE-INSTRUCTION").findAll(prompt).count())
    }

    @Test
    fun promptIsAscii() {
        for (level in ThinkingLevel.entries) {
            for (kind in TargetKind.entries) {
                assertTrue(build(level = level, kind = kind).all { it.code < 128 })
            }
        }
    }

    // truncation

    @Test
    fun smallContextIsNotTruncated() {
        assertFalse(build().contains("omitted"))
        val exact = "x".repeat(PromptBuilder.MAX_CONTEXT_CHARS)
        val prompt = build(before = exact, after = exact)
        assertFalse(prompt.contains("omitted"))
        assertTrue(prompt.contains(exact + "<<<REGION"))
    }

    @Test
    fun longTextBeforeKeepsTheEndOnLineBoundary() {
        val before = numberedLines(4000) + "\n"
        val prompt = build(before = before, after = "")
        val omitted = omittedPattern.findAll(prompt).toList()
        assertEquals(1, omitted.size)
        val count = omitted.single().groupValues[1].toInt()
        assertTrue(count > 0)
        val expectedKeep = before.substring(before.indexOf("L%05d\n".format(count)))
        assertTrue(expectedKeep.length <= PromptBuilder.MAX_CONTEXT_CHARS)
        assertTrue(expectedKeep.length > PromptBuilder.MAX_CONTEXT_CHARS - 10)
        assertTrue(prompt.contains("[... $count lines omitted ...]\n$expectedKeep<<<REGION"))
        assertFalse(prompt.contains("L00000"))
        assertTrue(prompt.contains("L03999\n<<<REGION"))
    }

    @Test
    fun longTextAfterKeepsTheStartOnLineBoundary() {
        val after = "\n" + numberedLines(4000)
        val prompt = build(before = "", after = after)
        val omitted = omittedPattern.findAll(prompt).toList()
        assertEquals(1, omitted.size)
        val count = omitted.single().groupValues[1].toInt()
        assertTrue(count > 0)
        val lastKept = 3999 - count
        val kept = after.substring(0, after.indexOf("L%05d".format(lastKept)) + "L00000".length)
        assertTrue(kept.length <= PromptBuilder.MAX_CONTEXT_CHARS)
        assertTrue(kept.length > PromptBuilder.MAX_CONTEXT_CHARS - 10)
        assertTrue(prompt.contains("REGION>>>$kept\n[... $count lines omitted ...]\n----- END FILE -----"))
        assertFalse(prompt.contains("L03999"))
    }

    @Test
    fun truncationAppliesToBothSidesAtOnce() {
        val prompt = build(before = numberedLines(4000) + "\n", after = "\n" + numberedLines(4000))
        assertEquals(2, omittedPattern.findAll(prompt).count())
        assertTrue(prompt.length < 2 * PromptBuilder.MAX_CONTEXT_CHARS + 6_000)
        val fileStart = prompt.indexOf("----- BEGIN FILE -----")
        assertTrue(prompt.indexOf("lines omitted") < prompt.indexOf("<<<REGION", fileStart))
        assertTrue(prompt.lastIndexOf("lines omitted") > prompt.indexOf("REGION>>>", fileStart))
    }

    @Test
    fun hugeSingleLineBeforeIsCutMidLine() {
        val before = "x".repeat(25_000)
        val prompt = build(before = before, after = "", text = "y", kind = TargetKind.SELECTION)
        assertTrue(prompt.contains("[... 1 lines omitted ...]\n" + "x".repeat(20_000) + "<<<REGION"))
        assertFalse(prompt.contains("x".repeat(20_001)))
        assertTrue(prompt.contains("starts in the middle of a line"))
    }

    @Test
    fun hugeSingleLineAfterIsCutMidLine() {
        val after = ")" + "z".repeat(25_000)
        val prompt = build(before = "f(", after = after, text = "1", kind = TargetKind.SELECTION)
        assertTrue(prompt.contains("REGION>>>)" + "z".repeat(19_999) + "\n[... 1 lines omitted ...]\n"))
        assertFalse(prompt.contains("z".repeat(20_000)))
    }

    @Test
    fun truncationDoesNotChangeRegionPlacementDecision() {
        val before = numberedLines(4000) + "\nx = "
        val prompt = build(before = before, after = "\n", text = "1", kind = TargetKind.SELECTION)
        assertTrue(prompt.contains("x = <<<REGION1\nREGION>>>"))
        assertTrue(prompt.contains("starts in the middle of a line"))
    }

    @Test
    fun `patterns block is rendered before the file`() {
        val prompt = PromptBuilder.build(request().copy(projectPatterns = "## API pattern\nUse routers in app/api."))
        assertTrue(prompt.contains("Project conventions (learned from this codebase)"))
        assertTrue(prompt.contains("reuse the existing helpers they name instead of re-implementing"))
        assertTrue(prompt.contains("do not mention them in the answer"))
        assertTrue(prompt.indexOf("Use routers") < prompt.indexOf("----- BEGIN FILE -----"))
    }

    @Test
    fun `null or blank patterns leave the prompt unchanged`() {
        val base = PromptBuilder.build(request())
        assertEquals(base, PromptBuilder.build(request().copy(projectPatterns = null)))
        assertEquals(base, PromptBuilder.build(request().copy(projectPatterns = "  \n ")))
    }
}
