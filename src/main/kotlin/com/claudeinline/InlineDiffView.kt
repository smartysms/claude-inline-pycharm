package com.claudeinline

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.editor.ex.util.EditorUtil
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.Rectangle2D

/**
 * Renders a line diff of [show]'s baseline against the current region text inside the editor: new lines get the
 * scheme's "inserted" background, removed lines are drawn as a block inlay above them.
 */
class InlineDiffView(private val editor: Editor) : Disposable {

    private val highlighters = mutableListOf<RangeHighlighter>()
    private val inlays = mutableListOf<Inlay<*>>()

    /** One changed line range: old lines [oldStart, oldEnd) became new lines [newStart, newEnd). */
    private data class Chunk(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int)

    fun show(baselineText: String, regionStart: Int, regionEnd: Int) {
        clear()
        val document = editor.document
        val current = document.getText(TextRange(regionStart, regionEnd))
        val oldLines = lines(baselineText)
        val newLines = lines(current)
        val firstLine = document.getLineNumber(regionStart)

        for (chunk in chunks(baselineText, current, oldLines.size, newLines.size)) {
            val newEnd = minOf(chunk.newEnd, newLines.size)
            if (newEnd > chunk.newStart) {
                val from = document.getLineStartOffset(firstLine + chunk.newStart)
                val to = document.getLineEndOffset(firstLine + newEnd - 1)
                highlighters += editor.markupModel.addRangeHighlighter(
                    DiffColors.DIFF_INSERTED, from, to, HighlighterLayer.SELECTION - 1, HighlighterTargetArea.LINES_IN_RANGE,
                )
            }
            if (chunk.oldEnd > chunk.oldStart) {
                addRemoved(oldLines.subList(chunk.oldStart, chunk.oldEnd), firstLine + chunk.newStart)
            }
        }
    }

    fun clear() {
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
        inlays.forEach { Disposer.dispose(it) }
        inlays.clear()
    }

    override fun dispose() = clear()

    private fun addRemoved(removed: List<String>, documentLine: Int) {
        val document = editor.document
        val renderer = RemovedLinesRenderer(editor, removed)
        val inlay = if (documentLine < document.lineCount) {
            editor.inlayModel.addBlockElement(document.getLineStartOffset(documentLine), false, true, 0, renderer)
        } else {
            editor.inlayModel.addBlockElement(document.textLength, true, false, 0, renderer)
        }
        if (inlay != null) inlays += inlay
    }

    private fun chunks(oldText: String, newText: String, oldCount: Int, newCount: Int): List<Chunk> = try {
        ComparisonManager.getInstance()
            .compareLines(oldText, newText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
            .map { Chunk(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
    } catch (e: DiffTooBigException) {
        listOf(Chunk(0, oldCount, 0, newCount))
    }

    private fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")

    private class RemovedLinesRenderer(private val editor: Editor, private val lines: List<String>) : EditorCustomElementRenderer {
        private val shown = lines.map { it.replace("\t", " ".repeat(EditorUtil.getTabSize(editor))) }
        private val font get() = editor.colorsScheme.getFont(EditorFontType.PLAIN)

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val metrics = editor.contentComponent.getFontMetrics(font)
            return shown.maxOf { metrics.stringWidth(it) } + 8
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int = shown.size * editor.lineHeight

        override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
            val scheme = editor.colorsScheme
            val deleted = scheme.getAttributes(DiffColors.DIFF_DELETED)
            val width = maxOf(targetRegion.width, editor.scrollingModel.visibleArea.width - targetRegion.x).toInt()
            g.color = deleted?.backgroundColor ?: scheme.defaultBackground
            g.fillRect(targetRegion.x.toInt(), targetRegion.y.toInt(), width, targetRegion.height.toInt())
            g.color = deleted?.foregroundColor ?: scheme.defaultForeground
            g.font = font
            val metrics = g.fontMetrics
            val lineHeight = editor.lineHeight
            shown.forEachIndexed { i, line ->
                val baseline = targetRegion.y + i * lineHeight + (lineHeight - metrics.height) / 2 + metrics.ascent
                g.drawString(line, (targetRegion.x + 4).toFloat(), baseline.toFloat())
            }
        }
    }
}
