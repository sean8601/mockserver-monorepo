package com.mockserver.jetbrains

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.icons.AllIcons
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Tool window that embeds the live MockServer dashboard inside the IDE using the
 * bundled JCEF (Chromium) engine.
 *
 * When JCEF is available ([JBCefApp.isSupported]) it hosts a [JBCefBrowser] pointed
 * at [MockServerSettings.dashboardUrl] with Reload / Open-in-Browser controls. When
 * JCEF is unavailable (e.g. a JRE without the JCEF runtime, or a remote/headless
 * environment) it degrades gracefully to a panel offering the external browser.
 *
 * The embedded browser is registered with the tool window's disposable so its native
 * Chromium process is released when the tool window is disposed (otherwise the process
 * and its memory leak).
 */
class MockServerDashboardToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val dashboardUrl = MockServerSettings.getInstance().dashboardUrl()
        val component = if (JBCefApp.isSupported()) {
            createEmbeddedBrowser(toolWindow, dashboardUrl)
        } else {
            createUnsupportedPanel(dashboardUrl)
        }
        val content = ContentFactory.getInstance().createContent(component, "Dashboard", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun createEmbeddedBrowser(toolWindow: ToolWindow, dashboardUrl: String): JPanel {
        val browser = JBCefBrowser(dashboardUrl)
        // Release the native Chromium process when the tool window is disposed.
        Disposer.register(toolWindow.disposable, browser)

        val panel = JPanel(BorderLayout())

        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Reload", "Reload the dashboard", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    browser.cefBrowser.reload()
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            add(object : AnAction("Open in Browser", "Open the dashboard in your default browser", AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    BrowserUtil.browse(dashboardUrl)
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("MockServerDashboard", actionGroup, true)
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.NORTH)
        panel.add(browser.component, BorderLayout.CENTER)
        return panel
    }

    private fun createUnsupportedPanel(dashboardUrl: String): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        val label = JBLabel(
            "<html>The embedded dashboard requires JCEF (the bundled Chromium engine), " +
                "which is not available in this IDE or runtime.<br>" +
                "Open the MockServer dashboard in your default browser instead.</html>"
        )
        panel.add(label, BorderLayout.NORTH)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val openButton = JButton("Open in external browser")
        openButton.addActionListener { BrowserUtil.browse(dashboardUrl) }
        buttonPanel.add(openButton)
        panel.add(buttonPanel, BorderLayout.CENTER)
        return panel
    }
}
