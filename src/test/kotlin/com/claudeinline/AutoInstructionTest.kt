package com.claudeinline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoInstructionTest {
    @Test
    fun everyKindHasANonBlankSingleParagraphAsciiText() {
        for (kind in TargetKind.entries) {
            val text = AutoInstruction.forKind(kind)
            assertTrue("$kind", text.isNotBlank())
            assertFalse("$kind", text.contains('\n'))
            assertEquals("$kind", text.trim(), text)
            assertTrue("$kind", text.all { it.code in 32..126 })
            assertTrue("$kind", text.length in 80..600)
        }
    }

    @Test
    fun textsAreDistinctPerKind() {
        val texts = TargetKind.entries.map { AutoInstruction.forKind(it) }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun functionTextCoversIntentSourcesAndStubs() {
        val text = AutoInstruction.forKind(TargetKind.FUNCTION)
        for (word in listOf("name", "signature", "type hints", "docstring", "comments", "stub", "pass", "NotImplementedError", "TODO")) {
            assertTrue(word, text.contains(word))
        }
        assertTrue(text.contains("implement it fully"))
    }

    @Test
    fun commentTextKeepsCommentAndPutsCodeBelow() {
        val text = AutoInstruction.forKind(TargetKind.COMMENT)
        assertTrue(text.contains("Keep the comment(s)"))
        assertTrue(text.contains("directly below"))
        assertTrue(text.contains("same indentation"))
        assertTrue(text.contains("enclosing function"))
    }

    @Test
    fun insertTextUsesCommentsAboveOrEnclosingFunction() {
        val text = AutoInstruction.forKind(TargetKind.INSERT)
        assertTrue(text.contains("at this position"))
        assertTrue(text.contains("directly above"))
        assertTrue(text.contains("docstring"))
    }

    @Test
    fun selectionAndLineTextsReferToTheirRegion() {
        assertTrue(AutoInstruction.forKind(TargetKind.SELECTION).contains("selected code"))
        assertTrue(AutoInstruction.forKind(TargetKind.LINE).contains("this line"))
    }

    @Test
    fun textsAreStableAcrossCalls() {
        for (kind in TargetKind.entries) {
            assertEquals(AutoInstruction.forKind(kind), AutoInstruction.forKind(kind))
        }
    }
}
