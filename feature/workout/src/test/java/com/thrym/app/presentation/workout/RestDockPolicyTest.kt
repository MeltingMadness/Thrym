package com.thrym.app.presentation.workout

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestDockPolicyTest {
    private val a: WorkoutExerciseKey = WorkoutExerciseKey.AdHoc(1)
    private val b: WorkoutExerciseKey = WorkoutExerciseKey.AdHoc(2)
    private val c: WorkoutExerciseKey = WorkoutExerciseKey.AdHoc(3)

    private fun timer(startMillis: Long, durationSeconds: Int = 60) =
        RestTimerUi(Instant.ofEpochMilli(startMillis), durationSeconds)

    @Test
    fun `single countdown start opens its pause`() {
        val decision = decideRestDockStart(emptyList(), mapOf(a to timer(1_000)))

        assertEquals(a, decision.openKey)
        assertEquals(listOf(1_000L), decision.seenStarts)
    }

    @Test
    fun `second timer in a superset does not open and sole survivor stays closed`() {
        val both = mapOf(a to timer(1_000), b to timer(2_000))
        val added = decideRestDockStart(listOf(1_000), both)
        val survivor = decideRestDockStart(added.seenStarts, mapOf(b to timer(2_000)))

        assertNull(added.openKey)
        assertEquals(listOf(1_000L, 2_000L), added.seenStarts)
        assertNull(survivor.openKey)
        assertEquals(listOf(2_000L), survivor.seenStarts)
    }

    @Test
    fun `relogging the same exercise opens for the new countdown start`() {
        val decision = decideRestDockStart(listOf(1_000), mapOf(a to timer(2_000)))

        assertEquals(a, decision.openKey)
        assertEquals(listOf(2_000L), decision.seenStarts)
    }

    @Test
    fun `saved start after recreation does not reopen`() {
        val decision = decideRestDockStart(listOf(1_000), mapOf(a to timer(1_000)))

        assertNull(decision.openKey)
        assertEquals(listOf(1_000L), decision.seenStarts)
    }

    @Test
    fun `count up start never opens`() {
        val decision = decideRestDockStart(emptyList(), mapOf(a to timer(1_000, 0)))

        assertNull(decision.openKey)
        assertEquals(listOf(1_000L), decision.seenStarts)
    }

    @Test
    fun `pin does not move to another exercise when its timer disappears`() {
        val remaining = mapOf(b to timer(2_000))

        assertNull(resolvePinnedRestRow(a.stableListKey(), remaining, 3_000))
        assertEquals(b, resolvePinnedRestRow(b.stableListKey(), remaining, 3_000)?.exerciseKey)
    }

    @Test
    fun `countdowns order by remaining time then start then stable key`() {
        val rows = buildRestDockRows(
            linkedMapOf(
                c to timer(5_000, 25), // 20 s left, later start
                b to timer(0, 30),     // 20 s left, stable-key tie with a
                a to timer(0, 30),
                WorkoutExerciseKey.Planned(4) to timer(0, 15) // 5 s left
            ),
            nowMillis = 10_000
        )

        assertEquals(
            listOf(WorkoutExerciseKey.Planned(4), a, b, c),
            rows.map { it.exerciseKey }
        )
        assertEquals(listOf(5L, 20L, 20L, 20L), rows.map { it.remainingSeconds })
    }

    @Test
    fun `count ups collapse to newest and follow every countdown`() {
        val rows = buildRestDockRows(
            linkedMapOf(
                a to timer(1_000, 0),
                b to timer(3_000, -1),
                c to timer(2_000, 30)
            ),
            nowMillis = 4_000
        )

        assertEquals(listOf(c, b), rows.map { it.exerciseKey })
    }

    @Test
    fun `seen starts are pruned to current timers and new starts`() {
        val decision = decideRestDockStart(
            listOf(1_000, 2_000, 9_000),
            mapOf(b to timer(2_000), c to timer(3_000))
        )

        assertNull(decision.openKey)
        assertEquals(listOf(2_000L, 3_000L), decision.seenStarts)
    }
}
