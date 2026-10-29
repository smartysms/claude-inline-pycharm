package com.claudeinline

/** Turns the raw CLI output into an [EditResponse]. The reply format is defined by [PromptBuilder]. */
object ResponseParser {
    private const val REPLACEMENT_OPEN = "<replacement>"
    private const val REPLACEMENT_CLOSE = "</replacement>"
    private const val ERROR_OPEN = "<error>"
    private const val ERROR_CLOSE = "</error>"
    private const val SNIPPET_CHARS = 200

    private val fenceBlock = Regex(
        "^[ \\t]*```[^\\n]*\\n(.*?)^[ \\t]*```[ \\t]*$",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
    )
    private val fenceLine = Regex("^[ \\t]*```", RegexOption.MULTILINE)
    private val importsBlock = Regex("<imports>(.*?)</imports>", RegexOption.DOT_MATCHES_ALL)

    fun parse(raw: String, target: EditTarget): EditResponse {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')

        val open = text.indexOf(REPLACEMENT_OPEN)
        val close = text.lastIndexOf(REPLACEMENT_CLOSE)
        val hasReplacement = open >= 0 && close >= open + REPLACEMENT_OPEN.length

        errorReason(text, if (hasReplacement) open else -1)?.let { throw ClaudeEditException(it) }

        val content: String
        val outside: String
        if (hasReplacement) {
            content = text.substring(open + REPLACEMENT_OPEN.length, close)
            outside = text.removeRange(open, close + REPLACEMENT_CLOSE.length)
        } else {
            content = firstFencedBlock(text) ?: throw ClaudeEditException(noReplacementMessage(text))
            outside = text
        }

        val replacement = unwrapFence(content.removePrefix("\n").removeSuffix("\n"))
        val trailing = target.text.takeLastWhile { it == '\n' }
        return EditResponse(replacement.trimEnd('\n') + trailing, extractImports(outside))
    }

    /** An `<error>` tag wins unless it only appears inside the replacement (e.g. code that mentions it). */
    private fun errorReason(text: String, replacementStart: Int): String? {
        val open = text.indexOf(ERROR_OPEN)
        if (open < 0 || (replacementStart >= 0 && open > replacementStart)) return null
        val from = open + ERROR_OPEN.length
        val close = text.indexOf(ERROR_CLOSE, from).takeIf { it >= 0 } ?: text.length
        return text.substring(from, close).trim().ifEmpty { "Claude reported an error without a reason" }
    }

    private fun firstFencedBlock(text: String): String? =
        fenceBlock.find(text)?.groupValues?.get(1)?.removeSuffix("\n")

    private fun unwrapFence(content: String): String {
        val body = fenceBlock.matchEntire(content.trim())?.groupValues?.get(1) ?: return content
        return if (fenceLine.containsMatchIn(body)) content else body.removeSuffix("\n")
    }

    private fun extractImports(outside: String): List<String> =
        importsBlock.findAll(outside)
            .flatMap { it.groupValues[1].lineSequence() }
            .map { it.trim() }
            .filter { it.startsWith("import ") || it.startsWith("from ") }
            .toList()

    private fun noReplacementMessage(text: String): String {
        val snippet = text.trim().replace(Regex("\\s+"), " ")
        if (snippet.isEmpty()) return "Claude did not return a replacement (empty output)"
        val shown = if (snippet.length > SNIPPET_CHARS) snippet.take(SNIPPET_CHARS) + "..." else snippet
        return "Claude did not return a replacement. Output was: $shown"
    }
}
