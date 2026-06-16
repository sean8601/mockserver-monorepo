package com.mockserver.jetbrains

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Action that starts a MockServer Docker container using the configured image,
 * container name, and port (see [MockServerSettings]).
 */
class StartDockerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = MockServerSettings.getInstance()
        try {
            MockServerDocker.start(settings)
            notify(
                project,
                "MockServer (${settings.effectiveImage()}) starting on port ${settings.effectivePort()}",
                NotificationType.INFORMATION
            )
        } catch (ex: Exception) {
            notify(project, "Failed to start MockServer: ${ex.message}", NotificationType.ERROR)
        }
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MockServer Notifications")
            .createNotification(content, type)
            .notify(project)
    }
}
