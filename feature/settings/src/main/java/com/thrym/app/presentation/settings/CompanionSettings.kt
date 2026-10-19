package com.thrym.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.thrym.app.domain.repository.CompanionRepository
import com.thrym.app.domain.util.AppLogger
import com.thrym.app.presentation.companion.CompanionSprite
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.feature.settings.R
import com.thrym.shared.companion.CompanionCharacter
import com.thrym.shared.companion.CompanionChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class CompanionSettingsState(
    val choice: CompanionChoice = CompanionChoice.AUTO,
    val character: CompanionCharacter? = null
)

/** The companion choice in the settings, kept apart from [SettingsViewModel]. */
class CompanionSettingsViewModel(
    private val companionRepository: CompanionRepository
) : ViewModel() {

    val state: StateFlow<CompanionSettingsState> = combine(
        companionRepository.preferences,
        companionRepository.character
    ) { preferences, character ->
        CompanionSettingsState(choice = preferences.choice, character = character)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompanionSettingsState())

    fun updateChoice(choice: CompanionChoice) {
        viewModelScope.launch {
            try {
                companionRepository.updateChoice(choice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Begleiter konnte nicht gespeichert werden: ${e.message}", e)
            }
        }
    }

    private companion object {
        const val TAG = "CompanionSettingsVM"
    }
}

/** Choice of the companion with a preview of the one that is active. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionSettingsContent(
    viewModel: CompanionSettingsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dims = thrymDimens

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dims.spacingMd)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
            ) {
                CompanionChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = state.choice == choice,
                        onClick = { viewModel.updateChoice(choice) },
                        label = { Text(stringResource(choice.labelRes())) },
                        colors = ThrymInteractiveColors.filterChip()
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_companion_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.character?.let { character ->
            CompanionSprite(
                character = character,
                modifier = Modifier.size(72.dp),
                size = 72.dp
            )
        }
    }
}

private fun CompanionChoice.labelRes(): Int = when (this) {
    CompanionChoice.AUTO -> R.string.settings_companion_auto
    CompanionChoice.ODIN -> R.string.settings_companion_odin
    CompanionChoice.VALKYRIE -> R.string.settings_companion_valkyrie
    CompanionChoice.OFF -> R.string.settings_companion_off
}
