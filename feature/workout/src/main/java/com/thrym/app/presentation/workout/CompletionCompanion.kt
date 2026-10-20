package com.thrym.app.presentation.workout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thrym.app.domain.repository.CompanionRepository
import com.thrym.app.presentation.companion.CompanionCard
import com.thrym.shared.companion.CompanionRules
import org.koin.compose.koinInject

/**
 * The companion on the workout summary: it celebrates new records with a jump and cheers for a
 * finished workout otherwise. Nothing shows when the user switched the companion off.
 */
@Composable
internal fun CompletionCompanion(
    sessionId: Long,
    recordCount: Int,
    modifier: Modifier = Modifier,
    repository: CompanionRepository = koinInject()
) {
    val character by repository.character.collectAsStateWithLifecycle(initialValue = null)
    character?.let {
        CompanionCard(
            character = it,
            line = CompanionRules.completionLine(sessionId, recordCount),
            entrance = CompanionRules.completionAnimation(recordCount),
            modifier = modifier
        )
    }
}
