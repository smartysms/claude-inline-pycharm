package com.claudeinline

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Opens the inline panel and waits for the user's instruction (Enter on an empty input runs the auto instruction). */
class InlinePromptAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) = InlineSession.updatePresentation(e)

    override fun actionPerformed(e: AnActionEvent) = InlineSession.start(e, autoRun = false)
}
