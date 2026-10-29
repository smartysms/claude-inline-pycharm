package com.claudeinline

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

/** Balloon notifications for contexts that have no inline panel to show a banner in. */
object ClaudeNotifier {
    private const val GROUP_ID = "Inline AI Prompt"
    private const val TITLE = "Inline AI Prompt"
    private val LOG = Logger.getInstance(ClaudeNotifier::class.java)

    fun error(project: Project?, message: String) = notify(project, message, NotificationType.ERROR)

    fun info(project: Project?, message: String) = notify(project, message, NotificationType.INFORMATION)

    private fun notify(project: Project?, message: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        if (group == null) {
            LOG.warn("Notification group $GROUP_ID is not registered: $message")
            return
        }
        val html = StringUtil.escapeXmlEntities(message).replace("\n", "<br/>")
        group.createNotification(TITLE, html, type).notify(project)
    }
}
