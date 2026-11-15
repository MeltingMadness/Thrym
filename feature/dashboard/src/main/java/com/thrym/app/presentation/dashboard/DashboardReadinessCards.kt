package com.thrym.app.presentation.dashboard

import com.thrym.feature.dashboard.R as UxR
import com.thrym.core.designsystem.R as DesignR
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.domain.util.WeightFormatting
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.common.label
import com.thrym.app.presentation.common.localizedExerciseName
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText
import com.thrym.feature.dashboard.R
import com.thrym.shared.readiness.ExerciseTrend
import com.thrym.shared.readiness.ExerciseTrendStatus
import com.thrym.shared.readiness.MuscleGroupContext
import com.thrym.shared.readiness.MuscleGroupFlag
import com.thrym.shared.readiness.ReadinessAssessment
import com.thrym.shared.readiness.TrainingTrendAssessment
import com.thrym.shared.readiness.TrainingTrendStatus
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Grafischer Trainingstrend aus dem geteilten Readiness-Kern.
 *
 * Die Karte ersetzt die frühere Readiness-Prozentanzeige: Statt eines
 * erfundenen Werts zeigt sie den mehrwöchigen Vergleich je Übung, die
 * zugrunde liegende Evidenz und die Datenqualität. Ein Index erscheint nur,
 * wenn der Kern genug vergleichbare Einheiten gefunden hat.
 */
