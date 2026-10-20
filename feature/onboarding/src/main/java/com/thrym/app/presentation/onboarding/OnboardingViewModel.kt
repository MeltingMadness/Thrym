package com.thrym.app.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.CompanionRepository
import com.thrym.app.domain.repository.OnboardingRepository
import com.thrym.app.domain.util.AppLogger
import com.thrym.shared.companion.CompanionCharacter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The pages of the introduction, in order.
 *
 * A later step (for example a profile for the weekly goal and the trophies) goes in here; the
 * screen pages through whatever this list contains.
 */
enum class OnboardingStep {
    WELCOME,
    UNITS,
    START
}

class OnboardingViewModel(
    private val onboardingRepository: OnboardingRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    companionRepository: CompanionRepository
) : ViewModel() {

    val steps: List<OnboardingStep> = OnboardingStep.entries

    private val _stepIndex = MutableStateFlow(0)
    val stepIndex: StateFlow<Int> = _stepIndex.asStateFlow()

    val unitSystem: StateFlow<UnitSystem> = appPreferencesRepository.preferences
        .map { it.unitSystem }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnitSystem.METRIC)

    /** The companion who guides through the steps; `null` while loading or when switched off. */
    val companion: StateFlow<CompanionCharacter?> = companionRepository.character
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var finishing = false

    fun next() {
        _stepIndex.value = (_stepIndex.value + 1).coerceAtMost(steps.lastIndex)
    }

    fun back(): Boolean {
        if (_stepIndex.value == 0) return false
        _stepIndex.value -= 1
        return true
    }

    fun skipToStart() {
        _stepIndex.value = steps.lastIndex
    }

    fun selectUnitSystem(unitSystem: UnitSystem) {
        viewModelScope.launch {
            try {
                appPreferencesRepository.updateUnitSystem(unitSystem)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Einheit konnte nicht gespeichert werden: ${e.message}", e)
            }
        }
    }

    /**
     * Marks the introduction as done and then calls [onDone]. A failed write still lets the user
     * into the app; the introduction then appears once more on the next start.
     */
    fun finish(onDone: () -> Unit) {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            try {
                onboardingRepository.markCompleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Onboarding konnte nicht als erledigt gespeichert werden: ${e.message}", e)
            }
            onDone()
        }
    }

    private companion object {
        const val TAG = "OnboardingVM"
    }
}
