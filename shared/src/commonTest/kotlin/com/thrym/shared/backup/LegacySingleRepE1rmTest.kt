package com.thrym.shared.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LegacySingleRepE1rmTest {

    @Test
    fun warmupsDoNotCountForTheOldBest() {
        // Plain Epley rates the warm-up 120 kg × 2 at 128 kg. Only the work set explains 103.3 kg.
        val payload = payload(
            sets = listOf(set(id = 1L, reps = 2, weightKg = 120.0, completedAt = 1_000L, setType = WARMUP_SET_TYPE)) +
                set(id = 2L, reps = 1, weightKg = 100.0, completedAt = 1_500L),
            e1rmKg = LEGACY_SINGLE_REP_E1RM_KG,
        )

        val record = payload.repairLegacySingleRepE1rm().personalRecords.single()

        assertEquals(BackupPersonalRecord(id = 7L, exerciseId = 1L, type = "MAX_E1RM", value = 100.0, achievedAt = 1_500L), record)
    }

    @Test
    fun aTieGoesToTheLaterSet() {
        val payload = payload(
            sets = listOf(
                set(id = 1L, reps = 1, weightKg = 100.0, completedAt = 2_000L),
                set(id = 2L, reps = 1, weightKg = 100.0, completedAt = 1_000L),
            ),
            e1rmKg = LEGACY_SINGLE_REP_E1RM_KG,
        )

        val record = payload.repairLegacySingleRepE1rm().personalRecords.single()

        assertEquals(100.0, record.value)
        assertEquals(2_000L, record.achievedAt)
    }

    @Test
    fun aRepairedOrCurrentPayloadStaysAsItIs() {
        val repaired = payload(
            sets = listOf(
                set(id = 1L, reps = 1, weightKg = 100.0, completedAt = 1_000L),
                set(id = 2L, reps = 4, weightKg = 90.0, completedAt = 1_500L),
            ),
            e1rmKg = LEGACY_SINGLE_REP_E1RM_KG,
        ).repairLegacySingleRepE1rm()
        assertEquals(102.0, repaired.personalRecords.single().value, 1e-9)

        assertSame(repaired, repaired.repairLegacySingleRepE1rm())
        // Without a single rep both rules agree, so the stored best is no value of the old rule.
        val multiRepOnly = payload(sets = listOf(set(id = 1L, reps = 4, weightKg = 90.0, completedAt = 1_000L)), e1rmKg = 102.0)
        assertSame(multiRepOnly, multiRepOnly.repairLegacySingleRepE1rm())
    }

    @Test
    fun aMultiRepSetTyingTheOldSingleTakesOverTheDate() {
        // Plain Epley gives 85 kg × 1 87.83333333333334 and 77.5 kg × 4 87.83333333333333, one bit
        // apart. The old rule kept the later single; today only the four reps count as 87.8 kg.
        val sets = listOf(
            set(id = 1L, reps = 4, weightKg = 77.5, completedAt = 1_000L),
            set(id = 2L, reps = 1, weightKg = 85.0, completedAt = 2_000L),
        )
        val legacy = payload(sets = sets, e1rmKg = 85.0 * (1.0 + 1.0 / 30.0), achievedAt = 2_000L)

        val repaired = legacy.repairLegacySingleRepE1rm()
        val record = repaired.personalRecords.single()

        assertEquals(77.5 * (1.0 + 4.0 / 30.0), record.value)
        assertEquals(1_000L, record.achievedAt)
        assertEquals(7L, record.id)
        assertSame(repaired, repaired.repairLegacySingleRepE1rm())
    }

    @Test
    fun anExactTieWithTheOldSingleTakesOverTheDate() {
        // 87.5 kg × 1 and 77.5 kg × 5 tie exactly at 90.4 kg by plain Epley.
        val legacy = payload(
            sets = listOf(
                set(id = 1L, reps = 5, weightKg = 77.5, completedAt = 1_000L),
                set(id = 2L, reps = 1, weightKg = 87.5, completedAt = 2_000L),
            ),
            e1rmKg = 87.5 * (1.0 + 1.0 / 30.0),
            achievedAt = 2_000L,
        )

        val record = legacy.repairLegacySingleRepE1rm().personalRecords.single()

        assertEquals(1_000L, record.achievedAt)
    }

    @Test
    fun aTiedRecordDatedOnTheMultiRepSetStays() {
        // The current rule's record for the same sets: the four reps at their own time.
        val current = payload(
            sets = listOf(
                set(id = 1L, reps = 4, weightKg = 77.5, completedAt = 1_000L),
                set(id = 2L, reps = 1, weightKg = 85.0, completedAt = 2_000L),
            ),
            e1rmKg = 77.5 * (1.0 + 4.0 / 30.0),
            achievedAt = 1_000L,
        )

        assertSame(current, current.repairLegacySingleRepE1rm())
    }

    private fun set(
        id: Long,
        reps: Int,
        weightKg: Double,
        completedAt: Long,
        setType: String = NORMAL_SET_TYPE,
    ) = BackupWorkoutSet(
        id = id,
        sessionId = 1L,
        exerciseId = 1L,
        setNumber = id.toInt(),
        reps = reps,
        weightKg = weightKg,
        setType = setType,
        completedAt = completedAt,
    )

    /** One exercise with [sets] and its MAX_E1RM record of [e1rmKg], stored at [achievedAt] (default: the first set's time). */
    private fun payload(
        sets: List<BackupWorkoutSet>,
        e1rmKg: Double,
        achievedAt: Long = sets.first().completedAt,
    ) = BackupPayloadV1(
        formatVersion = 1,
        schemaVersion = CURRENT_BACKUP_SCHEMA_VERSION,
        appVersion = "1.0",
        exportedAtEpochMillis = 0L,
        exercises = listOf(
            BackupExercise(
                id = 1L,
                name = "Bankdrücken",
                primaryMuscleGroup = "BRUST",
                secondaryMuscleGroups = "",
                category = "LANGHANTEL",
                isCustom = false,
            ),
        ),
        workoutSessions = emptyList(),
        workoutSets = sets,
        trainingPlans = emptyList(),
        planExercises = emptyList(),
        personalRecords = listOf(
            BackupPersonalRecord(id = 7L, exerciseId = 1L, type = "MAX_E1RM", value = e1rmKg, achievedAt = achievedAt),
        ),
    )

    private companion object {
        /** 100 kg × 1 by plain Epley, the value older iOS builds stored. */
        const val LEGACY_SINGLE_REP_E1RM_KG = 100.0 * (1.0 + 1.0 / 30.0)
    }
}
