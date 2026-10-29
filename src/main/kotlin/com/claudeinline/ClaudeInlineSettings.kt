package com.claudeinline

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "ClaudeInlineSettings", storages = [Storage("inline-ai-prompt.xml")])
class ClaudeInlineSettings : SimplePersistentStateComponent<ClaudeInlineSettings.State>(State()) {

    class State : BaseState() {
        var executable by string("")
        var model by string("")
        var thinkingLevel by string(ThinkingLevel.DEFAULT.name)
        var isolated by property(true)
        var readOnlyTools by property(false)
        var useProjectPatterns by property(true)
        var timeoutSeconds by property(DEFAULT_TIMEOUT_SECONDS)
    }

    /** Remembered for the next popup; deliberately not persisted. */
    var lastPrompt: String = ""

    var level: ThinkingLevel
        get() = ThinkingLevel.fromName(state.thinkingLevel)
        set(value) {
            state.thinkingLevel = value.name
        }

    fun cliOptions(level: ThinkingLevel): ClaudeCliOptions = ClaudeCliOptions(
        executable = state.executable.orEmpty().trim(),
        model = state.model.orEmpty().trim(),
        level = level,
        isolated = state.isolated,
        readOnlyTools = state.readOnlyTools,
        timeoutSeconds = state.timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
    )

    companion object {
        const val MIN_TIMEOUT_SECONDS = 10
        const val MAX_TIMEOUT_SECONDS = 3600
        private const val DEFAULT_TIMEOUT_SECONDS = 600

        fun getInstance(): ClaudeInlineSettings = service()
    }
}
