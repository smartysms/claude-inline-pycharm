package com.claudeinline

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.ui.JBUI

/**
 * One inline chat per editor: the panel embedded above the target, the Claude rounds, and the accept/reject of
 * the result. Rounds are applied to the document at once; [InlineDiffView] renders them against the baseline.
 */
class InlineSession private constructor(
    private val project: Project,
    private val editor: EditorEx,
    private val initialTarget: EditTarget,
) : Disposable, InlineChatCallbacks {

    private val document: Document = editor.document
    private val settings = ClaudeInlineSettings.getInstance()
    private val diffView = InlineDiffView(editor)
    private var region: RangeMarker = document.createRangeMarker(initialTarget.startOffset, initialTarget.endOffset)
    private val panel: InlineChatPanel
    private var inlay: Inlay<*>? = null

    private var rounds = 0
    private var patterns: String? = null
    private var baselineText = ""
    private var roundStartText = ""
    private val importMarkers = mutableListOf<RangeMarker>()
    private var indicator: ProgressIndicator? = null
    private var disposed = false

    init {
        val firstLine = document.getLineStartOffset(document.getLineNumber(initialTarget.startOffset))
        val firstContent = initialTarget.startOffset + initialTarget.text.takeWhile { it == ' ' || it == '\t' }.length
        val indent = editor.offsetToXY(firstContent).x
        val visible = editor.scrollingModel.visibleArea.width
        val width = (visible * 0.8).toInt().coerceIn(JBUI.scale(420), JBUI.scale(760)) - indent.coerceAtLeast(0)
        panel = InlineChatPanel(initialTarget.label, settings.level, this, indent.coerceAtLeast(0), width.coerceAtLeast(JBUI.scale(320)))
        EditorUtil.disposeWithEditor(editor, this)
        val properties = EditorEmbeddedComponentManager.Properties(
            EditorEmbeddedComponentManager.ResizePolicy.none(), null, true, true, 0, firstLine,
        )
        inlay = EditorEmbeddedComponentManager.getInstance().addComponent(editor, panel, properties)
        editor.putUserData(KEY, this)
        showPatternsBanner()
        IdeFocusManager.getInstance(project).requestFocus(panel.focusTarget, true)
    }

    private fun showPatternsBanner() {
        if (!settings.state.useProjectPatterns) return
        val loaded = ProjectPatternsStore.load(project.basePath)
        if (loaded == null) {
            panel.showBanner(
                BannerKind.INFO,
                "No project patterns yet - run Tools | Inline AI: Analyze Project Patterns to match your API/DB/Redis style",
            )
            return
        }
        patterns = loaded.text
        val day = loaded.updatedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        panel.showBanner(BannerKind.INFO, "Using project patterns (updated $day)")
    }

    fun focus() {
        IdeFocusManager.getInstance(project).requestFocus(panel.focusTarget, true)
    }

    // ---- callbacks from the panel ----

    override fun onSubmit(text: String, level: ThinkingLevel) {
        if (disposed || panel.state == PanelState.PROCESSING) return
        if (text.isBlank() && rounds > 0) return
        settings.lastPrompt = text
        settings.level = level
        val instruction = if (text.isBlank()) AutoInstruction.forKind(initialTarget.kind) else SlashCommands.expand(text)
        startRound(instruction, level)
    }

    override fun onStop() {
        indicator?.cancel()
    }

    override fun onEscape() {
        when (panel.state) {
            PanelState.INPUT -> dispose()
            PanelState.PROCESSING -> onStop()
            PanelState.REVIEW -> onReject()
        }
    }

    override fun onClose() {
        when (panel.state) {
            PanelState.PROCESSING -> onStop()
            PanelState.REVIEW -> onAccept()
            PanelState.INPUT -> dispose()
        }
    }

    override fun onAccept() {
        if (!disposed) dispose()
    }

    override fun onReject() {
        if (disposed || panel.state != PanelState.REVIEW) return
        if (!region.isValid) {
            panel.showBanner(BannerKind.ERROR, "The changed region no longer exists; use Undo to revert.")
            return
        }
        val range = region.textRange
        try {
            writeCommand(REJECT_COMMAND) { EditReverter.revert(document, range, baselineText, importMarkers.toList()) }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Reject failed", e)
            panel.showBanner(BannerKind.ERROR, "Reject failed: ${e.message}")
            return
        }
        dispose()
    }

    override fun onShowDiff() {
        DiffPreviewDialog(project, fileType(), "Inline AI: ${initialTarget.label}", roundStartText, document.text).show()
    }

    // ---- rounds ----

    private fun startRound(instruction: String, level: ThinkingLevel) {
        val range = currentRange()
        if (range == null) {
            panel.showBanner(BannerKind.WARNING, CHANGED_MESSAGE)
            return
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val kind = when {
            rounds == 0 -> initialTarget.kind
            initialTarget.kind == TargetKind.FUNCTION -> TargetKind.FUNCTION
            else -> TargetKind.SELECTION
        }
        val target = EditTarget(kind, range.startOffset, range.endOffset, document.getText(range), initialTarget.label)
        if (rounds == 0) baselineText = target.text
        roundStartText = document.text
        val psiFile = psiFile()
        val request = EditRequest(
            fileName = virtualFileName(psiFile),
            language = psiFile?.language?.displayName ?: fileType().name,
            textBefore = document.getText(TextRange(0, range.startOffset)),
            target = target,
            textAfter = document.getText(TextRange(range.endOffset, document.textLength)),
            instruction = instruction,
            level = level,
            projectPatterns = patterns,
        )
        val options = settings.cliOptions(level)
        val workDir = project.basePath

        panel.clearBanner()
        panel.setState(PanelState.PROCESSING, level.label)
        val task = object : Task.Backgroundable(project, "Inline AI (${level.label}): ${abbreviate(instruction)}", true) {
            private var response: EditResponse? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val raw = ClaudeCli.run(PromptBuilder.build(request), options, workDir, indicator)
                response = ResponseParser.parse(raw, target)
            }

            override fun onSuccess() {
                finishRound(target, response)
            }

            override fun onCancel() {
                endRound(null)
            }

            override fun onThrowable(error: Throwable) {
                endRound(describe(error))
            }
        }
        val processIndicator = BackgroundableProcessIndicator(task)
        indicator = processIndicator
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, processIndicator)
    }

    private fun finishRound(target: EditTarget, response: EditResponse?) {
        if (disposed || response == null) return endRound(null)
        try {
            val range = currentRange()
            if (range == null || document.getText(range) != target.text) return endRound(CHANGED_MESSAGE, BannerKind.WARNING)
            val psiManager = PsiDocumentManager.getInstance(project)
            psiManager.commitDocument(document)
            val psiFile = psiManager.getPsiFile(document)
            val plan = EditPlanner.plan(document, psiFile, range, response)
            var applied: AppliedEdit? = null
            writeCommand(APPLY_COMMAND) { applied = plan.apply(document) }
            val edit = applied ?: return endRound("Claude's edit could not be applied")
            region.dispose()
            region = document.createRangeMarker(edit.regionStart, edit.regionEnd)
            importMarkers += edit.importMarkers
            rounds++
            diffView.show(baselineText, edit.regionStart, edit.regionEnd)
            editor.caretModel.moveToOffset(edit.regionEnd.coerceIn(0, document.textLength))
            editor.selectionModel.removeSelection()
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            indicator = null
            panel.clearInput()
            panel.setState(PanelState.REVIEW, "")
            focus()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            endRound(describe(e))
        }
    }

    private fun endRound(message: String?, kind: BannerKind = BannerKind.ERROR) {
        indicator = null
        if (disposed) return
        panel.setState(if (rounds == 0) PanelState.INPUT else PanelState.REVIEW, "")
        if (message != null) panel.showBanner(kind, message)
        focus()
    }

    // ---- helpers ----

    private fun currentRange(): TextRange? =
        if (editor.isDisposed || !region.isValid) null else region.textRange

    private fun psiFile(): PsiFile? = PsiDocumentManager.getInstance(project).getPsiFile(document)

    private fun fileType(): FileType =
        psiFile()?.fileType ?: FileDocumentManager.getInstance().getFile(document)?.fileType ?: PlainTextFileType.INSTANCE

    private fun virtualFileName(psiFile: PsiFile?): String =
        FileDocumentManager.getInstance().getFile(document)?.name ?: psiFile?.name ?: "untitled"

    private fun writeCommand(name: String, action: () -> Unit) {
        val psiFile = psiFile()
        val builder = if (psiFile != null) {
            WriteCommandAction.writeCommandAction(psiFile)
        } else {
            WriteCommandAction.writeCommandAction(project)
        }
        builder.withName(name).run<RuntimeException> { action() }
    }

    private fun describe(error: Throwable): String {
        if (error is ClaudeEditException) return error.message ?: "Claude request failed"
        LOG.warn("Claude inline edit failed", error)
        return "Unexpected error: ${error.message ?: error.javaClass.simpleName}"
    }

    private fun abbreviate(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > MAX_TITLE) oneLine.take(MAX_TITLE - 3) + "..." else oneLine
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        indicator?.cancel()
        indicator = null
        panel.disposePanel()
        Disposer.dispose(diffView)
        inlay?.let { Disposer.dispose(it) }
        inlay = null
        region.dispose()
        importMarkers.clear()
        if (editor.getUserData(KEY) === this) editor.putUserData(KEY, null)
    }

    companion object {
        private val KEY = Key.create<InlineSession>("claude.inline.session")
        private val LOG = Logger.getInstance(InlineSession::class.java)
        private const val APPLY_COMMAND = "Inline AI Edit"
        private const val REJECT_COMMAND = "Inline AI Reject"
        private const val CHANGED_MESSAGE = "The code changed while Claude was working; result discarded"
        private const val MAX_TITLE = 40

        fun updatePresentation(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
        }

        fun start(e: AnActionEvent, autoRun: Boolean) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.HOST_EDITOR) ?: e.getData(CommonDataKeys.EDITOR) ?: return
            ApplicationManager.getApplication().assertIsDispatchThread()
            editor.getUserData(KEY)?.let { it.focus(); return }
            if (editor.isViewer || !editor.document.isWritable) {
                ClaudeNotifier.info(project, "This file is read-only.")
                return
            }
            val editorEx = editor as? EditorEx ?: return ClaudeNotifier.info(project, "This editor is not supported.")
            val target = TargetResolver.resolve(project, editor, PsiDocumentManager.getInstance(project).getPsiFile(editor.document))
            val session = InlineSession(project, editorEx, target)
            if (autoRun) session.onSubmit("", session.settings.level)
        }
    }
}
