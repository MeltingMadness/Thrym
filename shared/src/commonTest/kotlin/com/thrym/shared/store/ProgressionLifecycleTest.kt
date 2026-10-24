package com.thrym.shared.store

import com.thrym.shared.backup.BackupExercise
import com.thrym.shared.backup.BackupPlanExercise
import com.thrym.shared.backup.BackupProgressionConfig
import com.thrym.shared.backup.BackupProgressionTarget
import com.thrym.shared.backup.BackupWorkoutPlanTarget
import com.thrym.shared.backup.BackupWorkoutSession
import com.thrym.shared.backup.BackupWorkoutSet
import com.thrym.shared.backup.NORMAL_SET_TYPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ProgressionLifecycleTest {

    @Test
    fun generationIsIdempotentAndNeverAutoAppliesPlanTarget() = runTest {
        val store = singleSessionStore()
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 9_000L })

        val first = lifecycle.generateForFinishedSession(20L)
        val firstSuggestion = store.snapshot().progressionSuggestions.single()
        val second = lifecycle.generateForFinishedSession(20L)

        assertEquals(1, first.insertedCount)
        assertEquals(1, first.reviewItemCount)
        assertEquals(0, second.insertedCount)
        assertEquals(1, second.pendingCount)
        assertEquals(100.0, store.snapshot().planExercises.single().targetWeightKg)
        assertEquals(firstSuggestion.id, store.snapshot().progressionSuggestions.single().id)
        assertEquals(
            firstSuggestion.countedSetIds,
            store.snapshot().progressionSuggestions.single().countedSetIds,
        )
        assertEquals(
            firstSuggestion.sourceTarget,
            store.snapshot().progressionSuggestions.single().sourceTarget,
        )
    }

    @Test
    fun acceptingEditedTargetUpdatesPlanOnlyAfterConfirmationAndKeepsSourceSnapshot() = runTest {
        val store = singleSessionStore()
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 10_000L })
        lifecycle.generateForFinishedSession(20L)
        val suggestion = store.snapshot().progressionSuggestions.single()

        val result = lifecycle.acceptSuggestion(
            suggestionId = suggestion.id,
            finalTarget = BackupProgressionTarget(sets = 3, reps = 8, weightKg = 103.0),
        )

        val accepted = assertIs<ProgressionDecisionResult.Accepted>(result)
        assertEquals(setOf(suggestion.id), accepted.suggestionIds)
        assertEquals(103.0, store.snapshot().planExercises.single().targetWeightKg)
        val row = store.snapshot().progressionSuggestions.single()
        assertEquals("ACCEPTED", row.status)
        assertEquals(100.0, row.sourceTarget.weightKg)
        assertEquals(103.0, row.finalTarget?.weightKg)
        assertTrue(row.wasEdited)
    }

    @Test
    fun staleAcceptanceDoesNotApplyEditedPlanAndMarksOnlySelectedRow() = runTest {
        val store = singleSessionStore()
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 11_000L })
        lifecycle.generateForFinishedSession(20L)
        val suggestion = store.snapshot().progressionSuggestions.single()
        store.transact { state ->
            state.copy(
                planExercises = state.planExercises.map {
                    it.copy(targetWeightKg = 101.0)
                },
            )
        }

        val result = lifecycle.acceptSuggestion(
            suggestion.id,
            BackupProgressionTarget(3, 8, 103.0),
        )

        assertEquals(setOf(suggestion.id), assertIs<ProgressionDecisionResult.Stale>(result).suggestionIds)
        assertEquals(101.0, store.snapshot().planExercises.single().targetWeightKg)
        assertEquals("STALE", store.snapshot().progressionSuggestions.single().status)
        assertEquals(null, store.snapshot().progressionSuggestions.single().finalTarget)
    }

    @Test
    fun acceptingIndependentBatchUpdatesBothPlansAndPreservesSourceSnapshots() = runTest {
        val store = twoIndependentPendingStore()
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 11_500L })
        lifecycle.generateForFinishedSession(20L)
        val before = store.snapshot()
        val suggestions = before.progressionSuggestions.sortedBy { it.orderIndex }
        assertEquals(2, suggestions.size)
        val first = suggestions[0]
        val second = suggestions[1]

        val result = lifecycle.acceptSuggestions(
            mapOf(
                first.id to BackupProgressionTarget(sets = 3, reps = 8, weightKg = 103.0),
                second.id to BackupProgressionTarget(sets = 3, reps = 8, weightKg = 63.0),
            ),
        )

        assertEquals(
            setOf(first.id, second.id),
            assertIs<ProgressionDecisionResult.Accepted>(result).suggestionIds,
        )
        val after = store.snapshot()
        assertEquals(
            mapOf(1L to 103.0, 2L to 63.0),
            after.planExercises.associate { it.exerciseId to it.targetWeightKg },
        )
        // Plan target snapshots are immutable evidence for the generated suggestions. They must
        // remain the exact source rows even though both current plan rows changed in one commit.
        assertEquals(before.workoutPlanTargets, after.workoutPlanTargets)
        assertEquals(before.progressionSuggestions.map { it.sourceTarget }, after.progressionSuggestions.map { it.sourceTarget })
        after.progressionSuggestions.forEach { row ->
            assertEquals("ACCEPTED", row.status)
            assertEquals(true, row.wasEdited)
            assertEquals(
                if (row.id == first.id) 103.0 else 63.0,
                row.finalTarget?.weightKg,
            )
        }
    }

    @Test
    fun staleMemberInBatchPreventsPartialPlanApplication() = runTest {
        val store = twoIndependentPendingStore()
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 11_600L })
        lifecycle.generateForFinishedSession(20L)
        val before = store.snapshot()
        val suggestions = before.progressionSuggestions.sortedBy { it.orderIndex }
        val first = suggestions[0]
        val second = suggestions[1]
        store.transact { state ->
            state.copy(
                planExercises = state.planExercises.map { planExercise ->
                    if (planExercise.id == 100L) planExercise.copy(targetWeightKg = 101.0) else planExercise
                },
            )
        }

        val result = lifecycle.acceptSuggestions(
            mapOf(
                first.id to BackupProgressionTarget(sets = 3, reps = 8, weightKg = 103.0),
                second.id to BackupProgressionTarget(sets = 3, reps = 8, weightKg = 63.0),
            ),
        )

        assertEquals(setOf(first.id), assertIs<ProgressionDecisionResult.Stale>(result).suggestionIds)
        val after = store.snapshot()
        assertEquals(101.0, after.planExercises.single { it.exerciseId == 1L }.targetWeightKg)
        assertEquals(60.0, after.planExercises.single { it.exerciseId == 2L }.targetWeightKg)
        assertEquals("STALE", after.progressionSuggestions.single { it.id == first.id }.status)
        assertEquals("PENDING", after.progressionSuggestions.single { it.id == second.id }.status)
        assertEquals(before.workoutPlanTargets, after.workoutPlanTargets)
        assertEquals(null, after.progressionSuggestions.single { it.id == first.id }.finalTarget)
        assertEquals(null, after.progressionSuggestions.single { it.id == second.id }.finalTarget)
    }

    @Test
    fun invalidMemberInBatchLeavesBothPlansAndSuggestionsUnchanged() = runTest {
        val store = twoIndependentPendingStore()
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 11_700L })
        lifecycle.generateForFinishedSession(20L)
        val before = store.snapshot()
        val suggestions = before.progressionSuggestions.sortedBy { it.orderIndex }
        val first = suggestions[0]
        val second = suggestions[1]

        val result = lifecycle.acceptSuggestions(
            mapOf(
                first.id to BackupProgressionTarget(sets = 0, reps = 8, weightKg = 103.0),
                second.id to BackupProgressionTarget(sets = 3, reps = 8, weightKg = 63.0),
            ),
        )

        val invalid = assertIs<ProgressionDecisionResult.Invalid>(result)
        assertTrue(invalid.message.contains("sets", ignoreCase = true))
        val after = store.snapshot()
        assertEquals(before.planExercises, after.planExercises)
        assertEquals(before.progressionSuggestions, after.progressionSuggestions)
        assertEquals(before.workoutPlanTargets, after.workoutPlanTargets)
    }

    @Test
    fun changedActualLoadBreaksFailureHistoryChain() = runTest {
        val store = twoSessionStore(firstWeight = 100.0, secondWeight = 110.0)
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 12_000L })

        assertEquals(2, lifecycle.generateMissingOutcomes())

        val rows = store.snapshot().progressionSuggestions.sortedBy { it.sourceSessionId }
        assertEquals(2, rows.size)
        assertEquals("REPEAT_TARGET", rows[0].reasonCode)
        assertEquals("INFORMATIONAL", rows[0].status)
        assertEquals("REPEAT_TARGET", rows[1].reasonCode)
        // The changed actual load cuts the prior failure chain. With no comparable
        // failure history, Android's engine proposes the recorded load for review.
        assertEquals("PENDING", rows[1].status)
        assertEquals(110.0, rows[1].suggestedTarget?.weightKg)
        assertEquals(110.0, rows[1].reasonArguments["actualWeightKg"])
    }

    @Test
    fun invalidEditedTargetLeavesPlanAndSuggestionUnchanged() = runTest {
        val store = singleSessionStore()
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 13_000L })
        lifecycle.generateForFinishedSession(20L)
        val before = store.snapshot()
        val suggestion = before.progressionSuggestions.single()

        val result = lifecycle.acceptSuggestion(
            suggestion.id,
            BackupProgressionTarget(sets = 0, reps = 8, weightKg = Double.NaN),
        )

        assertIs<ProgressionDecisionResult.Invalid>(result)
        assertEquals(before.planExercises, store.snapshot().planExercises)
        assertEquals(before.progressionSuggestions, store.snapshot().progressionSuggestions)
    }

    @Test
    fun rejectingSuggestionNeverChangesPlan() = runTest {
        val store = singleSessionStore()
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 14_000L })
        lifecycle.generateForFinishedSession(20L)
        val suggestion = store.snapshot().progressionSuggestions.single()

        val result = lifecycle.rejectSuggestion(suggestion.id)

        assertEquals(
            setOf(suggestion.id),
            assertIs<ProgressionDecisionResult.Rejected>(result).suggestionIds,
        )
        assertEquals(100.0, store.snapshot().planExercises.single().targetWeightKg)
        assertEquals("REJECTED", store.snapshot().progressionSuggestions.single().status)
    }

    @Test
    fun autoApplyTakesSafeIncreaseIntoPlan() = runTest {
        val store = singleSessionStore()
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 15_000L })
        lifecycle.generateForFinishedSession(20L)
        val suggestion = store.snapshot().progressionSuggestions.single()
        assertEquals("LOAD_ADVANCED", suggestion.reasonCode)

        val applied = lifecycle.autoApplySafeIncreases(20L)

        assertEquals(setOf(suggestion.id), applied)
        assertEquals(102.5, store.snapshot().planExercises.single().targetWeightKg)
        val row = store.snapshot().progressionSuggestions.single()
        assertEquals("ACCEPTED", row.status)
        assertEquals(false, row.wasEdited)
        assertEquals(emptySet(), lifecycle.autoApplySafeIncreases(20L))
    }

    @Test
    fun autoApplyLeavesNonIncreaseForReview() = runTest {
        val store = twoSessionStore(firstWeight = 100.0, secondWeight = 110.0)
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 16_000L })
        lifecycle.generateMissingOutcomes()
        val pending = store.snapshot().progressionSuggestions.single { it.sourceSessionId == 21L }
        assertEquals("PENDING", pending.status)

        val applied = lifecycle.autoApplySafeIncreases(21L)

        assertEquals(emptySet(), applied)
        assertEquals(100.0, store.snapshot().planExercises.single().targetWeightKg)
        assertEquals("PENDING", store.snapshot().progressionSuggestions.single { it.id == pending.id }.status)
    }

    @Test
    fun autoApplyOnlyTouchesSuggestionsOfGivenSession() = runTest {
        val store = singleSessionStore()
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 17_000L })
        lifecycle.generateForFinishedSession(20L)

        assertEquals(emptySet(), lifecycle.autoApplySafeIncreases(99L))
        assertEquals(100.0, store.snapshot().planExercises.single().targetWeightKg)
        assertEquals("PENDING", store.snapshot().progressionSuggestions.single().status)
    }

    private fun singleSessionStore(): SharedStateStore {
        val store = newStore()
        val config = linearConfig()
        return runBlockingStoreTransaction(store) { state ->
            state.copy(
                workoutSessions = listOf(
                    BackupWorkoutSession(
                        id = 20L,
                        startTime = 1_000L,
                        endTime = 2_000L,
                        durationSeconds = 1L,
                        name = "Push",
                        notes = "",
                        planId = 10L,
                    ),
                ),
                workoutSets = listOf(
                    workoutSet(40L, 1, 8, 100.0, 30L),
                    workoutSet(41L, 2, 8, 100.0, 30L),
                    workoutSet(42L, 3, 8, 100.0, 30L),
                ),
                trainingPlans = listOf(
                    com.thrym.shared.backup.BackupTrainingPlan(10L, "Push", 1_000L),
                ),
                planExercises = listOf(
                    BackupPlanExercise(
                        id = 100L,
                        planId = 10L,
                        exerciseId = 1L,
                        orderIndex = 0,
                        targetSets = 3,
                        targetReps = 8,
                        targetWeightKg = 100.0,
                        progression = config,
                    ),
                ),
                workoutPlanTargets = listOf(
                    BackupWorkoutPlanTarget(
                        id = 30L,
                        sessionId = 20L,
                        planId = 10L,
                        exerciseId = 1L,
                        orderIndex = 0,
                        target = BackupProgressionTarget(3, 8, 100.0),
                        progression = config,
                    ),
                ),
            )
        }
    }

    private fun twoSessionStore(firstWeight: Double, secondWeight: Double): SharedStateStore {
        val store = newStore()
        val config = linearConfig()
        return runBlockingStoreTransaction(store) { state ->
            state.copy(
                workoutSessions = listOf(
                    BackupWorkoutSession(20L, 1_000L, 2_000L, 1L, "A", "", planId = 10L),
                    BackupWorkoutSession(21L, 3_000L, 4_000L, 1L, "B", "", planId = 10L),
                ),
                workoutSets = listOf(
                    workoutSet(40L, 1, 7, firstWeight, 30L, sessionId = 20L),
                    workoutSet(41L, 2, 7, firstWeight, 30L, sessionId = 20L),
                    workoutSet(42L, 3, 7, firstWeight, 30L, sessionId = 20L),
                    workoutSet(50L, 1, 7, secondWeight, 31L, sessionId = 21L),
                    workoutSet(51L, 2, 7, secondWeight, 31L, sessionId = 21L),
                    workoutSet(52L, 3, 7, secondWeight, 31L, sessionId = 21L),
                ),
                trainingPlans = listOf(
                    com.thrym.shared.backup.BackupTrainingPlan(10L, "Push", 1_000L),
                ),
                planExercises = listOf(
                    BackupPlanExercise(100L, 10L, 1L, 0, targetSets = 3, targetReps = 8, targetWeightKg = 100.0, progression = config),
                ),
                workoutPlanTargets = listOf(
                    BackupWorkoutPlanTarget(30L, 20L, 10L, 1L, 0, target = BackupProgressionTarget(3, 8, 100.0), progression = config),
                    BackupWorkoutPlanTarget(31L, 21L, 10L, 1L, 0, target = BackupProgressionTarget(3, 8, 100.0), progression = config),
                ),
            )
        }
    }

    private fun twoIndependentPendingStore(): SharedStateStore {
        val store = newStore(
            additionalExercises = listOf(
                BackupExercise(2L, "Kniebeuge", "BEINE", "GESAEß", "LANGHANTEL", false),
            ),
        )
        val config = linearConfig()
        return runBlockingStoreTransaction(store) { state ->
            state.copy(
                workoutSessions = listOf(
                    BackupWorkoutSession(20L, 1_000L, 2_000L, 1L, "Push", "", planId = 10L),
                ),
                workoutSets = listOf(
                    workoutSet(40L, 1, 8, 100.0, 30L, exerciseId = 1L),
                    workoutSet(41L, 2, 8, 100.0, 30L, exerciseId = 1L),
                    workoutSet(42L, 3, 8, 100.0, 30L, exerciseId = 1L),
                    workoutSet(50L, 1, 8, 60.0, 31L, exerciseId = 2L),
                    workoutSet(51L, 2, 8, 60.0, 31L, exerciseId = 2L),
                    workoutSet(52L, 3, 8, 60.0, 31L, exerciseId = 2L),
                ),
                trainingPlans = listOf(
                    com.thrym.shared.backup.BackupTrainingPlan(10L, "Push", 1_000L),
                ),
                planExercises = listOf(
                    BackupPlanExercise(
                        id = 100L,
                        planId = 10L,
                        exerciseId = 1L,
                        orderIndex = 0,
                        targetSets = 3,
                        targetReps = 8,
                        targetWeightKg = 100.0,
                        progression = config,
                    ),
                    BackupPlanExercise(
                        id = 101L,
                        planId = 10L,
                        exerciseId = 2L,
                        orderIndex = 1,
                        targetSets = 3,
                        targetReps = 8,
                        targetWeightKg = 60.0,
                        progression = config,
                    ),
                ),
                workoutPlanTargets = listOf(
                    BackupWorkoutPlanTarget(
                        id = 30L,
                        sessionId = 20L,
                        planId = 10L,
                        exerciseId = 1L,
                        orderIndex = 0,
                        target = BackupProgressionTarget(3, 8, 100.0),
                        progression = config,
                    ),
                    BackupWorkoutPlanTarget(
                        id = 31L,
                        sessionId = 20L,
                        planId = 10L,
                        exerciseId = 2L,
                        orderIndex = 1,
                        target = BackupProgressionTarget(3, 8, 60.0),
                        progression = config,
                    ),
                ),
            )
        }
    }

    private fun workoutSet(
        id: Long,
        setNumber: Int,
        reps: Int,
        weightKg: Double,
        targetId: Long,
        sessionId: Long = 20L,
        exerciseId: Long = 1L,
    ) = BackupWorkoutSet(
        id = id,
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = setNumber,
        reps = reps,
        weightKg = weightKg,
        setType = NORMAL_SET_TYPE,
        completedAt = 1_000L + id,
        planTargetSnapshotId = targetId,
    )

    private fun linearConfig() = BackupProgressionConfig(
        scheme = "LINEAR",
        incrementValue = 2.5,
        incrementUnit = "METRIC",
        incrementKg = 2.5,
        stallThreshold = 2,
        backoffPercent = 10.0,
        ruleRevision = 1,
    )

    private fun newStore(
        additionalExercises: List<BackupExercise> = emptyList(),
    ): SharedStateStore = SharedStateStore(
        persistence = MemoryPersistence(),
        seedExercises = listOf(
            BackupExercise(1L, "Bankdruecken", "BRUST", "TRIZEPS", "LANGHANTEL", false),
        ) + additionalExercises,
        nowEpochMillis = { 500L },
    )

    private fun runBlockingStoreTransaction(
        store: SharedStateStore,
        transform: (com.thrym.shared.backup.BackupPayloadV1) -> com.thrym.shared.backup.BackupPayloadV1,
    ): SharedStateStore {
        kotlinx.coroutines.runBlocking { store.transact(transform) }
        return store
    }

    private class MemoryPersistence : SharedStatePersistence {
        private var serialized: String? = null

        override fun read(): String? = serialized

        override fun writeAtomically(serialized: String) {
            this.serialized = serialized
        }
    }
}
