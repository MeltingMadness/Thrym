package com.thrym.shared.store

import com.thrym.shared.backup.BackupExercise
import com.thrym.shared.backup.BackupMetaPlanItem
import com.thrym.shared.backup.BackupMetaTrainingPlan
import com.thrym.shared.backup.BackupPersonalRecord
import com.thrym.shared.backup.BackupPlanExercise
import com.thrym.shared.backup.BackupProgressionConfig
import com.thrym.shared.backup.BackupTrainingPlan
import com.thrym.shared.backup.BackupWorkoutSession
import com.thrym.shared.backup.BackupWorkoutSet
import com.thrym.shared.backup.DROP_SET_TYPE
import com.thrym.shared.backup.NORMAL_SET_TYPE
import com.thrym.shared.readinessdata.ReadinessCheckIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SharedStateStoreTest {

    private val exercise = BackupExercise(
        id = 1L,
        name = "Bench press",
        primaryMuscleGroup = "CHEST",
        secondaryMuscleGroups = "TRICEPS",
        category = "BARBELL",
        isCustom = false,
    )

    @Test
    fun firstLaunchSeedsAndPersistsCatalog() {
        val persistence = MemoryPersistence()

        val store = newStore(persistence)

        assertEquals(listOf(exercise), store.snapshot().exercises)
        assertEquals(1, persistence.writeCount)
        assertTrue(persistence.value.orEmpty().contains("Bench press"))
    }

    @Test
    fun workoutPlanSessionAndSetRoundTripAsOneGraph() = runTest {
        val persistence = MemoryPersistence()
        var now = 1_000L
        val store = newStore(persistence) { now }
        val planId = store.saveTrainingPlan(
            plan = BackupTrainingPlan(id = 0L, name = "Push", createdAt = 0L),
            exercises = listOf(
                BackupPlanExercise(
                    id = 0L,
                    planId = 0L,
                    exerciseId = exercise.id,
                    orderIndex = 0,
                    targetSets = 3,
                    targetReps = 8,
                    targetWeightKg = 60.0,
                ),
            ),
        )
        val sessionId = store.startWorkout(name = "Monday", planId = planId)
        val target = store.snapshot().workoutPlanTargets.single()
        val setId = store.addSet(
            BackupWorkoutSet(
                id = 0L,
                sessionId = sessionId,
                exerciseId = exercise.id,
                setNumber = 1,
                reps = 8,
                weightKg = 60.0,
                setType = NORMAL_SET_TYPE,
                completedAt = now,
                planTargetSnapshotId = target.id,
            ),
        )
        now = 2_000L
        store.finishWorkout(sessionId)

        val snapshot = store.snapshot()
        assertEquals(planId, snapshot.trainingPlans.single().id)
        assertEquals(sessionId, snapshot.workoutSessions.single().id)
        assertEquals(setId, snapshot.workoutSets.single().id)
        assertEquals(target.id, snapshot.workoutSets.single().planTargetSnapshotId)
        assertEquals(1L, snapshot.workoutSessions.single().durationSeconds)

        val reloaded = newStore(MemoryPersistence(persistence.value))
        assertEquals(snapshot, reloaded.snapshot())
    }

    @Test
    fun startWorkoutResumesMatchingRequestedContextAndGenericResume() = runTest {
        val store = newStore(MemoryPersistence())
        val planId = store.saveTrainingPlan(
            plan = BackupTrainingPlan(id = 0L, name = "Push", createdAt = 0L),
            exercises = emptyList(),
        )
        val metaPlanId = store.saveMetaTrainingPlan(
            plan = BackupMetaTrainingPlan(id = 0L, name = "Upper", createdAt = 0L),
            items = emptyList(),
        )

        val activeSessionId = store.startWorkout(
            name = "Push",
            planId = planId,
            metaPlanId = metaPlanId,
        )

        assertEquals(
            activeSessionId,
            store.startWorkout(planId = planId, metaPlanId = metaPlanId),
        )
        // Plan-list and meta-plan callers provide only the context they selected; an omitted
        // identifier is intentionally treated as unspecified for the active-session match.
        assertEquals(activeSessionId, store.startWorkout(planId = planId))
        assertEquals(activeSessionId, store.startWorkout(metaPlanId = metaPlanId))
        // A caller without plan context is the dashboard's generic resume action.
        assertEquals(activeSessionId, store.startWorkout())
        assertEquals(1, store.snapshot().workoutSessions.count { it.endTime == null })
    }

    @Test
    fun startWorkoutRejectsDifferentRequestedPlanOrMetaPlanWithoutMutation() = runTest {
        val store = newStore(MemoryPersistence())
        val firstPlanId = store.saveTrainingPlan(
            plan = BackupTrainingPlan(id = 0L, name = "Push", createdAt = 0L),
            exercises = emptyList(),
        )
        val secondPlanId = store.saveTrainingPlan(
            plan = BackupTrainingPlan(id = 0L, name = "Pull", createdAt = 0L),
            exercises = emptyList(),
        )
        val firstMetaPlanId = store.saveMetaTrainingPlan(
            plan = BackupMetaTrainingPlan(id = 0L, name = "Upper", createdAt = 0L),
            items = emptyList(),
        )
        val secondMetaPlanId = store.saveMetaTrainingPlan(
            plan = BackupMetaTrainingPlan(id = 0L, name = "Lower", createdAt = 0L),
            items = emptyList(),
        )
        val activeSessionId = store.startWorkout(
            name = "Push",
            planId = firstPlanId,
            metaPlanId = firstMetaPlanId,
        )
        val before = store.snapshot()

        val planConflict = assertFailsWith<IllegalStateException> {
            store.startWorkout(planId = secondPlanId)
        }
        assertTrue(planConflict.message.orEmpty().contains("anderes Training aktiv"))
        assertFailsWith<IllegalStateException> {
            store.startWorkout(metaPlanId = secondMetaPlanId)
        }

        assertEquals(before, store.snapshot())
        assertEquals(activeSessionId, store.snapshot().workoutSessions.single().id)
    }

    @Test
    fun failedAtomicWriteDoesNotPublishCandidate() = runTest {
        val persistence = MemoryPersistence()
        val store = newStore(persistence)
        val before = store.snapshot()
        persistence.failWrites = true

        assertFailsWith<IllegalStateException> {
            store.saveExercise(
                exercise.copy(id = 0L, name = "Pull-up", isCustom = true),
            )
        }

        assertEquals(before, store.snapshot())
    }

    @Test
    fun importRejectsSnapshotThatChangedAfterBackupPreview() = runTest {
        val persistence = MemoryPersistence()
        val store = newStore(persistence)
        val expectedSnapshot = store.snapshot()
        val serializedImport = store.exportJson()

        store.saveExercise(exercise.copy(id = 0L, name = "Pull-up", isCustom = true))
        val changedSnapshot = store.snapshot()
        val changedSerialized = persistence.value
        val writesBeforeStaleImport = persistence.writeCount

        assertFailsWith<IllegalStateException> {
            store.importJsonIfUnchanged(serializedImport, expectedSnapshot)
        }

        assertEquals(changedSnapshot, store.snapshot())
        assertEquals(changedSerialized, persistence.value)
        assertEquals(writesBeforeStaleImport, persistence.writeCount)
    }

    @Test
    fun guardedImportSavesCanonicalRecoveryBeforePublishingCandidate() = runTest {
        val persistence = MemoryPersistence()
        val store = newStore(persistence)
        val expectedSnapshot = store.snapshot()
        val imported = expectedSnapshot.copy(
            exercises = listOf(exercise.copy(id = 42L, name = "Imported")),
        )
        val importedJson = kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = true
        }.encodeToString(imported)
        var recovery: RecoverySnapshot? = null

        store.importJsonIfUnchangedWithRecovery(
            serialized = importedJson,
            expectedSnapshot = expectedSnapshot,
        ) { snapshot ->
            recovery = snapshot
        }

        val savedRecovery = requireNotNull(recovery)
        assertEquals(expectedSnapshot, savedRecovery.payload)
        assertEquals(
            expectedSnapshot,
            SharedStateStore.decodeAndUpgradeForPreview(savedRecovery.serialized),
        )
        assertEquals(listOf(42L), store.snapshot().exercises.map { it.id })
    }

    @Test
    fun recoveryWriteFailureLeavesGuardedImportUntouched() = runTest {
        val persistence = MemoryPersistence()
        val store = newStore(persistence)
        val before = store.snapshot()
        val beforeSerialized = persistence.value
        val importedJson = store.exportJson()

        assertFailsWith<IllegalStateException> {
            store.importJsonIfUnchangedWithRecovery(
                serialized = importedJson,
                expectedSnapshot = before,
            ) {
                error("recovery adapter failed")
            }
        }

        assertEquals(before, store.snapshot())
        assertEquals(beforeSerialized, persistence.value)
    }

    @Test
    fun restoreWithRecoveryGuardsCurrentGraphAndSavesItBeforeReplacement() = runTest {
        val persistence = MemoryPersistence()
        val store = newStore(persistence)
        val original = store.snapshot()
        val imported = original.copy(
            exercises = listOf(exercise.copy(id = 42L, name = "Imported")),
        )
        val importedJson = kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = true
        }.encodeToString(imported)
        var importedRecovery: RecoverySnapshot? = null
        store.importJsonIfUnchangedWithRecovery(
            serialized = importedJson,
            expectedSnapshot = original,
        ) { importedRecovery = it }
        val candidate = requireNotNull(importedRecovery)

        var restoreRecovery: RecoverySnapshot? = null
        store.restoreWithRecovery(
            candidate = candidate,
            expectedCurrent = store.snapshot(),
        ) { restoreRecovery = it }

        assertEquals(imported, restoreRecovery?.payload)
        assertEquals(original, store.snapshot())
    }

    @Test
    fun staleRestoreRejectsBeforeRecoveryWrite() = runTest {
        val persistence = MemoryPersistence()
        val store = newStore(persistence)
        val original = store.snapshot()
        val candidate = RecoverySnapshot(
            payload = original,
            serialized = store.exportJson(),
        )
        store.saveExercise(exercise.copy(id = 0L, name = "Changed", isCustom = true))
        val before = store.snapshot()
        val writesBefore = persistence.writeCount
        var callbackCalled = false

        assertFailsWith<IllegalStateException> {
            store.restoreWithRecovery(
                candidate = candidate,
                expectedCurrent = original,
            ) {
                callbackCalled = true
            }
        }

        assertFalse(callbackCalled)
        assertEquals(before, store.snapshot())
        assertEquals(writesBefore, persistence.writeCount)
    }

    @Test
    fun malformedPersistedStateFailsClosed() {
        val exception = assertFailsWith<IllegalArgumentException> {
            newStore(MemoryPersistence("{not-json"))
        }

        assertTrue(exception.message.orEmpty().contains("Malformed workout state"))
    }

    @Test
    fun positivePlanExerciseIdMustBelongToEditedPlan() = runTest {
        val store = newStore(MemoryPersistence())
        val planId = store.saveTrainingPlan(
            BackupTrainingPlan(id = 0L, name = "Push", createdAt = 0L),
            listOf(
                BackupPlanExercise(
                    id = 0L,
                    planId = 0L,
                    exerciseId = exercise.id,
                    orderIndex = 0,
                    targetSets = 3,
                    targetReps = 8,
                    targetWeightKg = 60.0,
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            store.saveTrainingPlan(
                BackupTrainingPlan(id = planId, name = "Push", createdAt = 0L),
                listOf(
                    BackupPlanExercise(
                        id = 999L,
                        planId = planId,
                        exerciseId = exercise.id,
                        orderIndex = 0,
                        targetSets = 3,
                        targetReps = 8,
                        targetWeightKg = 60.0,
                    ),
                ),
            )
        }
    }

    @Test
    fun sessionCrudAndDeletionRebuildPersonalRecords() = runTest {
        var now = 1_000L
        val store = newStore(MemoryPersistence()) { now }
        val firstSession = store.startWorkout(name = "Heavy")
        store.addSet(
            BackupWorkoutSet(
                id = 0L,
                sessionId = firstSession,
                exerciseId = exercise.id,
                setNumber = 1,
                reps = 5,
                weightKg = 100.0,
                setType = NORMAL_SET_TYPE,
                completedAt = now,
            ),
        )
        now = 2_000L
        store.finishWorkout(firstSession)

        now = 3_000L
        val secondSession = store.startWorkout(name = "Light")
        store.addSet(
            BackupWorkoutSet(
                id = 0L,
                sessionId = secondSession,
                exerciseId = exercise.id,
                setNumber = 1,
                reps = 4,
                weightKg = 80.0,
                setType = NORMAL_SET_TYPE,
                completedAt = now,
            ),
        )
        now = 4_000L
        store.finishWorkout(secondSession)

        val storedSecond = store.snapshot().workoutSessions.single { it.id == secondSession }
        store.updateSession(storedSecond.copy(name = "Light edited", notes = "Keep form"))
        store.deleteSession(firstSession)

        val afterDelete = store.snapshot()
        assertEquals(1, afterDelete.workoutSessions.size)
        assertEquals("Light edited", afterDelete.workoutSessions.single().name)
        assertEquals(80.0, afterDelete.personalRecords.single { it.type == "MAX_WEIGHT" }.value)
        assertEquals(4.0, afterDelete.personalRecords.single { it.type == "MAX_REPS" }.value)
        assertEquals(320.0, afterDelete.personalRecords.single { it.type == "MAX_VOLUME" }.value)

        val cancelledSession = store.startWorkout(name = "Abandoned")
        store.addSet(
            BackupWorkoutSet(
                id = 0L,
                sessionId = cancelledSession,
                exerciseId = exercise.id,
                setNumber = 1,
                reps = 20,
                weightKg = 200.0,
                setType = NORMAL_SET_TYPE,
                completedAt = now,
            ),
        )
        store.cancelWorkout(cancelledSession)

        val afterCancel = store.snapshot()
        assertTrue(afterCancel.workoutSessions.none { it.id == cancelledSession })
        assertEquals(80.0, afterCancel.personalRecords.single { it.type == "MAX_WEIGHT" }.value)
        assertTrue(afterCancel.workoutSets.none { it.sessionId == cancelledSession })
    }

    @Test
    fun completedHistorySetUpdateRebuildsRecordsAndKeepsIdentityImmutable() = runTest {
        var now = 1_000L
        val store = newStore(MemoryPersistence()) { now }
        val sessionId = store.startWorkout(name = "History")
        val setId = store.addSet(
            BackupWorkoutSet(
                id = 0L,
                sessionId = sessionId,
                exerciseId = exercise.id,
                setNumber = 1,
                reps = 5,
                weightKg = 100.0,
                setType = NORMAL_SET_TYPE,
                completedAt = now,
            ),
        )
        now = 2_000L
        store.finishWorkout(sessionId)

        val stored = store.snapshot().workoutSets.single()
        store.updateSet(stored.copy(reps = 8, weightKg = 90.0, rpe = 8.0))

        val updated = store.snapshot().workoutSets.single()
        assertEquals(setId, updated.id)
        assertEquals(stored.sessionId, updated.sessionId)
        assertEquals(stored.exerciseId, updated.exerciseId)
        assertEquals(stored.setNumber, updated.setNumber)
        assertEquals(stored.setType, updated.setType)
        assertEquals(stored.completedAt, updated.completedAt)
        assertEquals(stored.planTargetSnapshotId, updated.planTargetSnapshotId)
        assertEquals(90.0, updated.weightKg)
        assertEquals(8, updated.reps)
        assertEquals(90.0, store.snapshot().personalRecords.single { it.type == "MAX_WEIGHT" }.value)
        assertEquals(8.0, store.snapshot().personalRecords.single { it.type == "MAX_REPS" }.value)

        val beforeIdentityFailure = store.snapshot()
        assertFailsWith<IllegalArgumentException> {
            store.updateSet(updated.copy(setType = DROP_SET_TYPE))
        }
        assertEquals(beforeIdentityFailure, store.snapshot())
    }

    @Test
    fun completedHistorySetEditStalesPendingProgressionWithoutChangingSourceSnapshot() = runTest {
        var now = 1_000L
        val store = newStore(MemoryPersistence()) { now }
        val config = linearProgressionConfig()
        val planId = store.saveTrainingPlan(
            BackupTrainingPlan(id = 0L, name = "Push", createdAt = 0L),
            listOf(
                BackupPlanExercise(
                    id = 0L,
                    planId = 0L,
                    exerciseId = exercise.id,
                    orderIndex = 0,
                    targetSets = 3,
                    targetReps = 8,
                    targetWeightKg = 100.0,
                    progression = config,
                ),
            ),
        )
        val sessionId = store.startWorkout(name = "Push", planId = planId)
        val targetId = store.snapshot().workoutPlanTargets.single().id
        repeat(3) { index ->
            store.addSet(
                BackupWorkoutSet(
                    id = 0L,
                    sessionId = sessionId,
                    exerciseId = exercise.id,
                    setNumber = index + 1,
                    reps = 8,
                    weightKg = 100.0,
                    setType = NORMAL_SET_TYPE,
                    completedAt = now + index,
                    planTargetSnapshotId = targetId,
                ),
            )
        }
        now = 2_000L
        store.finishWorkout(sessionId)
        ProgressionLifecycle(store, nowEpochMillis = { 3_000L })
            .generateForFinishedSession(sessionId)

        val before = store.snapshot().progressionSuggestions.single()
        assertEquals("PENDING", before.status)
        val sourceTarget = before.sourceTarget
        val countedSetIds = before.countedSetIds
        val editedSet = store.snapshot().workoutSets.first()
        now = 3_000L
        store.updateSet(editedSet.copy(weightKg = 95.0))

        val after = store.snapshot().progressionSuggestions.single()
        assertEquals("STALE", after.status)
        assertEquals(sourceTarget, after.sourceTarget)
        assertEquals(countedSetIds, after.countedSetIds)
        assertEquals(3_000L, after.decidedAtEpochMillis)
    }

    @Test
    fun deletingCompletedSetKeepsAcceptedProgressionHistoryAndRebuildsRecords() = runTest {
        var now = 1_000L
        val store = newStore(MemoryPersistence()) { now }
        val config = linearProgressionConfig()
        val planId = store.saveTrainingPlan(
            BackupTrainingPlan(id = 0L, name = "Push", createdAt = 0L),
            listOf(
                BackupPlanExercise(
                    id = 0L,
                    planId = 0L,
                    exerciseId = exercise.id,
                    orderIndex = 0,
                    targetSets = 3,
                    targetReps = 8,
                    targetWeightKg = 100.0,
                    progression = config,
                ),
            ),
        )
        val sessionId = store.startWorkout(name = "Push", planId = planId)
        val targetId = store.snapshot().workoutPlanTargets.single().id
        repeat(3) { index ->
            store.addSet(
                BackupWorkoutSet(
                    id = 0L,
                    sessionId = sessionId,
                    exerciseId = exercise.id,
                    setNumber = index + 1,
                    reps = 8,
                    weightKg = 100.0,
                    setType = NORMAL_SET_TYPE,
                    completedAt = now + index,
                    planTargetSnapshotId = targetId,
                ),
            )
        }
        now = 2_000L
        store.finishWorkout(sessionId)
        val lifecycle = ProgressionLifecycle(store, nowEpochMillis = { 3_000L })
        lifecycle.generateForFinishedSession(sessionId)
        val pending = store.snapshot().progressionSuggestions.single()
        val accepted = assertIs<ProgressionDecisionResult.Accepted>(
            lifecycle.acceptSuggestion(
                suggestionId = pending.id,
                finalTarget = requireNotNull(pending.suggestedTarget),
            ),
        )
        assertEquals(setOf(pending.id), accepted.suggestionIds)
        val acceptedBeforeDelete = store.snapshot().progressionSuggestions.single()
        val sourceTarget = acceptedBeforeDelete.sourceTarget
        val evidenceIds = acceptedBeforeDelete.countedSetIds
        val deletedSet = store.snapshot().workoutSets.first()

        store.deleteSet(deletedSet.id)

        val after = store.snapshot()
        val acceptedAfterDelete = after.progressionSuggestions.single()
        assertEquals("ACCEPTED", acceptedAfterDelete.status)
        assertEquals(sourceTarget, acceptedAfterDelete.sourceTarget)
        assertEquals(evidenceIds, acceptedAfterDelete.countedSetIds)
        assertEquals(2, after.workoutSets.size)
        assertEquals(100.0, after.personalRecords.single { it.type == "MAX_WEIGHT" }.value)
        assertEquals(8.0, after.personalRecords.single { it.type == "MAX_REPS" }.value)
        assertEquals(102.5, after.planExercises.single().targetWeightKg)
    }

    @Test
    fun metaPlanSkipUsesCurrentRotationAndRejectsActiveWorkout() = runTest {
        var now = 1_000L
        val store = newStore(MemoryPersistence()) { now }
        val firstPlan = store.saveTrainingPlan(
            BackupTrainingPlan(id = 0L, name = "Push", createdAt = 0L),
            emptyList(),
        )
        val secondPlan = store.saveTrainingPlan(
            BackupTrainingPlan(id = 0L, name = "Pull", createdAt = 0L),
            emptyList(),
        )
        val metaPlan = store.saveMetaTrainingPlan(
            BackupMetaTrainingPlan(id = 0L, name = "Upper", createdAt = 0L),
            listOf(
                BackupMetaPlanItem(id = 0L, metaPlanId = 0L, trainingPlanId = firstPlan, orderIndex = 0),
                BackupMetaPlanItem(id = 0L, metaPlanId = 0L, trainingPlanId = secondPlan, orderIndex = 1),
            ),
        )

        // A fresh rotation starts at the first item; a stale dashboard expecting the second item
        // must not create a row.
        assertFalse(store.skipMetaPlan(metaPlan, secondPlan))
        assertTrue(store.snapshot().metaPlanSkips.isEmpty())

        assertTrue(store.skipMetaPlan(metaPlan, firstPlan))
        assertEquals(
            listOf(firstPlan),
            store.snapshot().metaPlanSkips.map { it.trainingPlanId },
        )
        assertEquals(now, store.snapshot().metaPlanSkips.single().skippedAt)

        now = 2_000L
        // The first skip makes the second plan current; using the old first-plan expectation is
        // stale and is rejected atomically.
        assertFalse(store.skipMetaPlan(metaPlan, firstPlan))
        assertTrue(store.skipMetaPlan(metaPlan, secondPlan))

        val activeSession = store.startWorkout(
            name = "Active",
            planId = firstPlan,
            metaPlanId = metaPlan,
        )
        assertFalse(store.skipMetaPlan(metaPlan, firstPlan))
        assertEquals(2, store.snapshot().metaPlanSkips.size)
        store.cancelWorkout(activeSession)
    }

    @Test
    fun importKeepsPayloadExerciseCatalogExact() = runTest {
        val store = newStore(MemoryPersistence())
        val imported = store.snapshot().copy(
            exercises = listOf(exercise.copy(id = 42L, name = "Imported")),
        )
        val importedJson = kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = true
        }.encodeToString(imported)

        val result = store.importJson(importedJson)

        assertEquals(listOf(42L), result.exercises.map { it.id })
        assertNotEquals(exercise.id, result.exercises.single().id)
    }

    @Test
    fun previewDecoderMatchesCanonicalImportState() {
        val store = newStore(MemoryPersistence())

        val preview = SharedStateStore.decodeAndUpgradeForPreview(store.exportJson())

        assertEquals(store.snapshot(), preview)
    }

    @Test
    fun upsertCheckInRejectsEntirelyEmptyCheckInAndDeleteClearsTheDay() = runTest {
        val store = newStore(MemoryPersistence())
        val date = LocalDate(2026, 9, 11)

        store.upsertCheckIn(ReadinessCheckIn(localDate = date, energy = 3))

        assertFailsWith<IllegalArgumentException> {
            store.upsertCheckIn(ReadinessCheckIn(localDate = date))
        }
        // The rejected write must not have replaced the answered day.
        assertEquals(3, store.snapshot().readinessData.checkIns.single().energy)

        store.deleteCheckIn(date)

        assertTrue(store.snapshot().readinessData.checkIns.isEmpty())
    }

    @Test
    fun individualTargetsRoundTripAndPartialFinishPreservesOpenSlots() = runTest {
        val persistence = MemoryPersistence()
        val store = newStore(persistence)
        val slots = listOf(
            com.thrym.shared.plans.PlannedSet("WARMUP", 10, 20.0),
            com.thrym.shared.plans.PlannedSet("NORMAL", 8, 60.0),
            com.thrym.shared.plans.PlannedSet("BACKOFF", 10, 55.0))
        val planId = store.saveTrainingPlan(BackupTrainingPlan(0, "Push", 0), listOf(
            BackupPlanExercise(0, 0, exercise.id, 0, targetSets = 2, targetReps = 8, targetWeightKg = 60.0, setTargets = slots)))
        val sessionId = store.startWorkout("Push", planId)
        val target = store.snapshot().workoutPlanTargets.single()
        assertEquals(slots, target.setTargets)
        store.addSet(BackupWorkoutSet(0, sessionId, exercise.id, 1, 9, 20.0, setType = "WARMUP", completedAt = 1000, planTargetSnapshotId = target.id))
        store.addSet(BackupWorkoutSet(0, sessionId, exercise.id, 2, 7, 60.0, setType = "NORMAL", completedAt = 1000, planTargetSnapshotId = target.id))
        store.finishWorkout(sessionId)
        assertEquals(slots, store.snapshot().planExercises.single().setTargets, "Finishing must never modify a plan")
        store.applyPerformedSetTargets(sessionId)
        val expected = listOf(slots[0].copy(reps = 9), slots[1].copy(reps = 7), slots[2])
        assertEquals(expected, store.snapshot().planExercises.single().setTargets)
        assertEquals(slots, store.snapshot().workoutPlanTargets.single().setTargets, "Snapshots remain immutable")
        assertEquals(store.snapshot(), newStore(MemoryPersistence(persistence.value)).snapshot())
    }

    @Test
    fun individualTargetsRejectStalePlanAndActiveSession() = runTest {
        val store = newStore(MemoryPersistence())
        val planId = store.saveTrainingPlan(BackupTrainingPlan(0, "Push", 0), listOf(
            BackupPlanExercise(0, 0, exercise.id, 0, targetSets = 3, targetReps = 10, targetWeightKg = 60.0)))
        val sessionId = store.startWorkout("Push", planId)
        val target = store.snapshot().workoutPlanTargets.single()
        store.addSet(BackupWorkoutSet(0, sessionId, exercise.id, 1, 9, 60.0, setType = "NORMAL", completedAt = 1000, planTargetSnapshotId = target.id))
        assertFailsWith<IllegalStateException> { store.applyPerformedSetTargets(sessionId) }
        store.finishWorkout(sessionId)
        val plan = store.snapshot().trainingPlans.single()
        val changed = store.snapshot().planExercises.single().copy(targetWeightKg = 70.0)
        store.saveTrainingPlan(plan, listOf(changed))
        assertFailsWith<IllegalStateException> { store.applyPerformedSetTargets(sessionId) }
        assertEquals(70.0, store.snapshot().planExercises.single().targetWeightKg)
    }

    @Test
    fun duplicatePersonalRecordIdsAreRepairedWhileLoading() = runTest {
        val seeded = newStore(MemoryPersistence())
        val poisoned = seeded.snapshot().copy(
            personalRecords = listOf(
                BackupPersonalRecord(id = 6L, exerciseId = exercise.id, type = "MAX_WEIGHT", value = 80.0, achievedAt = 1_000L),
                BackupPersonalRecord(id = 6L, exerciseId = exercise.id, type = "MAX_REPS", value = 8.0, achievedAt = 1_000L),
                BackupPersonalRecord(id = 9L, exerciseId = exercise.id, type = "MAX_E1RM", value = 100.0, achievedAt = 1_000L),
                BackupPersonalRecord(id = 9L, exerciseId = exercise.id, type = "MAX_VOLUME", value = 640.0, achievedAt = 1_000L),
            ),
        )
        val persistence = MemoryPersistence(testJson.encodeToString(poisoned))

        val store = newStore(persistence)

        val records = store.snapshot().personalRecords
        assertEquals(4, records.size)
        assertEquals(4, records.map { it.id }.toSet().size)
        assertEquals(listOf(80.0, 8.0, 100.0, 640.0), records.map { it.value })
        assertEquals(1, persistence.writeCount)
        // The repaired document is writable again, so a workout can be started and finished.
        val sessionId = store.startWorkout(name = "Session")
        assertTrue(sessionId > 0L)
    }

    @Test
    fun rebuildingRecordsAcrossExercisesKeepsIdsUnique() = runTest {
        var now = 1_000L
        val second = exercise.copy(id = 2L, name = "Kurzhantelrudern")
        val store = SharedStateStore(
            persistence = MemoryPersistence(),
            seedExercises = listOf(exercise, second),
            nowEpochMillis = { now },
        )
        val sessionId = store.startWorkout(name = "Gym")
        listOf(exercise.id to 100.0, second.id to 60.0).forEachIndexed { index, (exerciseId, weight) ->
            store.addSet(
                BackupWorkoutSet(
                    id = 0L,
                    sessionId = sessionId,
                    exerciseId = exerciseId,
                    setNumber = 1,
                    reps = 5,
                    weightKg = weight,
                    setType = NORMAL_SET_TYPE,
                    completedAt = now + index,
                ),
            )
        }
        now = 2_000L
        store.finishWorkout(sessionId)

        val records = store.snapshot().personalRecords
        assertEquals(8, records.size)
        assertEquals(8, records.map { it.id }.toSet().size)

        store.deleteSession(sessionId)
        val afterDelete = store.snapshot().personalRecords
        assertEquals(afterDelete.size, afterDelete.map { it.id }.toSet().size)
    }

    private val testJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    private fun newStore(
        persistence: MemoryPersistence,
        now: () -> Long = { 1_000L },
    ) = SharedStateStore(
        persistence = persistence,
        seedExercises = listOf(exercise),
        nowEpochMillis = now,
    )

    private fun linearProgressionConfig() = BackupProgressionConfig(
        scheme = "LINEAR",
        incrementValue = 2.5,
        incrementUnit = "METRIC",
        incrementKg = 2.5,
        stallThreshold = 2,
        backoffPercent = 10.0,
        ruleRevision = 1,
    )

    private class MemoryPersistence(
        var value: String? = null,
        var failWrites: Boolean = false,
    ) : SharedStatePersistence {
        var writeCount: Int = 0
            private set

        override fun read(): String? = value

        override fun writeAtomically(serialized: String) {
            check(!failWrites) { "injected write failure" }
            value = serialized
            writeCount += 1
        }
    }
}
