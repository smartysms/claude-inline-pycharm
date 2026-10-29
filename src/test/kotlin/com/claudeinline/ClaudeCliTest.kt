package com.claudeinline

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import kotlin.concurrent.thread

class ClaudeCliTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun options(
        level: ThinkingLevel = ThinkingLevel.DEFAULT,
        model: String = "",
        isolated: Boolean = true,
        readOnlyTools: Boolean = false,
        executable: String = "claude",
        timeoutSeconds: Int = 120,
    ) = ClaudeCliOptions(executable, model, level, isolated, readOnlyTools, timeoutSeconds)

    // ---- buildArguments ----

    @Test
    fun defaultLevelHasNoEffortAndAnEmptyToolsArgument() {
        assertEquals(
            listOf("-p", "--output-format", "text", "--tools", "", "--safe-mode", "--no-session-persistence"),
            ClaudeCli.buildArguments(options()),
        )
    }

    @Test
    fun everyThinkingLevelMapsToItsEffort() {
        val expected = mapOf(
            ThinkingLevel.DEFAULT to null,
            ThinkingLevel.THINK to "medium",
            ThinkingLevel.THINK_HARD to "high",
            ThinkingLevel.THINK_HARDER to "xhigh",
            ThinkingLevel.ULTRATHINK to "max",
        )
        assertEquals(ThinkingLevel.entries.toSet(), expected.keys)
        for ((level, effort) in expected) {
            val args = ClaudeCli.buildArguments(options(level = level))
            if (effort == null) {
                assertFalse("no --effort for $level", "--effort" in args)
            } else {
                assertEquals(
                    listOf("-p", "--output-format", "text", "--tools", "", "--safe-mode", "--effort", effort, "--no-session-persistence"),
                    args,
                )
            }
        }
    }

    @Test
    fun readOnlyToolsReplaceTheEmptyToolsArgument() {
        assertEquals(
            listOf(
                "-p", "--output-format", "text", "--tools", "Read,Grep,Glob", "--allowedTools", "Read,Grep,Glob",
                "--safe-mode", "--no-session-persistence",
            ),
            ClaudeCli.buildArguments(options(readOnlyTools = true)),
        )
    }

    @Test
    fun notIsolatedOmitsSafeMode() {
        val args = ClaudeCli.buildArguments(options(isolated = false))
        assertFalse("--safe-mode" in args)
        assertEquals(listOf("-p", "--output-format", "text", "--tools", "", "--no-session-persistence"), args)
    }

    @Test
    fun modelIsAppendedWhenNotBlank() {
        assertEquals(
            listOf("--no-session-persistence", "--model", "opus"),
            ClaudeCli.buildArguments(options(model = " opus ")).takeLast(3),
        )
        assertFalse("--model" in ClaudeCli.buildArguments(options(model = "   ")))
    }

    @Test
    fun allOptionsTogetherKeepAStableOrder() {
        assertEquals(
            listOf(
                "-p", "--output-format", "text", "--tools", "Read,Grep,Glob", "--allowedTools", "Read,Grep,Glob",
                "--effort", "max", "--no-session-persistence", "--model", "claude-opus-4-7",
            ),
            ClaudeCli.buildArguments(
                options(
                    level = ThinkingLevel.ULTRATHINK,
                    model = "claude-opus-4-7",
                    isolated = false,
                    readOnlyTools = true,
                ),
            ),
        )
    }

    // ---- resolveExecutable ----

    @Test
    fun resolveExecutableReturnsAnExistingExplicitPath() {
        val exe = tmp.newFile("my-claude.exe")
        assertEquals(exe.path, ClaudeCli.resolveExecutable(exe.path))
        assertEquals(exe.path, ClaudeCli.resolveExecutable("  \"${exe.path}\"  "))
    }

    @Test
    fun resolveExecutableFallsBackToSearchWhenThePathIsMissing() {
        val missing = File(tmp.root, "does-not-exist/claude.exe").path
        val searched = ClaudeCli.resolveExecutable("")
        assertEquals(searched, ClaudeCli.resolveExecutable(missing))
        if (searched != null) assertTrue(File(searched).isFile)
    }

    // ---- process handling ----

    @Test
    fun emptyArgumentReachesTheChildProcess() {
        val java = File(System.getProperty("java.home"), if (SystemInfo.isWindows) "bin/java.exe" else "bin/java")
        val echo = tmp.newFile("Echo.java")
        echo.writeText(
            "public class Echo { public static void main(String[] a) { " +
                "System.out.print(a.length + \":\" + String.join(\"|\", a)); } }",
        )
        val process = ClaudeCli.commandLine(java.path, listOf(echo.path, "", "x"), null).createProcess()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        assertEquals("2:|x", output)
    }

    @Test
    fun stdinIsDeliveredAndStdoutReturned() {
        val cli = fakeCli("findstr \"^\"")
        val prompt = "caf${Char(0xE9)} <replacement>ok</replacement>"
        val out = ClaudeCli.run(prompt, options(executable = cli), null, EmptyProgressIndicator(ModalityState.nonModal()))
        assertTrue(out, out.contains(prompt))
    }

    @Test
    fun nonZeroExitReportsStderrWithALoginHint() {
        val cli = fakeCli("echo Not logged in - please run /login 1>&2", "exit /b 1")
        val e = assertThrows(ClaudeEditException::class.java) {
            ClaudeCli.run("hi", options(executable = cli), null, EmptyProgressIndicator(ModalityState.nonModal()))
        }
        assertTrue(e.message, e.message!!.contains("exit code 1"))
        assertTrue(e.message, e.message!!.contains("Not logged in"))
        assertTrue(e.message, e.message!!.contains("log in"))
    }

    @Test
    fun nonZeroExitFallsBackToStdout() {
        val cli = fakeCli("echo something broke", "exit /b 2")
        val e = assertThrows(ClaudeEditException::class.java) {
            ClaudeCli.run("hi", options(executable = cli), null, EmptyProgressIndicator(ModalityState.nonModal()))
        }
        assertTrue(e.message, e.message!!.contains("exit code 2"))
        assertTrue(e.message, e.message!!.contains("something broke"))
        assertFalse(e.message, e.message!!.contains("log in"))
    }

    @Test
    fun blankOutputOnSuccessIsAnError() {
        val cli = fakeCli("rem prints nothing")
        assertThrows(ClaudeEditException::class.java) {
            ClaudeCli.run("hi", options(executable = cli), null, EmptyProgressIndicator(ModalityState.nonModal()))
        }
    }

    @Test
    fun timeoutKillsTheProcessTree() {
        val cli = fakeCli("ping -n 60 127.0.0.1 >nul")
        val started = Instant.now()
        val e = assertThrows(ClaudeEditException::class.java) {
            ClaudeCli.run("hi", options(executable = cli, timeoutSeconds = 1), null, EmptyProgressIndicator(ModalityState.nonModal()))
        }
        assertTrue(e.message, e.message!!.contains("did not answer within 1 seconds"))
        assertNoProcessRunning("ping", started)
    }

    @Test
    fun cancelKillsTheProcessTree() {
        val cli = fakeCli("ping -n 60 127.0.0.1 >nul")
        val started = Instant.now()
        val indicator = EmptyProgressIndicator(ModalityState.nonModal())
        val canceller = thread { Thread.sleep(1000); indicator.cancel() }
        assertThrows(ProcessCanceledException::class.java) {
            ClaudeCli.run("hi", options(executable = cli), null, indicator)
        }
        canceller.join()
        assertNoProcessRunning("ping", started)
    }

    // ---- live CLI (opt-in: CLAUDE_CLI_IT=1) ----

    @Test
    fun liveCliAnswersThroughStdin() {
        assumeLive()
        val out = ClaudeCli.run(
            "Reply with exactly: <replacement>ok</replacement>",
            options(executable = ""),
            null,
            EmptyProgressIndicator(ModalityState.nonModal()),
        )
        assertTrue(out, out.contains("ok"))
    }

    @Test
    fun liveCancelKillsTheCliAndItsChildren() {
        assumeLive()
        val started = Instant.now()
        val indicator = EmptyProgressIndicator(ModalityState.nonModal())
        val canceller = thread { Thread.sleep(4000); indicator.cancel() }
        val prompt = "Write the complete text of a 20000 word story about a lighthouse keeper. Do not stop early."
        assertThrows(ProcessCanceledException::class.java) {
            ClaudeCli.run(prompt, options(level = ThinkingLevel.ULTRATHINK, executable = "", timeoutSeconds = 300), null, indicator)
        }
        canceller.join()
        assertNoProcessRunning("claude", started)
    }

    private fun assumeLive() = assumeTrue("set CLAUDE_CLI_IT=1 to run", System.getenv("CLAUDE_CLI_IT") == "1")

    // The batch file is the CLI stand-in; GeneralCommandLine wraps it in cmd.exe like a real npm shim.
    private fun fakeCli(vararg lines: String): String {
        assumeTrue("fake CLI scripts are Windows batch files", SystemInfo.isWindows)
        val file = tmp.newFile("fake-${System.nanoTime()}.cmd")
        file.writeText((listOf("@echo off") + lines).joinToString("\r\n") + "\r\n")
        return file.path
    }

    // Orphans are not descendants of this JVM any more, so look at every process started since [since].
    private fun assertNoProcessRunning(namePart: String, since: Instant) {
        fun running() = ProcessHandle.allProcesses()
            .filter { it.isAlive }
            .filter { it.info().command().orElse("").contains(namePart, ignoreCase = true) }
            .filter { it.info().startInstant().map { start -> start.isAfter(since.minusSeconds(1)) }.orElse(false) }
            .map { "${it.pid()} ${it.info().command().orElse("?")}" }
            .toList()

        val deadline = System.nanoTime() + 5_000_000_000L
        while (running().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals("processes left running", emptyList<String>(), running())
    }
}
