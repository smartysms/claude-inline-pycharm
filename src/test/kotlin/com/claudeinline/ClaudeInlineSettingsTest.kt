package com.claudeinline

import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeInlineSettingsTest {
    @Test
    fun projectPatternsAreOnByDefault() {
        assertTrue(ClaudeInlineSettings.State().useProjectPatterns)
    }
}
