package com.mockserver.jetbrains

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessHandlerFactory

/**
 * Shared `docker run` launch logic, used by both the Tools-menu action and the
 * tool-window button so the command is built from settings in exactly one place.
 */
object MockServerDocker {

    /** Build the `docker run` command line from the current settings. */
    fun startCommand(settings: MockServerSettings = MockServerSettings.getInstance()): GeneralCommandLine =
        GeneralCommandLine(
            "docker", "run", "-d", "--rm",
            "--name", settings.effectiveContainerName(),
            // MockServer always listens on 1080 inside the container; map the
            // configured host port to it.
            "-p", "${settings.effectivePort()}:1080",
            settings.effectiveImage()
        )

    /** Start the container in the background. Throws if Docker is unavailable. */
    fun start(settings: MockServerSettings = MockServerSettings.getInstance()) {
        ProcessHandlerFactory.getInstance()
            .createProcessHandler(startCommand(settings))
            .startNotify()
    }
}
