package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Retrieves the expectations MockServer recorded from proxied/forwarded traffic via
 * `PUT /mockserver/retrieve?type=recorded_expectations&format=json` and opens them in
 * a new JSON editor tab. If nothing has been recorded yet (blank or `[]`) an info
 * notification is shown instead.
 *
 * The HTTP call runs on a background thread; opening the editor and posting
 * notifications happen back on the EDT.
 */
class SaveRecordedExpectationsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Retrieving recorded expectations from MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildRetrieveRecordedRequest(baseUrl, "json")
                    )
                    if (!result.ok) {
                        runOnEdt { MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR) }
                        return
                    }
                    if (MockServerRestClient.isEmptyExpectationsBody(result.body)) {
                        runOnEdt { MockServerNotifier.notify(project, "MockServer has not recorded any expectations yet.", NotificationType.INFORMATION) }
                        return
                    }
                    val pretty = MockServerRestClient.prettyPrintJson(result.body)
                    runOnEdt { MockServerEditors.openJsonInEditor(project, "recorded-expectations.mockserver.json", pretty) }
                } catch (ex: Exception) {
                    runOnEdt { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }

    private fun runOnEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block)
    }
}
