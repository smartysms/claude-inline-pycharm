package com.claudeinline

import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

class InlineDiffViewTest : BasePlatformTestCase() {

    private fun inlays() = myFixture.editor.inlayModel.getBlockElementsInRange(0, myFixture.editor.document.textLength)

    fun testShowsInsertedLinesAndRemovedBlockThenClears() {
        myFixture.configureByText("a.py", "x = 1\ndef f():\n    return 2\n    return 3\ny = 4\n")
        val document = myFixture.editor.document
        val start = document.getLineStartOffset(1)
        val end = document.getLineEndOffset(3)
        val view = InlineDiffView(myFixture.editor)
        view.show("def f():\n    pass", start, end)

        assertEquals(1, myFixture.editor.markupModel.allHighlighters.size)
        val highlighter = myFixture.editor.markupModel.allHighlighters.single()
        assertEquals(document.getLineStartOffset(2), highlighter.startOffset)
        assertEquals(document.getLineEndOffset(3), highlighter.endOffset)
        val inlay = inlays().single()
        assertEquals(document.getLineStartOffset(2), inlay.offset)
        assertEquals(myFixture.editor.lineHeight, inlay.heightInPixels)

        val image = BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        inlay.renderer.paint(inlay, g, Rectangle2D.Double(0.0, 0.0, 400.0, inlay.heightInPixels.toDouble()), TextAttributes())
        g.dispose()

        view.show("def f():\n    pass", start, end)
        assertEquals(1, inlays().size)
        view.clear()
        assertEquals(0, myFixture.editor.markupModel.allHighlighters.size)
        assertTrue(inlays().isEmpty())
        view.dispose()
    }

    fun testPureDeletionAndUnchangedText() {
        myFixture.configureByText("a.py", "a = 1\nc = 3\n")
        val document = myFixture.editor.document
        val view = InlineDiffView(myFixture.editor)
        view.show("a = 1\nb = 2\nc = 3\n", 0, document.textLength)
        assertEquals(0, myFixture.editor.markupModel.allHighlighters.size)
        assertEquals(1, inlays().size)
        view.show("a = 1\nc = 3\n", 0, document.textLength)
        assertTrue(inlays().isEmpty())
        view.dispose()
    }
}
