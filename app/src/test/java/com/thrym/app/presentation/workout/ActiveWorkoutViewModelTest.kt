package com.thrym.app.presentation.workout

import androidx.lifecycle.SavedStateHandle
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.FailurePolicy
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.RecordType
import com.thrym.app.domain.model.ProgressionConfig
import com.thrym.app.domain.model.ProgressionGenerationResult
import com.thrym.app.domain.model.ProgressionTarget
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeightStep
import com.thrym.app.domain.model.WorkoutPlanTarget
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.repository.StatisticsRepository
import com.thrym.app.domain.repository.ProgressionRepository
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.app.domain.util.AppLogger
import com.thrym.app.fakes.FakeAppPreferencesRepository
import com.thrym.app.fakes.FakeExerciseRepository
import com.thrym.app.fakes.FakeStatisticsRepository
import com.thrym.app.fakes.FakeTrainingPlanRepository
import com.thrym.app.fakes.FakeWorkoutRepository
import com.thrym.shared.workout.SessionExerciseOrderCodec
import com.thrym.shared.workout.SessionOrderMove
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveWorkoutViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var workoutRepo: FakeWorkoutRepository
    private lateinit var exerciseRepo: FakeExerciseRepository
    private lateinit var statsRepo: FakeStatisticsRepository
    private lateinit var planRepo: FakeTrainingPlanRepository
    private lateinit var progressionRepo: ProgressionRepository
    private lateinit var progressionTargets: MutableStateFlow<List<WorkoutPlanTarget>>
    private lateinit var prefsRepo: FakeAppPreferencesRepository
    private var progressionGenerateCalls = 0
    private var progressionGenerateError: Throwable? = null
    private var progressionGenerateGate: CompletableDeferred<Unit>? = null
    private var progressionGenerationResult = ProgressionGenerationResult(
        insertedCount = 0,
        reviewItemCount = 0,
        pendingCount = 0
    )
    private val finishCallLog = mutableListOf<String>()

    private val testExercise = Exercise(
        id = 1L,
        name = "Bankdruecken",
        primaryMuscleGroup = MuscleGroup.BRUST,
        category = ExerciseCategory.LANGHANTEL
    )

    private val sessionId = 1L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        workoutRepo = FakeWorkoutRepository()
        exerciseRepo = FakeExerciseRepository()
        statsRepo = FakeStatisticsRepository()
        planRepo = FakeTrainingPlanRepository()
        progressionRepo = mockk(relaxed = true)
        progressionTargets = MutableStateFlow(emptyList())
        every { progressionRepo.observeTargetsForSession(any()) } returns progressionTargets
        coEvery { progressionRepo.generateOutcomesForSession(any()) } coAnswers {
            val generatedSessionId = firstArg<Long>()
            progressionGenerateCalls += 1
            finishCallLog += if (workoutRepo.getSessionById(generatedSessionId)?.endTime != null) {
                "finish:$generatedSessionId"
            } else {
                "generate-before-finish:$generatedSessionId"
            }
            finishCallLog += "generate:$generatedSessionId"
            progressionGenerateGate?.await()
            progressionGenerateError?.let { throw it }
            progressionGenerationResult
        }
        prefsRepo = FakeAppPreferencesRepository()

        exerciseRepo.addExercise(testExercise)
        workoutRepo.addSession(
            WorkoutSession(id = sessionId, startTime = LocalDateTime.now()),
            isActive = true
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(statistics: StatisticsRepository = statsRepo): ActiveWorkoutViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId))
        return ActiveWorkoutViewModel(savedStateHandle, workoutRepo, exerciseRepo, statistics, progressionRepo, prefsRepo, strings = com.thrym.app.fakes.TestUiStrings)
    }

    /** A set of [testExercise] (or [exerciseId]) as the statistics repository returns it for the record rules. */
    private fun exerciseSet(
        id: Long,
        sessionId: Long,
        reps: Int,
        weightKg: Double,
        setType: SetType = SetType.NORMAL,
        exerciseId: Long = testExercise.id
    ) =
        WorkoutSet(
            id = id,
            sessionId = sessionId,
            exerciseId = exerciseId,
            setNumber = 1,
            reps = reps,
            weightKg = weightKg,
            setType = setType,
            completedAt = LocalDateTime.of(2026, 10, 1, 18, 0).plusMinutes(id)
        )

    private fun withMockedAppLoggerWarnings(block: () -> Unit) {
        mockkObject(AppLogger)
        try {
            every { AppLogger.w(any(), any(), any()) } returns Unit
            block()
        } finally {
            unmockkObject(AppLogger)
        }
    }

    // --- Log Set ---

    @Test
    fun `logSet speichert rpe korrekt wenn IntensitySystem ist RPE`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0, setType = SetType.NORMAL, intensity = "8.5")

        val sets = workoutRepo.getSetsForSessionList(sessionId)
        assertEquals(8.5, sets[0].rpe)
    }

    @Test
    fun `logSet rechnet rir um in rpe wenn IntensitySystem ist RIR`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RIR)
        val vm = createViewModel()

        // 1.5 RIR = 8.5 RPE
        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0, setType = SetType.NORMAL, intensity = "1.5")

        val sets = workoutRepo.getSetsForSessionList(sessionId)
        assertEquals(8.5, sets[0].rpe)
    }

    @Test
    fun `logSet weist ungueltige intensitaet zurueck`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0, setType = SetType.NORMAL, intensity = "abc")

        val sets = workoutRepo.getSetsForSessionList(sessionId)
        assertTrue(sets.isEmpty())
        assertNotNull(vm.uiState.value.error)
        collector.cancel()
    }

    @Test
    fun `logSet weist nicht endliche und ausserhalb liegende RPE-Werte zurueck`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        listOf("0", "11", "NaN", "Infinity").forEach { value ->
            vm.logSet(
                exerciseId = 1L,
                reps = 10,
                weightKg = 80.0,
                setType = SetType.NORMAL,
                intensity = value
            )
        }

        assertTrue(workoutRepo.getSetsForSessionList(sessionId).isEmpty())
        assertNotNull(vm.uiState.value.error)
        collector.cancel()
    }

    @Test
    fun `logSet weist RIR ausserhalb des gueltigen Bereichs zurueck`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RIR)
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.logSet(
            exerciseId = 1L,
            reps = 10,
            weightKg = 80.0,
            setType = SetType.NORMAL,
            intensity = "10"
        )

        assertTrue(workoutRepo.getSetsForSessionList(sessionId).isEmpty())
        assertNotNull(vm.uiState.value.error)
        collector.cancel()
    }

    @Test
    fun `logSet ignoriert intensitaet wenn IntensitySystem aus ist`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.OFF)
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0, setType = SetType.NORMAL, intensity = "8.5")

        val sets = workoutRepo.getSetsForSessionList(sessionId)
        assertEquals(null, sets[0].rpe)
    }

    @Test
    fun `logSet akzeptiert Komma als Dezimaltrennzeichen fuer Intensitaet`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0, setType = SetType.NORMAL, intensity = "8,5")

        val sets = workoutRepo.getSetsForSessionList(sessionId)
        assertEquals(8.5, sets[0].rpe)
    }

    @Test
    fun `logSet erstellt Satz korrekt`() = runTest {
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0)

        val sets = workoutRepo.getSetsForSessionList(sessionId)
        assertEquals(1, sets.size)
        assertEquals(10, sets[0].reps)
        assertEquals(80.0, sets[0].weightKg, 0.01)
        assertEquals(1, sets[0].setNumber)
    }

    @Test
    fun `logSet vergibt bei schnellen Folgeaufrufen fortlaufende Setnummern`() = runTest {
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0)
        vm.logSet(exerciseId = 1L, reps = 8, weightKg = 85.0)
        testDispatcher.scheduler.advanceUntilIdle()

        val sets = workoutRepo.getSetsForSessionList(sessionId).sortedBy { it.setNumber }
        assertEquals(listOf(1, 2), sets.map { it.setNumber })
    }

    @Test
    fun `logSet emittiert NewRecords mit Wert und bisheriger Bestmarke und schreibt keine PRs`() = runTest {
        val stats = mockk<StatisticsRepository>(relaxed = true)
        val earlier = exerciseSet(id = 50L, sessionId = 99L, reps = 6, weightKg = 80.0)
        val logged = exerciseSet(id = 51L, sessionId = sessionId, reps = 10, weightKg = 100.0)
        coEvery { stats.getSetsForExerciseList(testExercise.id) } returnsMany listOf(listOf(earlier), listOf(earlier, logged))
        val vm = createViewModel(stats)
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.events.collect { emitted += it } }

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 100.0)
        testDispatcher.scheduler.advanceUntilIdle()

        // Alle Rekorde eines Satzes kommen gebuendelt als eine Meldung, Volumen nie live.
        val event = emitted.single() as WorkoutEvent.NewRecords
        assertEquals(testExercise.name, event.exerciseName)
        assertEquals(listOf(RecordType.MAX_WEIGHT, RecordType.MAX_E1RM, RecordType.MAX_REPS), event.records.map { it.type })
        val expected = listOf(100.0 to 80.0, 100.0 * (1 + 10 / 30.0) to 80.0 * (1 + 6 / 30.0), 10.0 to 6.0)
        event.records.zip(expected).forEach { (record, values) ->
            assertEquals(values.first, record.value, 1e-9)
            assertEquals(values.second, record.previous, 1e-9)
        }
        assertTrue(event.records.none { it.addedLoad })
        coVerify(exactly = 0) { stats.checkAndUpdateRecord(any(), any(), any()) }
        coVerify(exactly = 0) { stats.getRecordsForExercisesList(any()) }

        eventCollector.cancel()
    }

    @Test
    fun `logSet bei Eigengewicht zaehlt das Gewicht als Zusatzlast und meldet kein e1RM`() = runTest {
        val pullUp = Exercise(id = 2L, name = "Klimmzuege", primaryMuscleGroup = MuscleGroup.RUECKEN, category = ExerciseCategory.EIGENGEWICHT)
        exerciseRepo.addExercise(pullUp)
        val stats = mockk<StatisticsRepository>(relaxed = true)
        val earlier = exerciseSet(id = 50L, sessionId = 99L, reps = 8, weightKg = 10.0, exerciseId = pullUp.id)
        val logged = exerciseSet(id = 51L, sessionId = sessionId, reps = 10, weightKg = 12.5, exerciseId = pullUp.id)
        coEvery { stats.getSetsForExerciseList(pullUp.id) } returnsMany listOf(listOf(earlier), listOf(earlier, logged))
        val vm = createViewModel(stats)
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.events.collect { emitted += it } }

        vm.logSet(exerciseId = pullUp.id, reps = 10, weightKg = 12.5)
        testDispatcher.scheduler.advanceUntilIdle()

        // Ein e1RM der Zusatzlast allein sagt nichts ueber die Uebung; die Meldung zeigt die Last.
        val event = emitted.single() as WorkoutEvent.NewRecords
        assertEquals(pullUp.name, event.exerciseName)
        assertEquals(listOf(RecordType.MAX_WEIGHT, RecordType.MAX_REPS), event.records.map { it.type })
        assertEquals(listOf(12.5 to 10.0, 10.0 to 8.0), event.records.map { it.value to it.previous })
        assertTrue(event.records.all { it.addedLoad })

        eventCollector.cancel()
    }

    @Test
    fun `logSet emittiert kein NewRecords beim ersten Training einer Uebung`() = runTest {
        val stats = mockk<StatisticsRepository>(relaxed = true)
        val logged = exerciseSet(id = 51L, sessionId = sessionId, reps = 10, weightKg = 100.0)
        coEvery { stats.getSetsForExerciseList(testExercise.id) } returnsMany listOf(emptyList(), listOf(logged))
        val vm = createViewModel(stats)
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.events.collect { emitted += it } }

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 100.0)
        testDispatcher.scheduler.advanceUntilIdle()

        // Ohne fruehere Bestmarke ist nichts gefallen; die Werte zeigt erst der Abschluss.
        assertEquals(1, workoutRepo.getSetsForSessionList(sessionId).size)
        assertTrue(emitted.isEmpty())

        eventCollector.cancel()
    }

    @Test
    fun `langsamer Event-Collector blockiert das Loggen des naechsten Satzes nicht`() = runTest {
        val stats = mockk<StatisticsRepository>(relaxed = true)
        val earlier = exerciseSet(id = 50L, sessionId = 99L, reps = 6, weightKg = 80.0)
        val logged = exerciseSet(id = 51L, sessionId = sessionId, reps = 10, weightKg = 100.0)
        coEvery { stats.getSetsForExerciseList(testExercise.id) } returnsMany listOf(listOf(earlier), listOf(earlier, logged))
        val vm = createViewModel(stats)
        // Wie die Snackbar im Screen: jedes Event haelt den Collector an.
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.events.collect { awaitCancellation() }
        }

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 100.0)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.logSet(exerciseId = testExercise.id, reps = 8, weightKg = 100.0)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, workoutRepo.getSetsForSessionList(sessionId).size)
        assertTrue(vm.uiState.value.logInFlightByExercise.values.none { it > 0 })

        eventCollector.cancel()
    }

    @Test
    fun `logSet emittiert kein NewRecords wenn kein Record verbessert wurde`() = runTest {
        val stats = mockk<StatisticsRepository>(relaxed = true)
        val earlier = exerciseSet(id = 50L, sessionId = 99L, reps = 12, weightKg = 120.0)
        val logged = exerciseSet(id = 51L, sessionId = sessionId, reps = 10, weightKg = 100.0)
        coEvery { stats.getSetsForExerciseList(testExercise.id) } returnsMany listOf(listOf(earlier), listOf(earlier, logged))
        val vm = createViewModel(stats)
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch { vm.events.collect { emitted += it } }

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 100.0)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(emitted.isEmpty())
        coVerify(exactly = 0) { stats.checkAndUpdateRecord(any(), any(), any()) }

        eventCollector.cancel()
    }

    @Test
    fun `logSet fuehrt Mutation aus wenn PR-Snapshot fehlschlaegt`() = runTest {
        val stats = mockk<StatisticsRepository>(relaxed = true)
        coEvery { stats.getSetsForExerciseList(testExercise.id) } throws
            IllegalStateException("stats boom")
        val vm = ActiveWorkoutViewModel(
            SavedStateHandle(mapOf("sessionId" to sessionId)),
            workoutRepo,
            exerciseRepo,
            stats,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.events.collect { emitted += it } }

        withMockedAppLoggerWarnings {
            vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 100.0)
            testDispatcher.scheduler.advanceUntilIdle()
        }

        assertEquals(1, workoutRepo.addSetCallCount)
        assertEquals(1, workoutRepo.getSetsForSessionList(sessionId).size)
        assertTrue(emitted.isEmpty())
        assertNull(vm.uiState.value.error)
        coVerify(exactly = 0) { stats.checkAndUpdateRecord(any(), any(), any()) }

        eventCollector.cancel()
    }

    @Test
    fun `Warmup-Satz no PR comparison and no PR write`() = runTest {
        val stats = mockk<StatisticsRepository>(relaxed = true)
        val vm = ActiveWorkoutViewModel(
            SavedStateHandle(mapOf("sessionId" to sessionId)),
            workoutRepo,
            exerciseRepo,
            stats,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch { vm.events.collect { emitted += it } }

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 50.0, setType = SetType.WARMUP)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(emitted.isEmpty())
        coVerify(exactly = 0) { stats.getSetsForExerciseList(any()) }
        coVerify(exactly = 0) { stats.checkAndUpdateRecord(any(), any(), any()) }

        eventCollector.cancel()
    }

    @Test
    fun `logSet haelt mutationMutex bis der Record-Vergleich abgeschlossen ist`() = runTest {
        val stats = mockk<StatisticsRepository>(relaxed = true)
        val comparisonGate = CompletableDeferred<Unit>()
        var statsCallCount = 0
        coEvery { stats.getSetsForExerciseList(testExercise.id) } coAnswers {
            statsCallCount++
            if (statsCallCount == 1) {
                emptyList()
            } else {
                comparisonGate.await()
                emptyList()
            }
        }
        val vm = ActiveWorkoutViewModel(
            SavedStateHandle(mapOf("sessionId" to sessionId)),
            workoutRepo,
            exerciseRepo,
            stats,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch { vm.events.collect { emitted += it } }

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 100.0)
        testDispatcher.scheduler.advanceUntilIdle()
        val addedSetId = workoutRepo.getSetsForSessionList(sessionId).first().id

        // The record comparison is still blocked inside the mutex, so a queued delete
        // must not run before it completes.
        vm.deleteSet(addedSetId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, workoutRepo.deleteSetCallCount)

        comparisonGate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.deleteSetCallCount)
        assertTrue(emitted.isEmpty())

        eventCollector.cancel()
    }

    @Test
    fun `Setnummer wird nach Delete monoton fortgesetzt`() = runTest {
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0)
        vm.logSet(exerciseId = 1L, reps = 8, weightKg = 85.0)

        val beforeDelete = workoutRepo.getSetsForSessionList(sessionId)
        vm.deleteSet(beforeDelete.first { it.setNumber == 1 }.id)

        vm.logSet(exerciseId = 1L, reps = 6, weightKg = 90.0)

        val after = workoutRepo.getSetsForSessionList(sessionId).sortedBy { it.setNumber }
        assertEquals(listOf(2, 3), after.map { it.setNumber })
    }

    // --- Update Set ---

    @Test
    fun `updateSet behaelt vorhandenes RPE wenn IntensitySystem aus ist`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0, setType = SetType.NORMAL, intensity = "8.5")
        val logged = workoutRepo.getSetsForSessionList(sessionId).first()

        // User switches intensity tracking off before editing the set; the (hidden)
        // intensity UI now sends a blank string, which must not wipe out the RPE.
        prefsRepo.updateIntensitySystem(IntensitySystem.OFF)
        vm.updateSet(setId = logged.id, reps = 12, weightKg = 82.5, intensity = "")

        val updated = workoutRepo.getSetsForSessionList(sessionId).first { it.id == logged.id }
        assertEquals(12, updated.reps)
        assertEquals(82.5, updated.weightKg, 0.01)
        assertEquals(8.5, updated.rpe)
    }

    @Test
    fun `updateSet loescht RPE wenn Intensitaetsfeld absichtlich geleert wird`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0, setType = SetType.NORMAL, intensity = "8.5")
        val logged = workoutRepo.getSetsForSessionList(sessionId).first()

        // Intensity tracking stays on, user clears the field intentionally.
        vm.updateSet(setId = logged.id, reps = 10, weightKg = 80.0, intensity = "")

        val updated = workoutRepo.getSetsForSessionList(sessionId).first { it.id == logged.id }
        assertNull(updated.rpe)
    }

    @Test
    fun `updateSet akzeptiert Komma als Dezimaltrennzeichen fuer Intensitaet`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0)
        val logged = workoutRepo.getSetsForSessionList(sessionId).first()

        vm.updateSet(setId = logged.id, reps = 10, weightKg = 80.0, intensity = "9,0")

        val updated = workoutRepo.getSetsForSessionList(sessionId).first { it.id == logged.id }
        assertEquals(9.0, updated.rpe)
    }

    @Test
    fun `updateSet weist ungueltige Intensitaet zurueck und behaelt Bearbeitung`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0, intensity = "8.0")
        val logged = workoutRepo.getSetsForSessionList(sessionId).single()

        vm.updateSet(setId = logged.id, reps = 12, weightKg = 82.5, intensity = "NaN")

        val unchanged = workoutRepo.getSetsForSessionList(sessionId).single()
        assertEquals(10, unchanged.reps)
        assertEquals(80.0, unchanged.weightKg, 0.01)
        assertEquals(8.0, unchanged.rpe)
        assertEquals(0, workoutRepo.updateSetCallCount)
        assertEquals(0, vm.uiState.value.updateSuccessCountBySet[logged.id] ?: 0)
        assertNotNull(vm.uiState.value.error)
        collector.cancel()
    }

    @Test
    fun `updateSet mit negativem oder nicht-endlichem Gewicht persistiert nicht`() = runTest {
        val vm = createViewModel()
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 901L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0,
                setType = SetType.NORMAL,
                completedAt = LocalDateTime.now()
            )
        )
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.updateSet(setId = 901L, reps = 10, weightKg = -5.0)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.updateSet(setId = 901L, reps = 10, weightKg = Double.POSITIVE_INFINITY)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.updateSet(setId = 901L, reps = 10, weightKg = Double.NaN)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, workoutRepo.updateSetCallCount)
        val stored = workoutRepo.getSetsForSessionList(sessionId).first { it.id == 901L }
        assertEquals(10, stored.reps)
        assertEquals(80.0, stored.weightKg, 0.01)
        assertNotNull(vm.uiState.value.error)

        collector.cancel()
    }

    // --- Finish Workout ---

    @Test
    fun `finishWorkout beendet Session`() = runTest {
        val vm = createViewModel()

        vm.finishWorkout()

        val activeSession = workoutRepo.getActiveSession()
        assertTrue(activeSession == null)
    }

    // --- Delete Set ---

    @Test
    fun `deleteSet entfernt Satz`() = runTest {
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0)

        val sets = workoutRepo.getSetsForSessionList(sessionId)
        assertEquals(1, sets.size)

        vm.deleteSet(sets[0].id)

        val setsAfter = workoutRepo.getSetsForSessionList(sessionId)
        assertEquals(0, setsAfter.size)
    }

    @Test
    fun `deleteSet meldet SetDeleted und undoDeleteSet stellt den Satz wieder her`() = runTest {
        val vm = createViewModel()
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.events.collect { emitted += it } }

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0)
        val original = workoutRepo.getSetsForSessionList(sessionId).single()

        vm.deleteSet(original.id)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(workoutRepo.getSetsForSessionList(sessionId).isEmpty())
        assertEquals(WorkoutEvent.SetDeleted(original.setNumber), emitted.filterIsInstance<WorkoutEvent.SetDeleted>().single())

        vm.undoDeleteSet()
        testDispatcher.scheduler.advanceUntilIdle()
        val restored = workoutRepo.getSetsForSessionList(sessionId).single()
        assertEquals(original.copy(id = restored.id), restored)

        // Ein zweites Rueckgaengig ohne neues Loeschen legt nichts doppelt an.
        vm.undoDeleteSet()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, workoutRepo.getSetsForSessionList(sessionId).size)

        eventCollector.cancel()
    }

    @Test
    fun `loadCompletionRecords liefert die Saetze aller Einheiten mit ihrem Abschlussstand`() = runTest {
        val stats = mockk<StatisticsRepository>(relaxed = true)
        val vm = createViewModel(stats)
        assertNull(vm.completionRecordInputs.value)
        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 100.0)
        testDispatcher.scheduler.advanceUntilIdle()
        workoutRepo.finishWorkout(sessionId)
        val finishedEarlier = 2L
        val unfinishedEarlier = 3L
        workoutRepo.addSession(
            WorkoutSession(id = finishedEarlier, startTime = LocalDateTime.now().minusDays(3), endTime = LocalDateTime.now().minusDays(3).plusHours(1))
        )
        val today = workoutRepo.getSetsForSessionList(sessionId).single()
        val earlier = exerciseSet(id = 50L, sessionId = finishedEarlier, reps = 6, weightKg = 80.0)
        val abandoned = exerciseSet(id = 60L, sessionId = unfinishedEarlier, reps = 6, weightKg = 90.0, setType = SetType.WARMUP)
        coEvery { stats.getSetsForExerciseList(testExercise.id) } returns listOf(today, earlier, abandoned)

        vm.loadCompletionRecords()
        testDispatcher.scheduler.advanceUntilIdle()

        val inputs = vm.completionRecordInputs.value!!.associateBy { it.id }
        assertEquals(setOf(today.id, 50L, 60L), inputs.keys)
        assertEquals(true, inputs.getValue(today.id).sessionFinished)
        assertEquals(true, inputs.getValue(50L).sessionFinished)
        assertEquals(false, inputs.getValue(60L).sessionFinished)
        assertEquals("WARMUP", inputs.getValue(60L).setType)
        assertEquals(
            today.completedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            inputs.getValue(today.id).completedAtEpochMillis
        )
        coVerify(exactly = 0) { stats.getRecordsForExercisesList(any()) }
    }

    @Test
    fun `loadCompletionRecords laedt nichts fuer ein laufendes Training`() = runTest {
        val stats = mockk<StatisticsRepository>(relaxed = true)
        val vm = createViewModel(stats)
        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 100.0)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.loadCompletionRecords()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.completionRecordInputs.value)
    }

    @Test
    fun `init loads exercises from plan when planId is provided`() = runTest {
        // Setup a training plan
        val planId = 99L
        val exercise1 = Exercise(id = 10L, name = "Kniebeugen", primaryMuscleGroup = MuscleGroup.BEINE, category = ExerciseCategory.LANGHANTEL)
        val exercise2 = Exercise(id = 20L, name = "Beinpresse", primaryMuscleGroup = MuscleGroup.BEINE, category = ExerciseCategory.MASCHINE)
        exerciseRepo.addExercise(exercise1)
        exerciseRepo.addExercise(exercise2)

        val plan = com.thrym.app.domain.model.TrainingPlan(
            id = planId,
            name = "Leg Day",
            exercises = listOf(
                com.thrym.app.domain.model.PlanExercise(
                    exerciseId = 10L,
                    orderIndex = 0,
                    supersetGroupId = 1,
                    targetSets = 3,
                    targetReps = 10,
                    targetWeightKg = 100.0
                ),
                com.thrym.app.domain.model.PlanExercise(
                    exerciseId = 20L,
                    orderIndex = 1,
                    supersetGroupId = 1,
                    targetSets = 3,
                    targetReps = 12,
                    targetWeightKg = 150.0
                )
            )
        )
        planRepo.savePlan(plan)
        progressionTargets.value = listOf(
            snapshotTarget(
                id = 991L,
                exerciseId = exercise1.id,
                orderIndex = 0,
                planId = planId,
                supersetGroupId = 1,
                reps = 10,
                weightKg = 100.0
            ),
            snapshotTarget(
                id = 992L,
                exerciseId = exercise2.id,
                orderIndex = 1,
                planId = planId,
                supersetGroupId = 1,
                reps = 12,
                weightKg = 150.0
            )
        )

        // Create ViewModel with planId
        val savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId, "planId" to planId))
        val vm = ActiveWorkoutViewModel(savedStateHandle, workoutRepo, exerciseRepo, statsRepo, progressionRepo, prefsRepo, strings = com.thrym.app.fakes.TestUiStrings)

        val collector = backgroundScope.launch { vm.uiState.collect() }
        testDispatcher.scheduler.advanceUntilIdle()

        // Check if exercises are loaded
        val state = vm.uiState.value
        assertEquals(2, state.exercisesWithSets.size)
        assertEquals("Kniebeugen", state.exercisesWithSets[0].exercise.name)
        assertEquals(3, state.exercisesWithSets[0].planTarget?.target?.sets)
        assertEquals(1, state.exercisesWithSets[0].supersetGroupId)
        assertEquals("Beinpresse", state.exercisesWithSets[1].exercise.name)
        assertEquals(1, state.exercisesWithSets[1].supersetGroupId)
        
        collector.cancel()
    }

    @Test
    fun `uiState shows previous completed session data per exercise`() = runTest {
        val previousSessionId = 99L
        workoutRepo.addSession(
            WorkoutSession(
                id = previousSessionId,
                startTime = LocalDateTime.now().minusDays(2),
                endTime = LocalDateTime.now().minusDays(2).plusHours(1),
                durationSeconds = 3600
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 900L,
                sessionId = previousSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 8,
                weightKg = 75.0,
                setType = SetType.WARMUP
            )
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 901L,
                sessionId = previousSessionId,
                exerciseId = testExercise.id,
                setNumber = 2,
                reps = 6,
                weightKg = 90.0,
                setType = SetType.NORMAL
            )
        )

        val vm = createViewModel()
        vm.addExercise(testExercise)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val exerciseState = vm.uiState.value.exercisesWithSets.first()
        assertNotNull(exerciseState.previousSession)
        assertEquals(previousSessionId, exerciseState.previousSession!!.sessionId)
        assertEquals(90.0, exerciseState.previousSession!!.lastWorkSetWeightKg ?: 0.0, 0.01)
        assertEquals(2, exerciseState.previousSession!!.sets.size)

        collector.cancel()
    }

    @Test
    fun `previous session hint ignores active session and warmup-only history`() = runTest {
        val previousSessionId = 77L
        workoutRepo.addSession(
            WorkoutSession(
                id = previousSessionId,
                startTime = LocalDateTime.now().minusDays(3),
                endTime = LocalDateTime.now().minusDays(3).plusHours(1),
                durationSeconds = 3600
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 700L,
                sessionId = previousSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 40.0,
                setType = SetType.WARMUP
            )
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 701L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 5,
                weightKg = 110.0,
                setType = SetType.NORMAL
            )
        )

        val vm = createViewModel()
        vm.addExercise(testExercise)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val previous = vm.uiState.value.exercisesWithSets.first().previousSession
        assertNotNull(previous)
        assertEquals(previousSessionId, previous!!.sessionId)
        assertNull(previous.lastWorkSetWeightKg)

        collector.cancel()
    }

    @Test
    fun `previous session hint prefers last completed session of same plan even if another plan is newer`() = runTest {
        val targetPlanId = 55L
        val otherPlanId = 77L
        val samePlanSessionId = 201L
        val otherPlanSessionId = 202L

        workoutRepo.addSession(
            WorkoutSession(
                id = samePlanSessionId,
                startTime = LocalDateTime.now().minusDays(3),
                endTime = LocalDateTime.now().minusDays(3).plusHours(1),
                durationSeconds = 3600,
                planId = targetPlanId,
                metaPlanId = 500L
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 801L,
                sessionId = samePlanSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 8,
                weightKg = 82.5,
                setType = SetType.NORMAL
            )
        )

        workoutRepo.addSession(
            WorkoutSession(
                id = otherPlanSessionId,
                startTime = LocalDateTime.now().minusDays(1),
                endTime = LocalDateTime.now().minusDays(1).plusHours(1),
                durationSeconds = 3600,
                planId = otherPlanId
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 802L,
                sessionId = otherPlanSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 5,
                weightKg = 95.0,
                setType = SetType.NORMAL
            )
        )

        val savedStateHandle = SavedStateHandle(
            mapOf(
                "sessionId" to sessionId,
                "planId" to targetPlanId,
                "metaPlanId" to 500L
            )
        )
        val vm = ActiveWorkoutViewModel(
            savedStateHandle,
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        vm.addExercise(testExercise)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val previous = vm.uiState.value.exercisesWithSets.first().previousSession
        assertNotNull(previous)
        assertEquals(samePlanSessionId, previous!!.sessionId)
        assertEquals(82.5, previous.lastWorkSetWeightKg ?: 0.0, 0.01)

        collector.cancel()
    }

    @Test
    fun `previous session hint separates normal plan from meta plan history by default`() = runTest {
        val planId = 55L
        val normalSessionId = 301L
        val metaSessionId = 302L

        workoutRepo.addSession(
            WorkoutSession(
                id = normalSessionId,
                startTime = LocalDateTime.now().minusDays(3),
                endTime = LocalDateTime.now().minusDays(3).plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = null
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 901L,
                sessionId = normalSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 8,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )

        workoutRepo.addSession(
            WorkoutSession(
                id = metaSessionId,
                startTime = LocalDateTime.now().minusDays(1),
                endTime = LocalDateTime.now().minusDays(1).plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = 500L
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 902L,
                sessionId = metaSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 6,
                weightKg = 95.0,
                setType = SetType.NORMAL
            )
        )

        val savedStateHandle = SavedStateHandle(
            mapOf("sessionId" to sessionId, "planId" to planId)
        )
        val vm = ActiveWorkoutViewModel(
            savedStateHandle,
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        vm.addExercise(testExercise)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val previous = vm.uiState.value.exercisesWithSets.first().previousSession
        assertNotNull(previous)
        assertEquals(normalSessionId, previous!!.sessionId)
        assertEquals(80.0, previous.lastWorkSetWeightKg ?: 0.0, 0.01)

        collector.cancel()
    }

    @Test
    fun `previous session hint picks matching meta plan subcontext by default`() = runTest {
        val planId = 55L
        val metaASessionId = 401L
        val metaBSessionId = 402L

        workoutRepo.addSession(
            WorkoutSession(
                id = metaASessionId,
                startTime = LocalDateTime.now().minusDays(3),
                endTime = LocalDateTime.now().minusDays(3).plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = 500L
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 911L,
                sessionId = metaASessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 8,
                weightKg = 82.5,
                setType = SetType.NORMAL
            )
        )

        workoutRepo.addSession(
            WorkoutSession(
                id = metaBSessionId,
                startTime = LocalDateTime.now().minusDays(1),
                endTime = LocalDateTime.now().minusDays(1).plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = 501L
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 912L,
                sessionId = metaBSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 6,
                weightKg = 95.0,
                setType = SetType.NORMAL
            )
        )

        val savedStateHandle = SavedStateHandle(
            mapOf(
                "sessionId" to sessionId,
                "planId" to planId,
                "metaPlanId" to 500L
            )
        )
        val vm = ActiveWorkoutViewModel(
            savedStateHandle,
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        vm.addExercise(testExercise)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val previous = vm.uiState.value.exercisesWithSets.first().previousSession
        assertNotNull(previous)
        assertEquals(metaASessionId, previous!!.sessionId)
        assertEquals(82.5, previous.lastWorkSetWeightKg ?: 0.0, 0.01)

        collector.cancel()
    }

    @Test
    fun `previous session hint shares history across contexts when setting enabled`() = runTest {
        prefsRepo.updateShareWeightHistoryAcrossContexts(true)
        val planId = 55L
        val normalSessionId = 501L
        val metaSessionId = 502L

        workoutRepo.addSession(
            WorkoutSession(
                id = normalSessionId,
                startTime = LocalDateTime.now().minusDays(3),
                endTime = LocalDateTime.now().minusDays(3).plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = null
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 921L,
                sessionId = normalSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 8,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )

        workoutRepo.addSession(
            WorkoutSession(
                id = metaSessionId,
                startTime = LocalDateTime.now().minusDays(1),
                endTime = LocalDateTime.now().minusDays(1).plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = 501L
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 922L,
                sessionId = metaSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 6,
                weightKg = 95.0,
                setType = SetType.NORMAL
            )
        )

        val savedStateHandle = SavedStateHandle(
            mapOf(
                "sessionId" to sessionId,
                "planId" to planId,
                "metaPlanId" to 500L
            )
        )
        val vm = ActiveWorkoutViewModel(
            savedStateHandle,
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        vm.addExercise(testExercise)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val previous = vm.uiState.value.exercisesWithSets.first().previousSession
        assertNotNull(previous)
        assertEquals(metaSessionId, previous!!.sessionId)
        assertEquals(95.0, previous.lastWorkSetWeightKg ?: 0.0, 0.01)

        collector.cancel()
    }

    @Test
    fun `other preference changes do not reload previous session history`() = runTest {
        val vm = createViewModel()
        vm.addExercise(testExercise)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val callsBefore = workoutRepo.getPreviousSessionDataForExercisesCallCount
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        prefsRepo.updateUnitSystem(com.thrym.app.domain.model.UnitSystem.IMPERIAL)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(callsBefore, workoutRepo.getPreviousSessionDataForExercisesCallCount)
        collector.cancel()
    }

    @Test
    fun `previous session hint ties equal start times to higher session id`() = runTest {
        val planId = 55L
        val olderSessionId = 601L
        val newerSessionId = 602L
        val sameStartTime = LocalDateTime.now().minusDays(2)

        workoutRepo.addSession(
            WorkoutSession(
                id = olderSessionId,
                startTime = sameStartTime,
                endTime = sameStartTime.plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = 500L
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 931L,
                sessionId = olderSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 8,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )

        workoutRepo.addSession(
            WorkoutSession(
                id = newerSessionId,
                startTime = sameStartTime,
                endTime = sameStartTime.plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = 500L
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 932L,
                sessionId = newerSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 6,
                weightKg = 95.0,
                setType = SetType.NORMAL
            )
        )

        val savedStateHandle = SavedStateHandle(
            mapOf(
                "sessionId" to sessionId,
                "planId" to planId,
                "metaPlanId" to 500L
            )
        )
        val vm = ActiveWorkoutViewModel(
            savedStateHandle,
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        vm.addExercise(testExercise)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val previous = vm.uiState.value.exercisesWithSets.first().previousSession
        assertNotNull(previous)
        assertEquals(newerSessionId, previous!!.sessionId)
        assertEquals(95.0, previous.lastWorkSetWeightKg ?: 0.0, 0.01)

        collector.cancel()
    }

    // --- Previous final-set target indicator ---

    @Test
    fun `lastWorkSetReachedTarget returns true when target was reached`() {
        val target = previousPlanTarget(reps = 8, weightKg = 80.0)

        assertTrue(lastWorkSetReachedTarget(target, listOf(previousSet(8, 80.0))))
    }

    @Test
    fun `lastWorkSetReachedTarget returns true when target was exceeded`() {
        val target = previousPlanTarget(reps = 8, weightKg = 80.0)

        assertTrue(lastWorkSetReachedTarget(target, listOf(previousSet(9, 85.0))))
    }

    @Test
    fun `lastWorkSetReachedTarget returns false when reps are below target`() {
        val target = previousPlanTarget(reps = 8, weightKg = 80.0)

        assertFalse(lastWorkSetReachedTarget(target, listOf(previousSet(7, 90.0))))
    }

    @Test
    fun `lastWorkSetReachedTarget returns false when weight is below target`() {
        val target = previousPlanTarget(reps = 8, weightKg = 80.0)

        assertFalse(lastWorkSetReachedTarget(target, listOf(previousSet(8, 79.0))))
    }

    @Test
    fun `lastWorkSetReachedTarget returns false when target is missing`() {
        assertFalse(lastWorkSetReachedTarget(null, listOf(previousSet(8, 80.0))))
    }

    @Test
    fun `lastWorkSetReachedTarget returns false when weight target is zero`() {
        val target = previousPlanTarget(reps = 8, weightKg = 0.0)

        assertFalse(lastWorkSetReachedTarget(target, listOf(previousSet(8, 90.0))))
    }

    @Test
    fun `lastWorkSetReachedTarget returns false when reps target is zero`() {
        val target = previousPlanTarget(reps = 0, weightKg = 80.0)

        assertFalse(lastWorkSetReachedTarget(target, listOf(previousSet(8, 90.0))))
    }

    @Test
    fun `lastWorkSetReachedTarget returns false for warmup-only history`() {
        val target = previousPlanTarget(reps = 8, weightKg = 80.0)

        assertFalse(lastWorkSetReachedTarget(target, listOf(previousSet(8, 80.0, warmup = true))))
    }

    @Test
    fun `lastWorkSetReachedTarget returns false without previous sets`() {
        val target = previousPlanTarget(reps = 8, weightKg = 80.0)

        assertFalse(lastWorkSetReachedTarget(target, emptyList()))
    }

    @Test
    fun `lastWorkSetReachedTarget evaluates last non-warmup set even when warmup comes last`() {
        val target = previousPlanTarget(reps = 8, weightKg = 80.0)

        assertTrue(
            lastWorkSetReachedTarget(
                target,
                listOf(previousSet(8, 80.0), previousSet(5, 60.0, warmup = true))
            )
        )
    }

    @Test
    fun `previous session indicator is true when last work set reached current targets`() = runTest {
        val planId = 55L
        val previousSessionId = 701L
        workoutRepo.addSession(
            WorkoutSession(
                id = previousSessionId,
                startTime = LocalDateTime.now().minusDays(2),
                endTime = LocalDateTime.now().minusDays(2).plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = 500L
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 951L,
                sessionId = previousSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 8,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )
        planRepo.savePlan(
            com.thrym.app.domain.model.TrainingPlan(
                id = planId,
                name = "Push Day",
                exercises = listOf(
                    com.thrym.app.domain.model.PlanExercise(
                        exerciseId = testExercise.id,
                        orderIndex = 0,
                        targetSets = 3,
                        targetReps = 8,
                        targetWeightKg = 80.0
                    )
                )
            )
        )
        progressionTargets.value = listOf(
            snapshotTarget(
                id = 551L,
                exerciseId = testExercise.id,
                orderIndex = 0,
                planId = planId,
                reps = 8,
                weightKg = 80.0
            )
        )

        val savedStateHandle = SavedStateHandle(
            mapOf(
                "sessionId" to sessionId,
                "planId" to planId,
                "metaPlanId" to 500L
            )
        )
        val vm = ActiveWorkoutViewModel(
            savedStateHandle,
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val previous = vm.uiState.value.exercisesWithSets.first().previousSession
        assertNotNull(previous)
        assertTrue(previous!!.lastWorkSetReachedTarget)

        collector.cancel()
    }

    @Test
    fun `previous session indicator is false when last work set missed current target`() = runTest {
        val planId = 55L
        val previousSessionId = 702L
        workoutRepo.addSession(
            WorkoutSession(
                id = previousSessionId,
                startTime = LocalDateTime.now().minusDays(2),
                endTime = LocalDateTime.now().minusDays(2).plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = 500L
            ),
            isActive = false
        )
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 952L,
                sessionId = previousSessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 6,
                weightKg = 90.0,
                setType = SetType.NORMAL
            )
        )
        planRepo.savePlan(
            com.thrym.app.domain.model.TrainingPlan(
                id = planId,
                name = "Push Day",
                exercises = listOf(
                    com.thrym.app.domain.model.PlanExercise(
                        exerciseId = testExercise.id,
                        orderIndex = 0,
                        targetSets = 3,
                        targetReps = 8,
                        targetWeightKg = 80.0
                    )
                )
            )
        )
        progressionTargets.value = listOf(
            snapshotTarget(
                id = 551L,
                exerciseId = testExercise.id,
                orderIndex = 0,
                planId = planId,
                reps = 8,
                weightKg = 80.0
            )
        )

        val savedStateHandle = SavedStateHandle(
            mapOf(
                "sessionId" to sessionId,
                "planId" to planId,
                "metaPlanId" to 500L
            )
        )
        val vm = ActiveWorkoutViewModel(
            savedStateHandle,
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val previous = vm.uiState.value.exercisesWithSets.first().previousSession
        assertNotNull(previous)
        assertFalse(previous!!.lastWorkSetReachedTarget)

        collector.cancel()
    }

    @Test
    fun `resuming active workout loads existing exercises from sets`() = runTest {
        // Setup existing set for the active session (simulating a resumed workout)
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 100L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 50.0,
                setType = SetType.NORMAL
            )
        )

        val vm = createViewModel()
        
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.exercisesWithSets.size)
        assertEquals(testExercise.id, state.exercisesWithSets[0].exercise.id)
        assertEquals(1, state.exercisesWithSets[0].sets.size)
        
        collector.cancel()
    }

    @Test
    fun `resuming active workout reconciles exercises from sets without duplicates`() = runTest {
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 101L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 8,
                weightKg = 70.0,
                setType = SetType.NORMAL
            )
        )

        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val firstIds = vm.uiState.value.exercisesWithSets.map { it.exercise.id }
        testDispatcher.scheduler.advanceUntilIdle()
        val secondIds = vm.uiState.value.exercisesWithSets.map { it.exercise.id }

        assertEquals(listOf(testExercise.id), firstIds)
        assertEquals(firstIds, secondIds)
        assertEquals(secondIds.distinct().size, secondIds.size)

        collector.cancel()
    }

    @Test
    fun `rest timer endet nach letztem geplanten Arbeitssatz mit Countdown`() = runTest {
        prefsRepo.updateAutoRestTimerEnabled(true)
        val planId = 88L
        val plan = com.thrym.app.domain.model.TrainingPlan(
            id = planId,
            name = "Push Day",
            exercises = listOf(
                com.thrym.app.domain.model.PlanExercise(
                    exerciseId = testExercise.id,
                    orderIndex = 0,
                    targetSets = 2,
                    targetReps = 8,
                    targetWeightKg = 80.0
                )
            )
        )
        planRepo.savePlan(plan)
        progressionTargets.value = listOf(
            snapshotTarget(
                id = 881L,
                exerciseId = testExercise.id,
                orderIndex = 0,
                planId = planId,
                sets = 2,
                reps = 8,
                weightKg = 80.0
            )
        )

        val savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId, "planId" to planId))
        val vm = ActiveWorkoutViewModel(savedStateHandle, workoutRepo, exerciseRepo, statsRepo, progressionRepo, prefsRepo, strings = com.thrym.app.fakes.TestUiStrings)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(
            key = WorkoutExerciseKey.Planned(881L),
            exerciseId = testExercise.id,
            reps = 8,
            weightKg = 80.0
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(WorkoutExerciseKey.Planned(881L) in vm.uiState.value.restTimers)

        // Reaching the planned count cancels the previous pause.
        vm.logSet(
            key = WorkoutExerciseKey.Planned(881L),
            exerciseId = testExercise.id,
            reps = 8,
            weightKg = 80.0
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(WorkoutExerciseKey.Planned(881L) !in vm.uiState.value.restTimers)

        // Extra sets beyond the target must not restart a pause.
        vm.logSet(
            key = WorkoutExerciseKey.Planned(881L),
            exerciseId = testExercise.id,
            reps = 8,
            weightKg = 80.0
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(WorkoutExerciseKey.Planned(881L) !in vm.uiState.value.restTimers)

        collector.cancel()
    }

    @Test
    fun `rest timer endet nach letztem geplanten Arbeitssatz ohne feste Dauer`() = runTest {
        prefsRepo.updateAutoRestTimerEnabled(false)
        val planId = 88L
        val plan = com.thrym.app.domain.model.TrainingPlan(
            id = planId,
            name = "Push Day",
            exercises = listOf(
                com.thrym.app.domain.model.PlanExercise(
                    exerciseId = testExercise.id,
                    orderIndex = 0,
                    targetSets = 2,
                    targetReps = 8,
                    targetWeightKg = 80.0
                )
            )
        )
        planRepo.savePlan(plan)
        progressionTargets.value = listOf(
            snapshotTarget(
                id = 881L,
                exerciseId = testExercise.id,
                orderIndex = 0,
                planId = planId,
                sets = 2,
                reps = 8,
                weightKg = 80.0
            )
        )

        val savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId, "planId" to planId))
        val vm = ActiveWorkoutViewModel(savedStateHandle, workoutRepo, exerciseRepo, statsRepo, progressionRepo, prefsRepo, strings = com.thrym.app.fakes.TestUiStrings)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(
            key = WorkoutExerciseKey.Planned(881L),
            exerciseId = testExercise.id,
            reps = 8,
            weightKg = 80.0
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(WorkoutExerciseKey.Planned(881L) in vm.uiState.value.restTimers)

        // Reaching the planned count cancels the previous pause.
        vm.logSet(
            key = WorkoutExerciseKey.Planned(881L),
            exerciseId = testExercise.id,
            reps = 8,
            weightKg = 80.0
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(WorkoutExerciseKey.Planned(881L) !in vm.uiState.value.restTimers)

        // Extra sets beyond the target must not restart a pause.
        vm.logSet(
            key = WorkoutExerciseKey.Planned(881L),
            exerciseId = testExercise.id,
            reps = 8,
            weightKg = 80.0
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(WorkoutExerciseKey.Planned(881L) !in vm.uiState.value.restTimers)

        collector.cancel()
    }

    @Test
    fun `rest timer counts failure work sets against the deload target`() = runTest {
        prefsRepo.updateAutoRestTimerEnabled(true)
        prefsRepo.updateDeloadMode(DeloadMode.HALVE_SET_VOLUME)
        val planId = 89L
        progressionTargets.value = listOf(
            snapshotTarget(
                id = 891L,
                exerciseId = testExercise.id,
                orderIndex = 0,
                planId = planId,
                sets = 4
            )
        )

        val vm = ActiveWorkoutViewModel(
            SavedStateHandle(mapOf("sessionId" to sessionId, "planId" to planId)),
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val key = WorkoutExerciseKey.Planned(891L)
        vm.logSet(key = key, exerciseId = testExercise.id, reps = 8, weightKg = 80.0)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(key in vm.uiState.value.restTimers)

        // A set performed to failure still completes one effective work slot.
        vm.logSet(
            key = key,
            exerciseId = testExercise.id,
            reps = 5,
            weightKg = 80.0,
            setType = SetType.FAILURE
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(key !in vm.uiState.value.restTimers)

        collector.cancel()
    }

    @Test
    fun `warmup Satz startet keinen Rest Timer und raeumt bestehenden`() = runTest {
        prefsRepo.updateAutoRestTimerEnabled(true)
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 50.0, setType = SetType.WARMUP)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.restTimers.isEmpty())

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 80.0, setType = SetType.NORMAL)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(WorkoutExerciseKey.AdHoc(testExercise.id) in vm.uiState.value.restTimers)

        // A subsequent warmup set cancels the pending timer for the same exercise.
        vm.logSet(exerciseId = testExercise.id, reps = 5, weightKg = 40.0, setType = SetType.WARMUP)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(WorkoutExerciseKey.AdHoc(testExercise.id) !in vm.uiState.value.restTimers)

        collector.cancel()
    }

    @Test
    fun `finishWorkout raeumt laufende Rest Timer`() = runTest {
        prefsRepo.updateAutoRestTimerEnabled(true)
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 80.0)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(WorkoutExerciseKey.AdHoc(testExercise.id) in vm.uiState.value.restTimers)

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.restTimers.isEmpty())

        collector.cancel()
    }

    @Test
    fun `hinzugefuegte Uebungen ueberleben Prozesstod ueber SavedStateHandle`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId))
        val vm = ActiveWorkoutViewModel(savedStateHandle, workoutRepo, exerciseRepo, statsRepo, progressionRepo, prefsRepo, strings = com.thrym.app.fakes.TestUiStrings)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.addExercise(testExercise)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(testExercise.id), vm.uiState.value.exercisesWithSets.map { it.exercise.id })
        collector.cancel()

        // Simulate process death + recreation: a new ViewModel instance is created
        // with the same (restored) SavedStateHandle, before any sets exist.
        val restoredVm = ActiveWorkoutViewModel(savedStateHandle, workoutRepo, exerciseRepo, statsRepo, progressionRepo, prefsRepo, strings = com.thrym.app.fakes.TestUiStrings)
        val restoredCollector = backgroundScope.launch { restoredVm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(testExercise.id),
            restoredVm.uiState.value.exercisesWithSets.map { it.exercise.id }
        )

        restoredCollector.cancel()
    }

    @Test
    fun `rest timer state uses instant and dismisses per exercise independently`() = runTest {
        prefsRepo.updateAutoRestTimerEnabled(true)
        val secondExercise = Exercise(
            id = 2L,
            name = "Schraegbankdruecken",
            primaryMuscleGroup = MuscleGroup.BRUST,
            category = ExerciseCategory.KURZHANTEL
        )
        exerciseRepo.addExercise(secondExercise)
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 80.0)
        vm.logSet(exerciseId = secondExercise.id, reps = 12, weightKg = 32.5)
        testDispatcher.scheduler.advanceUntilIdle()

        val restTimers = vm.uiState.value.restTimers
        val firstKey = WorkoutExerciseKey.AdHoc(testExercise.id)
        val secondKey = WorkoutExerciseKey.AdHoc(secondExercise.id)
        assertEquals(setOf(firstKey, secondKey), restTimers.keys)
        val firstTimer: RestTimerUi = restTimers.getValue(firstKey)
        val secondTimer: RestTimerUi = restTimers.getValue(secondKey)
        assertTrue(firstTimer.startTime.epochSecond > 0)
        assertTrue(secondTimer.startTime.epochSecond > 0)
        assertEquals(120, firstTimer.durationSeconds)
        assertEquals(120, secondTimer.durationSeconds)

        vm.dismissRestTimer(firstKey)

        val afterDismiss = vm.uiState.value.restTimers
        assertTrue(firstKey !in afterDismiss)
        assertTrue(secondKey in afterDismiss)

        collector.cancel()
    }

    @Test
    fun `rest timer survives ViewModel recreation through durable preference state`() = runTest {
        prefsRepo.updateAutoRestTimerEnabled(true)
        prefsRepo.updateDefaultRestTimeSeconds(180)
        val savedStateHandle = SavedStateHandle(mapOf("sessionId" to sessionId))
        val vm = ActiveWorkoutViewModel(
            savedStateHandle,
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 80.0)
        testDispatcher.scheduler.advanceUntilIdle()
        val key = WorkoutExerciseKey.AdHoc(testExercise.id)
        val persistedTimer = vm.uiState.value.restTimers.getValue(key)

        // A new ViewModel models process death while the DataStore-backed preferences object
        // remains available to the recreated activity.
        val restoredVm = ActiveWorkoutViewModel(
            savedStateHandle,
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val restoredCollector = backgroundScope.launch { restoredVm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        val restoredTimer = restoredVm.uiState.value.restTimers.getValue(key)
        // DataStore persists epoch milliseconds, so nanoseconds from Instant.now() are
        // intentionally normalized at the persistence boundary.
        assertEquals(persistedTimer.startTime.toEpochMilli(), restoredTimer.startTime.toEpochMilli())
        assertEquals(persistedTimer.durationSeconds, restoredTimer.durationSeconds)

        collector.cancel()
        restoredCollector.cancel()
    }

    @Test
    fun `new timer wins when restore cleanup is still waiting`() = runTest {
        prefsRepo.updateAutoRestTimerEnabled(true)
        val sessionStartEpochMillis = workoutRepo.getSessionById(sessionId)!!
            .startTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        // A malformed payload makes restore schedule a durable cleanup. Gate that cleanup so
        // logging can race it; the ViewModel must serialize the cleanup before the new timer.
        prefsRepo.writeRestTimerState(sessionId, "$sessionStartEpochMillis#malformed")
        val readGate = CompletableDeferred<Unit>()
        val clearGate = CompletableDeferred<Unit>()
        prefsRepo.restTimerReadGate = readGate
        prefsRepo.restTimerClearGate = clearGate

        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 80.0)

        readGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()
        // Restore is currently waiting inside the durable cleanup. The new timer has not been
        // allowed to mutate state yet, which proves that the same mutex covers both paths.
        assertTrue(vm.uiState.value.restTimers.isEmpty())

        clearGate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        val key = WorkoutExerciseKey.AdHoc(testExercise.id)
        assertTrue(key in vm.uiState.value.restTimers)
        assertNotNull(prefsRepo.readRestTimerState(sessionId))
        collector.cancel()
    }

    @Test
    fun `missing session sets phase Missing and clears Loading`() = runTest {
        val savedStateHandle = SavedStateHandle(mapOf("sessionId" to 98765L))
        val vm = ActiveWorkoutViewModel(
            savedStateHandle,
            workoutRepo,
            exerciseRepo,
            statsRepo,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ActiveWorkoutSessionPhase.Missing, vm.uiState.value.sessionPhase)

        collector.cancel()
    }

    @Test
    fun `logSet failure releases lock and retry persists exactly one set`() = runTest {
        workoutRepo.failAddSet = true
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0, submissionId = 321L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.addSetCallCount)
        assertTrue(workoutRepo.getSetsForSessionList(sessionId).isEmpty())
        assertNull(vm.uiState.value.logInFlightByExercise[WorkoutExerciseKey.AdHoc(1L)])
        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.retry is WorkoutRetryDescriptor.LogSet)
        assertEquals(321L, (error.retry as WorkoutRetryDescriptor.LogSet).submissionId)

        workoutRepo.failAddSet = false
        vm.retryLastError()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, workoutRepo.addSetCallCount)
        val persisted = workoutRepo.getSetsForSessionList(sessionId)
        assertEquals(1, persisted.size)
        assertEquals(10, persisted[0].reps)
        assertEquals(80.0, persisted[0].weightKg, 0.01)
        assertNull(vm.uiState.value.error)
        assertTrue(321L in vm.uiState.value.logSuccessSubmissions)

        vm.retryLastError()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, workoutRepo.addSetCallCount)

        collector.cancel()
    }

    @Test
    fun `updateSet bei reps kleiner gleich 0 persistiert nicht und behaelt Bearbeitung`() = runTest {
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0)
        testDispatcher.scheduler.advanceUntilIdle()
        val logged = workoutRepo.getSetsForSessionList(sessionId).first()

        vm.updateSet(setId = logged.id, reps = 0, weightKg = 90.0, intensity = "")
        testDispatcher.scheduler.advanceUntilIdle()

        // Nothing was persisted...
        assertEquals(0, workoutRepo.updateSetCallCount)
        val unchanged = workoutRepo.getSetsForSessionList(sessionId).first()
        assertEquals(10, unchanged.reps)
        assertEquals(80.0, unchanged.weightKg, 0.01)
        // ...but the user gets an error while the edit row remains open for correction.
        assertNotNull(vm.uiState.value.error)
        assertEquals(0, vm.uiState.value.updateSuccessCountBySet[logged.id] ?: 0)

        collector.cancel()
    }

    @Test
    fun `finishWorkout with zero sets discards session via deleteSession`() = runTest {
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.showFinishDialog()
        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.deleteSessionCallCount)
        assertEquals(0, workoutRepo.finishWorkoutCallCount)
        assertEquals(0, progressionGenerateCalls)
        assertNull(workoutRepo.getSessionById(sessionId))
        assertFalse(vm.uiState.value.showFinishDialog)
        assertEquals(WorkoutFinishState.CompletedWithoutReview, vm.uiState.value.finishState)

        collector.cancel()
    }

    @Test
    fun `finishWorkout with sets calls finishWorkout instead of deleteSession`() = runTest {
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 400L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.finishWorkoutCallCount)
        assertEquals(0, workoutRepo.deleteSessionCallCount)
        assertEquals(1, progressionGenerateCalls)
        assertNotNull(workoutRepo.getSessionById(sessionId)?.endTime)
        assertEquals(WorkoutFinishState.CompletedWithoutReview, vm.uiState.value.finishState)
        collector.cancel()
    }

    @Test
    fun `finishWorkout mit pendierenden Vorschlaegen oeffnet das Review`() = runTest {
        progressionGenerationResult = ProgressionGenerationResult(
            insertedCount = 2,
            reviewItemCount = 2,
            pendingCount = 2
        )
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 401L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(WorkoutFinishState.ReviewReady(sessionId), vm.uiState.value.finishState)
        collector.cancel()
    }

    @Test
    fun `finishWorkout mit nur informativen Outcomes oeffnet das Review statt still zu schliessen`() = runTest {
        // Der Coach hat bewertet (KEEP_TARGET / INSUFFICIENT_DATA) und informative
        // Zeilen eingefuegt, aber es gibt keine Entscheidungen: trotzdem Review zeigen.
        progressionGenerationResult = ProgressionGenerationResult(
            insertedCount = 2,
            reviewItemCount = 0,
            pendingCount = 0
        )
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 402L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(WorkoutFinishState.ReviewReady(sessionId), vm.uiState.value.finishState)
        collector.cancel()
    }

    @Test
    fun `finishWorkout ohne Outcomes schliesst weiterhin still`() = runTest {
        // Freies Training ohne Plan / bereits generierte Outcomes: kein Review.
        progressionGenerationResult = ProgressionGenerationResult(
            insertedCount = 0,
            reviewItemCount = 0,
            pendingCount = 0
        )
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 403L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(WorkoutFinishState.CompletedWithoutReview, vm.uiState.value.finishState)
        collector.cancel()
    }

    @Test
    fun `two finish taps while finish is suspended complete and generate exactly once in order`() = runTest {
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 405L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0
            )
        )
        val finishGate = CompletableDeferred<Unit>()
        val callLog = mutableListOf<String>()
        val gatedWorkoutRepo = object : WorkoutRepository by workoutRepo {
            var finishCalls = 0

            override suspend fun finishWorkout(sessionId: Long) {
                finishCalls += 1
                finishGate.await()
                workoutRepo.finishWorkout(sessionId)
                callLog += "finish:$sessionId"
            }
        }
        var generateCalls = 0
        val gatedProgressionRepo = mockk<ProgressionRepository>(relaxed = true)
        every { gatedProgressionRepo.observeTargetsForSession(any()) } returns MutableStateFlow(emptyList())
        coEvery { gatedProgressionRepo.generateOutcomesForSession(any()) } coAnswers {
            val generatedSessionId = firstArg<Long>()
            generateCalls += 1
            callLog += "generate:$generatedSessionId"
            ProgressionGenerationResult(insertedCount = 0, reviewItemCount = 0, pendingCount = 0)
        }
        val vm = ActiveWorkoutViewModel(
            SavedStateHandle(mapOf("sessionId" to sessionId)),
            gatedWorkoutRepo,
            exerciseRepo,
            statsRepo,
            gatedProgressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.finishWorkout()
        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, gatedWorkoutRepo.finishCalls)
        assertEquals(0, generateCalls)

        finishGate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, gatedWorkoutRepo.finishCalls)
        assertEquals(1, workoutRepo.finishWorkoutCallCount)
        assertEquals(1, generateCalls)
        assertEquals(listOf("finish:$sessionId", "generate:$sessionId"), callLog)
        collector.cancel()
    }

    @Test
    fun `workout is durably finished before progression generation starts`() = runTest {
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 410L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0
            )
        )
        progressionGenerateGate = CompletableDeferred()
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("finish:$sessionId", "generate:$sessionId"), finishCallLog.take(2))
        assertNotNull(workoutRepo.getSessionById(sessionId)?.endTime)
        assertEquals(WorkoutFinishState.Generating, vm.uiState.value.finishState)

        progressionGenerateGate?.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        collector.cancel()
    }

    @Test
    fun `generation result with review items becomes ReviewReady`() = runTest {
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 420L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0
            )
        )
        progressionGenerationResult = ProgressionGenerationResult(
            insertedCount = 2,
            reviewItemCount = 2,
            pendingCount = 1
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(WorkoutFinishState.ReviewReady(sessionId), vm.uiState.value.finishState)
        collector.cancel()
    }

    @Test
    fun `generation result without review items becomes CompletedWithoutReview`() = runTest {
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 430L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0
            )
        )
        progressionGenerationResult = ProgressionGenerationResult(
            insertedCount = 0,
            reviewItemCount = 0,
            pendingCount = 0
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(WorkoutFinishState.CompletedWithoutReview, vm.uiState.value.finishState)
        collector.cancel()
    }

    @Test
    fun `generation failure leaves workout completed and exposes retry plus later state`() = runTest {
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 440L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0
            )
        )
        progressionGenerateError = IOException("disk failure must not be rendered")
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(workoutRepo.getSessionById(sessionId)?.endTime)
        val state = vm.uiState.value.finishState
        assertTrue(state is WorkoutFinishState.GenerationFailed)
        assertEquals(sessionId, (state as WorkoutFinishState.GenerationFailed).sessionId)
        collector.cancel()
    }

    @Test
    fun `generation retry does not finish workout a second time`() = runTest {
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 450L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0
            )
        )
        progressionGenerateError = IOException("disk")
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()
        progressionGenerateError = null
        vm.retryProgressionGeneration()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.finishWorkoutCallCount)
        assertEquals(2, progressionGenerateCalls)
        assertEquals(WorkoutFinishState.CompletedWithoutReview, vm.uiState.value.finishState)
        collector.cancel()
    }

    @Test
    fun `finishWorkout failure keeps dialog and session and retry finishes exactly once`() = runTest {
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 500L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )
        workoutRepo.failFinishWorkout = true
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.showFinishDialog()
        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.finishWorkoutCallCount)
        assertTrue(vm.uiState.value.showFinishDialog)
        assertNotNull(workoutRepo.getSessionById(sessionId))
        assertEquals(WorkoutFinishState.Idle, vm.uiState.value.finishState)
        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.retry is WorkoutRetryDescriptor.FinishWorkout)

        workoutRepo.failFinishWorkout = false
        vm.retryLastError()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, workoutRepo.finishWorkoutCallCount)
        assertFalse(vm.uiState.value.showFinishDialog)
        assertEquals(WorkoutFinishState.CompletedWithoutReview, vm.uiState.value.finishState)
        assertNotNull(workoutRepo.getSessionById(sessionId)?.endTime)

        collector.cancel()
    }

    @Test
    fun `finishWorkout vor logSet verhindert nachtraeglichen Insert`() = runTest {
        val vm = createViewModel()

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.deleteSessionCallCount)
        assertEquals(0, workoutRepo.addSetCallCount)

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0, submissionId = 700L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, workoutRepo.addSetCallCount)
        assertFalse(700L in vm.uiState.value.logSuccessSubmissions)
    }

    @Test
    fun `logSet vor finishWorkout persistiert Satz und beendet normal`() = runTest {
        val vm = createViewModel()

        vm.logSet(exerciseId = 1L, reps = 10, weightKg = 80.0)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, workoutRepo.addSetCallCount)

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.finishWorkoutCallCount)
        assertEquals(0, workoutRepo.deleteSessionCallCount)
        assertEquals(1, workoutRepo.getSetsForSessionList(sessionId).size)
        assertNotNull(workoutRepo.getSessionById(sessionId)?.endTime)
    }

    @Test
    fun `updateSet emittiert verbesserte Records ohne PR-Schreibzugriff`() = runTest {
        val stats = mockk<StatisticsRepository>(relaxed = true)
        val earlier = exerciseSet(id = 50L, sessionId = 99L, reps = 10, weightKg = 100.0)
        val current = exerciseSet(id = 600L, sessionId = sessionId, reps = 10, weightKg = 100.0)
        coEvery {
            stats.getSetsForExerciseList(testExercise.id)
        } returnsMany listOf(listOf(earlier, current), listOf(earlier, current.copy(reps = 12, weightKg = 120.0)))

        workoutRepo.addSetDirectly(current.copy(completedAt = LocalDateTime.now()))
        val vm = createViewModel(stats)
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.events.collect { emitted += it } }

        vm.updateSet(setId = 600L, reps = 12, weightKg = 120.0)
        testDispatcher.scheduler.advanceUntilIdle()

        val weight = (emitted.single() as WorkoutEvent.NewRecords).records.first { it.type == RecordType.MAX_WEIGHT }
        assertEquals(120.0, weight.value, 0.0)
        // Die Bestmarke vor diesem Training, nicht der Satz vor der Korrektur.
        assertEquals(100.0, weight.previous, 0.0)
        coVerify(exactly = 0) { stats.checkAndUpdateRecord(any(), any(), any()) }

        eventCollector.cancel()
    }

    @Test
    fun `updateSet fuehrt Mutation aus wenn PR-Snapshot fehlschlaegt`() = runTest {
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 650L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 100.0,
                setType = SetType.NORMAL
            )
        )
        val stats = mockk<StatisticsRepository>(relaxed = true)
        coEvery { stats.getSetsForExerciseList(testExercise.id) } throws
            IllegalStateException("stats boom")
        val vm = ActiveWorkoutViewModel(
            SavedStateHandle(mapOf("sessionId" to sessionId)),
            workoutRepo,
            exerciseRepo,
            stats,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { vm.events.collect { emitted += it } }

        withMockedAppLoggerWarnings {
            vm.updateSet(setId = 650L, reps = 12, weightKg = 120.0)
            testDispatcher.scheduler.advanceUntilIdle()
        }

        assertEquals(1, workoutRepo.updateSetCallCount)
        val updated = workoutRepo.getSetsForSessionList(sessionId).first()
        assertEquals(12, updated.reps)
        assertEquals(120.0, updated.weightKg, 0.01)
        assertTrue(emitted.isEmpty())
        assertNull(vm.uiState.value.error)
        coVerify(exactly = 0) { stats.checkAndUpdateRecord(any(), any(), any()) }

        eventCollector.cancel()
    }

    @Test
    fun `delete letzter Satz vor finishWorkout fuehrt zu Session-Discard statt empty completed`() = runTest {
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 700L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )
        val vm = createViewModel()

        vm.deleteSet(700L)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.deleteSetCallCount)
        assertEquals(1, workoutRepo.deleteSessionCallCount)
        assertEquals(0, workoutRepo.finishWorkoutCallCount)
        assertNull(workoutRepo.getSessionById(sessionId))
        assertTrue(workoutRepo.getAllCompletedSessionsList().isEmpty())
    }

    @Test
    fun `finishWorkout vor update und delete verhindert beide Repo-Mutationen`() = runTest {
        workoutRepo.addSetDirectly(
            com.thrym.app.domain.model.WorkoutSet(
                id = 800L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 80.0,
                setType = SetType.NORMAL
            )
        )
        val vm = createViewModel()
        val emitted = mutableListOf<WorkoutEvent>()
        val eventCollector = backgroundScope.launch { vm.events.collect { emitted += it } }

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.updateSet(setId = 800L, reps = 12, weightKg = 90.0)
        vm.deleteSet(800L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.finishWorkoutCallCount)
        assertEquals(0, workoutRepo.updateSetCallCount)
        assertEquals(0, workoutRepo.deleteSetCallCount)
        assertNotNull(workoutRepo.getSessionById(sessionId)?.endTime)
        assertEquals(10, workoutRepo.getSetsForSessionList(sessionId).first().reps)
        assertTrue(vm.uiState.value.updateInFlightBySet.isEmpty())
        assertTrue(emitted.isEmpty())

        eventCollector.cancel()
    }

    @Test
    fun `restored completed session starts one recovery generation and locks all set mutations`() = runTest {
        workoutRepo = FakeWorkoutRepository().also { repository ->
            repository.addSession(
                WorkoutSession(
                    id = sessionId,
                    startTime = LocalDateTime.now().minusHours(1),
                    endTime = LocalDateTime.now(),
                    durationSeconds = 3600
                ),
                isActive = false
            )
            repository.addSetDirectly(
                WorkoutSet(
                    id = 900L,
                    sessionId = sessionId,
                    exerciseId = testExercise.id,
                    setNumber = 1,
                    reps = 10,
                    weightKg = 100.0
                )
            )
        }
        progressionGenerateGate = CompletableDeferred()
        val vm = createViewModel()
        val firstCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }
        val secondCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(exerciseId = testExercise.id, reps = 8, weightKg = 105.0)
        vm.updateSet(setId = 900L, reps = 12, weightKg = 110.0)
        vm.deleteSet(setId = 900L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, progressionGenerateCalls)
        assertEquals(0, workoutRepo.finishWorkoutCallCount)
        assertEquals(0, workoutRepo.addSetCallCount)
        assertEquals(0, workoutRepo.updateSetCallCount)
        assertEquals(0, workoutRepo.deleteSetCallCount)
        assertEquals(WorkoutFinishState.Generating, vm.uiState.value.finishState)

        progressionGenerateGate?.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        firstCollector.cancel()
        secondCollector.cancel()
    }

    @Test
    fun `mutations re-read a newly completed session instead of trusting idle finish state`() = runTest {
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 910L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 100.0
            )
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(WorkoutFinishState.Idle, vm.uiState.value.finishState)

        workoutRepo.finishWorkout(sessionId)
        vm.logSet(exerciseId = testExercise.id, reps = 8, weightKg = 105.0)
        vm.updateSet(setId = 910L, reps = 12, weightKg = 110.0)
        vm.deleteSet(setId = 910L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, workoutRepo.addSetCallCount)
        assertEquals(0, workoutRepo.updateSetCallCount)
        assertEquals(0, workoutRepo.deleteSetCallCount)
        assertEquals(10, workoutRepo.getSetsForSessionList(sessionId).single().reps)
        collector.cancel()
    }

    @Test
    fun `mutations re-read a missing session and never call set repositories`() = runTest {
        workoutRepo.addSetDirectly(
            WorkoutSet(
                id = 920L,
                sessionId = sessionId,
                exerciseId = testExercise.id,
                setNumber = 1,
                reps = 10,
                weightKg = 100.0
            )
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.uiState.collect { }
        }
        testDispatcher.scheduler.advanceUntilIdle()

        workoutRepo.deleteSession(sessionId)
        vm.logSet(exerciseId = testExercise.id, reps = 8, weightKg = 105.0)
        vm.updateSet(setId = 920L, reps = 12, weightKg = 110.0)
        vm.deleteSet(setId = 920L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, workoutRepo.addSetCallCount)
        assertEquals(0, workoutRepo.updateSetCallCount)
        assertEquals(0, workoutRepo.deleteSetCallCount)
        collector.cancel()
    }

    @Test
    fun `queued mutations re-read completion only after entering mutation mutex`() = runTest {
        val comparisonGate = CompletableDeferred<Unit>()
        var statisticsReadCount = 0
        val blockingStats = mockk<StatisticsRepository>(relaxed = true)
        coEvery { blockingStats.getSetsForExerciseList(testExercise.id) } coAnswers {
            statisticsReadCount += 1
            if (statisticsReadCount > 1) comparisonGate.await()
            emptyList()
        }
        val secondExercise = testExercise.copy(id = 2L, name = "Schulterdruecken")
        exerciseRepo.addExercise(secondExercise)
        val vm = ActiveWorkoutViewModel(
            SavedStateHandle(mapOf("sessionId" to sessionId)),
            workoutRepo,
            exerciseRepo,
            blockingStats,
            progressionRepo,
            prefsRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 100.0)
        testDispatcher.scheduler.advanceUntilIdle()
        val persistedSetId = workoutRepo.getSetsForSessionList(sessionId).single().id

        vm.logSet(exerciseId = secondExercise.id, reps = 8, weightKg = 50.0)
        vm.updateSet(setId = persistedSetId, reps = 12, weightKg = 110.0)
        vm.deleteSet(setId = persistedSetId)
        testDispatcher.scheduler.advanceUntilIdle()
        workoutRepo.finishWorkout(sessionId)

        comparisonGate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, workoutRepo.addSetCallCount)
        assertEquals(0, workoutRepo.updateSetCallCount)
        assertEquals(0, workoutRepo.deleteSetCallCount)
        assertEquals(10, workoutRepo.getSetsForSessionList(sessionId).single().reps)
    }

    @Test
    fun `duplicate planned exercise positions stay separate and receive only their own sets`() = runTest {
        val exercise = testExercise.copy(id = 7L)
        exerciseRepo.addExercise(exercise)
        val targets = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                snapshotTarget(id = 41L, exerciseId = 7L, orderIndex = 0, weightKg = 100.0),
                snapshotTarget(id = 42L, exerciseId = 7L, orderIndex = 1, weightKg = 80.0)
            )
        )
        val progressionRepository = mockk<com.thrym.app.domain.repository.ProgressionRepository>(relaxed = true)
        every { progressionRepository.observeTargetsForSession(sessionId) } returns targets
        workoutRepo.addSetDirectly(snapshotSet(id = 1L, exerciseId = 7L, snapshotId = 41L))
        workoutRepo.addSetDirectly(snapshotSet(id = 2L, exerciseId = 7L, snapshotId = 42L))
        val viewModel = createSnapshotViewModel(progressionRepository)
        val collector = backgroundScope.launch { viewModel.uiState.collect { } }

        testDispatcher.scheduler.advanceUntilIdle()

        val items = viewModel.uiState.value.exercisesWithSets
        assertEquals(
            listOf(WorkoutExerciseKey.Planned(41L), WorkoutExerciseKey.Planned(42L)),
            items.map { it.key }
        )
        assertEquals(listOf(listOf(1L), listOf(2L)), items.map { row -> row.sets.map { it.id } })
        collector.cancel()
    }

    @Test
    fun `logging against a planned position stores snapshot id and numbers within that position`() = runTest {
        val exercise = testExercise.copy(id = 7L)
        exerciseRepo.addExercise(exercise)
        val targets = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                snapshotTarget(id = 41L, exerciseId = 7L, orderIndex = 0),
                snapshotTarget(id = 42L, exerciseId = 7L, orderIndex = 1)
            )
        )
        val progressionRepository = mockk<com.thrym.app.domain.repository.ProgressionRepository>(relaxed = true)
        every { progressionRepository.observeTargetsForSession(sessionId) } returns targets
        workoutRepo.addSetDirectly(
            snapshotSet(id = 1L, exerciseId = 7L, snapshotId = 41L, setNumber = 1)
        )
        workoutRepo.addSetDirectly(
            snapshotSet(id = 2L, exerciseId = 7L, snapshotId = 42L, setNumber = 7)
        )
        val viewModel = createSnapshotViewModel(progressionRepository)

        viewModel.logSet(
            key = WorkoutExerciseKey.Planned(41L),
            exerciseId = 7L,
            reps = 8,
            weightKg = 100.0
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val added = workoutRepo.getSetsForSessionList(sessionId).last()
        assertEquals(41L, added.planTargetSnapshotId)
        assertEquals(2, added.setNumber)
    }

    @Test
    fun `logging an added exercise keeps snapshot id null`() = runTest {
        val exercise = testExercise.copy(id = 9L)
        exerciseRepo.addExercise(exercise)
        val progressionRepository = mockk<com.thrym.app.domain.repository.ProgressionRepository>(relaxed = true)
        every { progressionRepository.observeTargetsForSession(sessionId) } returns
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        val viewModel = createSnapshotViewModel(progressionRepository, planId = 0L)

        viewModel.logSet(
            key = WorkoutExerciseKey.AdHoc(9L),
            exerciseId = 9L,
            reps = 10,
            weightKg = 20.0
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(workoutRepo.getSetsForSessionList(sessionId).single().planTargetSnapshotId)
    }

    @Test
    fun `linked set restoration never creates a duplicate ad hoc row`() = runTest {
        val exercise = testExercise.copy(id = 7L)
        exerciseRepo.addExercise(exercise)
        val targets = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(snapshotTarget(id = 41L, exerciseId = 7L, orderIndex = 0))
        )
        val progressionRepository = mockk<com.thrym.app.domain.repository.ProgressionRepository>(relaxed = true)
        every { progressionRepository.observeTargetsForSession(sessionId) } returns targets
        workoutRepo.addSetDirectly(snapshotSet(id = 1L, exerciseId = 7L, snapshotId = 41L))
        val viewModel = createSnapshotViewModel(progressionRepository)
        val collector = backgroundScope.launch { viewModel.uiState.collect { } }

        testDispatcher.scheduler.advanceUntilIdle()

        val rows = viewModel.uiState.value.exercisesWithSets
        assertEquals(listOf(WorkoutExerciseKey.Planned(41L)), rows.map { it.key })
        collector.cancel()
    }

    @Test
    fun `rpe progression captures canonical rpe even when global intensity tracking is off`() = runTest {
        val exercise = testExercise.copy(id = 7L)
        exerciseRepo.addExercise(exercise)
        prefsRepo.updateIntensitySystem(IntensitySystem.OFF)
        val targets = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(
                snapshotTarget(
                    id = 41L,
                    exerciseId = 7L,
                    orderIndex = 0,
                    config = ProgressionConfig.RpeRir(
                        targetRpe = 8.0,
                        tolerance = 0.5,
                        step = WeightStep(2.5, UnitSystem.METRIC, 2.5)
                    )
                )
            )
        )
        val progressionRepository = mockk<com.thrym.app.domain.repository.ProgressionRepository>(relaxed = true)
        every { progressionRepository.observeTargetsForSession(sessionId) } returns targets
        val viewModel = createSnapshotViewModel(progressionRepository)

        viewModel.logSet(
            key = WorkoutExerciseKey.Planned(41L),
            exerciseId = 7L,
            reps = 8,
            weightKg = 100.0,
            intensity = "8"
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(8.0, workoutRepo.getSetsForSessionList(sessionId).single().rpe ?: 0.0, 0.0)
    }

    private fun createSnapshotViewModel(
        progressionRepository: com.thrym.app.domain.repository.ProgressionRepository,
        planId: Long = 3L
    ) = ActiveWorkoutViewModel(
        SavedStateHandle(mapOf("sessionId" to sessionId, "planId" to planId)),
        workoutRepo,
        exerciseRepo,
        statsRepo,
        progressionRepository,
        prefsRepo,
        strings = com.thrym.app.fakes.TestUiStrings
    )

    private fun snapshotTarget(
        id: Long,
        exerciseId: Long,
        orderIndex: Int,
        planId: Long = 3L,
        supersetGroupId: Int? = null,
        sets: Int = 3,
        reps: Int = 8,
        weightKg: Double = 100.0,
        config: ProgressionConfig = ProgressionConfig.Manual()
    ) = WorkoutPlanTarget(
        id = id,
        sessionId = sessionId,
        planId = planId,
        exerciseId = exerciseId,
        orderIndex = orderIndex,
        supersetGroupId = supersetGroupId,
        target = ProgressionTarget(sets = sets, reps = reps, weightKg = weightKg),
        config = config
    )

    private fun previousPlanTarget(reps: Int, weightKg: Double) = snapshotTarget(
        id = 1L,
        exerciseId = testExercise.id,
        orderIndex = 0,
        reps = reps,
        weightKg = weightKg
    )

    private fun rpeSnapshotTarget(
        id: Long,
        exerciseId: Long,
        orderIndex: Int,
        sets: Int = 3,
        reps: Int = 8,
        weightKg: Double = 100.0,
        targetRpe: Double = 8.0,
        backoffPercent: Double = 10.0
    ) = snapshotTarget(
        id = id,
        exerciseId = exerciseId,
        orderIndex = orderIndex,
        sets = sets,
        reps = reps,
        weightKg = weightKg,
        config = ProgressionConfig.RpeRir(
            targetRpe = targetRpe,
            tolerance = 0.5,
            step = WeightStep(originalValue = 2.5, originalUnit = UnitSystem.METRIC, kilograms = 2.5),
            failurePolicy = FailurePolicy(backoffPercent = backoffPercent)
        )
    )

    @Test
    fun `logSet startet hochzaehlenden Rest Timer ohne feste Pausenzeit`() = runTest {
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 80.0)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.uiState.value.restTimers.getValue(WorkoutExerciseKey.AdHoc(testExercise.id)).durationSeconds)

        collector.cancel()
    }

    @Test
    fun `logSet startet Rest Timer mit konfigurierter Standarddauer`() = runTest {
        prefsRepo.updateAutoRestTimerEnabled(true)
        prefsRepo.updateDefaultRestTimeSeconds(180)
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 80.0)
        testDispatcher.scheduler.advanceUntilIdle()

        val timer = vm.uiState.value.restTimers.getValue(WorkoutExerciseKey.AdHoc(testExercise.id))
        assertEquals(180, timer.durationSeconds)
        assertTrue(timer.startTime.epochSecond > 0)

        collector.cancel()
    }

    // --- Intra-Session Autoregulation (RPE) ---

    @Test
    fun `Ziel-RPE aktiv zeigt Empfehlung fuer naechsten Satz`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        progressionTargets.value = listOf(
            rpeSnapshotTarget(id = 881L, exerciseId = testExercise.id, orderIndex = 0, sets = 2, weightKg = 80.0)
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        // 8.5 RPE bei Ziel-RPE 8.0 -> Delta -0.5 -> -1.25 % -> 79.0 kg
        vm.logSet(
            key = WorkoutExerciseKey.Planned(881L),
            exerciseId = testExercise.id,
            reps = 8,
            weightKg = 80.0,
            intensity = "8.5"
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val key = WorkoutExerciseKey.Planned(881L)
        val recommendation = vm.uiState.value.nextSetRecommendations[key]
        assertNotNull(recommendation)
        assertEquals(79.0, recommendation!!.recommendedWeightKg, 0.01)
        assertEquals(80.0, recommendation.lastWeightKg, 0.01)
        assertEquals(8.5, recommendation.lastRpe, 0.01)
        assertEquals(8.0, recommendation.targetRpe!!, 0.01)
        assertFalse(recommendation.isOvershoot)
        assertNull(recommendation.backoffWeightKg)

        collector.cancel()
    }

    @Test
    fun `Overshoot ohne Ziel-RPE zeigt Reduktion und Backoff-Satz`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(exerciseId = testExercise.id, reps = 8, weightKg = 100.0, intensity = "9.5")
        testDispatcher.scheduler.advanceUntilIdle()

        val key = WorkoutExerciseKey.AdHoc(testExercise.id)
        val recommendation = vm.uiState.value.nextSetRecommendations[key]
        assertNotNull(recommendation)
        // 9.5 RPE ohne Ziel => Referenz 9.0 -> -1.25 % -> 98.8 kg
        assertEquals(98.8, recommendation!!.recommendedWeightKg, 0.01)
        assertEquals(9.5, recommendation.lastRpe, 0.01)
        assertTrue(recommendation.isOvershoot)
        assertNull(recommendation.targetRpe)
        // Dedizierter Backoff-Satz: 10 % unter dem Arbeitsgewicht
        assertEquals(90.0, recommendation.backoffWeightKg!!, 0.01)

        collector.cancel()
    }

    @Test
    fun `Overshoot uses configured plan backoff percent`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        progressionTargets.value = listOf(
            rpeSnapshotTarget(
                id = 882L,
                exerciseId = testExercise.id,
                orderIndex = 0,
                sets = 2,
                weightKg = 100.0,
                backoffPercent = 20.0
            )
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(
            key = WorkoutExerciseKey.Planned(882L),
            exerciseId = testExercise.id,
            reps = 8,
            weightKg = 100.0,
            intensity = "9.5"
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val recommendation = vm.uiState.value.nextSetRecommendations.getValue(
            WorkoutExerciseKey.Planned(882L)
        )
        assertEquals(20.0, recommendation.backoffPercent, 0.01)
        assertEquals(80.0, recommendation.backoffWeightKg!!, 0.01)

        collector.cancel()
    }

    @Test
    fun `keine Empfehlung ohne Arbeitssatz mit RPE`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        // Aufwaermsaetze zaehlen nie als Basis, fehlende RPE ebenso nicht.
        vm.logSet(exerciseId = testExercise.id, reps = 10, weightKg = 60.0, setType = SetType.WARMUP, intensity = "9")
        vm.logSet(exerciseId = testExercise.id, reps = 8, weightKg = 80.0)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.nextSetRecommendations.isEmpty())

        collector.cancel()
    }

    @Test
    fun `keine Empfehlung bei deaktivierter Intensitaetserfassung`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.OFF)
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(exerciseId = testExercise.id, reps = 8, weightKg = 100.0, intensity = "9.5")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.nextSetRecommendations.isEmpty())

        collector.cancel()
    }

    @Test
    fun `Empfehlung folgt RPE-Aenderung des letzten Satzes`() = runTest {
        prefsRepo.updateIntensitySystem(IntensitySystem.RPE)
        progressionTargets.value = listOf(
            rpeSnapshotTarget(id = 881L, exerciseId = testExercise.id, orderIndex = 0, sets = 2, weightKg = 80.0)
        )
        val vm = createViewModel()
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.logSet(
            key = WorkoutExerciseKey.Planned(881L),
            exerciseId = testExercise.id,
            reps = 8,
            weightKg = 80.0,
            intensity = "8"
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val key = WorkoutExerciseKey.Planned(881L)
        assertEquals(
            80.0,
            vm.uiState.value.nextSetRecommendations.getValue(key).recommendedWeightKg,
            0.01
        )

        // RPE des letzten Satzes nachtraeglich auf 9.5 korrigieren -> Empfehlung sinkt.
        val set = workoutRepo.getSetsForSessionList(sessionId).single()
        vm.updateSet(setId = set.id, reps = 8, weightKg = 80.0, intensity = "9.5")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            77.0,
            vm.uiState.value.nextSetRecommendations.getValue(key).recommendedWeightKg,
            0.01
        )

        collector.cancel()
    }

    // --- Session exercise order ---

    private fun orderExercise(id: Long): Exercise =
        testExercise.copy(id = id, name = "Uebung $id").also { exerciseRepo.addExercise(it) }

    private fun plannedTargets(vararg supersetGroups: Int?): List<WorkoutPlanTarget> =
        supersetGroups.mapIndexed { index, group ->
            val exercise = if (index == 0) testExercise else orderExercise(index + 1L)
            snapshotTarget(
                id = 41L + index,
                exerciseId = exercise.id,
                orderIndex = index,
                supersetGroupId = group
            )
        }

    /** Same row keys as the stored order (`stableListKey` is internal to the feature module). */
    private fun WorkoutExerciseKey.orderKey(): String = when (this) {
        is WorkoutExerciseKey.Planned -> "planned-$snapshotId"
        is WorkoutExerciseKey.AdHoc -> "adhoc-$exerciseId"
    }

    private fun ActiveWorkoutViewModel.displayedKeys(): List<String> =
        uiState.value.exercisesWithSets.map { it.key.orderKey() }

    private fun ActiveWorkoutViewModel.displayedGroupKeys(): List<String> =
        uiState.value.exerciseGroups.map { it.key }

    private suspend fun sessionStartEpochMillis(): Long = workoutRepo.getSessionById(sessionId)!!
        .startTime
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Test
    fun `moveExercise aendert nur die Anzeige und schreibt nichts in die Repositories`() = runTest {
        val targets = plannedTargets(null, 1, 1)
        progressionTargets.value = targets
        val vm = createSnapshotViewModel(progressionRepo)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("planned-41", "planned-42", "planned-43"), vm.displayedKeys())

        vm.moveExercise(WorkoutExerciseKey.Planned(41L), SessionOrderMove.DOWN)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("planned-42", "planned-43", "planned-41"), vm.displayedKeys())
        // The snapshot keeps its plan-slot identity: orderIndex and superset group are untouched.
        assertEquals(targets, progressionTargets.value)
        val rows = vm.uiState.value.exercisesWithSets.associateBy { it.key }
        targets.forEach { target ->
            val row = rows.getValue(WorkoutExerciseKey.Planned(target.id))
            assertEquals(target.orderIndex, row.originalPlanTarget?.orderIndex)
            assertEquals(target.supersetGroupId, row.originalPlanTarget?.supersetGroupId)
        }
        verify(exactly = 1) { progressionRepo.observeTargetsForSession(sessionId) }
        confirmVerified(progressionRepo)
        assertEquals(0, workoutRepo.addSetCallCount)
        assertEquals(0, workoutRepo.updateSetCallCount)
        assertEquals(0, workoutRepo.deleteSetCallCount)
        assertEquals(0, workoutRepo.finishWorkoutCallCount)
        assertEquals(0, workoutRepo.deleteSessionCallCount)
        assertNotNull(prefsRepo.workoutExerciseOrder)
        collector.cancel()
    }

    @Test
    fun `zwei Moves ohne Emission dazwischen ergeben zwei Schritte`() = runTest {
        progressionTargets.value = plannedTargets(null, null, null)
        val vm = createSnapshotViewModel(progressionRepo)
        testDispatcher.scheduler.advanceUntilIdle()
        // Nobody collects, so no list is built between the taps. The first write is held, so the
        // second tap has to wait for the first one under the store mutex.
        val writeGate = CompletableDeferred<Unit>()
        prefsRepo.workoutExerciseOrderWriteGate = writeGate

        vm.moveExercise(WorkoutExerciseKey.Planned(41L), SessionOrderMove.DOWN)
        vm.moveExercise(WorkoutExerciseKey.Planned(41L), SessionOrderMove.DOWN)
        assertEquals(1, prefsRepo.workoutExerciseOrderWriteCount)
        writeGate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        // Collected on the unconfined dispatcher: advanceUntilIdle skips a late background task.
        val collector = backgroundScope.launch(testDispatcher) { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("planned-42", "planned-43", "planned-41"), vm.displayedKeys())
        assertEquals(2, prefsRepo.workoutExerciseOrderWriteCount)
        collector.cancel()
    }

    @Test
    fun `ein Supersatz bewegt sich als Block und behaelt seinen Gruppen-Key`() = runTest {
        progressionTargets.value = plannedTargets(null, 1, 1, null)
        val vm = createSnapshotViewModel(progressionRepo)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        val supersetKey = "superset-1-planned-42-planned-43"
        assertEquals(
            listOf("single-planned-41", supersetKey, "single-planned-44"),
            vm.displayedGroupKeys()
        )

        // Any member key moves the whole block.
        vm.moveExercise(WorkoutExerciseKey.Planned(43L), SessionOrderMove.UP)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(supersetKey, "single-planned-41", "single-planned-44"),
            vm.displayedGroupKeys()
        )
        val superset = vm.uiState.value.exerciseGroups.first()
        assertEquals(1, superset.supersetGroupId)
        assertEquals(
            listOf(WorkoutExerciseKey.Planned(42L), WorkoutExerciseKey.Planned(43L)),
            superset.exercises.map { it.key }
        )

        vm.moveExercise(WorkoutExerciseKey.Planned(42L), SessionOrderMove.DOWN)
        vm.moveExercise(WorkoutExerciseKey.Planned(42L), SessionOrderMove.DOWN)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("single-planned-41", "single-planned-44", supersetKey),
            vm.displayedGroupKeys()
        )
        assertEquals(
            listOf("planned-41", "planned-44", "planned-42", "planned-43"),
            vm.displayedKeys()
        )
        collector.cancel()
    }

    @Test
    fun `eine Zusatzuebung laesst sich ueber geplante schieben und eine neue wird angehaengt`() = runTest {
        progressionTargets.value = plannedTargets(null, null)
        val firstExtra = orderExercise(5L)
        val secondExtra = orderExercise(6L)
        val vm = createSnapshotViewModel(progressionRepo)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.addExercise(firstExtra)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("planned-41", "planned-42", "adhoc-5"), vm.displayedKeys())

        vm.moveExercise(WorkoutExerciseKey.AdHoc(5L), SessionOrderMove.UP)
        vm.moveExercise(WorkoutExerciseKey.AdHoc(5L), SessionOrderMove.UP)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("adhoc-5", "planned-41", "planned-42"), vm.displayedKeys())

        vm.addExercise(secondExtra)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("adhoc-5", "planned-41", "planned-42", "adhoc-6"), vm.displayedKeys())
        collector.cancel()
    }

    @Test
    fun `die Reihenfolge uebersteht ein neues ViewModel und schon die erste Emission hat sie`() = runTest {
        progressionTargets.value = plannedTargets(null, null, null)
        val vm = createSnapshotViewModel(progressionRepo)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.moveExercise(WorkoutExerciseKey.Planned(43L), SessionOrderMove.UP)
        vm.moveExercise(WorkoutExerciseKey.Planned(43L), SessionOrderMove.UP)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(prefsRepo.workoutExerciseOrder)

        // Hold the read so the recreated ViewModel has rows but no order yet.
        val readGate = CompletableDeferred<Unit>()
        prefsRepo.workoutExerciseOrderReadGate = readGate
        val restoredVm = createSnapshotViewModel(progressionRepo)
        val emissions = mutableListOf<List<String>>()
        val collector = backgroundScope.launch(testDispatcher) {
            restoredVm.uiState.collect { state ->
                emissions += state.exercisesWithSets.map { it.key.orderKey() }
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(emissions.all { it.isEmpty() })

        readGate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        val expected = listOf("planned-43", "planned-41", "planned-42")
        assertEquals(expected, emissions.first { it.isNotEmpty() })
        assertTrue(emissions.filter { it.isNotEmpty() }.all { it == expected })
        collector.cancel()
    }

    @Test
    fun `ein Datensatz einer anderen Session oder Startzeit wird ignoriert und entfernt`() = runTest {
        progressionTargets.value = plannedTargets(null, null)
        val start = sessionStartEpochMillis()
        val swapped = listOf("planned-42", "planned-41")
        listOf(
            SessionExerciseOrderCodec.encode(sessionId + 1, start, swapped),
            SessionExerciseOrderCodec.encode(sessionId, start + 1, swapped),
            "kein gueltiger Datensatz"
        ).forEach { foreign ->
            prefsRepo.workoutExerciseOrder = foreign
            val vm = createSnapshotViewModel(progressionRepo)
            val collector = backgroundScope.launch { vm.uiState.collect { } }
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("planned-41", "planned-42"), vm.displayedKeys())
            assertNull(prefsRepo.workoutExerciseOrder)
            collector.cancel()
        }
    }

    @Test
    fun `ein Move auf eine beendete Session wird ignoriert`() = runTest {
        progressionTargets.value = plannedTargets(null, null)
        val vm = createSnapshotViewModel(progressionRepo)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        workoutRepo.finishWorkout(sessionId)
        vm.moveExercise(WorkoutExerciseKey.Planned(41L), SessionOrderMove.DOWN)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("planned-41", "planned-42"), vm.displayedKeys())
        assertEquals(0, prefsRepo.workoutExerciseOrderWriteCount)
        assertNull(prefsRepo.workoutExerciseOrder)
        collector.cancel()
    }

    @Test
    fun `Verwerfen einer leeren Session loescht die Reihenfolge`() = runTest {
        progressionTargets.value = plannedTargets(null, null)
        val vm = createSnapshotViewModel(progressionRepo)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        vm.moveExercise(WorkoutExerciseKey.Planned(41L), SessionOrderMove.DOWN)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(prefsRepo.workoutExerciseOrder)

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(workoutRepo.getSessionById(sessionId))
        assertNull(prefsRepo.workoutExerciseOrder)
        collector.cancel()
    }

    @Test
    fun `normales Beenden behaelt die Reihenfolge fuer den Abschlussbildschirm`() = runTest {
        progressionTargets.value = plannedTargets(null, null)
        val vm = createSnapshotViewModel(progressionRepo)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        vm.moveExercise(WorkoutExerciseKey.Planned(42L), SessionOrderMove.UP)
        vm.logSet(
            key = WorkoutExerciseKey.Planned(41L),
            exerciseId = testExercise.id,
            reps = 8,
            weightKg = 100.0
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.finishWorkout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(workoutRepo.getSessionById(sessionId)?.endTime)
        assertNotNull(prefsRepo.workoutExerciseOrder)
        assertEquals(listOf("planned-42", "planned-41"), vm.displayedKeys())
        collector.cancel()
    }

    @Test
    fun `eine Zusatzuebung ohne Saetze kommt nach einem Kaltstart zurueck`() = runTest {
        progressionTargets.value = plannedTargets(null)
        val extra = orderExercise(5L)
        val vm = createSnapshotViewModel(progressionRepo)
        val collector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        vm.addExercise(extra)
        vm.moveExercise(WorkoutExerciseKey.AdHoc(5L), SessionOrderMove.UP)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("adhoc-5", "planned-41"), vm.displayedKeys())
        collector.cancel()

        // A cold start brings no SavedStateHandle; only the stored order still knows the exercise.
        val restoredVm = createSnapshotViewModel(progressionRepo)
        val restoredCollector = backgroundScope.launch { restoredVm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("adhoc-5", "planned-41"), restoredVm.displayedKeys())
        assertTrue(restoredVm.uiState.value.exercisesWithSets.first().sets.isEmpty())
        restoredCollector.cancel()
    }

    @Test
    fun `eine Zusatzuebung vor dem Laden der Planziele behaelt die gespeicherte Reihenfolge`() = runTest {
        progressionTargets.value = plannedTargets(null, null)
        val vm = createSnapshotViewModel(progressionRepo)
        val firstCollector = backgroundScope.launch { vm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        vm.moveExercise(WorkoutExerciseKey.Planned(42L), SessionOrderMove.UP)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("planned-42", "planned-41"), vm.displayedKeys())
        firstCollector.cancel()

        // Cold start: the stored order is read, but the plan targets arrive late.
        val targetsGate = CompletableDeferred<Unit>()
        every { progressionRepo.observeTargetsForSession(any()) } returns flow {
            targetsGate.await()
            emitAll(progressionTargets)
        }
        val restoredVm = createSnapshotViewModel(progressionRepo)
        val collector = backgroundScope.launch { restoredVm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        restoredVm.addExercise(orderExercise(5L))
        testDispatcher.scheduler.advanceUntilIdle()
        targetsGate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("planned-42", "planned-41", "adhoc-5"), restoredVm.displayedKeys())
        collector.cancel()

        // The durable copy kept the moved plan order as well.
        every { progressionRepo.observeTargetsForSession(any()) } returns progressionTargets
        val nextVm = createSnapshotViewModel(progressionRepo)
        val nextCollector = backgroundScope.launch { nextVm.uiState.collect { } }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("planned-42", "planned-41", "adhoc-5"), nextVm.displayedKeys())
        nextCollector.cancel()
    }

    @Test
    fun `ein Schreibfehler behaelt die Reihenfolge im Speicher`() = runTest {
        progressionTargets.value = plannedTargets(null, null, null)
        prefsRepo.workoutExerciseOrderWriteError = IOException("Speicher voll")
        mockkObject(AppLogger)
        try {
            every { AppLogger.w(any(), any(), any()) } returns Unit
            val vm = createSnapshotViewModel(progressionRepo)
            val collector = backgroundScope.launch { vm.uiState.collect { } }
            testDispatcher.scheduler.advanceUntilIdle()

            vm.moveExercise(WorkoutExerciseKey.Planned(41L), SessionOrderMove.DOWN)
            vm.moveExercise(WorkoutExerciseKey.Planned(41L), SessionOrderMove.DOWN)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf("planned-42", "planned-43", "planned-41"), vm.displayedKeys())
            assertNull(prefsRepo.workoutExerciseOrder)
            assertNull(vm.uiState.value.error)
            verify(exactly = 2) {
                AppLogger.w(
                    "ActiveWorkoutVM",
                    match { it.startsWith("Uebungsreihenfolge konnte nicht gespeichert werden") },
                    any()
                )
            }
            collector.cancel()
        } finally {
            unmockkObject(AppLogger)
        }
    }

    private fun snapshotSet(
        id: Long,
        exerciseId: Long,
        snapshotId: Long?,
        setNumber: Int = 1
    ) = WorkoutSet(
        id = id,
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = setNumber,
        reps = 8,
        weightKg = 100.0,
        planTargetSnapshotId = snapshotId
    )
}

private fun previousSet(
    reps: Int,
    weightKg: Double,
    warmup: Boolean = false
) = WorkoutSet(
    sessionId = 1L,
    exerciseId = 2L,
    setNumber = 1,
    reps = reps,
    weightKg = weightKg,
    setType = if (warmup) SetType.WARMUP else SetType.NORMAL
)
