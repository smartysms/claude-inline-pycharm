package com.claudeinline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.ResourceBundle

class BrandingTest {
    @Test
    fun `name, short name and directory are filled in`() {
        assertTrue(Branding.NAME.isNotBlank())
        assertTrue(Branding.SHORT.isNotBlank())
        assertTrue(Branding.DIR.isNotBlank())
    }

    @Test
    fun `no build token is left unreplaced in the bundle`() {
        val bundle = ResourceBundle.getBundle("messages.Branding", Locale.ROOT, Branding::class.java.classLoader)
        for (key in bundle.keys) {
            assertFalse("$key still contains a build token", bundle.getString(key).contains("@"))
        }
    }

    @Test
    fun `action texts are present for every action id in plugin xml`() {
        val bundle = ResourceBundle.getBundle("messages.Branding", Locale.ROOT, Branding::class.java.classLoader)
        for (id in listOf("InlineAI.Prompt", "InlineAI.Implement", "InlineAI.AnalyzeProject")) {
            assertTrue("missing text for $id", bundle.containsKey("action.$id.text"))
            assertTrue("text of $id should start with the short name", bundle.getString("action.$id.text").startsWith(Branding.SHORT))
        }
    }
}
