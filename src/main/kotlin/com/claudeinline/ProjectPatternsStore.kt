package com.claudeinline

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

data class LoadedPatterns(val text: String, val updatedAt: Instant)

/** Reads and writes the learned project profile at `<base>/<Branding.DIR>/project-patterns.md`. */
object ProjectPatternsStore {
    const val MAX_INJECTED_CHARS = 12_000
    private const val TRUNCATED_LINE = "[... truncated ...]"

    fun path(basePath: String): Path = Paths.get(basePath, Branding.DIR, "project-patterns.md")

    fun load(basePath: String?): LoadedPatterns? {
        if (basePath.isNullOrBlank()) return null
        val file = path(basePath)
        if (!Files.isRegularFile(file)) return null
        val raw = try {
            String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            return null
        }
        val text = raw.trim()
        if (text.isEmpty()) return null
        val updatedAt = try {
            Files.getLastModifiedTime(file).toInstant()
        } catch (e: IOException) {
            Instant.now()
        }
        return LoadedPatterns(truncate(text), updatedAt)
    }

    fun save(basePath: String, text: String) {
        val file = path(basePath)
        Files.createDirectories(file.parent)
        Files.write(file, text.toByteArray(StandardCharsets.UTF_8))
    }

    internal fun truncate(text: String): String {
        if (text.length <= MAX_INJECTED_CHARS) return text
        val newline = text.lastIndexOf('\n', MAX_INJECTED_CHARS - 1)
        val keep = if (newline > 0) newline else MAX_INJECTED_CHARS
        return text.substring(0, keep).trimEnd() + "\n" + TRUNCATED_LINE
    }
}
