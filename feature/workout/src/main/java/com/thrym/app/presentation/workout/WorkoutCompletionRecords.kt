package com.thrym.app.presentation.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.RecordType
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.UiStrings
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.common.rememberUiStrings
import com.thrym.app.presentation.common.tracksAddedLoad
import com.thrym.app.presentation.theme.AthleticLabel
import com.thrym.app.presentation.theme.AthleticNumber
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText
import com.thrym.app.presentation.common.markTitle
import com.thrym.app.presentation.common.markValue
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import com.thrym.shared.recognition.StrengthMark
import com.thrym.shared.workout.SessionExerciseRecords
import com.thrym.shared.workout.SessionRecordLine
import com.thrym.shared.workout.SessionRecordSummary

/**
 * Record board of the completion screen, the same in Ember and Liquid Glass: the number of
 * records, then one section per exercise in workout order with label, value, the set it came
 * from and the gain over the earlier best. Exercises trained for the first time have no records
 * yet; they close the board as "Erste Bestwerte", or fill it with their best sets when nothing
 * else is new. Shows nothing when the workout has neither.
 */
@Composable
internal fun CompletionRecordsCard(
    summary: SessionRecordSummary,
    rows: List<ExerciseWithSets>,
    unitSystem: UnitSystem,
    marks: Map<Long, List<StrengthMark>> = emptyMap(),
    modifier: Modifier = Modifier
) {
    if (summary.exercises.isEmpty()) return
    val glass = isLiquidGlass()
    val strings = rememberUiStrings()
    val exercisesById = rows.associate { it.exercise.id to it.exercise }
    val setsById = rows.flatMap { it.sets }.associateBy { it.id }
    ThrymSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.COLORED,
        semanticColor = MaterialTheme.semantic.warning,
        shape = if (glass) RoundedCornerShape(30.dp) else MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (glass) 18.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (summary.recordCount == 0) {
                FirstBests(summary, exercisesById, setsById, strings, unitSystem)
            } else {
                RecordsBoard(summary, exercisesById, setsById, strings, unitSystem, marks)
            }
        }
    }
}

// FirstBests and RecordsBoard place their parts straight into the card's column.

/** Only exercises trained for the first time: their heaviest sets become the first bests. */
@Composable
private fun FirstBests(
    summary: SessionRecordSummary,
    exercisesById: Map<Long, Exercise>,
    setsById: Map<Long, WorkoutSet>,
    strings: UiStrings,
    unitSystem: UnitSystem
) {
    RecordsHeader(
        title = stringResource(WorkoutR.string.workout_records_first_title),
        subtitle = stringResource(WorkoutR.string.workout_records_first_subtitle),
        firstTimeOnly = true
    )
    SectionDivider()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        summary.exercises.forEach { records ->
            val exercise = exercisesById[records.exerciseId]
            FirstBestRow(
                name = exercise?.localizedName().orEmpty(),
                bestSet = records.bestSetId?.let(setsById::get)?.let { set ->
                    formatFirstBestSet(strings, set, unitSystem, addedLoad = exercise?.tracksAddedLoad == true)
                }
            )
        }
    }
}

