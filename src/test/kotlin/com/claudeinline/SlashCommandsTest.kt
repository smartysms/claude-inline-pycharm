package com.claudeinline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {
    private fun names(commands: List<SlashCommand>) = commands.map { it.name }

    private fun instruction(name: String) = SlashCommands.all.first { it.name == name }.instruction

    @Test
    fun allContainsTheFiveCommandsInOrder() {
        assertEquals(listOf("doc", "fix", "optimize", "simplify", "types"), names(SlashCommands.all))
    }

    @Test
    fun commandsAreWellFormed() {
        for (command in SlashCommands.all) {
            assertTrue(command.name, command.name.isNotEmpty() && command.name.none { it == '/' || it.isWhitespace() })
            assertTrue(command.name, command.description.isNotBlank())
            assertTrue(command.name, command.instruction.isNotBlank())
            assertTrue(command.name, !command.instruction.contains('\n'))
            assertTrue(command.name, (command.description + command.instruction).all { it.code in 32..126 })
        }
        assertEquals(SlashCommands.all.size, SlashCommands.all.map { it.instruction }.toSet().size)
    }

    @Test
    fun slashAloneMatchesAll() {
        assertEquals(SlashCommands.all, SlashCommands.matching("/"))
    }

    @Test
    fun prefixNarrowsTheMatches() {
        assertEquals(listOf("doc"), names(SlashCommands.matching("/d")))
        assertEquals(listOf("doc"), names(SlashCommands.matching("/doc")))
        assertEquals(listOf("simplify"), names(SlashCommands.matching("/si")))
        assertEquals(listOf("types"), names(SlashCommands.matching("/t")))
        assertEquals(listOf("fix"), names(SlashCommands.matching("/f")))
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(listOf("optimize"), names(SlashCommands.matching("/OPT")))
    }

    @Test
    fun unknownPrefixMatchesNothing() {
        assertEquals(emptyList<SlashCommand>(), SlashCommands.matching("/zzz"))
        assertEquals(emptyList<SlashCommand>(), SlashCommands.matching("/docs"))
    }

    @Test
    fun nonSlashTextMatchesNothing() {
        assertEquals(emptyList<SlashCommand>(), SlashCommands.matching(""))
        assertEquals(emptyList<SlashCommand>(), SlashCommands.matching("doc"))
        assertEquals(emptyList<SlashCommand>(), SlashCommands.matching("make it /fast"))
    }

    @Test
    fun onceArgumentsStartOnlyTheExactCommandMatches() {
        assertEquals(listOf("fix"), names(SlashCommands.matching("/fix handle None")))
        assertEquals(listOf("fix"), names(SlashCommands.matching("/fix ")))
        assertEquals(emptyList<SlashCommand>(), SlashCommands.matching("/fi handle"))
    }

    @Test
    fun expandsBareCommandToItsInstruction() {
        for (command in SlashCommands.all) {
            assertEquals(command.instruction, SlashCommands.expand("/${command.name}"))
        }
    }

    @Test
    fun expandAppendsGuidance() {
        assertEquals(
            instruction("fix") + " Additional guidance: handle None",
            SlashCommands.expand("/fix handle None"),
        )
    }

    @Test
    fun expandTrimsGuidanceAndAcceptsNewlineSeparator() {
        val expected = instruction("types") + " Additional guidance: use Protocol"
        assertEquals(expected, SlashCommands.expand("/types   use Protocol  \n"))
        assertEquals(expected, SlashCommands.expand("/types\nuse Protocol"))
    }

    @Test
    fun expandWithBlankGuidanceGivesOnlyTheInstruction() {
        assertEquals(instruction("doc"), SlashCommands.expand("/doc   "))
        assertEquals(instruction("doc"), SlashCommands.expand("/doc\n"))
    }

    @Test
    fun expandIgnoresLeadingWhitespaceAndCase() {
        assertEquals(instruction("optimize"), SlashCommands.expand("  /Optimize"))
    }

    @Test
    fun expandLeavesUnknownCommandsUntouched() {
        for (input in listOf("/unknown", "/unknown do it", "/fixit now", "/", "/ fix")) {
            assertEquals(input, SlashCommands.expand(input))
        }
    }

    @Test
    fun expandLeavesNonSlashInputUntouched() {
        for (input in listOf("", "fix it", "please /fix this", " rename x ", "//fix")) {
            assertEquals(input, SlashCommands.expand(input))
        }
    }

    @Test
    fun guidanceKeepsInternalWhitespaceAndMultipleLines() {
        assertEquals(
            instruction("simplify") + " Additional guidance: keep the loop\nand the early return",
            SlashCommands.expand("/simplify keep the loop\nand the early return"),
        )
    }
}
