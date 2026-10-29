package com.claudeinline

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class ClaudeInlineConfigurable : BoundConfigurable(Branding.NAME) {

    override fun createPanel(): DialogPanel {
        val settings = ClaudeInlineSettings.getInstance()
        val state = settings.state
        val effortMapping = ThinkingLevel.entries
            .filter { it.effort != null }
            .joinToString(", ") { "${it.label} = ${it.effort}" }

        return panel {
            row("Claude executable:") {
                textField()
                    .bindText({ state.executable.orEmpty() }, { state.executable = it.trim() })
                    .columns(COLUMNS_LARGE)
                    .comment("Leave blank to auto-detect <code>claude</code> on PATH.")
            }
            row("Model:") {
                textField()
                    .bindText({ state.model.orEmpty() }, { state.model = it.trim() })
                    .columns(COLUMNS_LARGE)
                    .comment("Leave blank for the CLI default. Use an alias such as <code>opus</code> or <code>sonnet</code>, or a full model id.")
            }
            row("Default thinking level:") {
                comboBox(ThinkingLevel.entries)
                    .bindItem({ settings.level }, { settings.level = it ?: ThinkingLevel.DEFAULT })
                    .comment("Maps to the CLI effort: Default = no effort flag, $effortMapping. Higher levels are slower.")
            }
            row {
                checkBox("Isolated mode (skip CLAUDE.md, hooks and MCP servers - faster)")
                    .bindSelected(state::isolated)
            }
            row {
                checkBox("Allow read-only project tools (Read, Grep, Glob)")
                    .bindSelected(state::readOnlyTools)
                    .comment("Lets Claude look at other files in the project while answering. Slower.")
            }
            row {
                checkBox("Use project patterns (learned from this codebase) when generating code")
                    .bindSelected(state::useProjectPatterns)
                    .comment("Run Tools | ${Branding.SHORT}: Analyze Project Patterns to create or refresh the profile (${Branding.DIR}/project-patterns.md).")
            }
            row("Timeout (seconds):") {
                intTextField(ClaudeInlineSettings.MIN_TIMEOUT_SECONDS..ClaudeInlineSettings.MAX_TIMEOUT_SECONDS)
                    .bindIntText(state::timeoutSeconds)
                    .columns(COLUMNS_SHORT)
            }
        }
    }
}
