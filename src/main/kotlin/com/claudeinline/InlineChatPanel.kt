package com.claudeinline

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

enum class PanelState { INPUT, PROCESSING, REVIEW }

enum class BannerKind(val background: JBColor, val foreground: JBColor) {
    ERROR(JBColor(0xFADBD8, 0x5C2B29), JBColor(0x7B241C, 0xF5B7B1)),
    WARNING(JBColor(0xFCF3CF, 0x4D4220), JBColor(0x7D6608, 0xF7DC6F)),
    INFO(JBColor(0xD6EAF8, 0x1F3A52), JBColor(0x1B4F72, 0xAED6F1)),
}

interface InlineChatCallbacks {
    fun onSubmit(text: String, level: ThinkingLevel)
    fun onStop()
    fun onAccept()
    fun onReject()
    fun onShowDiff()
    fun onClose()
    fun onEscape()
}

/** The Swing content embedded above the target region. Holds no editor state; the session drives it. */
class InlineChatPanel(
    targetLabel: String,
    initialLevel: ThinkingLevel,
    private val callbacks: InlineChatCallbacks,
    indent: Int,
    private val cardWidth: Int,
) : JPanel(BorderLayout()) {

    private val input = JBTextArea(1, 40)
    private val levelBox = ComboBox(ThinkingLevel.entries.toTypedArray())
    private val slashHint = JBLabel("", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER)
    private val banner = JBLabel()
    private val statusLabel = JBLabel()
    private val stopButton = JButton("Stop")
    private val acceptButton = JButton("Accept")
    private val rejectButton = JButton("Reject")
    private val diffButton = JButton("Show diff")
    private val stateRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
    private val timer = Timer(1000) { tick() }
    private var startedAt = 0L
    private var levelLabel = ""

    var state = PanelState.INPUT
        private set

    val level: ThinkingLevel get() = levelBox.selectedItem as ThinkingLevel
    val focusTarget: JComponent get() = input

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, indent, 4, 8)
        val card = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            background = UIUtil.getPanelBackground()
            border = BorderFactory.createCompoundBorder(
                RoundedLineBorder(JBColor.border(), JBUI.scale(10)), JBUI.Borders.empty(6, 8),
            )
        }
        card.add(buildHeader(targetLabel, initialLevel), BorderLayout.NORTH)
        card.add(buildBody(), BorderLayout.CENTER)
        add(card, BorderLayout.CENTER)
        setState(PanelState.INPUT, "")
        bindKeys()
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(cardWidth + insets.left + insets.right, size.height)
    }

    private fun buildHeader(targetLabel: String, initialLevel: ThinkingLevel): JComponent {
        levelBox.selectedItem = initialLevel
        levelBox.isFocusable = false
        val close = JButton("x").apply {
            isFocusable = false
            toolTipText = "Close"
            isBorderPainted = false
            isContentAreaFilled = false
            addActionListener { callbacks.onClose() }
        }
        val title = JBLabel("<html><b>Inline AI</b>  " + escape(targetLabel) + "</html>")
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(levelBox)
            add(close)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }

    private fun buildBody(): JComponent {
        input.lineWrap = true
        input.wrapStyleWord = true
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = inputChanged()
            override fun removeUpdate(e: DocumentEvent) = inputChanged()
            override fun changedUpdate(e: DocumentEvent) = inputChanged()
        })
        val scroll = JBScrollPane(
            input, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        )
        slashHint.isVisible = false
        banner.isOpaque = true
        banner.isVisible = false
        banner.border = JBUI.Borders.empty(4, 6)

        stopButton.addActionListener { callbacks.onStop() }
        acceptButton.addActionListener { callbacks.onAccept() }
        rejectButton.addActionListener { callbacks.onReject() }
        diffButton.addActionListener { callbacks.onShowDiff() }
        acceptButton.putClientProperty("DefaultButton", true)
        stateRow.isOpaque = false

        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            for (part in listOf<JComponent>(scroll, slashHint, stateRow, banner)) {
                part.alignmentX = LEFT_ALIGNMENT
                add(part)
            }
        }
    }

    fun setState(newState: PanelState, levelLabel: String) {
        state = newState
        this.levelLabel = levelLabel
        timer.stop()
        stateRow.removeAll()
        input.isEnabled = newState != PanelState.PROCESSING
        when (newState) {
            PanelState.INPUT -> Unit
            PanelState.PROCESSING -> {
                startedAt = System.currentTimeMillis()
                statusLabel.icon = AnimatedIcon.Default.INSTANCE
                tick()
                stateRow.add(statusLabel)
                stateRow.add(stopButton)
                timer.start()
            }
            PanelState.REVIEW -> {
                stateRow.add(acceptButton)
                stateRow.add(rejectButton)
                stateRow.add(diffButton)
            }
        }
        input.emptyText.text = if (newState == PanelState.REVIEW) "Refine the result..." else HINT
        stateRow.isVisible = newState != PanelState.INPUT
        relayout()
    }

    fun clearInput() {
        input.text = ""
    }

    fun showBanner(kind: BannerKind, message: String) {
        banner.background = kind.background
        banner.foreground = kind.foreground
        banner.text = "<html>" + escape(message).replace("\n", "<br/>") + "</html>"
        banner.isVisible = true
        relayout()
    }

    fun clearBanner() {
        if (banner.isVisible) {
            banner.isVisible = false
            relayout()
        }
    }

    fun disposePanel() = timer.stop()

    private fun stepLevel(delta: Int) {
        levelBox.selectedIndex = (levelBox.selectedIndex + delta).coerceIn(0, levelBox.itemCount - 1)
    }

    private fun tick() {
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        statusLabel.text = "Thinking ($levelLabel) - ${seconds}s"
    }

    private fun inputChanged() {
        val rows = input.text.lines().size.coerceIn(1, MAX_ROWS)
        if (rows != input.rows) input.rows = rows
        val typed = input.text
        val matches = if (typed.startsWith("/") && !typed.contains(' ')) SlashCommands.matching(typed) else emptyList()
        slashHint.text = matches.joinToString("   ") { "/${it.name} - ${it.description}" }
        slashHint.isVisible = matches.isNotEmpty()
        relayout()
    }

    private fun relayout() {
        revalidate()
        repaint()
    }

    private fun submit() {
        if (state != PanelState.PROCESSING) callbacks.onSubmit(input.text.trim(), level)
    }

    private fun bindKeys() {
        bindKey(KeyEvent.VK_ENTER, 0) { submit() }
        bindKey(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK) { input.replaceSelection("\n") }
        bindKey(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK) { if (state == PanelState.REVIEW) callbacks.onAccept() }
        bindKey(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK) { stepLevel(-1) }
        bindKey(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK) { stepLevel(1) }
        bindKey(KeyEvent.VK_ESCAPE, 0) { callbacks.onEscape() }
    }

    // Registered both as an IDE action (wins over keymap actions such as EditorEnter) and as a Swing binding.
    private fun bindKey(keyCode: Int, modifiers: Int, handler: () -> Unit) {
        val stroke = KeyStroke.getKeyStroke(keyCode, modifiers)
        val id = "claude.inline.$keyCode.$modifiers"
        input.inputMap.put(stroke, id)
        input.actionMap.put(id, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = handler()
        })
        DumbAwareAction.create { handler() }.registerCustomShortcutSet(CustomShortcutSet(stroke), input)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val MAX_ROWS = 6
        const val HINT = "Describe the change, / for commands, or press Enter to let the AI decide"
    }
}
