package com.claudeinline

/** The instruction sent when the user triggers an edit without typing anything. */
object AutoInstruction {
    fun forKind(kind: TargetKind): String = when (kind) {
        TargetKind.FUNCTION ->
            "Make this function do what its name, signature, type hints, docstring and comments describe. " +
                "If it is a stub (empty body, pass, ..., NotImplementedError, TODO), implement it fully. " +
                "Otherwise adjust it to match that intent and keep the behavior that already matches. " +
                "Turn step-by-step comments into code and keep the comments that stay useful."
        TargetKind.COMMENT ->
            "Implement what the comment(s) describe. Keep the comment(s) and write the code that implements " +
                "them directly below, at the same indentation. Use the enclosing function's name and " +
                "signature and the rest of the file for context, and reuse the names, helpers and style " +
                "that already exist there."
        TargetKind.INSERT ->
            "Write the code that belongs at this position, as described by the comment(s) directly above it " +
                "or, when there are none, by the enclosing function's name, signature and docstring. " +
                "Match the surrounding indentation and style and reuse what the file already defines."
        TargetKind.SELECTION ->
            "Complete or implement the selected code according to the comments, names and signatures it " +
                "contains. Keep what already works and keep the comments that stay useful."
        TargetKind.LINE ->
            "Complete this line so that it does what the comments, names and code around it call for. " +
                "Keep its indentation and the file's style."
    }
}
