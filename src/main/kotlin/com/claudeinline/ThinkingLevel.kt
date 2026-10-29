package com.claudeinline

/**
 * How hard Claude is asked to think. [keyword] is prepended to the prompt (the Claude Code
 * "think" ladder) and [effort] is passed to the CLI as `--effort`.
 */
enum class ThinkingLevel(val label: String, val keyword: String?, val effort: String?) {
    DEFAULT("Default", null, null),
    THINK("Think", "think", "medium"),
    THINK_HARD("Think hard", "think hard", "high"),
    THINK_HARDER("Think harder", "think harder", "xhigh"),
    ULTRATHINK("Ultrathink", "ultrathink", "max");

    override fun toString(): String = label

    companion object {
        fun fromName(name: String?): ThinkingLevel = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
