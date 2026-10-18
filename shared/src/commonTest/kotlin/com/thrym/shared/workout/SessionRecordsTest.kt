package com.thrym.shared.workout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRecordsTest {

    private val today = 2L
    private val bench = 10L
    private val pullUp = 11L
    private val deadlift = 12L
    private val hipThrust = 13L

    @Test
    fun beatenWeightGivesWeightAndE1rmWithThePreviousBest() {
        val sets = listOf(
            set(1, session = 1, exercise = bench, weight = 97.5, reps = 5),
            set(3, session = 1, exercise = bench, weight = 97.5, reps = 5),
            set(2, session = today, exercise = bench, weight = 100.0, reps = 5),
        )

        val summary = SessionRecords.summarize(today, listOf(bench), sets)

        val lines = summary.exercises.single().lines
        assertEquals(listOf("MAX_WEIGHT", "MAX_E1RM"), lines.map { it.type })
        assertEquals(100.0, lines[0].value)
        assertEquals(97.5, lines[0].previous)
        assertEquals(2L, lines[0].setId)
        assertEquals(100.0 * (1 + 5 / 30.0), lines[1].value, 1e-9)
        assertEquals(97.5 * (1 + 5 / 30.0), lines[1].previous!!, 1e-9)
        // Reps 5 against 5 is a tie, and one set has less volume than two.
        assertEquals(2, summary.recordCount)
        assertEquals(1, summary.exercisesWithRecords)
    }

    @Test
    fun aTieOrAGainBelowOneStepIsNoRecord() {
        val tie = listOf(
            set(1, session = 1, exercise = bench, weight = 100.0, reps = 5),
            set(2, session = today, exercise = bench, weight = 100.0, reps = 5),
        )
        assertTrue(SessionRecords.summarize(today, listOf(bench), tie).exercises.isEmpty())

        val noise = listOf(
            set(1, session = 1, exercise = bench, weight = 100.0, reps = 5),
            set(2, session = today, exercise = bench, weight = 100.05, reps = 5),
        )
        assertTrue(SessionRecords.summarize(today, listOf(bench), noise).exercises.isEmpty())

        val oneStep = listOf(
            set(1, session = 1, exercise = bench, weight = 100.0, reps = 1),
            set(2, session = today, exercise = bench, weight = 100.1, reps = 1),
        )
        val line = SessionRecords.summarize(today, listOf(bench), oneStep).exercises.single().lines.first()
        assertEquals("MAX_WEIGHT", line.type)
    }

    @Test
    fun theSameE1rmFromAnotherRepRangeIsNoRecord() {
        // 87.5 × 10 and 100 × 5 both estimate 116.67 kg; floating point must not make it a record.
        val sets = listOf(
            set(1, session = 1, exercise = bench, weight = 100.0, reps = 5),
            set(2, session = today, exercise = bench, weight = 87.5, reps = 10),
        )

        val lines = SessionRecords.summarize(today, listOf(bench), sets).exercises.single().lines

        assertEquals(listOf("MAX_REPS", "MAX_VOLUME"), lines.map { it.type })
    }

    @Test
    fun aRecordBeatenTwiceInOneWorkoutIsOneLineWithTheLaterSet() {
        val sets = listOf(
            set(1, session = 1, exercise = bench, weight = 100.0, reps = 3),
            set(2, session = today, exercise = bench, weight = 101.0, reps = 3, at = 10),
            set(3, session = today, exercise = bench, weight = 102.5, reps = 3, at = 20),
        )

        val weight = SessionRecords.summarize(today, listOf(bench), sets).exercises.single().lines.first()

        assertEquals("MAX_WEIGHT", weight.type)
        assertEquals(102.5, weight.value)
        assertEquals(100.0, weight.previous)
        assertEquals(3L, weight.setId)
    }

    @Test
    fun warmUpsAndSetsWithoutRepsCountOnNeitherSide() {
        val sets = listOf(
            set(1, session = 1, exercise = bench, weight = 140.0, reps = 3, type = "WARMUP"),
            set(2, session = 1, exercise = bench, weight = 90.0, reps = 5),
            set(3, session = today, exercise = bench, weight = 150.0, reps = 2, type = "WARMUP"),
            set(4, session = today, exercise = bench, weight = 160.0, reps = 0),
            set(5, session = today, exercise = bench, weight = 95.0, reps = 5),
        )

        val weight = SessionRecords.summarize(today, listOf(bench), sets).exercises.single().lines.first()

        assertEquals(95.0, weight.value)
        assertEquals(90.0, weight.previous)
        assertEquals(5L, weight.setId)
    }

    @Test
    fun anExerciseTrainedForTheFirstTimeHasNoRecordsButABestSet() {
        val sets = listOf(
            set(1, session = today, exercise = hipThrust, weight = 80.0, reps = 8, at = 10),
            set(2, session = today, exercise = hipThrust, weight = 90.0, reps = 6, at = 20),
            set(3, session = today, exercise = hipThrust, weight = 90.0, reps = 7, at = 30),
            set(4, session = today, exercise = pullUp, weight = 0.0, reps = 8, at = 10),
            set(5, session = today, exercise = pullUp, weight = 0.0, reps = 10, at = 20),
            set(6, session = today, exercise = pullUp, weight = 0.0, reps = 10, at = 30),
        )

        val summary = SessionRecords.summarize(today, listOf(hipThrust, pullUp), sets)

        assertEquals(listOf(hipThrust, pullUp), summary.firstTimeExerciseIds)
        assertTrue(summary.exercises.all { it.firstTime && it.lines.isEmpty() })
        assertEquals(3L, summary.exercises[0].bestSetId)
        // Bodyweight only: the set with the most reps, the earlier one on a tie.
        assertEquals(5L, summary.exercises[1].bestSetId)
        assertEquals(0, summary.recordCount)
    }

    @Test
    fun aBodyweightExerciseOnlyHasRepsAndAFirstWeightHasNoPrevious() {
        val reps = listOf(
            set(1, session = 1, exercise = pullUp, weight = 0.0, reps = 10),
            set(2, session = today, exercise = pullUp, weight = 0.0, reps = 12),
        )
        val repsLine = SessionRecords.summarize(today, listOf(pullUp), reps).exercises.single().lines.single()
        assertEquals("MAX_REPS", repsLine.type)
        assertEquals(12.0, repsLine.value)
        assertEquals(10.0, repsLine.previous)

        val firstWeight = listOf(
            set(1, session = 1, exercise = pullUp, weight = 0.0, reps = 12),
            set(2, session = today, exercise = pullUp, weight = 10.0, reps = 8),
        )
        val lines = SessionRecords.summarize(today, listOf(pullUp), firstWeight).exercises.single().lines
        assertEquals(listOf("MAX_WEIGHT", "MAX_E1RM", "MAX_VOLUME"), lines.map { it.type })
        assertTrue(lines.all { it.previous == null })
    }

    @Test
    fun volumeComparesFinishedSessionsOnly() {
        val sets = listOf(
            set(1, session = 1, exercise = deadlift, weight = 100.0, reps = 5),
            set(2, session = 1, exercise = deadlift, weight = 100.0, reps = 5),
            set(3, session = 3, exercise = deadlift, weight = 100.0, reps = 30, finished = false),
            set(4, session = today, exercise = deadlift, weight = 100.0, reps = 4),
            set(5, session = today, exercise = deadlift, weight = 100.0, reps = 4),
            set(6, session = today, exercise = deadlift, weight = 100.0, reps = 4),
        )

        val lines = SessionRecords.summarize(today, listOf(deadlift), sets).exercises.single().lines

        val volume = lines.single { it.type == "MAX_VOLUME" }
        assertEquals(1200.0, volume.value)
        assertEquals(1000.0, volume.previous)
        assertNull(volume.setId)
        assertEquals(listOf("MAX_VOLUME"), lines.map { it.type })
    }

    @Test
    fun aSingleRepE1rmIsItsWeight() {
        assertEquals(140.0, SessionRecords.estimatedOneRepMaxKg(140.0, 1))
        assertEquals(100.0 * (1 + 5 / 30.0), SessionRecords.estimatedOneRepMaxKg(100.0, 5), 1e-9)
    }

    @Test
    fun recordSetsBreakTiesByRepsThenWeightThenTime() {
        val sets = listOf(
            set(1, session = 1, exercise = bench, weight = 50.0, reps = 5),
            set(2, session = today, exercise = bench, weight = 60.0, reps = 3, at = 10),
            set(3, session = today, exercise = bench, weight = 60.0, reps = 5, at = 20),
            set(4, session = today, exercise = bench, weight = 55.0, reps = 8, at = 30),
            set(5, session = today, exercise = bench, weight = 57.5, reps = 8, at = 40),
        )

        val lines = SessionRecords.summarize(today, listOf(bench), sets).exercises.single().lines.associateBy { it.type }

        assertEquals(3L, lines.getValue("MAX_WEIGHT").setId)
        assertEquals(5L, lines.getValue("MAX_REPS").setId)
    }

    @Test
    fun outputFollowsTheGivenOrderOnceAndDropsExercisesWithoutRecords() {
        val sets = listOf(
            set(1, session = 1, exercise = bench, weight = 100.0, reps = 5),
            set(2, session = today, exercise = bench, weight = 100.0, reps = 5),
            set(3, session = 1, exercise = deadlift, weight = 150.0, reps = 5),
            set(4, session = today, exercise = deadlift, weight = 160.0, reps = 5),
            set(5, session = today, exercise = hipThrust, weight = 80.0, reps = 8),
            set(6, session = 1, exercise = pullUp, weight = 0.0, reps = 10),
        )

        val summary = SessionRecords.summarize(today, listOf(hipThrust, bench, deadlift, hipThrust, pullUp), sets)

        assertEquals(listOf(hipThrust, deadlift), summary.exercises.map { it.exerciseId })
        assertEquals(1, summary.exercisesWithRecords)
    }

    @Test
    fun liveStaysQuietForAFirstWorkoutAndForTypesWithoutHistory() {
        val before = listOf(set(1, session = today, exercise = bench, weight = 60.0, reps = 5))
        val after = before + set(2, session = today, exercise = bench, weight = 70.0, reps = 5)
        assertTrue(SessionRecords.liveImprovements(today, before, after).isEmpty())

        val history = listOf(set(9, session = 1, exercise = pullUp, weight = 0.0, reps = 12))
        val weighted = history + set(2, session = today, exercise = pullUp, weight = 10.0, reps = 8)
        assertTrue(SessionRecords.liveImprovements(today, history, weighted).isEmpty())
    }

    @Test
    fun liveReportsTheGainOverTheBestBeforeTheWorkout() {
        val history = listOf(set(1, session = 1, exercise = bench, weight = 100.0, reps = 5))
        val first = history + set(2, session = today, exercise = bench, weight = 102.5, reps = 5, at = 10)

        val lines = SessionRecords.liveImprovements(today, history, first)

        assertEquals(listOf("MAX_WEIGHT", "MAX_E1RM"), lines.map { it.type })
        assertEquals(102.5, lines[0].value)
        assertEquals(100.0, lines[0].previous)
        assertEquals(2L, lines[0].setId)

        // A second gain in the same workout keeps the pre-workout best as previous.
        val second = first + set(3, session = today, exercise = bench, weight = 105.0, reps = 5, at = 20)
        val again = SessionRecords.liveImprovements(today, first, second)
        assertEquals(105.0, again[0].value)
        assertEquals(100.0, again[0].previous)
        assertEquals(3L, again[0].setId)

        // Matching the workout best again is no new record.
        val equal = second + set(4, session = today, exercise = bench, weight = 105.0, reps = 5, at = 30)
        assertTrue(SessionRecords.liveImprovements(today, second, equal).isEmpty())
    }

    @Test
    fun liveIgnoresLoweredEditsDeletionsWarmUpsAndVolume() {
        val history = listOf(
            set(1, session = 1, exercise = bench, weight = 100.0, reps = 5),
            set(2, session = 1, exercise = bench, weight = 100.0, reps = 5),
        )
        val record = history + set(3, session = today, exercise = bench, weight = 105.0, reps = 5)
        val lowered = history + set(3, session = today, exercise = bench, weight = 95.0, reps = 5)
        assertTrue(SessionRecords.liveImprovements(today, record, lowered).isEmpty())
        assertTrue(SessionRecords.liveImprovements(today, record, history).isEmpty())

        val warmUp = history + set(4, session = today, exercise = bench, weight = 120.0, reps = 5, type = "WARMUP")
        assertTrue(SessionRecords.liveImprovements(today, history, warmUp).isEmpty())

        // Many light sets beat the volume of the earlier workout, but volume is never live.
        val light = history + (10L..30L).map { set(it, session = today, exercise = bench, weight = 40.0, reps = 10) }
        assertTrue(SessionRecords.liveImprovements(today, history, light).none { it.type == "MAX_VOLUME" })
    }

    private fun set(
        id: Long,
        session: Long,
        exercise: Long,
        weight: Double,
        reps: Int,
        type: String = "NORMAL",
        at: Long = id,
        finished: Boolean = session != today,
    ) = RecordSetInput(
        id = id,
        sessionId = session,
        exerciseId = exercise,
        weightKg = weight,
        reps = reps,
        setType = type,
        completedAtEpochMillis = at,
        sessionFinished = finished,
    )
}
