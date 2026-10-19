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

    /** One exercise with [sets] and its MAX_E1RM record of [e1rmKg], stored at the first set's time. */
    private fun payload(sets: List<BackupWorkoutSet>, e1rmKg: Double) = BackupPayloadV1(
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
            BackupPersonalRecord(id = 7L, exerciseId = 1L, type = "MAX_E1RM", value = e1rmKg, achievedAt = sets.first().completedAt),
        ),
    )

    private companion object {
        /** 100 kg × 1 by plain Epley, the value older iOS builds stored. */
        const val LEGACY_SINGLE_REP_E1RM_KG = 100.0 * (1.0 + 1.0 / 30.0)
    }
}
