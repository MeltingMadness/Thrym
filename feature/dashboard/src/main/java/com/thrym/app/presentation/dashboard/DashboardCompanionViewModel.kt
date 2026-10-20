package com.thrym.app.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrym.app.domain.repository.CompanionRepository
import com.thrym.app.domain.util.AppLogger
import com.thrym.shared.companion.CompanionCharacter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The companion on the home screen and the day the user hid it. */
data class DashboardCompanionState(
    val loaded: Boolean = false,
    val character: CompanionCharacter? = null,
    val hiddenEpochDay: Long? = null
)

/**
 * Companion settings for the home screen. Kept apart from [DashboardViewModel], which loads the
 * training data; whether the companion appears today is decided in the UI with
 * [com.thrym.shared.companion.CompanionRules.homeAppearance].
 */
class DashboardCompanionViewModel(
    private val companionRepository: CompanionRepository
) : ViewModel() {

    val state: StateFlow<DashboardCompanionState> = combine(
        companionRepository.character,
        companionRepository.preferences
    ) { character, preferences ->
        DashboardCompanionState(
            loaded = true,
            character = character,
            hiddenEpochDay = preferences.hiddenEpochDay
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardCompanionState())

    fun hideForDay(epochDay: Long) {
        viewModelScope.launch {
            try {
                companionRepository.hideForDay(epochDay)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Begleiter konnte nicht ausgeblendet werden: ${e.message}", e)
            }
        }
    }

    private companion object {
        const val TAG = "DashboardCompanionVM"
    }
}