@Composable
fun TrainingTrendCard(
    trend: DashboardTrendState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val assessment = trend.assessment
    DashboardPanel(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dashboard_trend_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.dashboard_trend_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (trend.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            when {
                assessment != null -> TrainingTrendContent(assessment)
                trend.error != null -> {
                    Text(
                        text = trend.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticText.warning
                    )
                    TextButton(onClick = onRetry, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(R.string.dashboard_trend_retry))
                    }
                }
                else -> Text(
                    text = stringResource(R.string.dashboard_trend_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.dashboard_trend_footer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/**
 * Kompakter Trendblock: Ring, Status und Evidenz sind immer sichtbar, alle
 * Details (Übungen, Gründe, Datenqualität) erst auf Wunsch.
 */
@Composable
private fun TrainingTrendContent(assessment: ReadinessAssessment) {
    val trend = assessment.trainingTrend
    var detailsExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TrainingTrendHeader(trend)
        TextButton(
            onClick = { detailsExpanded = !detailsExpanded },
            modifier = Modifier.flushStartLink(),
            colors = ThrymInteractiveColors.textButton()
        ) {
            Text(
                text = stringResource(
                    if (detailsExpanded) {
                        R.string.dashboard_trend_hide_details
                    } else {
                        R.string.dashboard_trend_show_details
                    }
                )
            )
        }
        if (detailsExpanded) {
            TrainingTrendDetails(trend)
        }
    }
}

/** Immer sichtbar: Index-Ring, Statuszeile und die Evidenzzahlen. */
@Composable
private fun TrainingTrendHeader(trend: TrainingTrendAssessment) {
    val statusColor = when (trend.status) {
        TrainingTrendStatus.MULTIPLE_EXERCISE_DECLINE,
        TrainingTrendStatus.SINGLE_EXERCISE_DECLINE -> MaterialTheme.semanticText.warning
        TrainingTrendStatus.NO_NOTABLE_STRAIN -> MaterialTheme.semanticText.teal
        TrainingTrendStatus.INSUFFICIENT_DATA -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TrainingIndexIndicator(index = trend.trainingIndex)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(DashboardReadinessText.trendStatusLabel(trend.status)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
            Text(
                text = stringResource(
                    R.string.dashboard_trend_evidence,
                    pluralStringResource(
                        R.plurals.dashboard_trend_evidence_exercises,
                        trend.analyzedExerciseCount,
                        trend.analyzedExerciseCount
                    ),
                    trend.exercisesWithSufficientHistory,
                    pluralStringResource(
                        R.plurals.dashboard_trend_evidence_sessions,
                        trend.dataQuality.comparableUnitCount,
                        trend.dataQuality.comparableUnitCount
                    )
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Aufgeklappte Details.
 *
 * Alle Gründe des Kerns werden gezeigt; keiner wird stillschweigend
 * abgeschnitten. Die Übungsliste ist nicht mehr künstlich auf fünf Zeilen
 * begrenzt, weil die Detailansicht ohnehin nur auf Wunsch erscheint.
 */
@Composable
private fun TrainingTrendDetails(trend: TrainingTrendAssessment) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(
                R.string.dashboard_trend_confidence,
                stringResource(DashboardReadinessText.confidenceLabel(trend.confidence))
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (trend.trainingIndex == null) {
            Text(
                text = stringResource(R.string.dashboard_trend_no_number),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val comparable = trend.exercises
            .filter { it.status != ExerciseTrendStatus.EXCLUDED }
            .filter { it.comparableUnitCount > 0 }
        if (comparable.isNotEmpty()) {
            Text(
                text = stringResource(R.string.dashboard_trend_exercises_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            comparable.forEach { exercise -> ExerciseTrendRow(exercise) }
        }

        if (trend.deloadSuggested) {
            Text(
                text = stringResource(R.string.dashboard_trend_deload_suggested),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticText.warning
            )
        }
        if (trend.deloadActive) {
            Text(
                text = stringResource(R.string.dashboard_trend_deload_active),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        trend.reasons.distinctBy { it.code }.forEach { reason ->
            Text(
                text = stringResource(DashboardReadinessText.reasonLabel(reason.code)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val quality = trend.dataQuality
        if (quality.missingRpeSetCount > 0 || quality.unknownIntentionSetCount > 0 ||
            quality.excludedDeloadUnitCount > 0
        ) {
            Text(
                text = stringResource(R.string.dashboard_trend_quality_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (quality.missingRpeSetCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.dashboard_trend_quality_rpe,
                        quality.missingRpeSetCount,
                        quality.missingRpeSetCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (quality.unknownIntentionSetCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.dashboard_trend_quality_intention,
                        quality.unknownIntentionSetCount,
                        quality.unknownIntentionSetCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (quality.excludedDeloadUnitCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.dashboard_trend_quality_deload,
                        quality.excludedDeloadUnitCount,
                        quality.excludedDeloadUnitCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Index nur mit Evidenz: ohne Index erscheint bewusst keine Zahl. */
@Composable
private fun TrainingIndexIndicator(index: Int?) {
    Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        if (index != null) {
            CircularProgressIndicator(
                progress = { index.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                color = MaterialTheme.semantic.teal,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                strokeWidth = 5.dp
            )
            Text(
                text = stringResource(R.string.dashboard_trend_index_value, index),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = stringResource(R.string.dashboard_trend_no_index),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Eine Übung als Balken gegenüber ihrer eigenen Basis.
 *
 * Der Balken zeigt den Betrag der Veränderung, die Farbe die Richtung; ohne
 * belastbare Zahl bleibt die Zeile bei "zu wenig Daten" stehen.
 */
@Composable
private fun ExerciseTrendRow(exercise: ExerciseTrend) {
    val change = exercise.changePercent
    val directionColor = when (exercise.status) {
        ExerciseTrendStatus.DECLINING -> MaterialTheme.semantic.warning
        ExerciseTrendStatus.IMPROVING -> MaterialTheme.semantic.teal
        else -> MaterialTheme.colorScheme.outline
    }
    val directionTextColor = when (exercise.status) {
        ExerciseTrendStatus.DECLINING -> MaterialTheme.semanticText.warning
        ExerciseTrendStatus.IMPROVING -> MaterialTheme.semanticText.teal
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scale = change?.let { (kotlin.math.abs(it) / 20.0).coerceIn(0.05, 1.0).toFloat() } ?: 0f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = localizedExerciseName(exercise.exerciseName).ifBlank { "?" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(scale)
                    .fillMaxHeight()
                    .background(directionColor)
            )
        }
        Text(
            text = change?.let { formatChange(it) }
                ?: stringResource(DashboardReadinessText.exerciseStatusLabel(exercise.status)),
            style = MaterialTheme.typography.labelSmall,
            color = directionTextColor
        )
    }
}

/** Signed change in the UI locale: "+2.5%" (en), "+2,5 %" (de), no trailing ".0". */
@Composable
private fun formatChange(changePercent: Double): String {
    val rounded = kotlin.math.round(changePercent * 10) / 10
    val sign = if (rounded > 0) "+" else ""
    val number = WeightFormatting.formatNumber(rounded, maxFractionDigits = 1, locale = currentLocale())
    return stringResource(DesignR.string.common_percent_value, sign + number)
}

/**
 * Optionaler Tagesform-Check-in.
 *
 * Es gibt keinen Gesamtwert und keine Voreinstellung: Jede Dimension bleibt
 * leer, bis der Nutzer sie beantwortet. Gespeichert wird nur die Tagesform,
 * nie eine Plan- oder Deload-Änderung.
 */
@Composable
fun DailyCheckInCard(
    checkIn: DashboardCheckInState,
    notice: DashboardCheckInNotice?,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSleepChange: (Int?) -> Unit,
    onEnergyChange: (Int?) -> Unit,
    onStressChange: (Int?) -> Unit,
    onSorenessChange: (MuscleGroup, Int?) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stored = checkIn.stored
    DashboardPanel(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.dashboard_checkin_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.dashboard_checkin_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            CheckInNoticeRow(notice = notice, onDismissNotice = onDismissNotice)

            if (checkIn.isEditing) {
                ScaleSelector(
                    label = stringResource(R.string.dashboard_checkin_sleep),
                    hint = stringResource(R.string.dashboard_checkin_sleep_hint),
                    value = checkIn.draft.sleepQuality,
                    onValueChange = onSleepChange
                )
                ScaleSelector(
                    label = stringResource(R.string.dashboard_checkin_energy),
                    hint = stringResource(R.string.dashboard_checkin_energy_hint),
                    value = checkIn.draft.energy,
                    onValueChange = onEnergyChange
                )
                ScaleSelector(
                    label = stringResource(R.string.dashboard_checkin_stress),
                    hint = stringResource(R.string.dashboard_checkin_stress_hint),
                    value = checkIn.draft.stress,
                    onValueChange = onStressChange
                )

                Text(
                    text = stringResource(R.string.dashboard_checkin_soreness_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.dashboard_checkin_scale_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MuscleGroup.entries.forEach { muscle ->
                        val value = checkIn.draft.sorenessByMuscle[muscle]
                        TextButton(
                            onClick = { onSorenessChange(muscle, nextScaleValue(value)) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (value != null) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                containerColor = if (value != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                }
                            )
                        ) {
                            Text(
                                text = value?.let { stringResource(UxR.string.dashboard_checkin_scale_value, muscle.label(), it) }
                                    ?: muscle.label(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancelEdit, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(R.string.dashboard_checkin_skip))
                    }
                    Button(onClick = onSave, enabled = !checkIn.isSaving) {
                        Text(stringResource(R.string.dashboard_checkin_save))
                    }
                }
            } else if (stored?.hasAnyAnswer == true) {
                StoredCheckInSummary(stored)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onStartEdit) {
                        Text(stringResource(R.string.dashboard_checkin_edit))
                    }
                    TextButton(onClick = onDelete, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(R.string.dashboard_checkin_delete))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.dashboard_checkin_none_stored),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onStartEdit) {
                    Text(stringResource(R.string.dashboard_checkin_add))
                }
            }
        }
    }
}

@Composable
internal fun CheckInNoticeRow(notice: DashboardCheckInNotice?, onDismissNotice: () -> Unit) {
    if (notice == null) return
    LaunchedEffect(notice) {
        delay(2_500)
        onDismissNotice()
    }
    val (text, color) = when (notice) {
        DashboardCheckInNotice.SAVED ->
            stringResource(R.string.dashboard_checkin_saved) to MaterialTheme.semanticText.teal
        DashboardCheckInNotice.DELETED ->
            stringResource(R.string.dashboard_checkin_deleted) to MaterialTheme.colorScheme.onSurfaceVariant
        DashboardCheckInNotice.NEEDS_ANSWER ->
            stringResource(R.string.dashboard_checkin_answers_required) to MaterialTheme.semanticText.warning
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun StoredCheckInSummary(stored: DashboardStoredCheckIn) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        stored.sleepQuality?.let {
            Text(
                text = stringResource(UxR.string.dashboard_checkin_scale_value, stringResource(R.string.dashboard_checkin_sleep), it),
                style = MaterialTheme.typography.bodySmall
            )
        }
        stored.energy?.let {
            Text(
                text = stringResource(UxR.string.dashboard_checkin_scale_value, stringResource(R.string.dashboard_checkin_energy), it),
                style = MaterialTheme.typography.bodySmall
            )
        }
        stored.stress?.let {
            Text(
                text = stringResource(UxR.string.dashboard_checkin_scale_value, stringResource(R.string.dashboard_checkin_stress), it),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (stored.sorenessByMuscle.isNotEmpty()) {
            Text(
                text = stringResource(R.string.dashboard_checkin_soreness_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            stored.sorenessByMuscle.entries
                .sortedBy { it.key.ordinal }
                .forEach { (muscle, value) ->
                    Text(
                        text = stringResource(UxR.string.dashboard_checkin_scale_value, muscle.label(), value),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
        }
    }
}

/** Neun-Punkte-Leiste: erneutes Tippen auf den gewählten Wert entfernt ihn wieder. */
@Composable
private fun ScaleSelector(
    label: String,
    hint: String,
    value: Int?,
    onValueChange: (Int?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (candidate in 1..5) {
                val selected = value == candidate
                TextButton(
                    onClick = { onValueChange(if (selected) null else candidate) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Text(
                        text = candidate.toString(),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** null -> 1 -> 2 -> 3 -> 4 -> 5 -> null. */
private fun nextScaleValue(value: Int?): Int? =
    when (value) {
        null -> 1
        in 1..4 -> value + 1
        else -> null
    }

/**
 * Muskelkontext für heute.
 *
 * Zeigt die heute geplanten Gruppen, die letzte Belastung, das Volumen der
 * letzten sieben Tage und den heute gemeldeten Muskelkater. Es gibt bewusst
 * keinen Erholungs-Prozentwert: [MuscleGroupContext.lastTrainedEpochMillis] und
 * [MuscleGroupContext.setsInWindow] sind Beobachtungen, keine Messung.
 *
 * [MuscleGroupContext.plannedToday] kommt ausschließlich aus dem Kern (heutige
 * Session bzw. der vom Nutzer gewählte Plan). Gruppen ohne diesen Marker
 * werden nie als "geplant" ausgegeben, auch wenn sie historisch belastet
 * wurden.
 */
@Composable
internal fun MuscleContextRow(context: MuscleGroupContext, nowEpochMillis: Long) {
    val nameColor = if (context.plannedToday) {
        MaterialTheme.semanticText.teal
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = context.muscleGroup.label(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = nameColor
            )
            if (context.plannedToday) {
                Text(
                    text = stringResource(R.string.dashboard_muscles_planned_marker),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticText.teal
                )
            }
        }
        Text(
            text = context.lastTrainedEpochMillis?.let { lastTrained ->
                stringResource(
                    R.string.dashboard_muscles_last_trained,
                    relativeDaysLabel(lastTrained, nowEpochMillis)
                )
            } ?: stringResource(R.string.dashboard_muscles_never),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = pluralStringResource(
                R.plurals.dashboard_muscles_window,
                setCountQuantity(context.setsInWindow),
                formatSets(context.setsInWindow)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (context.setsToday > 0.0) {
            Text(
                text = pluralStringResource(
                    R.plurals.dashboard_muscles_today,
                    setCountQuantity(context.setsToday),
                    formatSets(context.setsToday)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        context.soreness?.let { soreness ->
            Text(
                text = stringResource(R.string.dashboard_muscles_soreness, soreness),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        context.flags.forEach { flag ->
            Text(
                text = stringResource(flag.labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun relativeDaysLabel(epochMillis: Long, nowEpochMillis: Long): String {
    val days = ((nowEpochMillis - epochMillis).coerceAtLeast(0L) / 86_400_000L).toInt()
    return if (days <= 0) {
        stringResource(R.string.dashboard_muscles_trained_today)
    } else {
        pluralStringResource(R.plurals.dashboard_muscles_trained_days_ago, days, days)
    }
}

/** Muskelgruppe aus dem geteilten Code; unbekannte Codes bleiben als Code sichtbar. */
@Composable
private fun String.label(): String =
    DashboardReadinessText.muscleGroupFor(this)?.label() ?: this

/** Ganze Sätze ohne Nachkommastelle, halbe Sätze mit einer. */
private fun formatSets(value: Double): String = WeightFormatting.formatNumber(value)

/** Plural quantity for a set count that may be fractional (0.5 for secondary muscles): only exactly 1 is singular. */
private fun setCountQuantity(value: Double): Int = if (value == 1.0) 1 else 0

private fun MuscleGroupFlag.labelRes(): Int = when (this) {
    MuscleGroupFlag.TRAINED_TODAY -> R.string.dashboard_reason_muscle_trained_today
    MuscleGroupFlag.HIGH_SORENESS -> R.string.dashboard_reason_muscle_high_soreness
    MuscleGroupFlag.HIGH_RECENT_VOLUME -> R.string.dashboard_reason_muscle_high_volume
    MuscleGroupFlag.LOW_RECENT_VOLUME -> R.string.dashboard_reason_muscle_low_volume
    MuscleGroupFlag.NO_RECENT_LOAD -> R.string.dashboard_reason_muscle_no_load
}

/**
 * Shifts a left-aligned [TextButton] by its inner padding so its label lines up
 * with the card text above it; the touch target stays unchanged.
 */
private fun Modifier.flushStartLink(): Modifier = offset(x = (-12).dp)
