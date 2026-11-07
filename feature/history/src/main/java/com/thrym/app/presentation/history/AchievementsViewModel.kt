package com.thrym.app.presentation.history

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.GamificationRepository
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.app.domain.util.AppLogger
import com.thrym.shared.gamification.HeroJourney
import com.thrym.shared.gamification.JourneyChapter
import com.thrym.shared.gamification.LevelProgress
import com.thrym.shared.gamification.XpTotal
import com.thrym.shared.recognition.WeeklyGoalChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the achievements show below the switcher. */
sealed interface AchievementsContent {
    data object Loading : AchievementsContent

    data object Error : AchievementsContent

    /** The current stand, the eight chapters and the XP per source, all from one evaluation. */
    data class Ready(
        val progress: LevelProgress,
        val chapters: List<JourneyChapter>,
        val xpBySource: List<XpTotal>
    ) : AchievementsContent
}

data class AchievementsUiState(
    /** Null until the setting has loaded, so the switcher never flashes for someone who turned it off. */
    val gamificationEnabled: Boolean? = null,
    val content: AchievementsContent = AchievementsContent.Loading
)

/**
 * The achievements of the history tab. They come from the same derived state as the level card
 * on the dashboard and are computed again when a completed workout or a setting they depend on
 * changes. With game elements off nothing is computed.
 */
class AchievementsViewModel(
    private val workoutRepository: WorkoutRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val gamificationRepository: GamificationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AchievementsUiState())
    val uiState: StateFlow<AchievementsUiState> = _uiState.asStateFlow()

    private val retries = MutableStateFlow(0)

    /** The evaluation reads the whole history, so it runs off the main thread. */
    @VisibleForTesting
    var evaluationDispatcher: CoroutineDispatcher = Dispatchers.Default

    init {
        observeJourney()
    }

    /** Computes the achievements again after an error. */
    fun retry() {
        retries.update { it + 1 }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeJourney() {
        viewModelScope.launch {
            appPreferencesRepository.preferences
                .map { JourneySettings(it.gamificationEnabled, it.weekStart, it.weeklyGoalHistory, it.unitSystem) }
                .distinctUntilChanged()
                .onEach { settings -> _uiState.update { it.copy(gamificationEnabled = settings.gamificationEnabled) } }
                .flatMapLatest { settings ->
                    if (settings.gamificationEnabled) {
                        combine(workoutRepository.getAllCompletedSessions(), retries) { _, _ -> Unit }
                    } else {
                        emptyFlow()
                    }
                }
                .catch { e ->
                    AppLogger.w(TAG, "Erfolge nicht beobachtet: ${e.message}", e)
                    _uiState.update { it.copy(content = AchievementsContent.Error) }
                }
                .collectLatest {
                    // A refresh keeps the shown journey; only a first load or a retry shows progress.
                    _uiState.update { state ->
                        if (state.content is AchievementsContent.Ready) state else state.copy(content = AchievementsContent.Loading)
                    }
                    val content = evaluate()
                    _uiState.update { it.copy(content = content) }
                }
        }
    }

    private suspend fun evaluate(): AchievementsContent = try {
        withContext(evaluationDispatcher) {
            val state = gamificationRepository.evaluate()
            AchievementsContent.Ready(
                progress = state.progress,
                chapters = HeroJourney.chapters(state.progress),
                xpBySource = HeroJourney.xpBySource(state)
            )
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLogger.w(TAG, "Erfolge nicht berechnet: ${e.message}", e)
        AchievementsContent.Error
    }

    /** The settings the evaluation depends on; other settings do not trigger a new one. */
    private data class JourneySettings(
        val gamificationEnabled: Boolean,
        val weekStart: WeekStart,
        val weeklyGoalHistory: List<WeeklyGoalChange>,
        val unitSystem: UnitSystem
    )

    private companion object {
        const val TAG = "AchievementsVM"
    }
}
