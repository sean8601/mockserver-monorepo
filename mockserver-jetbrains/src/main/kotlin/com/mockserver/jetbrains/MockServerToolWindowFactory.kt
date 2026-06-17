package com.mockserver.jetbrains

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Bottom "MockServer" tool window — a launcher that surfaces every MockServer
 * action as a button so users don't have to hunt through the Tools > MockServer
 * menu. Server controls (open dashboard, start, reset) and the editor-file
 * actions (load/save/generate/send/drift) are grouped into labelled rows.
 *
 * The editor-dependent actions are the registered [com.intellij.openapi.actionSystem.AnAction]
 * instances themselves (looked up by id via [ActionManager]) — firing them here is
 * identical to choosing them from the menu, so their editor/notification/threading
 * behaviour is reused verbatim rather than duplicated.
 */
class MockServerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = createPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "MockServer", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun createPanel(project: Project): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        panel.add(JBLabel("Server"))
        panel.add(row().apply {
            add(button("Open Dashboard in IDE") {
                ToolWindowManager.getInstance(project).getToolWindow("MockServerDashboard")?.activate(null)
            })
            add(button("Open Dashboard (Browser)") {
                com.intellij.ide.BrowserUtil.browse(MockServerSettings.getInstance().dashboardUrl())
            })
            add(actionButton("Start (Docker)", "MockServer.StartDocker", project))
            add(actionButton("Reset", "MockServer.Reset", project))
        })

        panel.add(sectionGap())
        panel.add(JBLabel("Editor actions (use the active file)"))
        panel.add(row().apply {
            add(actionButton("Load Expectations", "MockServer.LoadExpectations", project))
            add(actionButton("Save Recorded", "MockServer.SaveRecordedExpectations", project))
            add(actionButton("Generate From OpenAPI", "MockServer.GenerateFromOpenApi", project))
            add(actionButton("Send Test Request", "MockServer.SendRequest", project))
            add(actionButton("Show Drift Report", "MockServer.ShowDriftReport", project))
            add(actionButton("Find Requests by Trace", "MockServer.FindByTrace", project))
        })

        panel.add(sectionGap())
        panel.add(JBLabel("WASM"))
        panel.add(row().apply {
            add(actionButton("Upload WASM Module", "MockServer.UploadWasm", project))
            add(actionButton("List WASM Modules", "MockServer.ListWasm", project))
        })

        return panel
    }

    private fun row(): JPanel {
        val flow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        flow.alignmentX = Component.LEFT_ALIGNMENT
        flow.border = BorderFactory.createEmptyBorder()
        return flow
    }

    private fun sectionGap(): JComponent =
        JPanel().apply {
            preferredSize = JBUI.size(0, 8)
            isOpaque = false
        }

    private fun button(text: String, onClick: () -> Unit): JButton {
        val b = JButton(text)
        b.addActionListener { onClick() }
        return b
    }

    /**
     * A button that fires the registered action with [actionId]. The action is
     * resolved at click time via [ActionManager] and invoked with a [SimpleDataContext]
     * carrying the project and the currently selected text editor, so the
     * editor-dependent actions (Load/Save/Generate/SendRequest) resolve `e.project`
     * and `e.getData(CommonDataKeys.EDITOR)` exactly as they do from the menu — the
     * action's own validation handles the "no editor open" case.
     */
    private fun actionButton(text: String, actionId: String, project: Project): JButton {
        val b = JButton(text)
        b.addActionListener {
            val action = ActionManager.getInstance().getAction(actionId) ?: return@addActionListener
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val builder = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PlatformDataKeys.CONTEXT_COMPONENT, b)
            if (editor != null) {
                builder.add(CommonDataKeys.EDITOR, editor)
            }
            ActionUtil.invokeAction(action, builder.build(), ActionPlaces.TOOLWINDOW_CONTENT, null, null)
        }
        return b
    }
}
