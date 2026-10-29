package com.claudeinline

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction

object TargetResolver {

    /** Must run on the EDT: the document is committed so the PSI matches what the user sees. */
    fun resolve(project: Project, editor: Editor, file: PsiFile?): EditTarget {
        val document = editor.document
        val psiManager = PsiDocumentManager.getInstance(project)
        psiManager.commitDocument(document)

        val selection = editor.selectionModel
        if (selection.hasSelection()) {
            return selectionTarget(document, selection.selectionStart, selection.selectionEnd)
        }

        val caret = editor.caretModel.offset
        val line = document.getLineNumber(caret)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val lineText = document.getText(TextRange(lineStart, lineEnd))
        val psiFile = file ?: psiManager.getPsiFile(document)
        if (isCommentLine(document, psiFile, line)) return commentTarget(document, psiFile, line)
        if (lineText.isBlank()) {
            return target(document, TargetKind.INSERT, lineStart, lineEnd, "Insert at line ${line + 1}")
        }

        enclosingFunction(psiFile, caret, lineStart, lineText)?.let { return functionTarget(document, it) }
        return target(document, TargetKind.LINE, lineStart, lineEnd, "Line ${line + 1}")
    }

    /** True when the first non-blank token of the line is a comment (a trailing comment after code does not count). */
    private fun isCommentLine(document: Document, file: PsiFile?, line: Int): Boolean {
        if (file == null) return false
        val first = firstCodeOffset(document, line) ?: return false
        return file.findElementAt(first) is PsiComment
    }

    private fun firstCodeOffset(document: Document, line: Int): Int? {
        val start = document.getLineStartOffset(line)
        val text = document.getText(TextRange(start, document.getLineEndOffset(line)))
        val index = text.indexOfFirst { !it.isWhitespace() }
        return if (index < 0) null else start + index
    }

    private fun indentOf(document: Document, line: Int): String {
        val start = document.getLineStartOffset(line)
        return document.getText(TextRange(start, document.getLineEndOffset(line))).takeWhile { it.isWhitespace() }
    }

    /** A comment block above a def belongs to the function; otherwise the block itself is the target. */
    private fun commentTarget(document: Document, file: PsiFile?, caretLine: Int): EditTarget {
        val indent = indentOf(document, caretLine)
        fun sameBlock(line: Int) = isCommentLine(document, file, line) && indentOf(document, line) == indent
        var first = caretLine
        while (first > 0 && sameBlock(first - 1)) first--
        var last = caretLine
        while (last < document.lineCount - 1 && sameBlock(last + 1)) last++

        val start = document.getLineStartOffset(first)
        if (file is PyFile && last + 1 < document.lineCount) {
            val next = firstCodeOffset(document, last + 1)
            val function = next?.let { file.findElementAt(it) }?.let { PsiTreeUtil.getParentOfType(it, PyFunction::class.java) }
            if (function != null && function.textRange.startOffset == next) {
                return target(document, TargetKind.FUNCTION, start, function.textRange.endOffset, "Function ${function.name ?: "?"} (with comment)")
            }
        }
        val lines = last - first + 1
        val label = "Comment ($lines ${if (lines == 1) "line" else "lines"})"
        return target(document, TargetKind.COMMENT, start, document.getLineEndOffset(last), label)
    }

    private fun selectionTarget(document: Document, selectionStart: Int, selectionEnd: Int): EditTarget {
        val startLine = document.getLineNumber(selectionStart)
        val lineStart = document.getLineStartOffset(startLine)
        val start = if (document.getText(TextRange(lineStart, selectionStart)).isBlank()) lineStart else selectionStart

        // A selection that ends at column 0 of a line (whole-line selection) does not cover that last line.
        var endLine = document.getLineNumber(selectionEnd)
        if (endLine > startLine && selectionEnd == document.getLineStartOffset(endLine)) endLine--
        val lines = endLine - startLine + 1
        val label = "Selection ($lines ${if (lines == 1) "line" else "lines"})"
        return target(document, TargetKind.SELECTION, start, selectionEnd, label)
    }

    private fun functionTarget(document: Document, function: PyFunction): EditTarget {
        val range = function.textRange
        val startLine = document.getLineNumber(range.startOffset)
        val lineStart = document.getLineStartOffset(startLine)
        val start = if (document.getText(TextRange(lineStart, range.startOffset)).isBlank()) lineStart else range.startOffset
        val endLine = document.getLineNumber(range.endOffset)
        val label = "Function ${function.name ?: "?"} (lines ${startLine + 1}-${endLine + 1})"
        return target(document, TargetKind.FUNCTION, start, range.endOffset, label)
    }

    /** The caret is clamped to the line's code so that indentation and trailing blanks still resolve to a token. */
    private fun enclosingFunction(file: PsiFile?, caret: Int, lineStart: Int, lineText: String): PyFunction? {
        if (file !is PyFile) return null
        val first = lineStart + lineText.indexOfFirst { !it.isWhitespace() }
        val last = lineStart + lineText.indexOfLast { !it.isWhitespace() }
        val element = file.findElementAt(caret.coerceIn(first, last)) ?: return null
        return PsiTreeUtil.getParentOfType(element, PyFunction::class.java)
    }

    private fun target(document: Document, kind: TargetKind, start: Int, end: Int, label: String) =
        EditTarget(kind, start, end, document.getText(TextRange(start, end)), label)
}
