package com.claudeinline

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/** One-off analysis of the codebase conventions; the saved profile is injected into later edit requests. */
class AnalyzeProjectAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath
        if (basePath == null) {
            ClaudeNotifier.error(project, "This project has no base directory to analyze.")
            return
        }
        val options = ClaudeInlineSettings.getInstance().cliOptions(ProjectAnalyzer.LEVEL)
        object : Task.Backgroundable(project, "${Branding.SHORT}: analyzing project patterns", true) {
            private var saved: Path? = null
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    saved = ProjectAnalyzer.analyze(basePath, options, indicator)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onSuccess() {
                val file = saved
                val message = failure
                when {
                    message != null -> ClaudeNotifier.error(project, "Project analysis failed: $message")
                    file != null -> notifyDone(project, file)
                }
            }
        }.queue()
    }

    private fun notifyDone(project: Project, file: Path) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(Branding.NOTIFICATION_GROUP_ID)
            ?: return ClaudeNotifier.info(project, "Project patterns saved to $file")
        val notification = group.createNotification(
            Branding.NAME, "Project patterns saved to ${Branding.DIR}/project-patterns.md", NotificationType.INFORMATION,
        )
        notification.addAction(NotificationAction.createSimple("Open profile") { openFile(project, file) })
        notification.notify(project)
    }

    private fun openFile(project: Project, file: Path) {
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return@invokeLater
            OpenFileDescriptor(project, vf).navigate(true)
        }
    }
}
