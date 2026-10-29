package com.claudeinline

import com.intellij.openapi.progress.ProgressIndicator
import java.nio.file.Path

/** Asks Claude (read-only tools) to profile the repository and stores the result for later prompts. */
object ProjectAnalyzer {
    val LEVEL = ThinkingLevel.THINK_HARDER
    const val MIN_ANSWER_CHARS = 200
    private const val MIN_TIMEOUT_SECONDS = 900

    fun buildPrompt(): String = """
        You are analyzing the code repository in the current working directory to write a compact "project profile" that other tools will use to generate new code consistent with this codebase.

        Explore the repository with the Read, Grep and Glob tools. Then reply with ONLY a markdown document (no preamble, no closing remarks, not wrapped in a code fence) with exactly these sections, as "## " headings:

        ## Stack and layout
        ## API pattern
        (routers/controllers, request/response models, validation, error handling, auth, naming)
        ## DB pattern
        (ORM/driver, session/connection lifecycle, repositories/DAOs, transactions, migrations)
        ## Redis/cache pattern
        (client creation/wrapper, key naming, TTL constants, serialization, invalidation)
        ## Cache-aside
        (read path: get -> miss -> load from DB -> set with TTL; write path: invalidate/update; stampede protection; the existing helper(s) to REUSE, each with its file path and verbatim signature)
        ## Conventions
        (typing, logging, config/env, tests)
        ## Reuse checklist for new code

        Rules:
        - Ignore these directories: venv, .venv, node_modules, site-packages, build, dist, __pycache__, .git, .idea.
        - Cite file paths for every claim.
        - Quote only short verbatim signatures, never whole bodies.
        - For any topic absent from the codebase write "Not found in this codebase" instead of guessing.
        - Keep the whole document under about 250 lines.
        - Do not modify any file.
    """.trimIndent()

    fun analyze(basePath: String, options: ClaudeCliOptions, indicator: ProgressIndicator): Path {
        val effective = options.copy(
            level = LEVEL,
            readOnlyTools = true,
            timeoutSeconds = maxOf(options.timeoutSeconds, MIN_TIMEOUT_SECONDS),
        )
        val raw = ClaudeCli.run(buildPrompt(), effective, basePath, indicator)
        ProjectPatternsStore.save(basePath, cleanAnswer(raw))
        return ProjectPatternsStore.path(basePath)
    }

    /** Strips a fence wrapping the whole answer and rejects blank or implausibly short output. */
    internal fun cleanAnswer(raw: String): String {
        val text = stripWrappingFence(raw.trim())
        if (text.length < MIN_ANSWER_CHARS) {
            throw ClaudeEditException(
                if (text.isBlank()) "Claude returned an empty project analysis."
                else "Claude's project analysis was too short to be useful (${text.length} characters).",
            )
        }
        return text
    }

    internal fun stripWrappingFence(text: String): String {
        val lines = text.lines()
        if (lines.size < 2) return text
        if (!lines.first().trim().startsWith("```") || lines.last().trim() != "```") return text
        val inner = lines.subList(1, lines.size - 1)
        if (inner.any { it.trim() == "```" }) return text
        return inner.joinToString("\n").trim()
    }
}
