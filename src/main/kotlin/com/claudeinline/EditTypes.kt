package com.claudeinline

enum class TargetKind { SELECTION, FUNCTION, LINE, INSERT, COMMENT }

/** The region of the document Claude is asked to rewrite. Offsets are document offsets. */
data class EditTarget(
    val kind: TargetKind,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val label: String,
)

data class EditRequest(
    val fileName: String,
    val language: String,
    val textBefore: String,
    val target: EditTarget,
    val textAfter: String,
    val instruction: String,
    val level: ThinkingLevel,
    val projectPatterns: String? = null,
)

/** [replacement] takes the place of the target text; [imports] are statements to add near the top of the file. */
data class EditResponse(val replacement: String, val imports: List<String>)

/** Any failure the user should see as a notification: CLI missing, not logged in, bad output, timeout. */
class ClaudeEditException(message: String, cause: Throwable? = null) : Exception(message, cause)
