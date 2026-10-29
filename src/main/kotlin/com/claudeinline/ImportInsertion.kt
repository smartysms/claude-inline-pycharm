package com.claudeinline

object ImportInsertion {
    /**
     * Returns [imports] (trimmed, in order) minus blanks, duplicates and statements that already
     * appear as a whole trimmed line of [documentText]. Deliberately line-based, not a Python parser.
     */
    fun missingImports(imports: List<String>, documentText: String): List<String> {
        val present = documentText.lineSequence().map { it.trim() }.toHashSet()
        val seen = HashSet<String>()
        return imports
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in present && seen.add(it) }
    }
}
