package com.claudeinline

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectPatternsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun base(): String = tmp.root.absolutePath

    @Test
    fun `save then load round trips and trims`() {
        ProjectPatternsStore.save(base(), "\n## Stack\nPython\n\n")
        val loaded = ProjectPatternsStore.load(base())
        assertNotNull(loaded)
        assertEquals("## Stack\nPython", loaded!!.text)
        assertTrue(Files.isRegularFile(ProjectPatternsStore.path(base())))
    }

    @Test
    fun `path is under the inline-ai directory`() {
        val p = ProjectPatternsStore.path(base())
        assertEquals("project-patterns.md", p.fileName.toString())
        assertEquals(Branding.DIR, p.parent.fileName.toString())
    }

    @Test
    fun `missing file blank file and null base give null`() {
        assertNull(ProjectPatternsStore.load(base()))
        ProjectPatternsStore.save(base(), "  \n\n ")
        assertNull(ProjectPatternsStore.load(base()))
        assertNull(ProjectPatternsStore.load(null))
        assertNull(ProjectPatternsStore.load("  "))
    }

    @Test
    fun `utf-8 content survives`() {
        ProjectPatternsStore.save(base(), "café 中")
        assertEquals("café 中", ProjectPatternsStore.load(base())!!.text)
    }

    @Test
    fun `long text is cut on a line boundary with a marker`() {
        val text = (0 until 3000).joinToString("\n") { "line-%05d".format(it) }
        ProjectPatternsStore.save(base(), text)
        val loaded = ProjectPatternsStore.load(base())!!.text
        assertTrue(loaded.length <= ProjectPatternsStore.MAX_INJECTED_CHARS + 30)
        assertTrue(loaded.endsWith("\n[... truncated ...]"))
        val kept = loaded.removeSuffix("\n[... truncated ...]").lines()
        assertTrue(kept.all { Regex("line-\\d{5}").matches(it) })
        assertEquals("line-%05d".format(kept.size - 1), kept.last())
    }
}
