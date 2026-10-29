package com.claudeinline

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Zero-prompt entry point: starts a session at once with the auto instruction for the target. */
class ImplementFromContextAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) = InlineSession.updatePresentation(e)

    override fun actionPerformed(e: AnActionEvent) = InlineSession.start(e, autoRun = true)
}
