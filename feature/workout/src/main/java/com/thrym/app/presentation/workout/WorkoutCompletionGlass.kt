package com.thrym.app.presentation.workout

import com.thrym.feature.workout.R as UxR
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.util.DateFormatting
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.tracksAddedLoad
import com.thrym.app.presentation.common.setLoadText
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.presentation.theme.AthleticLabel
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.theme.GlassLevel
import com.thrym.app.presentation.theme.liquidGlass
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import com.thrym.shared.workout.SessionRecordSummary
/**
 * Workout summary in Liquid Glass: "Geschafft.", three lenses (duration, volume, exercises),
 * the record board on tinted glass, the logged sets, and "Fertig" as the bright main action.
 * The follow-up actions (plan changes, progression) are the same as in Ember and come in as slots.
 */
@Composable
internal fun GlassWorkoutCompletion(
    session: WorkoutSession,
    rows: List<ExerciseWithSets>,
    recordSummary: SessionRecordSummary?,
    unitSystem: UnitSystem,
    onClose: () -> Unit,
    onDetails: () -> Unit,
    followUps: @Composable () -> Unit
) {
    val sets = rows.flatMap { it.sets }.filter { it.reps > 0 }.distinctBy { it.id }
    val remaining = rows.sumOf { it.openSlotCount() }
    val trainedRows = rows.filter { row -> row.sets.any { it.reps > 0 } }
    val volumeText = WeightFormatting.formatVolume(sets.sumOf { it.weightKg * it.reps }, unitSystem)
    val volumeNumber = volumeText.substringBeforeLast(' ')
    val volumeUnit = volumeText.substringAfterLast(' ')

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = listOfNotNull(
                        session.startTime.format(DateFormatting.weekdayDayMonth(currentLocale())),
                        session.name.takeIf { it.isNotBlank() }
                    ).joinToString(" · ").uppercase(currentLocale()),
                    style = AthleticLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(
                        if (remaining > 0) R.string.workout_summary_headline_partial else WorkoutR.string.workout_glass_done_headline
                    ),
                    fontSize = if (remaining > 0) 34.sp else 54.sp,
                    lineHeight = if (remaining > 0) 38.sp else 56.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.5).sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                if (remaining > 0) {
                    Text(
                        pluralStringResource(R.plurals.workout_summary_partial_subtitle, sets.size, sets.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val minutes = (session.durationSeconds / 60).toInt()
                CompletionLens(
                    value = if (session.durationSeconds < 60) "< 1" else minutes.toString(),
                    unit = stringResource(WorkoutR.string.workout_glass_unit_minutes),
                    label = stringResource(R.string.workout_summary_duration),
                    modifier = Modifier.weight(1f)
                )
                CompletionLens(
                    value = volumeNumber,
                    unit = volumeUnit,
                    label = stringResource(R.string.workout_summary_volume),
                    modifier = Modifier.weight(1f)
                )
                CompletionLens(
                    value = trainedRows.size.toString(),
                    unit = pluralStringResource(WorkoutR.plurals.workout_glass_exercise_unit, trainedRows.size),
                    label = stringResource(WorkoutR.string.workout_glass_exercises_label),
                    modifier = Modifier.weight(1f)
                )
            }

            recordSummary?.let {
                CompletionCompanion(session.id, it.recordCount)
                CompletionRecordsCard(it, rows, unitSystem)
            }

            trainedRows.forEach { row ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(GlassLevel.STANDARD, RoundedCornerShape(26.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(row.exercise.localizedName(), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    row.sets.filter { it.reps > 0 }.forEach { set ->
                        Text(
                            pluralStringResource(
                                R.plurals.workout_summary_set_line,
                                set.reps,
                                set.setNumber,
                                setLoadText(set.weightKg, unitSystem, row.exercise.tracksAddedLoad),
                                set.reps
                            ),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            followUps()
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .liquidGlass(GlassLevel.STANDARD, RoundedCornerShape(27.dp))
                    .clickable(role = Role.Button, onClick = onDetails),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.workout_summary_open_details), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
            GlassPrimaryButton(text = stringResource(R.string.workout_summary_done), enabled = true, onClick = onClose)
        }
    }
}

@Composable
private fun CompletionLens(value: String, unit: String, label: String, modifier: Modifier) {
    val description = stringResource(UxR.string.workout_completion_metric_cd, label, value, unit)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .aspectRatio(1f)
                .liquidGlass(GlassLevel.STANDARD, CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                fontSize = if (value.length > 5) 22.sp else 28.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(fontFeatureSettings = "tnum"),
                maxLines = 1
            )
            Text(unit, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(label.uppercase(currentLocale()), style = AthleticLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
