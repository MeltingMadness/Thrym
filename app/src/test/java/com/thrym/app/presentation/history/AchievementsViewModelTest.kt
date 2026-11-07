package com.thrym.app.presentation.history

import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.PersonalRecord
import com.thrym.app.domain.model.RecordType
import com.thrym.app.domain.model.ThemeMode
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.app.domain.util.AppLogger
import com.thrym.app.fakes.FakeAppPreferencesRepository
import com.thrym.app.fakes.FakeGamificationRepository
import com.thrym.app.fakes.FakeStatisticsRepository
import com.thrym.app.fakes.FakeWorkoutRepository
import com.thrym.shared.gamification.ChapterStatus
import com.thrym.shared.gamification.GamificationState
import com.thrym.shared.gamification.HeroJourney
import com.thrym.shared.gamification.LevelCurve
import com.thrym.shared.gamification.SessionXp
import com.thrym.shared.gamification.XpEntry
import com.thrym.shared.gamification.XpSource
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var workoutRepo: FakeWorkoutRepository
    private lateinit var statisticsRepo: FakeStatisticsRepository
    private lateinit var preferencesRepo: FakeAppPreferencesRepository
    private lateinit var gamificationRepo: FakeGamificationRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        workoutRepo = FakeWorkoutRepository()
        statisticsRepo = FakeStatisticsRepository()
        preferencesRepo = FakeAppPreferencesRepository()
        gamificationRepo = FakeGamificationRepository(state = stateWith(xp = 2_300L))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(workouts: WorkoutRepository = workoutRepo) =
        AchievementsViewModel(workouts, statisticsRepo, preferencesRepo, gamificationRepo).also {
            // Same scheduler as the test, so advanceUntilIdle also runs the evaluation.
            it.evaluationDispatcher = testDispatcher
        }

    private fun record(id: Long) = PersonalRecord(
        id = id,
        exerciseId = 1L,
        type = RecordType.MAX_WEIGHT,
        value = 100.0 + id,
        achievedAt = LocalDateTime.now().plusMinutes(id)
    )

    /** Runs [block] with AppLogger silenced, as on the JVM it would reach android.util.Log. */
    private inline fun <T> withSilentLogger(block: () -> T): T {
        mockkObject(AppLogger)
        return try {
            every { AppLogger.w(any(), any(), any()) } returns Unit
            block()
        } finally {
            unmockkObject(AppLogger)
        }
    }

    /** A state of [xp] XP: one workout with a training and a record, the rest in a second one. */
    private fun stateWith(xp: Long): GamificationState {
        val first = SessionXp(
            sessionId = 1L,
            xp = 140,
            entries = listOf(XpEntry(XpSource.TRAINING, 1, 100), XpEntry(XpSource.RECORD, 1, 40)),
            totalAfter = 140L
        )
        val second = SessionXp(
            sessionId = 2L,
            xp = (xp - 140L).toInt(),
            entries = listOf(XpEntry(XpSource.TRAINING, 1, (xp - 140L).toInt())),
            totalAfter = xp
        )
        return GamificationState(xp, LevelCurve.progress(xp), listOf(first, second))
    }

    private fun completedSession(id: Long): WorkoutSession {
        val start = LocalDateTime.now().minusDays(id)
        return WorkoutSession(id = id, startTime = start, endTime = start.plusHours(1), durationSeconds = 3600)
    }

    private fun AchievementsViewModel.ready(): AchievementsContent.Ready {
        val content = uiState.value.content
        assertTrue("Erwartet Ready, war $content", content is AchievementsContent.Ready)
        return content as AchievementsContent.Ready
    }

    @Test
    fun `zeigt Stand, Heldenreise und XP je Quelle`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(true, vm.uiState.value.gamificationEnabled)
        val ready = vm.ready()
        val progress = LevelCurve.progress(2_300L)
        assertEquals(progress, ready.progress)
        assertEquals(HeroJourney.chapters(progress), ready.chapters)
        assertEquals(
            listOf(ChapterStatus.DONE, ChapterStatus.CURRENT) + List(6) { ChapterStatus.LOCKED },
            ready.chapters.map { it.status }
        )
        assertEquals(HeroJourney.SOURCE_ORDER, ready.xpBySource.map { it.source })
        val training = ready.xpBySource.first { it.source == XpSource.TRAINING }
        assertEquals(2, training.count)
        assertEquals(2_260L, training.xp)
        val record = ready.xpBySource.first { it.source == XpSource.RECORD }
        assertEquals(1, record.count)
        assertEquals(40L, record.xp)
        assertEquals(2_300L, ready.xpBySource.sumOf { it.xp })
        assertEquals(1, gamificationRepo.evaluateCalls)
    }

    @Test
    fun `bis die Einstellung geladen ist, ist offen ob es Erfolge gibt`() = runTest(testDispatcher) {
        val vm = createViewModel()

        assertNull(vm.uiState.value.gamificationEnabled)
        assertEquals(AchievementsContent.Loading, vm.uiState.value.content)
    }

    @Test
    fun `ausgeschaltete Spielelemente zeigen keine Erfolge und rechnen nichts`() = runTest(testDispatcher) {
        preferencesRepo = FakeAppPreferencesRepository(AppPreferences(gamificationEnabled = false))
        val vm = createViewModel()
        advanceUntilIdle()

        workoutRepo.addSession(completedSession(1L))
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.gamificationEnabled)
        assertEquals(0, gamificationRepo.evaluateCalls)
    }

    @Test
    fun `der Schalter fuer Spielelemente wirkt sofort`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.gamificationEnabled)

        preferencesRepo.updateGamificationEnabled(false)
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.gamificationEnabled)

        // Off, a new workout computes nothing.
        workoutRepo.addSession(completedSession(1L))
        advanceUntilIdle()
        assertEquals(1, gamificationRepo.evaluateCalls)

        gamificationRepo.state = stateWith(xp = 9_000L)
        preferencesRepo.updateGamificationEnabled(true)
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.gamificationEnabled)
        assertEquals(LevelCurve.progress(9_000L), vm.ready().progress)
        assertEquals(2, gamificationRepo.evaluateCalls)
    }

    @Test
    fun `ein Fehler zeigt den Fehlerzustand und Erneut versuchen rechnet nochmal`() = runTest(testDispatcher) {
        gamificationRepo.evaluateFailure = IllegalStateException("xp boom")
        val vm = withSilentLogger {
            createViewModel().also {
                advanceUntilIdle()
                verify { AppLogger.w("AchievementsVM", any(), any()) }
            }
        }

        assertEquals(true, vm.uiState.value.gamificationEnabled)
        assertEquals(AchievementsContent.Error, vm.uiState.value.content)

        gamificationRepo.evaluateFailure = null
        val gate = CompletableDeferred<Unit>()
        gamificationRepo.evaluateGate = gate
        vm.retry()
        // The retry shows progress until the new result is there.
        advanceUntilIdle()
        assertEquals(AchievementsContent.Loading, vm.uiState.value.content)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(LevelCurve.progress(2_300L), vm.ready().progress)
        assertEquals(2, gamificationRepo.evaluateCalls)
    }

    @Test
    fun `ein neues beendetes Training rechnet die Erfolge neu`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(LevelCurve.progress(2_300L), vm.ready().progress)

        gamificationRepo.state = stateWith(xp = 2_700L)
        val gate = CompletableDeferred<Unit>()
        gamificationRepo.evaluateGate = gate
        workoutRepo.addSession(completedSession(1L))
        // The shown journey stays while it refreshes; no flash of the progress indicator.
        advanceUntilIdle()
        assertEquals(2, gamificationRepo.evaluateCalls)
        assertEquals(LevelCurve.progress(2_300L), vm.ready().progress)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(LevelCurve.progress(2_700L), vm.ready().progress)
        assertEquals(2, gamificationRepo.evaluateCalls)
    }

    @Test
    fun `nur Einstellungen, die die XP aendern, rechnen neu`() = runTest(testDispatcher) {
        createViewModel()
        advanceUntilIdle()
        assertEquals(1, gamificationRepo.evaluateCalls)

        preferencesRepo.updateThemeMode(ThemeMode.LIGHT)
        advanceUntilIdle()
        assertEquals(1, gamificationRepo.evaluateCalls)

        preferencesRepo.updateWeekStart(WeekStart.SUNDAY)
        advanceUntilIdle()
        assertEquals(2, gamificationRepo.evaluateCalls)
    }

    @Test
    fun `ein neuer Rekord rechnet die Erfolge neu`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(1, gamificationRepo.evaluateCalls)

        // An edited set can change a record without a new completed workout.
        gamificationRepo.state = stateWith(xp = 2_340L)
        statisticsRepo.addRecord(record(1L))
        advanceUntilIdle()

        assertEquals(2, gamificationRepo.evaluateCalls)
        assertEquals(LevelCurve.progress(2_340L), vm.ready().progress)
    }

    @Test
    fun `refresh rechnet neu und zeigt bis dahin den alten Stand`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(LevelCurve.progress(2_300L), vm.ready().progress)

        gamificationRepo.state = stateWith(xp = 2_400L)
        val gate = CompletableDeferred<Unit>()
        gamificationRepo.evaluateGate = gate
        vm.refresh()
        advanceUntilIdle()
        // Still the shown journey, no progress indicator, while the new one is computed.
        assertEquals(2, gamificationRepo.evaluateCalls)
        assertEquals(LevelCurve.progress(2_300L), vm.ready().progress)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(LevelCurve.progress(2_400L), vm.ready().progress)
        assertEquals(2, gamificationRepo.evaluateCalls)
    }

    @Test
    fun `refresh waehrend der ersten Berechnung rechnet nicht doppelt`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        gamificationRepo.evaluateGate = gate
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(1, gamificationRepo.evaluateCalls)
        assertEquals(AchievementsContent.Loading, vm.uiState.value.content)

        // The screen asks as soon as the achievements show; the running evaluation is current.
        vm.refresh()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gamificationRepo.evaluateCalls)
        assertEquals(LevelCurve.progress(2_300L), vm.ready().progress)
    }

    @Test
    fun `ausgeschaltete Spielelemente rechnen auch bei refresh und neuen Rekorden nichts`() = runTest(testDispatcher) {
        preferencesRepo = FakeAppPreferencesRepository(AppPreferences(gamificationEnabled = false))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refresh()
        vm.retry()
        statisticsRepo.addRecord(record(1L))
        workoutRepo.addSession(completedSession(1L))
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.gamificationEnabled)
        assertEquals(AchievementsContent.Loading, vm.uiState.value.content)
        assertEquals(0, gamificationRepo.evaluateCalls)
    }

    @Test
    fun `ein Fehler beim Beobachten zeigt den Fehlerzustand und Erneut versuchen beobachtet neu`() =
        runTest(testDispatcher) {
            var failNext = true
            var subscriptions = 0
            // The first observation of the completed workouts fails, every later one works.
            val workouts = object : WorkoutRepository by workoutRepo {
                override fun getAllCompletedSessions(): Flow<List<WorkoutSession>> {
                    subscriptions++
                    return if (failNext) {
                        failNext = false
                        flow { throw IOException("db boom") }
                    } else {
                        workoutRepo.getAllCompletedSessions()
                    }
                }
            }
            val vm = withSilentLogger {
                createViewModel(workouts).also {
                    advanceUntilIdle()
                    verify { AppLogger.w("AchievementsVM", any(), any()) }
                }
            }

            assertEquals(true, vm.uiState.value.gamificationEnabled)
            assertEquals(AchievementsContent.Error, vm.uiState.value.content)
            assertEquals(0, gamificationRepo.evaluateCalls)

            vm.retry()
            advanceUntilIdle()

            assertEquals(2, subscriptions)
            assertEquals(LevelCurve.progress(2_300L), vm.ready().progress)
            assertEquals(1, gamificationRepo.evaluateCalls)

            // Observed again: a new workout still computes the achievements anew.
            workoutRepo.addSession(completedSession(1L))
            advanceUntilIdle()
            assertEquals(2, gamificationRepo.evaluateCalls)
        }
}
