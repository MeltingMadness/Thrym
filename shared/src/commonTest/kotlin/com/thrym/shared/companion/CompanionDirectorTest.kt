package com.thrym.shared.companion

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompanionDirectorTest {

    @Test
    fun walksStayOnTheStageAndAreNotTiny() {
        val random = Random(7)
        val width = 200f
        var x = 100f
        var facing = true
        var previous: CompanionAnimation? = null
        repeat(500) {
            val action = CompanionDirector.next(random, x, facing, width, previous)
            if (action.animation == CompanionAnimation.WALK) {
                assertTrue(action.targetX in CompanionDirector.EDGE..(width - CompanionDirector.EDGE), "target ${action.targetX}")
                assertTrue(kotlin.math.abs(action.targetX - x) >= CompanionDirector.MIN_WALK, "walk from $x to ${action.targetX}")
                assertEquals(action.targetX >= x, action.facingRight)
            } else {
                assertEquals(x, action.targetX)
            }
            assertTrue(action.durationMs > 0)
            x = action.targetX
            facing = action.facingRight
            previous = action.animation
        }
    }

    @Test
    fun noTwoWalksInARow() {
        val random = Random(3)
        repeat(200) {
            val action = CompanionDirector.next(random, 60f, true, 200f, CompanionAnimation.WALK)
            assertTrue(action.animation != CompanionAnimation.WALK)
        }
    }

    @Test
    fun thrustPointsToTheSideWithRoom() {
        val random = Random(1)
        repeat(300) {
            val nearRight = CompanionDirector.next(random, 190f, true, 200f, CompanionAnimation.WALK)
            if (nearRight.animation == CompanionAnimation.ATTACK) assertEquals(false, nearRight.facingRight)
            val nearLeft = CompanionDirector.next(random, 10f, false, 200f, CompanionAnimation.WALK)
            if (nearLeft.animation == CompanionAnimation.ATTACK) assertEquals(true, nearLeft.facingRight)
        }
    }

    @Test
    fun narrowStageMeansNoWalking() {
        val random = Random(11)
        repeat(200) {
            val action = CompanionDirector.next(random, 30f, true, 60f, null)
            assertTrue(action.animation != CompanionAnimation.WALK)
        }
    }

    @Test
    fun allActionsShowUp() {
        val random = Random(5)
        val seen = mutableSetOf<CompanionAnimation>()
        var previous: CompanionAnimation? = null
        repeat(300) {
            val action = CompanionDirector.next(random, 100f, true, 200f, previous)
            seen += action.animation
            previous = action.animation
        }
        assertEquals(
            setOf(CompanionAnimation.WALK, CompanionAnimation.IDLE, CompanionAnimation.ATTACK, CompanionAnimation.CHEER),
            seen
        )
    }

    @Test
    fun completionCelebratesRecords() {
        assertEquals(CompanionAnimation.CELEBRATE, CompanionRules.completionAnimation(2))
        assertEquals(CompanionAnimation.CHEER, CompanionRules.completionAnimation(0))
        val record = CompanionRules.completionLine(42, 1)
        assertTrue(record == CompanionLine.RECORD_SKALDS || record == CompanionLine.RECORD_SAGA)
        val done = CompanionRules.completionLine(42, 0)
        assertTrue(done == CompanionLine.WORKOUT_DONE_FOUGHT || done == CompanionLine.WORKOUT_DONE_HALL)
        assertEquals(record, CompanionRules.completionLine(42, 3))
    }
}
