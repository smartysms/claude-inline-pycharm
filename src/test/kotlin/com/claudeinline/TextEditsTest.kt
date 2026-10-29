package com.claudeinline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextEditsTest {

    @Test
    fun `no edits leaves the text alone`() {
        assertEquals("abc", applyEdits("abc", emptyList()))
    }

    @Test
    fun `single replacement`() {
        assertEquals("a-XY-c", applyEdits("a-bb-c", listOf(TextEdit(2, 4, "XY"))))
    }

    @Test
    fun `replacement may change the length and may be empty`() {
        assertEquals("ac", applyEdits("abc", listOf(TextEdit(1, 2, ""))))
        assertEquals("a1234c", applyEdits("abc", listOf(TextEdit(1, 2, "1234"))))
    }

    @Test
    fun `edits are applied highest offset first whatever the input order`() {
        val edits = listOf(TextEdit(0, 1, "A"), TextEdit(4, 5, "E"), TextEdit(2, 3, "CCC"))
        assertEquals("AbCCCdE", applyEdits("abcde", edits))
        assertEquals("AbCCCdE", applyEdits("abcde", edits.reversed()))
    }

    @Test
    fun `insertion before the region keeps the region offsets valid`() {
        val text = "import os\n\ndef f():\n    pass\n"
        val region = TextEdit(11, text.length - 1, "def f():\n    return 1")
        val insertion = TextEdit(9, 9, "\nimport sys")
        assertEquals("import os\nimport sys\n\ndef f():\n    return 1\n", applyEdits(text, listOf(region, insertion)))
    }

    @Test
    fun `insertion right after the region end does not touch the replacement`() {
        val region = TextEdit(0, 3, "XYZ!")
        val insertion = TextEdit(3, 3, "|")
        assertEquals("XYZ!|def", applyEdits("abcdef", listOf(region, insertion)))
    }

    @Test
    fun `insertion at the start of a replacement lands in front of it`() {
        val region = TextEdit(0, 3, "new")
        val insertion = TextEdit(0, 0, "import x\n")
        assertEquals("import x\nnew rest", applyEdits("old rest", listOf(region, insertion)))
        assertEquals("import x\nnew rest", applyEdits("old rest", listOf(insertion, region)))
    }

    @Test
    fun `two empty edits at one offset keep their listed order, first listed ends up last`() {
        val region = TextEdit(0, 0, "new")
        val insertion = TextEdit(0, 0, "import x\n")
        assertEquals("import x\nnew", applyEdits("", listOf(region, insertion)))
    }

    @Test
    fun `overlapping edits are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            applyEdits("abcdef", listOf(TextEdit(1, 4, "x"), TextEdit(3, 5, "y")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            applyEdits("abcdef", listOf(TextEdit(1, 4, "x"), TextEdit(2, 2, "y")))
        }
    }
}
