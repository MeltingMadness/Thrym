package com.thrym.app.presentation.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.presentation.common.markLadderUnit
import com.thrym.app.presentation.common.markTitle
import com.thrym.app.presentation.common.rememberUiStrings
import com.thrym.app.presentation.common.skeletonDateFormatter
import com.thrym.app.presentation.theme.AthleticLabel
import com.thrym.app.presentation.theme.GlassLevel
import com.thrym.app.presentation.theme.liquidGlass
import com.thrym.core.designsystem.R
import com.thrym.shared.recognition.StrengthMarkLadder
import java.time.Instant
import java.time.ZoneId

/** How many reached marks the row shows; older ones stay in the history. */
private const val VISIBLE_MARKS = 4

/**
 * One ladder of round marks: "MARKEN · KG", the last reached marks with month and year, then the
 * next one dimmed. The next mark appears only here, never during a workout, so nobody chases a
 * round number with a single attempt.
 */
@Composable
internal fun ExerciseMarksCard(ladder: StrengthMarkLadder, unitSystem: UnitSystem, modifier: Modifier = Modifier) {
    val strings = rememberUiStrings()
    val locale = currentLocale()
    val zone = ZoneId.systemDefault()
    val monthYear = skeletonDateFormatter(locale, "MMMyy")
    val fullDate = skeletonDateFormatter(locale, "yMMMd")
    val shown = ladder.reached.takeLast(VISIBLE_MARKS)
    val last = ladder.reached.last()
    fun dateOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.recognition_marks_title, strings.markLadderUnit(ladder.kind, unitSystem)).uppercase(locale),
                    style = AthleticLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.recognition_marks_last, dateOf(last.reachedAtEpochMillis).format(fullDate)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { reached ->
                    val date = dateOf(reached.reachedAtEpochMillis)
                    val description = stringResource(R.string.recognition_marks_reached_cd, strings.markTitle(reached.mark), date.format(fullDate))
                    MarkCell(
                        value = reached.mark.value.toString(),
                        caption = date.format(monthYear),
                        dimmed = false,
                        description = description,
                        modifier = Modifier.weight(1f)
                    )
                }
                ladder.next?.let { next ->
                    MarkCell(
                        value = next.value.toString(),
                        caption = stringResource(R.string.recognition_marks_next),
                        dimmed = true,
                        description = stringResource(R.string.recognition_marks_next_cd, strings.markTitle(next)),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text(
                text = stringResource(R.string.recognition_marks_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (isLiquidGlass()) {
        Column(modifier.fillMaxWidth().liquidGlass(GlassLevel.STANDARD, RoundedCornerShape(24.dp))) { content() }
    } else {
        ThrymSurfaceCard(modifier = modifier.fillMaxWidth(), tone = ThrymSurfaceTone.MUTED) { content() }
    }
}

@Composable
private fun MarkCell(value: String, caption: String, dimmed: Boolean, description: String, modifier: Modifier) {
    val ink = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = if (dimmed) FontWeight.Medium else FontWeight.Bold,
            style = TextStyle(fontFeatureSettings = "tnum"),
            color = ink,
            maxLines = 1
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
