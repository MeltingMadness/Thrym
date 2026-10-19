package com.thrym.app.data.repository

import android.net.Uri
import com.thrym.app.data.backup.BackupConcurrentModificationException
import com.thrym.app.data.backup.BackupDocumentIo
import com.thrym.app.data.backup.BackupHashMismatchException
import com.thrym.app.data.backup.BackupSchemaTooNewException
import com.thrym.app.data.backup.RecoveryBackupStore
import com.thrym.app.data.backup.sha256Hex
import com.thrym.app.data.db.TransactionRunner
import com.thrym.app.data.local.dao.ExerciseDao
import com.thrym.app.data.local.dao.MetaTrainingPlanDao
import com.thrym.app.data.local.dao.PersonalRecordDao
import com.thrym.app.data.local.dao.ProgressionDao
import com.thrym.app.data.local.dao.ReadinessDataDao
import com.thrym.app.data.local.dao.TrainingPlanDao
import com.thrym.app.data.local.dao.WorkoutSessionDao
import com.thrym.app.data.local.dao.WorkoutSetDao
import com.thrym.app.data.local.entity.ExerciseEntity
import com.thrym.app.data.local.entity.MetaPlanSkipEntity
import com.thrym.app.data.local.entity.PersonalRecordEntity
import com.thrym.app.data.local.entity.PlanExerciseEntity
import com.thrym.app.data.local.entity.ProgressionConfigColumns
import com.thrym.app.data.local.entity.ProgressionSuggestionEntity
import com.thrym.app.data.local.entity.ProgressionTargetColumns
import com.thrym.app.data.local.entity.ReadinessDataEntity
import com.thrym.app.data.local.entity.TrainingPlanEntity
import com.thrym.app.data.local.entity.WorkoutPlanTargetEntity
import com.thrym.app.data.local.entity.WorkoutSessionEntity
import com.thrym.app.data.local.entity.WorkoutSetEntity
import com.thrym.app.domain.repository.RecoveryBackup
import com.thrym.app.domain.util.BuildInfo
import com.thrym.shared.backup.BackupExercise
import com.thrym.shared.backup.BackupMetaPlanItem
import com.thrym.shared.backup.BackupMetaPlanSkip
import com.thrym.shared.backup.BackupMetaTrainingPlan
import com.thrym.shared.backup.BackupPayloadV1
import com.thrym.shared.backup.BackupPersonalRecord
import com.thrym.shared.backup.BackupPlanExercise
import com.thrym.shared.backup.BackupProgressionConfig
import com.thrym.shared.backup.BackupProgressionSuggestion
import com.thrym.shared.backup.BackupProgressionTarget
import com.thrym.shared.backup.BackupTrainingPlan
import com.thrym.shared.backup.BackupWorkoutPlanTarget
import com.thrym.shared.backup.BackupWorkoutSession
import com.thrym.shared.backup.BackupWorkoutSet
import com.thrym.shared.model.MuscleGroup
import com.thrym.shared.readinessdata.ReadinessCheckIn
import com.thrym.shared.readinessdata.ReadinessData
import com.thrym.shared.readinessdata.ReadinessDataCodec
import com.thrym.shared.readinessdata.SetIntention
import com.thrym.shared.readinessdata.SetIntentionRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class BackupRepositoryImplTest {

    @Test
    fun `export serializes one snapshot transaction before writing the document`() {
        val harness = Harness()
        val exercise = ExerciseEntity(
            id = 1L,
            name = "Bankdruecken",
            primaryMuscleGroup = "BRUST",
            secondaryMuscleGroups = "TRIZEPS",
            category = "LANGHANTEL",
            isCustom = false,
            notes = "notiz",
            isArchived = false
        )
        val session = WorkoutSessionEntity(
            id = 10L,
            startTime = 1000L,
            endTime = 2000L,
            durationSeconds = 1L,
            name = "Push",
            notes = "notes",
            planId = 5L,
            metaPlanId = null
        )
        val sets = progressionSetEntities()
        val skip = MetaPlanSkipEntity(
            id = 30L,
            metaPlanId = 40L,
            trainingPlanId = 50L,
            skippedAt = 1500L
        )
        harness.stubSnapshotReads(
            exercise = exercise,
            session = session,
            sets = sets,
            trainingPlan = progressionPlanEntity(),
            planExercise = progressionPlanExerciseEntity(),
            target = progressionTargetEntity(),
            suggestion = progressionSuggestionEntity(),
            skip = skip
        )

        runBlocking { harness.repository.exportBackup(URI) }

        val written = harness.documentIo.writtenBytes ?: throw AssertionError("no document written")
        val payload = json.decodeFromString(BackupPayloadV1.serializer(), written.decodeToString())
        assertEquals(15, payload.schemaVersion)
        assertEquals(8.5, payload.workoutSets.single().rpe)
        assertEquals(30L, payload.workoutSets.single().planTargetSnapshotId)
        assertEquals("notiz", payload.exercises.single().notes)
        assertEquals("LINEAR", payload.planExercises.single().progression.scheme)
        assertEquals(listOf(20L), payload.progressionSuggestions.single().countedSetIds)
        assertEquals(
            listOf("actualWorkSets", "targetSets"),
            payload.progressionSuggestions.single().reasonArguments.keys.toList()
        )
        assertEquals(1, payload.workoutPlanTargets.size)
        assertEquals(1, payload.progressionSuggestions.size)
        assertEquals(
            BackupMetaPlanSkip(id = 30L, metaPlanId = 40L, trainingPlanId = 50L, skippedAt = 1500L),
            payload.metaPlanSkips.single()
        )
        val txnEndIndex = harness.order.indexOf("txn-end")
        val writeIndex = harness.order.indexOf("write")
        assertTrue(
            "transaction must complete before the document write, saw $harness.order",
            txnEndIndex in 0 until writeIndex
        )
        assertEquals(1, harness.documentIo.writes)
    }

    @Test
    fun `verified export failure propagates without writing a document`() {
        val harness = Harness()
        harness.stubSnapshotReads()
        harness.documentIo.failWrite = IOException("disk full")

        assertThrows(IOException::class.java) {
            runBlocking { harness.repository.exportBackup(URI) }
        }

        assertNull(harness.documentIo.writtenBytes)
        assertTrue(harness.recoveryStore.saved.isEmpty())
    }

    @Test
    fun `preview of a backup from a newer schema fails with a clear error instead of a serialization exception`() {
        val harness = Harness()
        val newerJson = """
            {
              "formatVersion": 1,
              "schemaVersion": 16,
              "appVersion": "2.0",
              "exportedAtEpochMillis": 1000,
              "exercises": [
                {
                  "id": 1,
                  "name": "Bankdruecken",
                  "primaryMuscleGroup": "BRUST",
                  "secondaryMuscleGroups": "TRIZEPS",
                  "category": "LANGHANTEL",
                  "isCustom": false,
                  "notes": "",
                  "isArchived": false,
                  "someFutureField": true
                }
              ],
              "workoutSessions": [],
              "workoutSets": [],
              "trainingPlans": [],
              "planExercises": [],
              "personalRecords": [],
              "metaTrainingPlans": [],
              "metaPlanItems": [],
              "metaPlanSkips": [],
              "workoutPlanTargets": [],
              "progressionSuggestions": [],
              "anotherFutureField": "value"
            }
        """.trimIndent()
        harness.documentIo.bytes = newerJson.encodeToByteArray()

        val error = assertThrows(BackupSchemaTooNewException::class.java) {
            runBlocking { harness.repository.previewImport(URI) }
        }

        assertEquals(16, error.backupSchemaVersion)
        assertEquals(15, error.appSchemaVersion)
        assertTrue(error.message.orEmpty().contains("newer than this app", ignoreCase = true))
        assertTrue(harness.transactionRunner.events.isEmpty())
        coVerify(exactly = 0) { harness.exerciseDao.getAllExercisesList() }
        coVerify(exactly = 0) { harness.exerciseDao.deleteAll() }
    }

    @Test
    fun `legacy document without readiness or deload fields decodes to defaults`() {
        val legacy = """
            {
              "formatVersion": 1,
              "schemaVersion": 12,
              "appVersion": "1.0",
              "exportedAtEpochMillis": 42,
              "exercises": [],
              "workoutSessions": [
                {
                  "id": 10,
                  "startTime": 1000,
                  "endTime": 2000,
                  "durationSeconds": 1,
                  "name": "Push",
                  "notes": ""
                }
              ],
              "workoutSets": [],
              "trainingPlans": [],
              "planExercises": [],
              "personalRecords": [],
              "metaTrainingPlans": [],
              "metaPlanItems": [],
              "metaPlanSkips": [],
              "workoutPlanTargets": [],
              "progressionSuggestions": []
            }
        """.trimIndent()

        val decoded = json.decodeFromString(BackupPayloadV1.serializer(), legacy)

        // A pre-readiness backup has no key and must resolve to "no data" rather than a
        // guessed check-in, and a pre-deload session stays unknown instead of "not a deload".
        assertEquals(ReadinessData(), decoded.readinessData)
        assertNull(decoded.workoutSessions.single().isDeload)
    }

    @Test
    fun `current readiness document survives an export and import round trip`() {
        val stored = ReadinessData(
            checkIns = listOf(
                ReadinessCheckIn(
                    localDate = LocalDate(2026, 9, 11),
                    sleepQuality = 4,
                    energy = 3,
                    muscleSoreness = mapOf(MuscleGroup.BEINE to 5),
                    recordedAtEpochMillis = 1_757_500_000_000
                )
            ),
            setIntentions = listOf(
                SetIntentionRecord(
                    setId = 20L,
                    intention = SetIntention.PLANNED_FAILURE,
                    note = "geplant"
                )
            )
        )

        val exporter = Harness()
        exporter.readinessPayload = ReadinessDataCodec.encode(stored)
        exporter.stubSnapshotReads(
            exercise = ExerciseEntity(
                id = 1L,
                name = "Bankdruecken",
                primaryMuscleGroup = "BRUST",
                secondaryMuscleGroups = "TRIZEPS",
                category = "LANGHANTEL",
                isCustom = false,
                notes = "",
                isArchived = false
            ),
            session = WorkoutSessionEntity(
                id = 10L,
                startTime = 1000L,
                endTime = 2000L,
                durationSeconds = 1L,
                name = "Push",
                notes = "",
                planId = null,
                metaPlanId = null
            ),
            sets = listOf(
                WorkoutSetEntity(
                    id = 20L,
                    sessionId = 10L,
                    exerciseId = 1L,
                    setNumber = 1,
                    reps = 8,
                    weightKg = 80.0,
                    setType = "NORMAL",
                    completedAt = 1200L
                )
            )
        )

        runBlocking { exporter.repository.exportBackup(URI) }
        val document = exporter.documentIo.writtenBytes ?: throw AssertionError("no document written")
        val exported = json.decodeFromString(BackupPayloadV1.serializer(), document.decodeToString())
        assertEquals(stored, exported.readinessData)

        val importer = Harness()
        importer.stubSnapshotReads()
        importer.stubMutations()
        importer.documentIo.bytes = document

        runBlocking { importer.repository.importBackup(URI, document.sha256Hex()) }

        val restored = ReadinessDataCodec.decode(
            importer.readinessPayload ?: throw AssertionError("no readiness row written")
        )
        assertEquals(stored, restored)
    }

    @Test
    fun `preview reads and validates without touching the database`() {
        val harness = Harness()
        val payload = validPayload()
        harness.documentIo.bytes = json.encodeToString(BackupPayloadV1.serializer(), payload).encodeToByteArray()

        val preview = runBlocking { harness.repository.previewImport(URI) }

        assertTrue(preview.isValid)
        assertEquals(
            harness.documentIo.bytes.sha256Hex(),
            preview.sha256
        )
        assertEquals(1, preview.counts.exercises)
        assertEquals(1, preview.counts.workoutSessions)
        assertEquals(1, preview.counts.workoutSets)
        assertTrue(harness.transactionRunner.events.isEmpty())
        coVerify(exactly = 0) { harness.exerciseDao.getAllExercisesList() }
        coVerify(exactly = 0) { harness.exerciseDao.deleteAll() }
    }

    @Test
    fun `preview and verified import preserve progression counts mapping and foreign key order`() {
        val harness = Harness()
        harness.stubSnapshotReads()
        harness.stubMutations()
        val payload = validProgressionPayload()
        harness.documentIo.bytes = json.encodeToString(
            BackupPayloadV1.serializer(),
            payload
        ).encodeToByteArray()

        val preview = runBlocking { harness.repository.previewImport(URI) }

        assertTrue(preview.isValid)
        assertEquals(1, preview.counts.workoutPlanTargets)
        assertEquals(1, preview.counts.progressionSuggestions)

        runBlocking { harness.repository.importBackup(URI, preview.sha256) }

        coVerifyOrder {
            harness.progressionDao.deleteAllSuggestions()
            harness.workoutSetDao.deleteAll()
            harness.progressionDao.deleteAllTargets()
            harness.progressionDao.replaceAllTargets(any())
            harness.workoutSetDao.replaceAll(any())
            harness.progressionDao.replaceAllSuggestions(any())
        }
        coVerify(exactly = 1) {
            harness.progressionDao.replaceAllSuggestions(
                match { suggestions ->
                    suggestions.single().reasonArgumentsJson ==
                        "{\"actualWorkSets\":3.0,\"targetSets\":3.0}" &&
                        suggestions.single().countedSetIdsJson == "[20,21,22]"
                }
            )
        }
    }

    @Test
    fun `progression validation failure performs no progression mutation`() {
        val harness = Harness()
        harness.stubSnapshotReads()
        harness.stubMutations()
        val valid = validProgressionPayload()
        val invalid = valid.copy(
            progressionSuggestions = valid.progressionSuggestions.map {
                it.copy(countedSetIds = listOf(999L))
            }
        )
        harness.documentIo.bytes = json.encodeToString(
            BackupPayloadV1.serializer(),
            invalid
        ).encodeToByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                harness.repository.importBackup(URI, harness.documentIo.bytes.sha256Hex())
            }
        }

        coVerify(exactly = 0) { harness.progressionDao.deleteAllSuggestions() }
        coVerify(exactly = 0) { harness.progressionDao.deleteAllTargets() }
        coVerify(exactly = 0) { harness.progressionDao.replaceAllTargets(any()) }
        coVerify(exactly = 0) { harness.progressionDao.replaceAllSuggestions(any()) }
    }

    @Test
    fun `import saves recovery snapshot then runs guard before deletes in one transaction`() {
        val harness = Harness()
        harness.stubSnapshotReads()
        harness.stubMutations()
        harness.documentIo.bytes = json.encodeToString(BackupPayloadV1.serializer(), validPayload()).encodeToByteArray()
        val expectedSha256 = harness.documentIo.bytes.sha256Hex()

        runBlocking { harness.repository.importBackup(URI, expectedSha256) }

        assertEquals(1, harness.recoveryStore.saved.size)
        val events = harness.transactionRunner.events
        val secondTxnStart = events.lastIndexOf("txn-start")
        val secondTxnEnd = events.lastIndexOf("txn-end")
        val guardRead = events.subList(secondTxnStart, secondTxnEnd).indexOf("read-exercises")
        val delete = events.subList(secondTxnStart, secondTxnEnd).indexOf("delete-records")
        assertTrue("guard read must precede deletes", guardRead in 0 until delete)
        coVerify(exactly = 1) { harness.exerciseDao.deleteAll() }
        coVerify(exactly = 1) { harness.exerciseDao.replaceAll(any()) }
    }

    @Test
    fun `import deletes skips before referenced plans and inserts them after plans exist`() {
        val harness = Harness()
        harness.stubSnapshotReads()
        harness.stubMutations()
        val payload = validPayload().copy(
            trainingPlans = listOf(
                BackupTrainingPlan(id = 5L, name = "Push", createdAt = 1000L)
            ),
            metaTrainingPlans = listOf(
                BackupMetaTrainingPlan(
                    id = 40L,
                    name = "Meta",
                    createdAt = 1000L
                )
            ),
            metaPlanItems = listOf(
                BackupMetaPlanItem(
                    id = 41L,
                    metaPlanId = 40L,
                    trainingPlanId = 5L,
                    orderIndex = 0
                )
            ),
            metaPlanSkips = listOf(
                BackupMetaPlanSkip(id = 42L, metaPlanId = 40L, trainingPlanId = 5L, skippedAt = 1500L)
            )
        )
        harness.documentIo.bytes = json.encodeToString(BackupPayloadV1.serializer(), payload).encodeToByteArray()

        runBlocking { harness.repository.importBackup(URI, harness.documentIo.bytes.sha256Hex()) }

        val events = harness.transactionRunner.events
        val deleteSkips = events.indexOf("delete-meta-skips")
        val deleteItems = events.indexOf("delete-meta-items")
        val deleteMetaPlans = events.indexOf("delete-meta-plans")
        val deletePlans = events.indexOf("delete-plans")
        assertTrue("skips must be deleted before meta plan items", deleteSkips in 0 until deleteItems)
        assertTrue("items must be deleted before meta plans", deleteItems in 0 until deleteMetaPlans)
        assertTrue("meta plans must be deleted before training plans", deleteMetaPlans in 0 until deletePlans)

        val insertPlans = events.indexOf("insert-plans")
        val insertMetaPlans = events.indexOf("insert-meta-plans")
        val insertSkips = events.indexOf("insert-meta-skips")
        assertTrue("plans must be inserted before skips", insertPlans in 0 until insertSkips)
        assertTrue("meta plans must be inserted before skips", insertMetaPlans in 0 until insertSkips)
        coVerify(exactly = 1) { harness.metaTrainingPlanDao.deleteAllMetaPlanSkips() }
        coVerify(exactly = 1) { harness.metaTrainingPlanDao.replaceAllMetaPlanSkips(any()) }
    }

    @Test
    fun `import repairs only e1RM records that older iOS builds stored by plain Epley`() {
        val harness = Harness()
        harness.stubSnapshotReads()
        harness.stubMutations()
        val written = slot<List<PersonalRecordEntity>>()
        coEvery { harness.personalRecordDao.replaceAll(capture(written)) } answers {
            harness.events += "insert-records"
        }
        // Both exercises have 100 kg × 1 and 90 kg × 4. Older iOS builds rated the single at
        // 100 × (1 + 1/30) = 103.3 kg. Today it counts as 100 kg, so 90 × 4 = 102 kg is the best.
        val base = validPayload()
        val squat = base.exercises.single().copy(id = 2L, name = "Kniebeuge", primaryMuscleGroup = "BEINE")
        val sets = listOf(1L, 2L).flatMap { exerciseId ->
            listOf(1 to 100.0, 4 to 90.0).mapIndexed { index, (reps, weightKg) ->
                BackupWorkoutSet(
                    id = exerciseId * 10L + index,
                    sessionId = 10L,
                    exerciseId = exerciseId,
                    setNumber = index + 1,
                    reps = reps,
                    weightKg = weightKg,
                    isWarmup = false,
                    completedAt = 1200L + index * 300L
                )
            }
        }
        val legacyE1rm = PersonalRecordEntity(
            id = 30L,
            exerciseId = 1L,
            type = "MAX_E1RM",
            value = 100.0 * (1.0 + 1.0 / 30.0),
            achievedAt = 1200L
        )
        val maxWeight = PersonalRecordEntity(id = 31L, exerciseId = 1L, type = "MAX_WEIGHT", value = 100.0, achievedAt = 1200L)
        // Not a value of the old rule, e.g. from a set deleted later: it stays as it is.
        val otherE1rm = PersonalRecordEntity(id = 32L, exerciseId = 2L, type = "MAX_E1RM", value = 110.0, achievedAt = 1200L)
        val payload = base.copy(
            exercises = base.exercises + squat,
            workoutSets = sets,
            personalRecords = listOf(legacyE1rm, maxWeight, otherE1rm).map {
                BackupPersonalRecord(
                    id = it.id,
                    exerciseId = it.exerciseId,
                    type = it.type,
                    value = it.value,
                    achievedAt = it.achievedAt
                )
            }
        )
        harness.documentIo.bytes = json.encodeToString(BackupPayloadV1.serializer(), payload).encodeToByteArray()

        runBlocking { harness.repository.importBackup(URI, harness.documentIo.bytes.sha256Hex()) }

        val records = written.captured.associateBy { it.id }
        assertEquals(setOf(30L, 31L, 32L), records.keys)
        val repaired = records.getValue(30L)
        assertEquals(102.0, repaired.value, 1e-9)
        assertEquals(1500L, repaired.achievedAt)
        assertEquals(legacyE1rm.copy(value = repaired.value, achievedAt = 1500L), repaired)
        assertEquals(maxWeight, records.getValue(31L))
        assertEquals(otherE1rm, records.getValue(32L))
    }

    @Test
    fun `recovery snapshot failure produces zero deletes`() {
        val harness = Harness()
        harness.stubSnapshotReads()
        harness.stubMutations()
        harness.documentIo.bytes = json.encodeToString(BackupPayloadV1.serializer(), validPayload()).encodeToByteArray()
        harness.recoveryStore.failSave = IOException("no space")

        assertThrows(IOException::class.java) {
            runBlocking {
                harness.repository.importBackup(URI, harness.documentIo.bytes.sha256Hex())
            }
        }

        assertTrue(harness.transactionRunner.events.none { it.startsWith("delete-") })
        coVerify(exactly = 0) { harness.exerciseDao.deleteAll() }
        coVerify(exactly = 0) { harness.progressionDao.deleteAllSuggestions() }
        coVerify(exactly = 0) { harness.progressionDao.deleteAllTargets() }
        coVerify(exactly = 0) { harness.progressionDao.replaceAllTargets(any()) }
        coVerify(exactly = 0) { harness.progressionDao.replaceAllSuggestions(any()) }
        coVerify(exactly = 0) { harness.exerciseDao.replaceAll(any()) }
    }

    @Test
    fun `source hash change between preview and import is rejected without mutation`() {
        val harness = Harness()
        harness.stubSnapshotReads()
        harness.stubMutations()
        val original = json.encodeToString(BackupPayloadV1.serializer(), validPayload()).encodeToByteArray()
        harness.documentIo.bytes = original
        val preview = runBlocking { harness.repository.previewImport(URI) }

        harness.documentIo.bytes = json.encodeToString(
            BackupPayloadV1.serializer(),
            validPayload().copy(exportedAtEpochMillis = 999L)
        ).encodeToByteArray()

        val error = assertThrows(BackupHashMismatchException::class.java) {
            runBlocking { harness.repository.importBackup(URI, preview.sha256) }
        }

        assertEquals(preview.sha256, error.expectedSha256)
        assertTrue(harness.recoveryStore.saved.isEmpty())
        assertTrue(harness.transactionRunner.events.isEmpty())
        coVerify(exactly = 0) { harness.exerciseDao.deleteAll() }
        coVerify(exactly = 0) { harness.progressionDao.deleteAllSuggestions() }
        coVerify(exactly = 0) { harness.progressionDao.deleteAllTargets() }
        coVerify(exactly = 0) { harness.progressionDao.replaceAllTargets(any()) }
        coVerify(exactly = 0) { harness.progressionDao.replaceAllSuggestions(any()) }
    }

    @Test
    fun `repository serializes concurrent import calls`() {
        val harness = Harness(slowDocumentIo = true)
        harness.stubSnapshotReads()
        harness.documentIo.bytes = json.encodeToString(BackupPayloadV1.serializer(), validPayload()).encodeToByteArray()
        val expectedSha256 = harness.documentIo.bytes.sha256Hex()

        runBlocking {
            val jobs = List(5) {
                launch {
                    runCatching {
                        harness.repository.importBackup(URI, expectedSha256)
                    }
                }
            }
            jobs.forEach { it.join() }
        }

        assertEquals(1, harness.documentIo.maxActive.get())
    }

    @Test
    fun `restore returns null when no recovery snapshot exists`() {
        val harness = Harness()

        val result = runBlocking { harness.repository.restoreLatestRecovery() }

        assertNull(result)
    }

    @Test
    fun `restore saves a fresh recovery snapshot before replacing current state`() {
        val harness = Harness()
        harness.stubSnapshotReads()
        harness.stubMutations()
        val recoveryBytes = json.encodeToString(
            BackupPayloadV1.serializer(),
            validPayload()
        ).encodeToByteArray()
        harness.recoveryStore.saved += recoveryBytes
        harness.recoveryStore.latestMeta = RecoveryBackup(
            timestampMillis = 1L,
            sha256 = recoveryBytes.sha256Hex(),
            sizeBytes = recoveryBytes.size.toLong()
        )

        val restored = runBlocking { harness.repository.restoreLatestRecovery() }

        assertTrue(restored != null)
        assertEquals(2, harness.recoveryStore.saved.size)
        coVerify(exactly = 1) { harness.exerciseDao.deleteAll() }
        coVerify(exactly = 2) { harness.exerciseDao.getAllExercisesList() }
    }

    @Test
    fun `restore recovers exact edited progression state after importing another graph`() {
        val harness = Harness()
        val recoveryTarget = progressionTargetEntity()
        val recoverySuggestion = acceptedEditedProgressionSuggestionEntity()
        val recoverySets = progressionSetEntities()
        harness.stubSnapshotReads(
            exercise = ExerciseEntity(
                id = 1L,
                name = "Bankdruecken",
                primaryMuscleGroup = "BRUST",
                secondaryMuscleGroups = "TRIZEPS",
                category = "LANGHANTEL",
                isCustom = false
            ),
            session = WorkoutSessionEntity(
                id = 10L,
                startTime = 1000L,
                endTime = 2000L,
                durationSeconds = 1L,
                name = "Push",
                notes = "",
                planId = 5L,
                metaPlanId = null
            ),
            sets = recoverySets,
            trainingPlan = progressionPlanEntity(),
            planExercise = progressionPlanExerciseEntity(),
            target = recoveryTarget,
            suggestion = recoverySuggestion
        )
        harness.stubMutations()
        harness.stubMutableProgressionState(
            sets = recoverySets,
            targets = listOf(recoveryTarget),
            suggestions = listOf(recoverySuggestion)
        )
        val importedPayload = validProgressionPayload()
        harness.documentIo.bytes = json.encodeToString(
            BackupPayloadV1.serializer(),
            importedPayload
        ).encodeToByteArray()

        runBlocking {
            harness.repository.importBackup(URI, harness.documentIo.bytes.sha256Hex())
        }

        assertEquals(1, harness.recoveryStore.saved.size)
        val recoveryPayload = json.decodeFromString(
            BackupPayloadV1.serializer(),
            harness.recoveryStore.saved.single().decodeToString()
        )
        assertEquals(BackupProgressionTarget(sets = 1, reps = 8, weightKg = 80.0), recoveryPayload.workoutPlanTargets.single().target)
        val savedSuggestion = recoveryPayload.progressionSuggestions.single()
        assertEquals("ACCEPTED", savedSuggestion.status)
        assertEquals(BackupProgressionTarget(sets = 1, reps = 8, weightKg = 82.5), savedSuggestion.suggestedTarget)
        assertEquals(BackupProgressionTarget(sets = 1, reps = 8, weightKg = 85.0), savedSuggestion.finalTarget)
        assertTrue(savedSuggestion.wasEdited)
        assertEquals(2300L, savedSuggestion.decidedAtEpochMillis)
        assertTrue(savedSuggestion.decidedAtEpochMillis!! >= savedSuggestion.createdAtEpochMillis)

        assertEquals(importedPayload.workoutPlanTargets.single().target.sets, harness.currentProgressionTargets.single().target.sets)
        assertEquals("PENDING", harness.currentProgressionSuggestions.single().status)
        assertEquals(3, harness.currentProgressionSets.size)

        val restored = runBlocking { harness.repository.restoreLatestRecovery() }

        assertTrue(restored != null)
        assertEquals(listOf(recoveryTarget), harness.currentProgressionTargets)
        assertEquals(recoverySets, harness.currentProgressionSets)
        assertEquals(listOf(recoverySuggestion), harness.currentProgressionSuggestions)
        val restoredSuggestion = harness.currentProgressionSuggestions.single()
        assertEquals(ProgressionTargetColumns(sets = 1, reps = 8, weightKg = 85.0), restoredSuggestion.finalTarget)
        assertTrue(restoredSuggestion.wasEdited)
        assertEquals(2300L, restoredSuggestion.decidedAtEpochMillis)
        assertTrue(restoredSuggestion.decidedAtEpochMillis!! >= restoredSuggestion.createdAtEpochMillis)
        val fkCycle = listOf(
            "delete-suggestions",
            "delete-sets",
            "delete-targets",
            "insert-targets",
            "insert-sets",
            "insert-suggestions"
        )
        assertEquals(
            fkCycle + fkCycle,
            harness.events.filter { it in fkCycle }
        )
    }

    @Test
    fun `database change between recovery and guard snapshot aborts with zero deletes and removes recovery`() {
        val harness = Harness()
        var snapshotReadCount = 0
        val firstSnapshotExercise = ExerciseEntity(
            id = 1L,
            name = "Bankdruecken",
            primaryMuscleGroup = "BRUST",
            secondaryMuscleGroups = "TRIZEPS",
            category = "LANGHANTEL",
            isCustom = false
        )
        val secondSnapshotExercise = firstSnapshotExercise.copy(name = "Changed after snapshot")
        coEvery { harness.exerciseDao.getAllExercisesList() } answers {
            snapshotReadCount += 1
            if (snapshotReadCount == 1) {
                listOf(firstSnapshotExercise)
            } else {
                listOf(secondSnapshotExercise)
            }
        }
        coEvery { harness.workoutSessionDao.getAllSessionsList() } returns emptyList()
        coEvery { harness.workoutSetDao.getAllSetsList() } returns emptyList()
        coEvery { harness.trainingPlanDao.getAllPlansList() } returns emptyList()
        coEvery { harness.trainingPlanDao.getAllPlanExercisesList() } returns emptyList()
        coEvery { harness.personalRecordDao.getAllRecordsList() } returns emptyList()
        coEvery { harness.metaTrainingPlanDao.getAllMetaPlansList() } returns emptyList()
        coEvery { harness.metaTrainingPlanDao.getAllMetaPlanItemsList() } returns emptyList()
        coEvery { harness.metaTrainingPlanDao.getAllMetaPlanSkipsList() } returns emptyList()
        coEvery { harness.progressionDao.getAllTargets() } returns emptyList()
        coEvery { harness.progressionDao.getAllSuggestions() } returns emptyList()
        harness.stubMutations()
        harness.documentIo.bytes = json.encodeToString(BackupPayloadV1.serializer(), validPayload()).encodeToByteArray()

        val error = assertThrows(BackupConcurrentModificationException::class.java) {
            runBlocking { harness.repository.importBackup(URI, harness.documentIo.bytes.sha256Hex()) }
        }

        assertTrue(error.message.orEmpty().contains("no rows were deleted"))
        assertTrue(harness.transactionRunner.events.none { it.startsWith("delete-") })
        coVerify(exactly = 0) { harness.exerciseDao.deleteAll() }
        coVerify(exactly = 0) { harness.exerciseDao.replaceAll(any()) }
        assertEquals(1, harness.recoveryStore.deleted.size)
        assertEquals(1, harness.recoveryStore.saved.size)
        assertEquals(
            harness.recoveryStore.saved.single().sha256Hex(),
            harness.recoveryStore.deleted.single().sha256
        )
        assertEquals(2, snapshotReadCount)
    }

    @Test
    fun `reset deletes user data and custom exercises in one transaction without wiping the catalog`() {
        val harness = Harness()
        harness.stubMutations()

        runBlocking { harness.repository.resetUserData() }

        coVerify(exactly = 1) { harness.personalRecordDao.deleteAll() }
        coVerify(exactly = 1) { harness.workoutSetDao.deleteAll() }
        coVerify(exactly = 1) { harness.progressionDao.deleteAllSuggestions() }
        coVerify(exactly = 1) { harness.progressionDao.deleteAllTargets() }
        coVerify(exactly = 1) { harness.metaTrainingPlanDao.deleteAllMetaPlanSkips() }
        coVerify(exactly = 1) { harness.metaTrainingPlanDao.deleteAllMetaPlanItems() }
        coVerify(exactly = 1) { harness.trainingPlanDao.deleteAllPlanExercises() }
        coVerify(exactly = 1) { harness.workoutSessionDao.deleteAll() }
        coVerify(exactly = 1) { harness.metaTrainingPlanDao.deleteAllMetaPlans() }
        coVerify(exactly = 1) { harness.trainingPlanDao.deleteAllPlans() }
        coVerify(exactly = 1) { harness.exerciseDao.deleteAllCustomExercises() }
        coVerify(exactly = 0) { harness.exerciseDao.deleteAll() }
        val events = harness.transactionRunner.events
        assertEquals("txn-start", events.first())
        assertEquals("txn-end", events.last())
        assertTrue("delete-custom-exercises" in events)
        coVerifyOrder {
            harness.progressionDao.deleteAllSuggestions()
            harness.workoutSetDao.deleteAll()
            harness.progressionDao.deleteAllTargets()
        }
    }

    private fun validPayload(): BackupPayloadV1 = BackupPayloadV1(
        formatVersion = 1,
        schemaVersion = 11,
        appVersion = "1.0",
        exportedAtEpochMillis = 42L,
        exercises = listOf(
            BackupExercise(
                id = 1L,
                name = "Bankdruecken",
                primaryMuscleGroup = "BRUST",
                secondaryMuscleGroups = "TRIZEPS",
                category = "LANGHANTEL",
                isCustom = false
            )
        ),
        workoutSessions = listOf(
            BackupWorkoutSession(
                id = 10L,
                startTime = 1000L,
                endTime = 2000L,
                durationSeconds = 1L,
                name = "Push",
                notes = ""
            )
        ),
        workoutSets = listOf(
            BackupWorkoutSet(
                id = 20L,
                sessionId = 10L,
                exerciseId = 1L,
                setNumber = 1,
                reps = 8,
                weightKg = 80.0,
                isWarmup = false,
                completedAt = 1200L,
                rpe = 8.5
            )
        ),
        trainingPlans = emptyList(),
        planExercises = emptyList(),
        personalRecords = emptyList(),
        metaTrainingPlans = emptyList(),
        metaPlanItems = emptyList(),
        metaPlanSkips = emptyList()
    )

    private fun validProgressionPayload(): BackupPayloadV1 {
        val progression = backupProgressionConfig()
        val target = BackupProgressionTarget(sets = 3, reps = 8, weightKg = 80.0)
        return validPayload().copy(
            workoutSessions = listOf(
                BackupWorkoutSession(
                    id = 10L,
                    startTime = 1000L,
                    endTime = 2000L,
                    durationSeconds = 1L,
                    name = "Push",
                    notes = "",
                    planId = 5L
                )
            ),
            workoutSets = listOf(20L, 21L, 22L).mapIndexed { index, id ->
                BackupWorkoutSet(
                    id = id,
                    sessionId = 10L,
                    exerciseId = 1L,
                    setNumber = index + 1,
                    reps = 8,
                    weightKg = 80.0,
                    isWarmup = false,
                    completedAt = 1200L + index,
                    rpe = 8.5,
                    planTargetSnapshotId = 30L
                )
            },
            trainingPlans = listOf(BackupTrainingPlan(id = 5L, name = "Push", createdAt = 1000L)),
            planExercises = listOf(
                BackupPlanExercise(
                    id = 15L,
                    planId = 5L,
                    exerciseId = 1L,
                    orderIndex = 0,
                    targetSets = 3,
                    targetReps = 8,
                    targetWeightKg = 80.0,
                    progression = progression
                )
            ),
            workoutPlanTargets = listOf(
                BackupWorkoutPlanTarget(
                    id = 30L,
                    sessionId = 10L,
                    planId = 5L,
                    exerciseId = 1L,
                    orderIndex = 0,
                    target = target,
                    progression = progression
                )
            ),
            progressionSuggestions = listOf(
                BackupProgressionSuggestion(
                    id = 40L,
                    sourceSessionId = 10L,
                    sourceTargetSnapshotId = 30L,
                    planId = 5L,
                    exerciseId = 1L,
                    orderIndex = 0,
                    sourceTarget = target,
                    sourceProgression = progression,
                    outcomeType = "PROPOSE_CHANGE",
                    reasonCode = "LOAD_ADVANCED",
                    reasonArguments = linkedMapOf("targetSets" to 3.0, "actualWorkSets" to 3.0),
                    countedSetIds = listOf(20L, 21L, 22L),
                    streakEffect = "INCREMENT",
                    suggestedTarget = target.copy(weightKg = 82.5),
                    status = "PENDING",
                    createdAtEpochMillis = 2200L
                )
            )
        )
    }

    private fun backupProgressionConfig() = BackupProgressionConfig(
        scheme = "LINEAR",
        incrementValue = 2.5,
        incrementUnit = "METRIC",
        incrementKg = 2.5,
        stallThreshold = 2,
        backoffPercent = 10.0,
        ruleRevision = 1
    )

    private fun progressionConfigColumns() = ProgressionConfigColumns(
        scheme = "LINEAR",
        incrementValue = 2.5,
        incrementUnit = "METRIC",
        incrementKg = 2.5,
        stallThreshold = 2,
        backoffPercent = 10.0,
        ruleRevision = 1
    )

    private fun progressionPlanEntity() = TrainingPlanEntity(
        id = 5L,
        name = "Push",
        createdAt = 1000L
    )

    private fun progressionPlanExerciseEntity() = PlanExerciseEntity(
        id = 15L,
        planId = 5L,
        exerciseId = 1L,
        orderIndex = 0,
        targetSets = 1,
        targetReps = 8,
        targetWeightKg = 80.0,
        progression = progressionConfigColumns()
    )

    private fun progressionTargetEntity() = WorkoutPlanTargetEntity(
        id = 30L,
        sessionId = 10L,
        planId = 5L,
        exerciseId = 1L,
        orderIndex = 0,
        supersetGroupId = null,
        target = ProgressionTargetColumns(sets = 1, reps = 8, weightKg = 80.0),
        progression = progressionConfigColumns()
    )

    private fun progressionSuggestionEntity() = ProgressionSuggestionEntity(
        id = 40L,
        sourceSessionId = 10L,
        sourceTargetSnapshotId = 30L,
        planId = 5L,
        exerciseId = 1L,
        orderIndex = 0,
        supersetGroupId = null,
        sourceTarget = ProgressionTargetColumns(sets = 1, reps = 8, weightKg = 80.0),
        sourceProgression = progressionConfigColumns(),
        outcomeType = "PROPOSE_CHANGE",
        reasonCode = "LOAD_ADVANCED",
        reasonArgumentsJson = "{\"actualWorkSets\":1.0,\"targetSets\":1.0}",
        countedSetIdsJson = "[20]",
        streakEffect = "INCREMENT",
        suggestedTarget = ProgressionTargetColumns(sets = 1, reps = 8, weightKg = 82.5),
        status = "PENDING",
        wasEdited = false,
        finalTarget = null,
        createdAtEpochMillis = 2200L,
        decidedAtEpochMillis = null
    )

    private fun acceptedEditedProgressionSuggestionEntity() = progressionSuggestionEntity().copy(
        status = "ACCEPTED",
        wasEdited = true,
        finalTarget = ProgressionTargetColumns(sets = 1, reps = 8, weightKg = 85.0),
        decidedAtEpochMillis = 2300L
    )

    private fun progressionSetEntities() = listOf(
        WorkoutSetEntity(
            id = 20L,
            sessionId = 10L,
            exerciseId = 1L,
            setNumber = 1,
            reps = 8,
            weightKg = 80.0,
            setType = "NORMAL",
            completedAt = 1200L,
            rpe = 8.5,
            planTargetSnapshotId = 30L
        )
    )

    private class Harness(
        slowDocumentIo: Boolean = false
    ) {
        val events = mutableListOf<String>()
        val order = mutableListOf<String>()
        val transactionRunner = FakeTransactionRunner(events, order)
        val documentIo = FakeDocumentIo().apply {
            readDelayMillis = if (slowDocumentIo) 20 else 0
            orderRef = this@Harness.order
        }
        val recoveryStore = FakeRecoveryStore()
        val exerciseDao = mockk<ExerciseDao>(relaxed = true)
        val workoutSessionDao = mockk<WorkoutSessionDao>(relaxed = true)
        val workoutSetDao = mockk<WorkoutSetDao>(relaxed = true)
        val trainingPlanDao = mockk<TrainingPlanDao>(relaxed = true)
        val metaTrainingPlanDao = mockk<MetaTrainingPlanDao>(relaxed = true)
        val personalRecordDao = mockk<PersonalRecordDao>(relaxed = true)
        val progressionDao = mockk<ProgressionDao>(relaxed = true)
        val readinessDataDao = mockk<ReadinessDataDao>(relaxed = true)

        /** Raw readiness row; `null` mirrors "no row yet" and decodes to the empty document. */
        var readinessPayload: String? = null
        var currentProgressionSets = emptyList<WorkoutSetEntity>()
            private set
        var currentProgressionTargets = emptyList<WorkoutPlanTargetEntity>()
            private set
        var currentProgressionSuggestions = emptyList<ProgressionSuggestionEntity>()
            private set
        val repository = BackupRepositoryImpl(
            transactionRunner = transactionRunner,
            documentIo = documentIo,
            recoveryStore = recoveryStore,
            exerciseDao = exerciseDao,
            workoutSessionDao = workoutSessionDao,
            workoutSetDao = workoutSetDao,
            trainingPlanDao = trainingPlanDao,
            metaTrainingPlanDao = metaTrainingPlanDao,
            personalRecordDao = personalRecordDao,
            progressionDao = progressionDao,
            readinessDataDao = readinessDataDao,
            buildInfo = BuildInfo(versionName = "1.0", versionCode = 1)
        )

        init {
            // A relaxed mock would answer "" here, which is malformed JSON. Be explicit
            // so export/import guard snapshots see the real "missing row" contract.
            coEvery { readinessDataDao.getPayload() } answers { readinessPayload }
            coEvery { readinessDataDao.upsert(any()) } answers {
                readinessPayload = firstArg<ReadinessDataEntity>().payload
            }
        }

        fun stubSnapshotReads(
            exercise: ExerciseEntity? = null,
            session: WorkoutSessionEntity? = null,
            set: WorkoutSetEntity? = null,
            sets: List<WorkoutSetEntity> = listOfNotNull(set),
            trainingPlan: TrainingPlanEntity? = null,
            planExercise: PlanExerciseEntity? = null,
            target: WorkoutPlanTargetEntity? = null,
            suggestion: ProgressionSuggestionEntity? = null,
            skip: MetaPlanSkipEntity? = null
        ) {
            coEvery { exerciseDao.getAllExercisesList() } answers {
                events += "read-exercises"
                listOfNotNull(exercise)
            }
            coEvery { workoutSessionDao.getAllSessionsList() } answers {
                events += "read-sessions"
                listOfNotNull(session)
            }
            coEvery { workoutSetDao.getAllSetsList() } answers {
                events += "read-sets"
                sets
            }
            coEvery { trainingPlanDao.getAllPlansList() } answers {
                events += "read-plans"
                listOfNotNull(trainingPlan)
            }
            coEvery { trainingPlanDao.getAllPlanExercisesList() } answers {
                events += "read-plan-exercises"
                listOfNotNull(planExercise)
            }
            coEvery { personalRecordDao.getAllRecordsList() } answers {
                events += "read-records"
                emptyList()
            }
            coEvery { metaTrainingPlanDao.getAllMetaPlansList() } answers {
                events += "read-meta-plans"
                emptyList()
            }
            coEvery { metaTrainingPlanDao.getAllMetaPlanItemsList() } answers {
                events += "read-meta-items"
                emptyList()
            }
            coEvery { metaTrainingPlanDao.getAllMetaPlanSkipsList() } answers {
                events += "read-meta-skips"
                listOfNotNull(skip)
            }
            coEvery { progressionDao.getAllTargets() } answers {
                events += "read-targets"
                listOfNotNull(target)
            }
            coEvery { progressionDao.getAllSuggestions() } answers {
                events += "read-suggestions"
                listOfNotNull(suggestion)
            }
        }

        fun stubMutations() {
            coEvery { progressionDao.deleteAllSuggestions() } answers {
                events += "delete-suggestions"
            }
            coEvery { personalRecordDao.deleteAll() } answers {
                events += "delete-records"
            }
            coEvery { workoutSetDao.deleteAll() } answers {
                events += "delete-sets"
            }
            coEvery { progressionDao.deleteAllTargets() } answers {
                events += "delete-targets"
            }
            coEvery { metaTrainingPlanDao.deleteAllMetaPlanItems() } answers {
                events += "delete-meta-items"
            }
            coEvery { metaTrainingPlanDao.deleteAllMetaPlanSkips() } answers {
                events += "delete-meta-skips"
            }
            coEvery { trainingPlanDao.deleteAllPlanExercises() } answers {
                events += "delete-plan-exercises"
            }
            coEvery { workoutSessionDao.deleteAll() } answers {
                events += "delete-sessions"
            }
            coEvery { metaTrainingPlanDao.deleteAllMetaPlans() } answers {
                events += "delete-meta-plans"
            }
            coEvery { trainingPlanDao.deleteAllPlans() } answers {
                events += "delete-plans"
            }
            coEvery { exerciseDao.deleteAll() } answers {
                events += "delete-exercises"
            }
            coEvery { exerciseDao.deleteAllCustomExercises() } answers {
                events += "delete-custom-exercises"
            }
            coEvery { exerciseDao.replaceAll(any()) } answers {
                events += "insert-exercises"
            }
            coEvery { trainingPlanDao.replaceAllPlans(any()) } answers {
                events += "insert-plans"
            }
            coEvery { metaTrainingPlanDao.replaceAllMetaPlans(any()) } answers {
                events += "insert-meta-plans"
            }
            coEvery { metaTrainingPlanDao.replaceAllMetaPlanSkips(any()) } answers {
                events += "insert-meta-skips"
            }
            coEvery { metaTrainingPlanDao.replaceAllItems(any()) } answers {
                events += "insert-meta-items"
            }
            coEvery { progressionDao.replaceAllTargets(any()) } answers {
                events += "insert-targets"
            }
            coEvery { workoutSetDao.replaceAll(any()) } answers {
                events += "insert-sets"
            }
            coEvery { progressionDao.replaceAllSuggestions(any()) } answers {
                events += "insert-suggestions"
            }
        }

        fun stubMutableProgressionState(
            sets: List<WorkoutSetEntity>,
            targets: List<WorkoutPlanTargetEntity>,
            suggestions: List<ProgressionSuggestionEntity>
        ) {
            currentProgressionSets = sets
            currentProgressionTargets = targets
            currentProgressionSuggestions = suggestions
            coEvery { workoutSetDao.getAllSetsList() } answers {
                events += "read-sets"
                currentProgressionSets
            }
            coEvery { progressionDao.getAllTargets() } answers {
                events += "read-targets"
                currentProgressionTargets
            }
            coEvery { progressionDao.getAllSuggestions() } answers {
                events += "read-suggestions"
                currentProgressionSuggestions
            }
            coEvery { progressionDao.deleteAllSuggestions() } answers {
                events += "delete-suggestions"
                currentProgressionSuggestions = emptyList()
            }
            coEvery { workoutSetDao.deleteAll() } answers {
                events += "delete-sets"
                currentProgressionSets = emptyList()
            }
            coEvery { progressionDao.deleteAllTargets() } answers {
                events += "delete-targets"
                currentProgressionTargets = emptyList()
            }
            coEvery { progressionDao.replaceAllTargets(any()) } answers {
                events += "insert-targets"
                currentProgressionTargets = firstArg()
            }
            coEvery { workoutSetDao.replaceAll(any()) } answers {
                events += "insert-sets"
                currentProgressionSets = firstArg()
            }
            coEvery { progressionDao.replaceAllSuggestions(any()) } answers {
                events += "insert-suggestions"
                currentProgressionSuggestions = firstArg()
            }
        }
    }

    private class FakeTransactionRunner(
        val events: MutableList<String>,
        private val order: MutableList<String>
    ) : TransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T {
            events += "txn-start"
            order += "txn-start"
            val result = block()
            events += "txn-end"
            order += "txn-end"
            return result
        }
    }

    private open class FakeDocumentIo : BackupDocumentIo {
        lateinit var orderRef: MutableList<String>
        var bytes: ByteArray = ByteArray(0)
        var writtenBytes: ByteArray? = null
        var failWrite: Throwable? = null
        var writes = 0
        var readDelayMillis = 0L
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        override suspend fun writeVerified(uri: Uri, bytes: ByteArray) {
            failWrite?.let { throw it }
            writtenBytes = bytes
            writes += 1
            orderRef += "write"
        }

        override suspend fun readBytes(uri: Uri): ByteArray {
            val current = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, current) }
            if (readDelayMillis > 0) delay(readDelayMillis)
            active.decrementAndGet()
            orderRef += "read"
            return bytes
        }
    }

    private class FakeRecoveryStore : RecoveryBackupStore {
        var failSave: Throwable? = null
        val saved = mutableListOf<ByteArray>()
        val deleted = mutableListOf<RecoveryBackup>()
        var latestMeta: RecoveryBackup? = null

        override suspend fun latest(): RecoveryBackup? = latestMeta

        override suspend fun save(bytes: ByteArray): RecoveryBackup {
            failSave?.let { throw it }
            saved += bytes
            val meta = RecoveryBackup(
                timestampMillis = saved.size.toLong(),
                sha256 = bytes.sha256Hex(),
                sizeBytes = bytes.size.toLong()
            )
            latestMeta = meta
            return meta
        }

        override suspend fun loadLatestBytes(): ByteArray? = saved.lastOrNull()

        override suspend fun delete(backup: RecoveryBackup) {
            deleted += backup
            latestMeta = null
        }
    }

    private companion object {
        val URI = mockk<Uri>()
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
