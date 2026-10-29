package com.claudeinline

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/** Shows [original] against [proposed]; `showAndGet()` is true when the user accepts. */
class DiffPreviewDialog(
    private val project: Project,
    private val fileType: FileType,
    title: String,
    private val original: String,
    private val proposed: String,
) : DialogWrapper(project) {

    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)

    init {
        this.title = title
        setOKButtonText("Accept")
        setCancelButtonText("Discard")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val request = SimpleDiffRequest(null, readOnlyContent(original), readOnlyContent(proposed), "Current", "Proposed")
        diffPanel.setRequest(request)
        return diffPanel.component.apply { preferredSize = JBUI.size(1000, 620) }
    }

    override fun getPreferredFocusedComponent(): JComponent? = diffPanel.preferredFocusedComponent

    override fun getDimensionServiceKey(): String = "ClaudeInlinePrompt.DiffPreviewDialog"

    private fun readOnlyContent(text: String): DocumentContent =
        DiffContentFactory.getInstance().create(project, text, fileType).also { it.document.setReadOnly(true) }
}
