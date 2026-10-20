package com.thrym.shared.recognition

import com.thrym.shared.model.WeekStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeeklyGoalEngineTest {

    // 2026-10-19 is a Monday.
    private val monday = 20_745L
    private val goal3 = listOf(WeeklyGoalChange(0L, 3))

    private fun week(offset: Int, vararg days: Int, deload: Boolean? = false) =
        days.map { WeeklyGoalSession(monday + offset * 7L + it, deload) }

    @Test
    fun epochDayMathMatchesTheCalendar() {
        assertEquals(4, WeeklyGoalRules.isoDayOfWeek(0L))
        assertEquals(1, WeeklyGoalRules.isoDayOfWeek(monday))
        assertEquals(monday, WeeklyGoalRules.weekStartOf(monday + 6, WeekStart.MONDAY))
        assertEquals(monday - 1, WeeklyGoalRules.weekStartOf(monday + 5, WeekStart.SUNDAY))
        assertEquals(monday + 6, WeeklyGoalRules.weekStartOf(monday + 6, WeekStart.SUNDAY))
        assertEquals(-3L, WeeklyGoalRules.weekStartOf(0L, WeekStart.MONDAY))
    }

    @Test
    fun heldWeeksInARowMakeTheStreak() {
        val sessions = week(-3, 0, 2, 4) + week(-2, 0, 2, 4) + week(-1, 1, 3, 5)

        val status = WeeklyGoalEngine.evaluate(monday + 1, WeekStart.MONDAY, sessions, goal3)

        assertEquals(3, status.streakWeeks)
        assertEquals(0, status.completedThisWeek)
        assertFalse(status.goalMetThisWeek)
    }

    @Test
    fun theRunningWeekOnlyAddsOnceItsGoalIsMet() {
        val past = week(-1, 0, 2, 4)

        val open = WeeklyGoalEngine.evaluate(monday + 3, WeekStart.MONDAY, past + week(0, 0, 2), goal3)
        val met = WeeklyGoalEngine.evaluate(monday + 3, WeekStart.MONDAY, past + week(0, 0, 2, 3), goal3)

        assertEquals(1, open.streakWeeks)
        assertEquals(2, open.completedThisWeek)
        assertEquals(2, met.streakWeeks)
        assertTrue(met.goalMetThisWeek)
    }

    @Test
    fun aDeloadWeekHoldsTheStreakButUnknownDoesNot() {
        val deload = week(-3, 0, 2, 4) + week(-2, 1, deload = true) + week(-1, 0, 2, 4)
        val unknown = week(-3, 0, 2, 4) + week(-2, 1, deload = null) + week(-1, 0, 2, 4)

        assertEquals(3, WeeklyGoalEngine.evaluate(monday, WeekStart.MONDAY, deload, goal3).streakWeeks)
        assertEquals(1, WeeklyGoalEngine.evaluate(monday, WeekStart.MONDAY, unknown, goal3).streakWeeks)
    }

    @Test
    fun fourHeldWeeksEarnOneBufferThatCoversAMiss() {
        val sessions = (-6..-3).flatMap { week(it, 0, 2, 4) } + week(-2, 0) + week(-1, 0, 2, 4)

        val status = WeeklyGoalEngine.evaluate(monday, WeekStart.MONDAY, sessions, goal3)

        assertEquals(6, status.streakWeeks)
        assertEquals(0, status.buffers)
        assertEquals(WeekOutcome.BUFFER, status.weeks[4].outcome)
    }

    @Test
    fun buffersStopAtTwoAndBufferWeeksEarnNothing() {
        val sessions = (-14..-3).flatMap { week(it, 0, 2, 4) } + week(-2, 0) + week(-1, 0)

        val status = WeeklyGoalEngine.evaluate(monday, WeekStart.MONDAY, sessions, goal3)

        assertEquals(14, status.streakWeeks)
        assertEquals(0, status.buffers)
    }

    @Test
    fun twoMissesWithoutBufferBreakTheStreak() {
        val sessions = week(-5, 0, 2, 4) + week(-4, 0) + week(-3, 0) + week(-2, 0, 2, 4) + week(-1, 0, 2, 4)

        val status = WeeklyGoalEngine.evaluate(monday, WeekStart.MONDAY, sessions, goal3)

        assertEquals(2, status.streakWeeks)
        assertEquals(2, status.longestStreakWeeks)
        assertEquals(WeekOutcome.MISSED, status.weeks[1].outcome)
    }

    @Test
    fun aGoalChangeOnlyAppliesFromItsWeekOn() {
        val history = WeeklyGoalRules.withChange(emptyList(), monday - 7, 4)
        val sessions = week(-2, 0, 2, 4) + week(-1, 0, 2, 4, 5)

        val status = WeeklyGoalEngine.evaluate(monday, WeekStart.MONDAY, sessions, history)

        assertEquals(listOf(WeeklyGoalChange(0L, 3), WeeklyGoalChange(monday - 7, 4)), history)
        assertEquals(2, status.streakWeeks)
        assertEquals(4, status.goal)
    }

    @Test
    fun aRaisedGoalDoesNotBreakPastWeeks() {
        val history = WeeklyGoalRules.withChange(goal3, monday, 5)
        val sessions = week(-2, 0, 2, 4) + week(-1, 0, 2, 4)

        val status = WeeklyGoalEngine.evaluate(monday, WeekStart.MONDAY, sessions, history)

        assertEquals(2, status.streakWeeks)
        assertEquals(5, status.goal)
    }

    @Test
    fun aSecondChangeInTheSameWeekReplacesTheFirst() {
        val once = WeeklyGoalRules.withChange(goal3, monday, 5)
        val twice = WeeklyGoalRules.withChange(once, monday, 4)
        val back = WeeklyGoalRules.withChange(twice, monday, 3)

        assertEquals(listOf(WeeklyGoalChange(0L, 3), WeeklyGoalChange(monday, 4)), twice)
        assertEquals(goal3, back)
    }

    @Test
    fun goalsAreClampedAndTheHistoryRoundTrips() {
        val decoded = WeeklyGoalRules.decode("20745:9, x, 0:1,12:")

        assertEquals(listOf(WeeklyGoalChange(0L, 2), WeeklyGoalChange(monday, 7)), decoded)
        assertEquals("0:2,20745:7", WeeklyGoalRules.encode(decoded))
        assertEquals(emptyList(), WeeklyGoalRules.decode(null))
        assertEquals(3, WeeklyGoalRules.goalFor(emptyList(), monday))
    }

    @Test
    fun severalWorkoutsOnOneDayCountSeparately() {
        val status = WeeklyGoalEngine.evaluate(monday, WeekStart.MONDAY, week(0, 0, 0, 0), goal3)

        assertTrue(status.goalMetThisWeek)
        assertEquals(1, status.streakWeeks)
    }

    @Test
    fun weeksFollowTheConfiguredWeekStart() {
        // Sunday and Monday fall into one Sunday-week but into two Monday-weeks.
        val sessions = listOf(
            WeeklyGoalSession(monday - 1, false),
            WeeklyGoalSession(monday, false),
            WeeklyGoalSession(monday + 1, false),
        )

        assertTrue(WeeklyGoalEngine.evaluate(monday + 1, WeekStart.SUNDAY, sessions, goal3).goalMetThisWeek)
        assertEquals(2, WeeklyGoalEngine.evaluate(monday + 1, WeekStart.MONDAY, sessions, goal3).completedThisWeek)
    }

    @Test
    fun anEmptyHistoryHasNoStreakAndFutureWorkoutsAreIgnored() {
        val status = WeeklyGoalEngine.evaluate(monday, WeekStart.MONDAY, week(0, 3), goal3)

        assertEquals(0, status.streakWeeks)
        assertEquals(0, status.completedThisWeek)
        assertEquals(emptyList(), status.weeks)
    }
}