/** Count header, one section per exercise with records, then the first-time exercises. */
@Composable
private fun RecordsBoard(
    summary: SessionRecordSummary,
    exercisesById: Map<Long, Exercise>,
    setsById: Map<Long, WorkoutSet>,
    strings: UiStrings,
    unitSystem: UnitSystem,
    marks: Map<Long, List<StrengthMark>>
) {
    RecordsHeader(
        title = pluralStringResource(WorkoutR.plurals.workout_records_count, summary.recordCount, summary.recordCount),
        subtitle = pluralStringResource(
            WorkoutR.plurals.workout_records_exercise_count,
            summary.exercisesWithRecords,
            summary.exercisesWithRecords
        ),
        firstTimeOnly = false
    )
    summary.exercises.filter { it.lines.isNotEmpty() }.forEach { records ->
        SectionDivider()
        ExerciseRecordsSection(
            records,
            exercisesById[records.exerciseId],
            setsById,
            strings,
            unitSystem,
            marks[records.exerciseId].orEmpty()
        )
    }
    val firstTimeNames = summary.firstTimeExerciseIds.mapNotNull { exercisesById[it]?.localizedName() }
    if (firstTimeNames.isNotEmpty()) {
        SectionDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.semanticText.warning
            )
            Text(
                text = stringResource(WorkoutR.string.workout_records_first_footer, firstTimeNames.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Gold medallion with the trophy (the star for first bests only), count and subline. */
@Composable
private fun RecordsHeader(title: String, subtitle: String, firstTimeOnly: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.semantic.warning.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (firstTimeOnly) Icons.Default.Star else Icons.Default.EmojiEvents,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.semanticText.warning
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = AthleticNumber, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun ExerciseRecordsSection(
    records: SessionExerciseRecords,
    exercise: Exercise?,
    setsById: Map<Long, WorkoutSet>,
    strings: UiStrings,
    unitSystem: UnitSystem,
    marks: List<StrengthMark>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = exercise?.localizedName().orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val addedLoad = exercise?.tracksAddedLoad == true
        marks.forEach { mark -> MarkLineRow(mark, strings) }
        records.lines.forEach { line ->
            val type = recordTypeOf(line.type) ?: return@forEach
            RecordLineRow(line, type, line.setId?.let(setsById::get), addedLoad, strings, unitSystem)
        }
    }
}

/**
 * One record: the label in a fixed column, value and set context, the gain as a green chip
 * ("Erstmals" in gold when the exercise had no value of this type). TalkBack reads the row as
 * one sentence, so the colour never carries the meaning alone. Large text (font scale from
 * [STACKED_RECORD_FONT_SCALE]) stacks label, value and chip, so neither squeezes the value.
 * With [addedLoad] (a bodyweight exercise) loads read as the set does, "Körpergewicht + 12,5 kg".
 */
@Composable
private fun RecordLineRow(
    line: SessionRecordLine,
    type: RecordType,
    recordSet: WorkoutSet?,
    addedLoad: Boolean,
    strings: UiStrings,
    unitSystem: UnitSystem
) {
    val label = stringResource(recordLabelRes(type))
    val value = formatRecordValue(strings, type, line.value, unitSystem, addedLoad)
    val context = formatRecordContext(strings, type, recordSet, unitSystem, addedLoad)
    val delta = line.previous?.let { line.value - it }
    val description = recordRowDescription(strings, type, line.value, context, delta, unitSystem, addedLoad)
    val valueStyle = MaterialTheme.typography.titleMedium.toSpanStyle()
        .copy(fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum", color = MaterialTheme.colorScheme.onSurface)
    val contextStyle = MaterialTheme.typography.bodyMedium.toSpanStyle()
        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valueText = buildAnnotatedString {
        withStyle(valueStyle) { append(value) }
        context?.let {
            append(" ")
            withStyle(contextStyle) { append(it) }
        }
    }
    val labelText = label.uppercase(currentLocale())
    val chip: @Composable () -> Unit = {
        if (delta != null) {
            RecordChip(
                text = formatRecordDelta(strings, type, delta, unitSystem),
                ink = MaterialTheme.semanticText.success,
                fill = MaterialTheme.semantic.success.copy(alpha = 0.14f)
            )
        } else {
            RecordChip(
                text = stringResource(WorkoutR.string.workout_records_first_type),
                ink = MaterialTheme.semanticText.warning,
                fill = MaterialTheme.semantic.warning.copy(alpha = 0.16f)
            )
        }
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .clearAndSetSemantics { contentDescription = description }
    if (LocalDensity.current.fontScale >= STACKED_RECORD_FONT_SCALE) {
        Column(modifier = rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = labelText, style = AthleticLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = valueText, style = MaterialTheme.typography.titleMedium)
            chip()
        }
        return
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = labelText,
            modifier = Modifier.fillMaxWidth(0.3f),
            style = AthleticLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = valueText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        chip()
    }
}

/**
 * A round mark above the records of its exercise: "MARKE  100 kg  [Erstmals]". It shares the
 * grammar of a record row but is no record, so the header count leaves it out.
 */
@Composable
private fun MarkLineRow(mark: StrengthMark, strings: UiStrings) {
    val description = stringResource(R.string.recognition_mark_row_cd, strings.markTitle(mark))
    val chip: @Composable () -> Unit = {
        RecordChip(
            text = stringResource(WorkoutR.string.workout_records_first_type),
            ink = MaterialTheme.semanticText.warning,
            fill = MaterialTheme.semantic.warning.copy(alpha = 0.16f)
        )
    }
    val labelText = stringResource(R.string.recognition_mark_label).uppercase(currentLocale())
    val value = strings.markValue(mark)
    val valueStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum")
    val rowModifier = Modifier
        .fillMaxWidth()
        .clearAndSetSemantics { contentDescription = description }
    if (LocalDensity.current.fontScale >= STACKED_RECORD_FONT_SCALE) {
        Column(modifier = rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = labelText, style = AthleticLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = valueStyle)
            chip()
        }
        return
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = labelText,
            modifier = Modifier.fillMaxWidth(0.3f),
            style = AthleticLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(text = value, modifier = Modifier.weight(1f), style = valueStyle, maxLines = 1)
        chip()
    }
}

/** From this font scale on, a record row stacks its parts instead of lining them up. */
private const val STACKED_RECORD_FONT_SCALE = 1.3f

@Composable
private fun RecordChip(text: String, ink: Color, fill: Color) {
    Text(
        text = text,
        modifier = Modifier
            .background(fill, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.Bold,
        color = ink
    )
}

/**
 * An exercise trained for the first time with its heaviest set: "Bankdrücken" over
 * "80 kg × 8 Wdh" (see [formatFirstBestSet]). Stacked, so a long value never squeezes the name.
 */
@Composable
private fun FirstBestRow(name: String, bestSet: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        bestSet?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
