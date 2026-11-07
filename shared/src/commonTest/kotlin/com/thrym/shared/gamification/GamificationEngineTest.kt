package com.thrym.shared.gamification

import com.thrym.shared.model.UnitSystem
import com.thrym.shared.model.WeekStart
import com.thrym.shared.recognition.MilestoneSession
import com.thrym.shared.recognition.WeeklyGoalChange
import com.thrym.shared.workout.RecordSetInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GamificationEngineTest {

    private var nextSetId = 1L

    /** 2024-01-01 was a Monday. */
    private val monday = 19_723L

    private fun session(id: Long, day: Long) = MilestoneSession(id, day, orderKey = day * 86_400_000L + id, isDeload = null)

    private fun set(sessionId: Long, exerciseId: Long = EX_MACHINE, kg: Double = 50.0, reps: Int = 5, type: String = "WORKING") =
        RecordSetInput(nextSetId++, sessionId, exerciseId, kg, reps, type, sessionId * 1000 + nextSetId, sessionFinished = true)

    private fun evaluate(
        sessions: List<MilestoneSession>,
        sets: List<RecordSetInput>,
        increases: List<Long> = emptyList(),
        goalHistory: List<WeeklyGoalChange> = listOf(WeeklyGoalChange(0, 7)),
        unit: UnitSystem = UnitSystem.METRIC,
    ) = GamificationEngine.evaluate(
        GamificationInput(
            sessions = sessions,
            sets = sets,
            categoryByExercise = mapOf(EX_BARBELL to "LANGHANTEL", EX_MACHINE to "MASCHINE", EX_PULLUP to "EIGENGEWICHT"),
            addedLoadExerciseIds = setOf(EX_PULLUP),
            acceptedIncreaseSessionIds = increases,
            weekStart = WeekStart.MONDAY,
            goalHistory = goalHistory,
            unitSystem = unit,
        )
    )

    private fun GamificationState.entry(sessionId: Long, source: XpSource) =
        session(sessionId)?.entries?.firstOrNull { it.source == source }

    @Test
    fun aWorkoutWithWorkSetsEarnsTheBase() {
        val state = evaluate(listOf(session(1, monday)), listOf(set(1)))

        assertEquals(100, state.totalXp)
        assertEquals(listOf(XpEntry(XpSource.TRAINING, 1, 100)), state.session(1)?.entries)
    }

    @Test
    fun onlyWarmUpsEarnNothing() {
        val state = evaluate(listOf(session(1, monday)), listOf(set(1, type = "WARMUP"), set(1, reps = 0)))

        assertEquals(0, state.totalXp)
        assertNull(state.session(1))
    }

    @Test
    fun setsOfUnfinishedWorkoutsDoNotCount() {
        val state = evaluate(listOf(session(1, monday)), listOf(set(1), set(2, kg = 200.0)))

        assertEquals(100, state.totalXp)
    }

    @Test
    fun furtherWorkoutsOfADayEarnLess() {
        val state = evaluate(
            listOf(session(1, monday), session(2, monday), session(3, monday + 1)),
            listOf(set(1), set(2), set(3)),
        )

        assertEquals(100, state.session(1)?.xp)
        assertEquals(25, state.session(2)?.xp)
        assertEquals(100, state.session(3)?.xp)
    }

    @Test
    fun theFirstWorkoutOfAnExerciseIsNoRecord() {
        val state = evaluate(listOf(session(1, monday)), listOf(set(1, kg = 80.0), set(1, EX_BARBELL, kg = 80.0)))

        assertNull(state.entry(1, XpSource.RECORD))
    }

    @Test
    fun aRecordCountsOncePerExercise() {
        val state = evaluate(
            listOf(session(1, monday), session(2, monday + 1)),
            listOf(set(1, kg = 50.0), set(2, kg = 55.0, reps = 8)),
        )

        assertEquals(XpEntry(XpSource.RECORD, 1, 40), state.entry(2, XpSource.RECORD))
    }

    @Test
    fun aTieIsNoRecord() {
        val state = evaluate(
            listOf(session(1, monday), session(2, monday + 1)),
            listOf(set(1, kg = 50.0), set(2, kg = 50.0)),
        )

        assertNull(state.entry(2, XpSource.RECORD))
    }

    @Test
    fun recordsCompareWithEarlierWorkoutsOnly() {
        val state = evaluate(
            listOf(session(1, monday), session(2, monday + 1), session(3, monday + 2)),
            listOf(set(1, kg = 50.0), set(2, kg = 60.0), set(3, kg = 70.0)),
        )

        assertEquals(1, state.entry(2, XpSource.RECORD)?.count)
        assertEquals(1, state.entry(3, XpSource.RECORD)?.count)
    }

    @Test
    fun atMostThreeExercisesEarnRecords() {
        val exercises = (10L..14L).toList()
        val state = evaluate(
            listOf(session(1, monday), session(2, monday + 1)),
            exercises.map { set(1, it, kg = 50.0) } + exercises.map { set(2, it, kg = 60.0) },
        )

        assertEquals(XpEntry(XpSource.RECORD, 3, 120), state.entry(2, XpSource.RECORD))
    }

    @Test
    fun addedLoadOnlyCountsWeightAndReps() {
        // A better e1RM or volume alone is no record for an added-load exercise.
        val state = evaluate(
            listOf(session(1, monday), session(2, monday + 1)),
            listOf(set(1, EX_PULLUP, kg = 10.0, reps = 5), set(1, EX_PULLUP, kg = 5.0, reps = 8), set(2, EX_PULLUP, kg = 10.0, reps = 5), set(2, EX_PULLUP, kg = 10.0, reps = 5)),
        )

        assertNull(state.entry(2, XpSource.RECORD))
    }

    @Test
    fun aNewMarkCountsOncePerExercise() {
        val state = evaluate(
            listOf(session(1, monday), session(2, monday + 1)),
            listOf(set(1, EX_BARBELL, kg = 95.0), set(2, EX_BARBELL, kg = 102.5), set(2, EX_BARBELL, kg = 125.0)),
        )

        assertEquals(XpEntry(XpSource.MARK, 1, 60), state.entry(2, XpSource.MARK))
    }

    @Test
    fun aMarkHeldBeforeEarnsNothing() {
        val state = evaluate(
            listOf(session(1, monday), session(2, monday + 1)),
            listOf(set(1, EX_BARBELL, kg = 100.0, reps = 1), set(2, EX_BARBELL, kg = 100.0, reps = 8)),
        )

        assertNull(state.entry(2, XpSource.MARK))
    }

    @Test
    fun marksFollowTheUnitSystem() {
        val sets = listOf(set(1, EX_BARBELL, kg = 58.0), set(2, EX_BARBELL, kg = 61.3))
        val sessions = listOf(session(1, monday), session(2, monday + 1))

        // 61.3 kg passes 135 lb and 60 kg.
        assertEquals(1, evaluate(sessions, sets, unit = UnitSystem.METRIC).entry(2, XpSource.MARK)?.count)
        assertEquals(1, evaluate(sessions, sets, unit = UnitSystem.IMPERIAL).entry(2, XpSource.MARK)?.count)
    }

    @Test
    fun machinesHaveNoMarks() {
        val state = evaluate(
            listOf(session(1, monday), session(2, monday + 1)),
            listOf(set(1, kg = 95.0), set(2, kg = 105.0)),
        )

        assertNull(state.entry(2, XpSource.MARK))
    }

    @Test
    fun increasesCountUpToThree() {
        val state = evaluate(
            listOf(session(1, monday)),
            listOf(set(1)),
            increases = List(5) { 1L },
        )

        assertEquals(XpEntry(XpSource.INCREASE, 3, 90), state.entry(1, XpSource.INCREASE))
    }

    @Test
    fun theWeeklyGoalPaysOncePerWeek() {
        val state = evaluate(
            (0L..3L).map { session(it + 1, monday + it) } + session(5, monday + 7),
            (1L..5L).map { set(it) },
            goalHistory = listOf(WeeklyGoalChange(0, 3)),
        )

        assertNull(state.entry(2, XpSource.WEEKLY_GOAL))
        assertEquals(75, state.entry(3, XpSource.WEEKLY_GOAL)?.xp)
        assertNull(state.entry(4, XpSource.WEEKLY_GOAL))
        assertNull(state.entry(5, XpSource.WEEKLY_GOAL))
    }

    @Test
    fun anEmptyGoalHistoryMeansTheDefaultGoal() {
        val state = evaluate(
            (0L..2L).map { session(it + 1, monday + it) },
            (1L..3L).map { set(it) },
            goalHistory = emptyList(),
        )

        assertEquals(75, state.entry(3, XpSource.WEEKLY_GOAL)?.xp)
    }

    @Test
    fun aPauseOfThreeWeeksBringsAComeback() {
        val state = evaluate(
            listOf(session(1, monday), session(2, monday + 20), session(3, monday + 41)),
            listOf(set(1), set(2), set(3)),
        )

        assertNull(state.entry(1, XpSource.COMEBACK))
        assertNull(state.entry(2, XpSource.COMEBACK))
        assertEquals(150, state.entry(3, XpSource.COMEBACK)?.xp)
    }

    @Test
    fun aWorkoutIsCappedAndItsRowsAddUp() {
        val exercises = listOf(EX_BARBELL) + (10L..14L).toList()
        val state = evaluate(
            listOf(session(1, monday), session(2, monday + 30)),
            exercises.map { set(1, it, kg = 50.0) } + exercises.map { set(2, it, kg = 70.0) },
            increases = List(3) { 2L },
        )

        val session = assertNotNull(state.session(2))
        assertEquals(GamificationEngine.MAX_XP_PER_SESSION, session.xp)
        assertEquals(session.xp, session.entries.sumOf { it.xp })
        assertEquals(XpSource.TRAINING, session.entries.first().source)
        assertNull(state.entry(2, XpSource.INCREASE))
    }

    @Test
    fun totalsRunThroughTheHistoryInOrder() {
        val state = evaluate(
            listOf(session(2, monday + 1), session(1, monday)),
            listOf(set(1), set(2)),
        )

        assertEquals(listOf(1L, 2L), state.sessions.map { it.sessionId })
        assertEquals(listOf(100L, 200L), state.sessions.map { it.totalAfter })
        assertEquals(200, state.totalXp)
    }

    @Test
    fun deletingAWorkoutTakesItsXp() {
        val sets = listOf(set(1), set(2))
        val full = evaluate(listOf(session(1, monday), session(2, monday + 1)), sets)
        val after = evaluate(listOf(session(2, monday + 1)), sets)

        assertEquals(200, full.totalXp)
        assertEquals(100, after.totalXp)
    }

    private fun stateWithTotalBefore(before: Long, xp: Int = 100): GamificationState {
        val total = before + xp
        return GamificationState(
            total,
            LevelCurve.progress(total),
            listOf(SessionXp(1, xp, listOf(XpEntry(XpSource.TRAINING, 1, xp)), total)),
        )
    }

    @Test
    fun noCelebrationWithoutXp() {
        assertNull(GamificationEngine.celebration(stateWithTotalBefore(0), sessionId = 99, celebratedXp = null))
    }

    @Test
    fun theXpBarRunsFromBeforeToAfter() {
        val celebration = assertNotNull(GamificationEngine.celebration(stateWithTotalBefore(100), 1, celebratedXp = 100))

        assertEquals(100, celebration.before.totalXp)
        assertEquals(200, celebration.after.totalXp)
        assertNull(celebration.levelUp)
    }

    @Test
    fun aNewTierIsDue() {
        val celebration = assertNotNull(GamificationEngine.celebration(stateWithTotalBefore(250), 1, celebratedXp = 250))

        assertEquals(LevelUpKind.TIER, celebration.levelUp?.kind)
        assertEquals(2, celebration.levelUp?.progress?.tier)
    }

    @Test
    fun aNewChapterIsDue() {
        val start = LevelCurve.totalXpFor(4)
        val celebration = assertNotNull(GamificationEngine.celebration(stateWithTotalBefore(start - 50), 1, celebratedXp = start - 50))

        assertEquals(LevelUpKind.CHAPTER, celebration.levelUp?.kind)
        assertEquals(2, celebration.levelUp?.progress?.chapter)
    }

    @Test
    fun aNewStarIsDue() {
        val top = LevelCurve.totalXpFor(LevelCurve.MAX_LEVEL) + LevelCurve.XP_PER_STAR
        val celebration = assertNotNull(GamificationEngine.celebration(stateWithTotalBefore(top - 50), 1, celebratedXp = top - 50))

        assertEquals(LevelUpKind.STAR, celebration.levelUp?.kind)
        assertEquals(1, celebration.levelUp?.progress?.stars)
    }

    @Test
    fun aStepPassedInBetweenIsCaughtUp() {
        // An increase accepted after the last celebration carried the sum over the step.
        val celebration = assertNotNull(GamificationEngine.celebration(stateWithTotalBefore(320), 1, celebratedXp = 250))

        assertEquals(LevelUpKind.TIER, celebration.levelUp?.kind)
    }

    @Test
    fun withoutACelebratedSumNothingOlderIsCaughtUp() {
        // First use or a fresh backup import: years of levels are not replayed.
        val celebration = assertNotNull(GamificationEngine.celebration(stateWithTotalBefore(50_000), 1, celebratedXp = null))

        assertNull(celebration.levelUp)
    }

    @Test
    fun aDeletedWorkoutDoesNotRepeatALevelUp() {
        val celebration = assertNotNull(GamificationEngine.celebration(stateWithTotalBefore(250), 1, celebratedXp = 400))

        assertNull(celebration.levelUp)
    }

    private companion object {
        const val EX_BARBELL = 1L
        const val EX_MACHINE = 2L
        const val EX_PULLUP = 3L
    }
}
