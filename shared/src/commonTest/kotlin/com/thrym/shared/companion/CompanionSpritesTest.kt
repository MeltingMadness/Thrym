package com.thrym.shared.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompanionSpritesTest {

    @Test
    fun everyFrameDecodesToAFullSquareGrid() {
        for (character in CompanionCharacter.entries) {
            for (animation in CompanionAnimation.entries) {
                val count = CompanionSprites.frameCount(animation)
                assertTrue(count > 0, "$character $animation has frames")
                assertEquals(count, CompanionSprites.encoded(character, animation).size, "$character $animation count")
                repeat(count) { index ->
                    val frame = CompanionSprites.frame(character, animation, index)
                    assertEquals(CompanionSprites.SIZE, frame.width)
                    assertEquals(CompanionSprites.SIZE, frame.height)
                    assertTrue(frame.argb.any { it != 0 }, "$character $animation $index is not empty")
                }
            }
        }
    }

    @Test
    fun decodingFollowsTheRuns() {
        val palette = mapOf('A' to 0xFF000001.toInt(), 'B' to 0xFF000002.toInt())
        val frame = CompanionFrame.decode("A.3B11A", 4, 4, palette)
        assertEquals(0xFF000001.toInt(), frame.pixel(0, 0))
        assertEquals(0, frame.pixel(1, 0))
        assertEquals(0, frame.pixel(3, 0))
        assertEquals(0xFF000002.toInt(), frame.pixel(0, 1))
        assertEquals(0xFF000002.toInt(), frame.pixel(2, 3))
        assertEquals(0xFF000001.toInt(), frame.pixel(3, 3))
    }

    @Test
    fun framesOfAnAnimationDiffer() {
        for (character in CompanionCharacter.entries) {
            for (animation in CompanionAnimation.entries) {
                val frames = CompanionSprites.encoded(character, animation)
                assertEquals(frames.size, frames.toSet().size, "$character $animation has distinct frames")
            }
        }
    }

    @Test
    fun charactersDiffer() {
        assertNotEquals(
            CompanionSprites.encoded(CompanionCharacter.ODIN, CompanionAnimation.IDLE),
            CompanionSprites.encoded(CompanionCharacter.VALKYRIE, CompanionAnimation.IDLE)
        )
    }

    @Test
    fun figureStandsOnTheAnchor() {
        // The idle figure covers its centre line, so mirroring around the anchor keeps it in place.
        val frame = CompanionSprites.frame(CompanionCharacter.ODIN, CompanionAnimation.IDLE, 0)
        assertTrue((0 until frame.height).any { frame.pixel(CompanionSprites.ANCHOR_X, it) != 0 })
    }
}
