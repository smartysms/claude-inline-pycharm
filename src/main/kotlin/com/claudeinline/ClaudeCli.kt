package com.claudeinline

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutionException as FutureExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ClaudeCliOptions(
    val executable: String,
    val model: String,
    val level: ThinkingLevel,
    val isolated: Boolean,
    val readOnlyTools: Boolean,
    val timeoutSeconds: Int,
)

object ClaudeCli {
    const val NOT_FOUND_MESSAGE =
        "Claude CLI not found. Install Claude Code or set its path in Settings | Tools | Inline AI Prompt."

    private const val READ_ONLY_TOOLS = "Read,Grep,Glob"
    private const val POLL_MILLIS = 200L
    private const val KILL_WAIT_SECONDS = 2L
    private const val DRAIN_GRACE_MILLIS = 3_000L
    private const val MAX_DETAIL_CHARS = 600
    private val AUTH_HINT = Regex("log ?in|logged in|auth|api key|credential|\\b401\\b", RegexOption.IGNORE_CASE)

    private val executableNames =
        if (SystemInfo.isWindows) listOf("claude.exe", "claude.cmd", "claude.bat") else listOf("claude")

    /** Everything after the executable. The prompt is never an argument; it goes through stdin. */
    fun buildArguments(options: ClaudeCliOptions): List<String> = buildList {
        add("-p")
        add("--output-format")
        add("text")
        add("--tools")
        if (options.readOnlyTools) {
            add(READ_ONLY_TOOLS)
            add("--allowedTools")
            add(READ_ONLY_TOOLS)
        } else {
            add("")
        }
        if (options.isolated) add("--safe-mode")
        options.level.effort?.let {
            add("--effort")
            add(it)
        }
        add("--no-session-persistence")
        if (options.model.isNotBlank()) {
            add("--model")
            add(options.model.trim())
        }
    }

    fun resolveExecutable(configured: String): String? {
        val explicit = configured.trim().trim('"')
        if (explicit.isNotEmpty() && File(explicit).isFile) return explicit

        for (name in executableNames) {
            PathEnvironmentVariableUtil.findInPath(name)?.let { return it.path }
        }
        val fallbackDirs = listOfNotNull(
            System.getProperty("user.home")?.let { File(it, ".local/bin") },
            System.getenv("APPDATA")?.let { File(it, "npm") },
        )
        for (dir in fallbackDirs) {
            for (name in executableNames) {
                File(dir, name).takeIf { it.isFile }?.let { return it.path }
            }
        }
        return null
    }

    fun run(prompt: String, options: ClaudeCliOptions, workDir: String?, indicator: ProgressIndicator): String {
        val executable = resolveExecutable(options.executable) ?: throw ClaudeEditException(NOT_FOUND_MESSAGE)
        val commandLine = commandLine(executable, buildArguments(options), workDir)
        val process = try {
            commandLine.createProcess()
        } catch (e: ExecutionException) {
            throw ClaudeEditException("Could not start the Claude CLI ($executable): ${e.message}", e)
        }

        try {
            val stdout = Drain(process.inputStream)
            val stderr = Drain(process.errorStream)
            feedStdin(process, prompt)
            awaitExit(process, options, indicator)
            val out = stdout.text()
            val err = stderr.text()
            val exitCode = process.exitValue()
            if (exitCode != 0) throw failure(exitCode, out, err)
            if (out.isBlank()) throw emptyAnswer(err)
            return out
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ProcessCanceledException(e)
        } finally {
            if (process.isAlive) killTree(process)
        }
    }

    internal fun commandLine(executable: String, arguments: List<String>, workDir: String?): GeneralCommandLine {
        val commandLine = GeneralCommandLine(executable).withParameters(arguments).withCharset(Charsets.UTF_8)
        if (workDir != null && File(workDir).isDirectory) commandLine.withWorkDirectory(workDir)
        return commandLine
    }

    private fun feedStdin(process: Process, prompt: String) {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                process.outputStream.use { it.write(prompt.toByteArray(Charsets.UTF_8)) }
            } catch (_: IOException) {
                // The process ended before reading everything; its exit code and output explain why.
            }
        }
    }

    private fun awaitExit(process: Process, options: ClaudeCliOptions, indicator: ProgressIndicator) {
        val timeoutSeconds = options.timeoutSeconds.coerceAtLeast(1)
        val startNanos = System.nanoTime()
        val deadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        var shownSeconds = -1L
        while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            indicator.checkCanceled()
            val now = System.nanoTime()
            if (now >= deadlineNanos) {
                throw ClaudeEditException(
                    "Claude did not answer within $timeoutSeconds seconds. " +
                        "Raise the timeout in Settings | Tools | Inline AI Prompt or try a lower thinking level.",
                )
            }
            val seconds = TimeUnit.NANOSECONDS.toSeconds(now - startNanos)
            if (seconds != shownSeconds) {
                shownSeconds = seconds
                indicator.text2 = "Waiting for Claude (${options.level.label}) - ${seconds}s"
            }
        }
    }

    // Windows does not kill child processes with their parent, so the whole tree is taken down explicitly.
    private fun killTree(process: Process) {
        try {
            process.descendants().toList().forEach { it.destroyForcibly() }
        } catch (_: UnsupportedOperationException) {
            // Not a native process handle; killing the process itself is all that can be done.
        }
        process.destroyForcibly()
        try {
            process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun failure(exitCode: Int, stdout: String, stderr: String): ClaudeEditException {
        val detail = listOf(stdout, stderr).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val message = StringBuilder("Claude CLI failed (exit code $exitCode)")
        if (detail.isNotEmpty()) message.append(": ").append(shorten(detail))
        if (AUTH_HINT.containsMatchIn(detail)) message.append(" (Hint: run `claude` once in a terminal to log in.)")
        return ClaudeEditException(message.toString())
    }

    private fun emptyAnswer(stderr: String): ClaudeEditException {
        val detail = stderr.trim()
        return ClaudeEditException(
            if (detail.isEmpty()) "Claude returned an empty answer." else "Claude returned an empty answer: ${shorten(detail)}",
        )
    }

    private fun shorten(text: String): String =
        if (text.length > MAX_DETAIL_CHARS) text.take(MAX_DETAIL_CHARS) + "..." else text

    /** Reads a stream to its end on a pooled thread so a full pipe can never stall the child. */
    private class Drain(stream: InputStream) {
        private val buffer = ByteArrayOutputStream()
        private val task: Future<*> = AppExecutorUtil.getAppExecutorService().submit(
            Runnable {
                try {
                    stream.copyTo(buffer)
                } catch (_: IOException) {
                    // Stream closed because the process was killed.
                }
            },
        )

        fun text(): String {
            try {
                task.get(DRAIN_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // A grandchild still holds the pipe open; use what has arrived.
            } catch (_: FutureExecutionException) {
                // The copy loop only throws IOException, which it handles.
            }
            return buffer.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
