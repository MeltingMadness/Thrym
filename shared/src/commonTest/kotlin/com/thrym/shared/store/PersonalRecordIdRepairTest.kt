package com.thrym.shared.store

import com.thrym.shared.backup.BackupExercise
import com.thrym.shared.backup.BackupPayloadV1
import com.thrym.shared.backup.BackupPersonalRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PersonalRecordIdRepairTest {

    private val exercise = BackupExercise(
        id = 1L,
        name = "Bankdrücken",
        primaryMuscleGroup = "CHEST",
        secondaryMuscleGroups = "",
        category = "BARBELL",
        isCustom = false,
    )

    @Test
    fun firstOccurrenceKeepsItsIdAndDuplicatesGetFreshIds() {
        val payload = payloadWith(
            record(id = 6L, type = "MAX_WEIGHT", value = 80.0),
            record(id = 6L, type = "MAX_REPS", value = 8.0),
            record(id = 9L, type = "MAX_E1RM", value = 100.0),
            record(id = 9L, type = "MAX_VOLUME", value = 640.0),
        )

        val repaired = PersonalRecordIdRepair.repair(payload).personalRecords

        assertEquals(4, repaired.size)
        assertEquals(listOf(6L, 10L, 9L, 11L), repaired.map { it.id })
        assertEquals(listOf("MAX_WEIGHT", "MAX_REPS", "MAX_E1RM", "MAX_VOLUME"), repaired.map { it.type })
        assertEquals(listOf(80.0, 8.0, 100.0, 640.0), repaired.map { it.value })
    }

    @Test
    fun stateWithoutDuplicatesIsReturnedUnchanged() {
        val payload = payloadWith(
            record(id = 1L, type = "MAX_WEIGHT", value = 80.0),
            record(id = 2L, type = "MAX_REPS", value = 8.0),
        )

        assertSame(payload, PersonalRecordIdRepair.repair(payload))
    }

    @Test
    fun nonPositiveIdsGetFreshIdsAboveTheCurrentMaximum() {
        val payload = payloadWith(
            record(id = 0L, type = "MAX_WEIGHT", value = 80.0),
            record(id = -3L, type = "MAX_REPS", value = 8.0),
            record(id = 4L, type = "MAX_E1RM", value = 100.0),
        )

        val repaired = PersonalRecordIdRepair.repair(payload).personalRecords

        assertEquals(listOf(5L, 6L, 4L), repaired.map { it.id })
        assertEquals(listOf(80.0, 8.0, 100.0), repaired.map { it.value })
    }

    @Test
    fun repairIsIdempotent() {
        val payload = payloadWith(
            record(id = 6L, type = "MAX_WEIGHT", value = 80.0),
            record(id = 6L, type = "MAX_REPS", value = 8.0),
        )

        val once = PersonalRecordIdRepair.repair(payload)
        val twice = PersonalRecordIdRepair.repair(once)

        assertEquals(once.personalRecords, twice.personalRecords)
    }

    private fun record(id: Long, type: String, value: Double) = BackupPersonalRecord(
        id = id,
        exerciseId = exercise.id,
        type = type,
        value = value,
        achievedAt = 1_000L,
    )

    private fun payloadWith(vararg records: BackupPersonalRecord) = BackupPayloadV1(
        formatVersion = 1,
        schemaVersion = 15,
        appVersion = "",
        exportedAtEpochMillis = 0L,
        exercises = listOf(exercise),
        workoutSessions = emptyList(),
        workoutSets = emptyList(),
        trainingPlans = emptyList(),
        planExercises = emptyList(),
        personalRecords = records.toList(),
    )
}
