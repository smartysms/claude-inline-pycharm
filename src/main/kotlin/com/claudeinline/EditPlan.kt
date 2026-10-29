package com.claudeinline

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.jetbrains.python.psi.PyFile

/** Replaces `[start, end)` of a text with [text]; `start == end` is an insertion. */
internal data class TextEdit(val start: Int, val end: Int, val text: String)

/**
 * Edits in the order they must be applied: highest offset first so earlier offsets stay valid. Among edits with
 * the same start the longer one goes first, so an insertion at a replacement's start ends up in front of it.
 */
internal fun applicationOrder(edits: List<TextEdit>): List<TextEdit> {
    val ordered = edits.sortedWith(compareByDescending<TextEdit> { it.start }.thenByDescending { it.end })
    for ((later, earlier) in ordered.zipWithNext()) {
        require(earlier.end <= later.start) { "Overlapping edits: $earlier and $later" }
    }
    return ordered
}

internal fun applyEdits(text: String, edits: List<TextEdit>): String {
    val result = StringBuilder(text)
    for (edit in applicationOrder(edits)) result.replace(edit.start, edit.end, edit.text)
    return result.toString()
}

/** Offsets of the new region in the post-apply document, and markers over the inserted import text (if any). */
class AppliedEdit(val regionStart: Int, val regionEnd: Int, val importMarkers: List<RangeMarker>)

/** The planned change to a whole document: [resultText] is exactly what the document holds after [apply]. */
class EditPlan internal constructor(
    val originalText: String,
    private val replacement: TextEdit,
    private val importInsertion: TextEdit?,
) {
    private val edits = listOfNotNull(replacement, importInsertion)
    val resultText: String = applyEdits(originalText, edits)

    /** Call inside a write command, on the document the plan was made for and before anything else changes it. */
    fun apply(document: Document): AppliedEdit {
        check(document.text == originalText) { "The document changed after the edit was planned" }
        for (edit in applicationOrder(edits)) document.replaceString(edit.start, edit.end, edit.text)

        val insertion = importInsertion
        val shift = if (insertion != null && insertion.start <= replacement.start) insertion.text.length else 0
        val regionStart = replacement.start + shift
        val markers = if (insertion == null) {
            emptyList()
        } else {
            val start = if (shift > 0) insertion.start else insertion.start + replacement.text.length - (replacement.end - replacement.start)
            listOf(document.createRangeMarker(start, start + insertion.text.length))
        }
        return AppliedEdit(regionStart, regionStart + replacement.text.length, markers)
    }
}

object EditReverter {

    /** Call inside a write command: puts [baselineText] back over [region] and removes the imports the edit inserted. */
    fun revert(document: Document, region: TextRange, baselineText: String, importMarkers: List<RangeMarker>) {
        val edits = importMarkers.filter { it.isValid }.map { TextEdit(it.startOffset, it.endOffset, "") } +
            TextEdit(region.startOffset, region.endOffset, baselineText)
        for (edit in applicationOrder(edits)) document.replaceString(edit.start, edit.end, edit.text)
        importMarkers.forEach { it.dispose() }
    }
}

object EditPlanner {

    /** [region] is the target range in the current document; Claude's imports are added to Python files only. */
    fun plan(document: Document, file: PsiFile?, region: TextRange, response: EditResponse): EditPlan {
        val replacement = TextEdit(region.startOffset, region.endOffset, normalizeLineBreaks(response.replacement))
        return EditPlan(document.text, replacement, importEdit(document, file, region, response.imports))
    }

    private fun importEdit(document: Document, file: PsiFile?, region: TextRange, imports: List<String>): TextEdit? {
        if (imports.isEmpty() || file !is PyFile || !file.isValid) return null
        val missing = ImportInsertion.missingImports(imports, document.text)
        if (missing.isEmpty()) return null

        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        val block = missing.joinToString("\n") { normalizeLineBreaks(it) }
        val lastImport = file.importBlock.lastOrNull()
        val docString = file.docStringExpression
        val (offset, text) = when {
            lastImport != null -> lineEnd(document, lastImport.textRange.endOffset) to "\n$block"
            docString != null -> lineEnd(document, docString.textRange.endOffset) to "\n\n$block"
            else -> 0 to "$block\n"
        }
        if (offset > region.startOffset && offset < region.endOffset) return null
        return TextEdit(offset, offset, text)
    }

    private fun lineEnd(document: Document, elementEnd: Int): Int =
        document.getLineEndOffset(document.getLineNumber(maxOf(elementEnd - 1, 0)))

    private fun normalizeLineBreaks(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
}
