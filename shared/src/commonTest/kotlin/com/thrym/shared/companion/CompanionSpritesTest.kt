package com.thrym.shared.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompanionSpritesTest {

    @Test
    fun everyFrameIsASquareGridWithKnownColours() {
        for (character in CompanionCharacter.entries) {
            for (pose in CompanionPose.entries) {
                val rows = CompanionSprites.rows(character, pose)
                assertEquals(CompanionSprites.SIZE, rows.size, "$character $pose height")
                rows.forEachIndexed { y, row ->
                    assertEquals(CompanionSprites.SIZE, row.length, "$character $pose row $y")
                    row.forEach { char ->
                        assertTrue(char == '.' || char in CompanionSprites.palette, "$character $pose '$char'")
                    }
                }
            }
        }
    }

    @Test
    fun decodedFrameMatchesTheRows() {
        val frame = CompanionSprites.frame(CompanionCharacter.ODIN, CompanionPose.IDLE)
        val rows = CompanionSprites.rows(CompanionCharacter.ODIN, CompanionPose.IDLE)
        assertEquals(CompanionSprites.SIZE, frame.width)
        assertEquals(CompanionSprites.SIZE, frame.height)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val char = rows[y][x]
                val expected = if (char == '.') 0 else CompanionSprites.palette.getValue(char)
                assertEquals(expected, frame.pixel(x, y), "pixel $x,$y")
            }
        }
    }

    @Test
    fun posesDifferFromIdle() {
        for (character in CompanionCharacter.entries) {
            val idle = CompanionSprites.rows(character, CompanionPose.IDLE)
            assertNotEquals(idle, CompanionSprites.rows(character, CompanionPose.BLINK), "$character blink")
            assertNotEquals(idle, CompanionSprites.rows(character, CompanionPose.CHEER), "$character cheer")
        }
    }

    @Test
    fun charactersDiffer() {
        assertNotEquals(
            CompanionSprites.rows(CompanionCharacter.ODIN, CompanionPose.IDLE),
            CompanionSprites.rows(CompanionCharacter.VALKYRIE, CompanionPose.IDLE)
        )
    }
}
