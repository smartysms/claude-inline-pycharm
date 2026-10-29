package com.claudeinline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectAnalyzerTest {
    private val body = "## Stack and layout\n" + "FastAPI app in app/main.py\n".repeat(12)

    @Test
    fun `prompt names every section and the ignore list`() {
        val prompt = ProjectAnalyzer.buildPrompt()
        listOf(
            "Stack and layout", "API pattern", "DB pattern", "Redis/cache pattern",
            "Cache-aside", "Conventions", "Reuse checklist for new code",
            "venv", ".venv", "node_modules", "site-packages", "build", "dist", "__pycache__", ".git", ".idea",
            "Not found in this codebase", "250 lines", "Do not modify any file",
        ).forEach { assertTrue("missing: $it", prompt.contains(it)) }
    }

    @Test
    fun `wrapping fence is stripped`() {
        assertEquals(body.trim(), ProjectAnalyzer.cleanAnswer("```markdown\n$body\n```"))
    }

    @Test
    fun `inner fences are kept`() {
        val withInner = body + "```python\nx = 1\n```\n" + "tail line\n".repeat(3)
        assertEquals(withInner.trim(), ProjectAnalyzer.cleanAnswer(withInner))
    }

    @Test
    fun `plain answer is trimmed`() {
        assertEquals(body.trim(), ProjectAnalyzer.cleanAnswer("\n$body\n\n"))
    }

    @Test
    fun `blank or short answers are rejected`() {
        for (bad in listOf("", "   \n", "```\nshort\n```", "too short")) {
            try {
                ProjectAnalyzer.cleanAnswer(bad)
                fail("expected rejection for: $bad")
            } catch (e: ClaudeEditException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }
}
