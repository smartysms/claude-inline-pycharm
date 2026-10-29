package com.claudeinline

/** [name] is written without the leading slash. */
data class SlashCommand(val name: String, val description: String, val instruction: String)

object SlashCommands {
    val all: List<SlashCommand> = listOf(
        SlashCommand(
            "doc",
            "Add or update documentation",
            "Add or update the documentation of this code: a clear docstring or doc comment in the file's " +
                "existing style (purpose, parameters, return value, raised errors) and short comments only " +
                "where the logic is not obvious. Do not change behavior.",
        ),
        SlashCommand(
            "fix",
            "Find and fix bugs",
            "Find and fix the bugs in this code: logic errors, unhandled edge cases (empty or None input, " +
                "off-by-one, wrong types), resource leaks and wrong error handling. Fix the root cause with the " +
                "smallest sensible change and keep the public interface.",
        ),
        SlashCommand(
            "optimize",
            "Improve performance",
            "Optimize this code for speed and resource use without changing its behavior: better algorithms " +
                "or data structures, no repeated work inside loops, fewer redundant computations, I/O calls " +
                "or queries. Keep it readable.",
        ),
        SlashCommand(
            "simplify",
            "Make the code simpler",
            "Simplify this code: remove redundancy and needless nesting, use clearer names and idiomatic " +
                "constructs, and extract a helper only where it clearly helps readability. Preserve the " +
                "behavior exactly.",
        ),
        SlashCommand(
            "types",
            "Add type annotations",
            "Add or complete the type annotations (parameters, return types and variables where they help) " +
                "following the file's typing style, and add any imports they need. Do not change behavior.",
        ),
    )

    /** Commands whose name starts with the command word of [prefix]; empty unless [prefix] starts with "/". */
    fun matching(prefix: String): List<SlashCommand> {
        val (word, rest) = split(prefix) ?: return emptyList()
        return if (rest == null) {
            all.filter { it.name.startsWith(word, ignoreCase = true) }
        } else {
            all.filter { it.name.equals(word, ignoreCase = true) }
        }
    }

    /** Replaces a leading known "/command [guidance]" by its instruction; anything else is returned unchanged. */
    fun expand(input: String): String {
        val (word, rest) = split(input) ?: return input
        val command = all.firstOrNull { it.name.equals(word, ignoreCase = true) } ?: return input
        val guidance = rest?.trim().orEmpty()
        return if (guidance.isEmpty()) command.instruction else "${command.instruction} Additional guidance: $guidance"
    }

    /** Splits "/word rest" into (word, rest); rest is null when no whitespace follows the word. */
    private fun split(input: String): Pair<String, String?>? {
        val text = input.trimStart()
        if (!text.startsWith("/")) return null
        val body = text.substring(1)
        val end = body.indexOfFirst { it.isWhitespace() }
        return if (end < 0) body to null else body.substring(0, end) to body.substring(end)
    }
}
