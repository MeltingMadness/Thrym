package com.thrym.app.presentation.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.completionFactTexts
import com.thrym.app.presentation.common.rememberUiStrings
import com.thrym.app.presentation.common.toSharedUnit
import com.thrym.shared.recognition.CompletionFactKind
import com.thrym.shared.recognition.CompletionFacts
import com.thrym.shared.recognition.StrengthMark
import com.thrym.shared.recognition.StrengthMarks
import com.thrym.shared.workout.RecordSetInput

/**
 * Marks of the workout [sessionId] per exercise (see [StrengthMarks.sessionMarks]); exercises
 * without a new mark are left out. [categories] maps exercise ids to the stored category code.
 */
internal fun sessionMarksByExercise(
    sessionId: Long,
    inputs: List<RecordSetInput>,
    categories: Map<Long, String>,
    unitSystem: UnitSystem
): Map<Long, List<StrengthMark>> = inputs.groupBy { it.exerciseId }
    .mapNotNull { (exerciseId, sets) ->
        val category = categories[exerciseId] ?: return@mapNotNull null
        val marks = StrengthMarks.sessionMarks(sessionId, sets, category, unitSystem.toSharedUnit())
        if (marks.isEmpty()) null else exerciseId to marks
    }
    .toMap()

/**
 * At most two facts under the summary: "Wochenziel erreicht / 3 von 3 · 8 Wochen in Folge",
 * "Dein 100. Training / Seit dem 14. März 2025" or "Wieder dabei / Nach 26 Tagen Pause".
 * Plain rows without animation; each row reads as one sentence. Shows nothing without facts.
 */
@Composable
internal fun CompletionFactsBlock(facts: CompletionFacts, modifier: Modifier = Modifier) {
    val strings = rememberUiStrings()
    val texts = strings.completionFactTexts(facts)
    if (texts.isEmpty()) return
    val glass = isLiquidGlass()
    ThrymSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.ELEVATED,
        shape = if (glass) RoundedCornerShape(26.dp) else MaterialTheme.shapes.large
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            texts.forEachIndexed { index, text ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = factIcon(text.kind),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text.detail,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun factIcon(kind: CompletionFactKind): ImageVector = when (kind) {
    CompletionFactKind.WEEKLY_GOAL -> Icons.Outlined.EventAvailable
    CompletionFactKind.JUBILEE -> Icons.Outlined.Flag
    CompletionFactKind.COMEBACK -> Icons.Outlined.Replay
}
