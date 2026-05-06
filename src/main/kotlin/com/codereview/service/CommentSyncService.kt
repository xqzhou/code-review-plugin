package com.codereview.service

import com.codereview.git.GitBranchProvider
import com.codereview.ui.CommentNotifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@Service(Service.Level.PROJECT)
class CommentSyncService(private val project: Project) {

    private val running = AtomicBoolean(false)
    private val activeMode = AtomicReference<SyncMode?>(null)

    fun isSyncing(): Boolean = running.get()

    fun activeSyncText(): String? = activeMode.get()?.activeText

    fun syncFromMr() {
        runSync(SyncMode.PULL)
    }

    fun pushToMr() {
        runSync(SyncMode.PUSH)
    }

    private fun runSync(mode: SyncMode) {
        if (!running.compareAndSet(false, true)) {
            notifyInfo("MR Comment Sync Running", "A sync is already in progress.")
            return
        }
        activeMode.set(mode)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, mode.taskTitle, true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    syncInBackground(indicator, mode)
                } finally {
                    activeMode.set(null)
                    running.set(false)
                }
            }
        })
    }

    fun openLastSyncLog() {
        val logFile = latestSyncLogFile() ?: run {
            notifyWarn("Sync Log Missing", "No sync log has been written for this project yet.")
            return
        }
        ApplicationManager.getApplication().invokeLater {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(logFile)
            if (virtualFile == null) {
                CommentNotifications.warn(project, "Sync Log Missing", "Cannot open `$logFile`.")
                return@invokeLater
            }
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }

    private fun syncInBackground(indicator: ProgressIndicator, mode: SyncMode) {
        val projectDir = project.basePath?.let(Path::of) ?: run {
            reloadLocalOnly("Project Path Missing", "Cannot sync MR comments because this project has no local path.")
            return
        }
        val skillFile = projectDir.resolve(".agents/skills/sync-comments/SKILL.md")
        if (!Files.isRegularFile(skillFile)) {
            reloadLocalOnly(
                "Sync Skill Missing",
                "Cannot find `.agents/skills/sync-comments/SKILL.md`; reloaded local comments only."
            )
            return
        }

        // Fast path: pure-shell driver covers common sync modes without launching the agent.
        // Falls through to the agent on exit 65 (unsupported state, e.g. MCP-only ops).
        val shellResult = runCatching {
            runShellDriver(projectDir, indicator, mode)
        }.getOrNull()
        if (shellResult != null && shellResult.exitCode == 0 && !shellResult.canceled && !shellResult.timedOut) {
            CommentService.getInstance(project).reloadFromDiskAndNotify()
            val summary = summarizeOutput(shellResult.output).ifBlank {
                mode.shellFallbackSummary
            }
            notifyInfo(mode.successTitle, "$summary\nLog: ${shellResult.logFile}")
            return
        }
        if (shellResult != null && shellResult.canceled) {
            notifyWarn("${mode.operationTitle} Canceled", "Shell driver was canceled.\nLog: ${shellResult.logFile}")
            return
        }
        if (shellResult != null && shellResult.timedOut) {
            notifyWarn(
                "${mode.operationTitle} Timed Out",
                "Shell driver did not finish within ${SYNC_TIMEOUT_MINUTES} minutes.\nLog: ${shellResult.logFile}"
            )
            return
        }
        if (shellResult != null && shellResult.exitCode != 0) {
            notifyWarn(
                "${mode.operationTitle} Failed",
                "Shell driver exited with code ${shellResult.exitCode}.\n${summarizeOutput(shellResult.output)}\nLog: ${shellResult.logFile}"
            )
            return
        }

        val agent = resolveAgent(projectDir, mode) ?: run {
            reloadLocalOnly("Local Agent Missing", "Cannot find `claude` or `codex`; reloaded local comments only.")
            return
        }

        indicator.text = "Running ${agent.displayName} sync-comments ${mode.shellMode}"
        val result = runCatching {
            runAgent(agent, projectDir, indicator)
        }.getOrElse { error ->
            notifyWarn("${mode.operationTitle} Failed", "Could not start ${agent.displayName}: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        if (result.canceled) {
            notifyWarn("${mode.operationTitle} Canceled", "The local agent process was canceled.\nLog: ${result.logFile}")
            return
        }
        if (result.timedOut) {
            notifyWarn(
                "${mode.operationTitle} Timed Out",
                "The local agent did not finish within ${SYNC_TIMEOUT_MINUTES} minutes.\nLog: ${result.logFile}"
            )
            return
        }
        if (result.exitCode != 0) {
            notifyWarn(
                "${mode.operationTitle} Failed",
                "${agent.displayName} exited with code ${result.exitCode}.\n${summarizeOutput(result.output)}\nLog: ${result.logFile}"
            )
            return
        }

        CommentService.getInstance(project).reloadFromDiskAndNotify()
        val summary = summarizeOutput(result.output).ifBlank {
            mode.agentFallbackSummary
        }
        notifyInfo(mode.successTitle, "$summary\nLog: ${result.logFile}")
    }

    private fun runShellDriver(projectDir: Path, indicator: ProgressIndicator, mode: SyncMode): AgentResult? {
        val script = projectDir.resolve(".agents/skills/sync-comments/sync.sh")
        if (!Files.isRegularFile(script)) return null
        val command = listOf("/usr/bin/env", "bash", script.toString(), "--mode", mode.shellMode)
        val agent = AgentCommand(displayName = mode.shellAgentName, command = command, prompt = "")
        indicator.text = "Running sync-comments shell driver (${mode.shellMode})"
        val result = runProcess(agent, projectDir, indicator)
        if (result.exitCode == 65) return null  // unsupported state — caller falls back to agent
        return result
    }

    private fun runAgent(agent: AgentCommand, projectDir: Path, indicator: ProgressIndicator): AgentResult =
        runProcess(agent, projectDir, indicator)

    private fun runProcess(agent: AgentCommand, projectDir: Path, indicator: ProgressIndicator): AgentResult {
        val logFile = nextSyncLogFile(projectDir)
        Files.createDirectories(logFile.parent)
        val t0 = System.nanoTime()
        fun stamp(label: String, writer: BufferedWriter) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            synchronized(writer) {
                writer.appendLine("[T+%6dms] %s".format(ms, label))
                writer.flush()
            }
        }
        val process = ProcessBuilder(agent.command)
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .withLocalAgentEnvironment()
            .start()
        val output = StringBuffer()
        val logWriter = Files.newBufferedWriter(logFile)
        logWriter.writeSyncHeader(agent, projectDir)
        stamp("subprocess spawned (pid=${process.pid()})", logWriter)
        runCatching {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(agent.prompt)
                writer.newLine()
            }
        }.onFailure { error ->
            synchronized(output) {
                output.append("Failed to write prompt to ${agent.displayName} stdin: ")
                    .append(error.message ?: error.javaClass.simpleName)
                    .append('\n')
            }
        }
        val readerThread = thread(
            start = true,
            isDaemon = true,
            name = "code-review-comment-sync-output"
        ) {
            var firstLine = true
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (firstLine) { stamp("first stdout line", logWriter); firstLine = false }
                    synchronized(logWriter) { logWriter.appendLine(line); logWriter.flush() }
                    synchronized(output) {
                        output.append(line).append('\n')
                        if (output.length > MAX_OUTPUT_CHARS) {
                            output.delete(0, output.length - MAX_OUTPUT_CHARS)
                        }
                    }
                }
            }
        }

        val deadlineMillis = System.currentTimeMillis() + SYNC_TIMEOUT_MILLIS
        while (process.isAlive) {
            if (indicator.isCanceled) {
                process.destroyForcibly()
                readerThread.join(1_000)
                stamp("subprocess canceled", logWriter)
                logWriter.close()
                return AgentResult(-1, output.toString(), logFile, canceled = true, timedOut = false)
            }
            if (System.currentTimeMillis() > deadlineMillis) {
                process.destroyForcibly()
                readerThread.join(1_000)
                stamp("subprocess timed out", logWriter)
                logWriter.close()
                return AgentResult(-1, output.toString(), logFile, canceled = false, timedOut = true)
            }
            Thread.sleep(200)
        }
        readerThread.join(1_000)
        val exitCode = process.exitValue()
        stamp("subprocess exited code=$exitCode", logWriter)
        logWriter.close()
        return AgentResult(exitCode, output.toString(), logFile, canceled = false, timedOut = false)
    }

    private fun resolveAgent(projectDir: Path, mode: SyncMode): AgentCommand? {
        val prompt = syncPrompt(projectDir, mode)
        if (isExecutableAvailable("claude")) {
            return AgentCommand(
                displayName = "Claude",
                command = listOf(
                    ENV_EXECUTABLE,
                    "claude",
                    "-p",
                    "--output-format",
                    "text",
                    "--permission-mode",
                    "acceptEdits",
                    "--allowedTools",
                    "Read,Glob,Grep,Bash,Edit,Write"
                ),
                prompt = prompt
            )
        }

        if (isExecutableAvailable("codex")) {
            return AgentCommand(
                displayName = "Codex",
                command = listOf(
                    ENV_EXECUTABLE,
                    "codex",
                    "exec",
                    "-C",
                    projectDir.toString(),
                    "-s",
                    "workspace-write",
                    "-a",
                    "never",
                    "-"
                ),
                prompt = prompt
            )
        }

        return null
    }

    private fun syncPrompt(projectDir: Path, mode: SyncMode): String {
        val branch = GitBranchProvider.currentBranch(project)
        val storePath = CommentService.getInstance(project).storeFilePath()
        return """
            Use the repository-local sync-comments skill to ${mode.promptAction}.

            Working directory: $projectDir
            Skill file: ${projectDir.resolve(".agents/skills/sync-comments/SKILL.md")}
            Current branch: $branch
            Local comment store: $storePath

            Requirements:
            - Run the sync-comments workflow in ${mode.shellMode} mode with confirmation, equivalent to: ${mode.promptCommand}
            - If the skill needs the MR URL, discover the MR for the current branch using the tools described by the skill.
            - Only update the MR comments and local .code-review comment JSON required by this operation.
            - Do not edit source files.
            - Print a concise result summary.
        """.trimIndent()
    }

    private fun isExecutableAvailable(name: String): Boolean =
        augmentedPathEntries()
            .asSequence()
            .filter { it.isNotBlank() }
            .map { Path.of(it).resolve(name) }
            .firstOrNull { Files.isExecutable(it) }
            ?.let { true } == true

    private fun ProcessBuilder.withLocalAgentEnvironment(): ProcessBuilder {
        val environment = environment()
        environment["PATH"] = augmentedPathEntries().joinToString(System.getProperty("path.separator"))
        environment.putIfAbsent("TERM", "dumb")
        return this
    }

    private fun augmentedPathEntries(): List<String> {
        val pathSeparator = System.getProperty("path.separator")
        val existingPath = System.getenv("PATH").orEmpty().split(pathSeparator)
        return (LOCAL_BIN_DIRS + existingPath)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun nextSyncLogFile(projectDir: Path): Path {
        val timestamp = LocalDateTime.now().format(LOG_TIMESTAMP_FORMAT)
        return syncLogsDir(projectDir).resolve("sync-comments-$timestamp.log")
    }

    private fun latestSyncLogFile(): Path? {
        val projectDir = project.basePath?.let(Path::of) ?: return null
        val logsDir = syncLogsDir(projectDir)
        if (!Files.isDirectory(logsDir)) return null
        return Files.list(logsDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("sync-comments-") }
                .max(compareBy { Files.getLastModifiedTime(it).toMillis() })
                .orElse(null)
        }
    }

    private fun syncLogsDir(projectDir: Path): Path =
        projectDir.resolve(".code-review").resolve("logs")

    private fun BufferedWriter.writeSyncHeader(agent: AgentCommand, projectDir: Path) {
        appendLine("Code Review Plugin MR Comment Sync")
        appendLine("Started: ${LocalDateTime.now()}")
        appendLine("Project: $projectDir")
        appendLine("Agent: ${agent.displayName}")
        appendLine("Command: ${agent.commandForLog()}")
        appendLine()
        appendLine("Output")
        appendLine("------")
        flush()
    }

    private fun reloadLocalOnly(title: String, content: String) {
        CommentService.getInstance(project).reloadFromDiskAndNotify()
        notifyWarn(title, content)
    }

    private fun notifyInfo(title: String, content: String) {
        ApplicationManager.getApplication().invokeLater {
            CommentNotifications.info(project, title, content)
        }
    }

    private fun notifyWarn(title: String, content: String) {
        ApplicationManager.getApplication().invokeLater {
            CommentNotifications.warn(project, title, content)
        }
    }

    private fun summarizeOutput(output: String): String =
        output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(8)
            .joinToString("\n")
            .take(MAX_NOTIFICATION_CHARS)

    private data class AgentCommand(
        val displayName: String,
        val command: List<String>,
        val prompt: String
    )

    private data class AgentResult(
        val exitCode: Int,
        val output: String,
        val logFile: Path,
        val canceled: Boolean,
        val timedOut: Boolean
    )

    private enum class SyncMode(
        val shellMode: String,
        val taskTitle: String,
        val operationTitle: String,
        val successTitle: String,
        val activeText: String,
        val shellAgentName: String,
        val promptAction: String,
        val promptCommand: String,
        val shellFallbackSummary: String,
        val agentFallbackSummary: String
    ) {
        PULL(
            shellMode = "pull",
            taskTitle = "Syncing MR Comments",
            operationTitle = "MR Comment Sync",
            successTitle = "MR Comments Synced",
            activeText = "Syncing from MR",
            shellAgentName = "ShellPull",
            promptAction = "pull MR comments into the code-review plugin JSON",
            promptCommand = "sync-comments --pull --confirm",
            shellFallbackSummary = "Pulled MR comments via shell driver.",
            agentFallbackSummary = "Pulled MR comments into the local comment store."
        ),
        PUSH(
            shellMode = "push",
            taskTitle = "Pushing MR Comments",
            operationTitle = "MR Comment Push",
            successTitle = "MR Comments Pushed",
            activeText = "Pushing to MR",
            shellAgentName = "ShellPush",
            promptAction = "push local code-review comments to the MR",
            promptCommand = "sync-comments --push --confirm",
            shellFallbackSummary = "Pushed local comments via shell driver.",
            agentFallbackSummary = "Pushed local comments to the MR."
        )
    }

    private fun AgentCommand.commandForLog(): String =
        command.joinToString(" ") { part ->
            when {
                part.contains('\n') -> "<prompt>"
                part.any { it.isWhitespace() } -> "\"${part.replace("\"", "\\\"")}\""
                else -> part
            }
        }

    companion object {
        private const val ENV_EXECUTABLE = "/usr/bin/env"
        private const val SYNC_TIMEOUT_MINUTES = 10
        private const val SYNC_TIMEOUT_MILLIS = SYNC_TIMEOUT_MINUTES * 60 * 1_000L
        private const val MAX_OUTPUT_CHARS = 20_000
        private const val MAX_NOTIFICATION_CHARS = 1_200
        private val LOG_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        private val HOME = System.getProperty("user.home")
        private val LOCAL_BIN_DIRS = listOf(
            "$HOME/.local/bin",
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/usr/bin",
            "/bin"
        )

        fun getInstance(project: Project): CommentSyncService = project.service()
    }
}
