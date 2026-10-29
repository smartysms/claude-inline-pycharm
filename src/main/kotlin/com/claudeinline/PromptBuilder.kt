package com.claudeinline

/** Builds the single stdin prompt for `claude -p`. The reply format here must match [ResponseParser]. */
object PromptBuilder {
    const val MAX_CONTEXT_CHARS = 20_000

    private const val MARKER_OPEN = "<<<REGION"
    private const val MARKER_CLOSE = "REGION>>>"

    fun build(request: EditRequest): String {
        val target = request.target
        val startsLine = request.textBefore.isEmpty() || request.textBefore.endsWith("\n")
        val endsLine = request.textAfter.isEmpty() || request.textAfter.startsWith("\n")
        val python = request.language.equals("Python", ignoreCase = true)

        val body = buildString {
            append(truncateBefore(request.textBefore))
            append(MARKER_OPEN)
            if (startsLine) append('\n')
            append(target.text)
            if (endsLine) append('\n')
            append(MARKER_CLOSE)
            append(truncateAfter(request.textAfter))
        }

        val head = mutableListOf(
            "You are the editing engine of a code editor. Rewrite ONE region of the file below as the user " +
                "instructs. The region is delimited by the literal markers $MARKER_OPEN and $MARKER_CLOSE; " +
                "the markers are not part of the file.",
            "",
            "Reply format:",
            "- Reply with ONLY <replacement>TEXT</replacement>, where TEXT is the exact text that replaces " +
                "everything between the markers. No markdown fences, no commentary, nothing outside the tags.",
            "- A line break directly after $MARKER_OPEN or directly before $MARKER_CLOSE is layout only and is " +
                "not part of the region. A marker in the middle of a line means your text is spliced in between " +
                "the surrounding characters, so do not repeat them.",
            "- If the instruction cannot be carried out, reply ONLY with <error>short reason</error>.",
        )
        if (python) {
            head += "- If your change needs imports the file does not already have, add after the replacement a " +
                "separate block <imports>...</imports> with one import statement per line, listing only the " +
                "missing ones. Do not put import statements in the replacement."
        }
        head += ""
        head += "Rules:"
        head += "- " + kindRule(target)
        indentationRule(target.kind, startsLine)?.let { head += "- $it" }
        head += "- Make only the change the instruction asks for and do not touch code outside the region."
        head += "- Follow the file's existing style and naming conventions."
        head += ""
        head += "File: " + request.fileName + request.language.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        head += "Region: ${target.label}. It ${placement(startsLine, endsLine)}."
        request.projectPatterns?.takeIf { it.isNotBlank() }?.let { patterns ->
            head += ""
            head += "Project conventions (learned from this codebase):"
            head += "Follow these conventions and reuse the existing helpers they name instead of " +
                "re-implementing; do not mention them in the answer."
            head += patterns.trim()
        }
        head += ""
        head += "----- BEGIN FILE -----"

        return buildString {
            request.level.keyword?.let { append(it).append("\n\n") }
            append(head.joinToString("\n")).append('\n')
            append(body)
            if (!body.endsWith("\n")) append('\n')
            append("----- END FILE -----\n\n")
            append("User instruction:\n")
            append(request.instruction)
        }
    }

    private fun kindRule(target: EditTarget): String = when (target.kind) {
        TargetKind.FUNCTION ->
            "The region is one whole function, including its decorators. Reply with the entire function " +
                "(decorators included) and keep its name and signature unless the instruction asks to change them."
        TargetKind.SELECTION ->
            "The region is the user's selection. Change only the selected text; the code around it stays as " +
                "it is, so your text must fit in seamlessly."
        TargetKind.LINE ->
            "The region is a single line. Reply with the rewritten line only: one line, keeping its indentation."
        TargetKind.COMMENT ->
            "The region is one or more comment lines. Return them (you may tidy the wording) followed by the " +
                "code that implements them, at the same indentation; never drop the comment."
        TargetKind.INSERT ->
            "The region is a blank line where new code is wanted; generate new code for that position. " +
                "Its indentation is currently ${describeIndentation(target.text)}. Your text replaces that " +
                "line, so start every line you write with the indentation the code at this position needs."
    }

    private fun indentationRule(kind: TargetKind, startsLine: Boolean): String? = when {
        kind == TargetKind.INSERT -> null
        startsLine ->
            "The region starts at the beginning of a line, so your first line must include that line's leading " +
                "whitespace exactly as in the original (unless the instruction changes the nesting). Indent " +
                "the following lines consistently, in the file's own style (tabs or spaces)."
        else ->
            "The region starts in the middle of a line, so do not add leading whitespace to your first line. " +
                "Indent any following lines consistently with the surrounding code, in the file's own style " +
                "(tabs or spaces)."
    }

    private fun placement(startsLine: Boolean, endsLine: Boolean): String {
        val start = if (startsLine) "starts at the beginning of a line" else "starts in the middle of a line"
        val end = if (endsLine) "ends at the end of a line" else "ends in the middle of a line"
        return "$start and $end"
    }

    private fun describeIndentation(text: String): String {
        val lead = text.takeWhile { it == ' ' || it == '\t' }
        val tabs = lead.count { it == '\t' }
        val spaces = lead.count { it == ' ' }
        val parts = buildList {
            if (tabs > 0) add(if (tabs == 1) "1 tab" else "$tabs tabs")
            if (spaces > 0) add(if (spaces == 1) "1 space" else "$spaces spaces")
        }
        return if (parts.isEmpty()) "none (the line is empty)" else parts.joinToString(" and ")
    }

    /** Keeps the END of [text], cut on a line boundary when possible. */
    private fun truncateBefore(text: String): String {
        if (text.length <= MAX_CONTEXT_CHARS) return text
        var cut = text.length - MAX_CONTEXT_CHARS
        if (text[cut - 1] != '\n') {
            val newline = text.indexOf('\n', cut)
            if (newline >= 0) cut = newline + 1
        }
        return omittedMarker(text.substring(0, cut)) + "\n" + text.substring(cut)
    }

    /** Keeps the START of [text], cut on a line boundary when possible. */
    private fun truncateAfter(text: String): String {
        if (text.length <= MAX_CONTEXT_CHARS) return text
        val newline = text.lastIndexOf('\n', MAX_CONTEXT_CHARS - 1)
        val keepEnd = if (newline >= 0) newline else MAX_CONTEXT_CHARS
        val omitted = text.substring(if (newline >= 0) newline + 1 else keepEnd)
        return text.substring(0, keepEnd) + "\n" + omittedMarker(omitted)
    }

    private fun omittedMarker(omitted: String): String {
        val lines = omitted.count { it == '\n' } + if (omitted.endsWith("\n")) 0 else 1
        return "[... $lines lines omitted ...]"
    }
}
