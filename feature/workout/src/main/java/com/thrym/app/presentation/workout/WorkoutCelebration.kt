package com.thrym.app.presentation.workout

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.UiStrings
import com.thrym.app.presentation.common.completionFactText
import com.thrym.app.presentation.common.exerciseName
import com.thrym.app.presentation.common.markTitle
import com.thrym.app.presentation.common.rememberHapticFeedback
import com.thrym.app.presentation.common.rememberUiStrings
import com.thrym.app.presentation.common.tracksAddedLoad
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText
import com.thrym.app.presentation.theme.thrymMotion
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import com.thrym.shared.recognition.CompletionFactKind
import com.thrym.shared.recognition.CompletionFacts
import com.thrym.shared.recognition.CompletionHighlight
import com.thrym.shared.recognition.CompletionHighlightKind
import com.thrym.shared.recognition.CompletionHighlightSelection
import kotlinx.coroutines.delay

private const val CELEBRATION_STEPS = 2

/**
 * The celebration after "Beenden", before the known summary: "Geschafft" with the key numbers,
 * then at most three highlights (see `CompletionHighlights`). A tap on the content or "Weiter"
 * goes on, "Überspringen" and Back go straight to the summary. With reduced motion the steps
 * change without animation. The caller shows it once per workout and only with highlights.
 */
@Composable
internal fun WorkoutCelebration(
    session: WorkoutSession,
    rows: List<ExerciseWithSets>,
    facts: CompletionFacts?,
    selection: CompletionHighlightSelection,
    unitSystem: UnitSystem,
    onFinish: () -> Unit
) {
    var step by rememberSaveable(session.id) { mutableIntStateOf(0) }
    val reducedMotion = thrymMotion.reduced
    val haptic = rememberHapticFeedback()
    val advance = { if (step < CELEBRATION_STEPS - 1) step += 1 else onFinish() }
    BackHandler(onBack = onFinish)
    LaunchedEffect(step) {
        if (step == CELEBRATION_STEPS - 1) haptic.confirm()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StepIndicator(step, Modifier.weight(1f))
                TextButton(onClick = onFinish, colors = ThrymInteractiveColors.textButton()) {
                    Text(stringResource(WorkoutR.string.workout_celebration_skip))
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = advance)
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (reducedMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 12 }) togetherWith fadeOut(tween(160))
                        }
                    },
                    label = "celebrationStep"
                ) { current ->
                    when (current) {
                        0 -> DoneStep(session, rows, facts, unitSystem)
                        else -> HighlightsStep(selection, rows, facts, unitSystem, reducedMotion)
                    }
                }
            }
            Button(onClick = advance, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(
                    stringResource(
                        if (step < CELEBRATION_STEPS - 1) WorkoutR.string.workout_celebration_next else WorkoutR.string.workout_celebration_to_summary
                    )
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(step: Int, modifier: Modifier = Modifier) {
    val description = stringResource(WorkoutR.string.workout_celebration_step_cd, step + 1, CELEBRATION_STEPS)
    Row(modifier.semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(CELEBRATION_STEPS) { index ->
            Box(
                Modifier
                    .width(24.dp)
                    .height(5.dp)
                    .background(
                        if (index <= step) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
                        RoundedCornerShape(3.dp)
                    )
            )
        }
    }
}

@Composable
private fun DoneStep(session: WorkoutSession, rows: List<ExerciseWithSets>, facts: CompletionFacts?, unitSystem: UnitSystem) {
    val sets = rows.flatMap { it.sets }.filter { it.reps > 0 }.distinctBy { it.id }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(WorkoutR.string.workout_celebration_done_title),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() }
        )
        val subtitle = listOfNotNull(
            session.name.takeIf { it.isNotBlank() },
            facts?.let { stringResource(R.string.recognition_training_number, it.trainingNumber) }
        ).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CelebrationMetric(
                stringResource(R.string.workout_summary_duration),
                if (session.durationSeconds < 60) {
                    stringResource(R.string.workout_summary_duration_under_minute)
                } else {
                    stringResource(R.string.workout_summary_duration_minutes, (session.durationSeconds / 60).toInt())
                },
                Modifier.weight(1f)
            )
            CelebrationMetric(stringResource(R.string.workout_summary_sets), sets.size.toString(), Modifier.weight(1f))
        }
        CelebrationMetric(
            stringResource(R.string.workout_summary_volume),
            WeightFormatting.formatVolume(sets.sumOf { it.weightKg * it.reps }, unitSystem),
            Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CelebrationMetric(label: String, value: String, modifier: Modifier) {
    ThrymSurfaceCard(modifier = modifier, tone = ThrymSurfaceTone.ELEVATED) {
        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HighlightsStep(
    selection: CompletionHighlightSelection,
    rows: List<ExerciseWithSets>,
    facts: CompletionFacts?,
    unitSystem: UnitSystem,
    reducedMotion: Boolean
) {
    val strings = rememberUiStrings()
    val texts = remember(selection, rows, facts, unitSystem) {
        selection.shown.mapNotNull { highlightText(strings, it, rows, facts, unitSystem) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(WorkoutR.string.workout_celebration_highlights_title),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() }
        )
        texts.forEachIndexed { index, text ->
            // The highlights come in one after another; with reduced motion they are there at once.
            val visible = remember { MutableTransitionState(reducedMotion) }
            LaunchedEffect(Unit) {
                if (!reducedMotion) {
                    delay(140L * index)
                    visible.targetState = true
                }
            }
            AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(240)) + slideInVertically(tween(280)) { it / 3 }) {
                HighlightCard(text)
            }
        }
        if (selection.more > 0) {
            Text(
                pluralStringResource(WorkoutR.plurals.workout_celebration_more, selection.more, selection.more),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HighlightCard(text: HighlightText) {
    val tint = if (text.warm) MaterialTheme.semanticText.warning else MaterialTheme.colorScheme.primary
    val container = if (text.warm) MaterialTheme.semantic.warning.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    ThrymSurfaceCard(modifier = Modifier.fillMaxWidth(), tone = ThrymSurfaceTone.ELEVATED) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(Modifier.size(48.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
                Icon(text.icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text.detail,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A highlight as text. [warm] highlights (records, marks) use the warning tone, facts the accent. */
private data class HighlightText(val icon: ImageVector, val title: String, val detail: String, val warm: Boolean)

private fun highlightText(
    strings: UiStrings,
    highlight: CompletionHighlight,
    rows: List<ExerciseWithSets>,
    facts: CompletionFacts?,
    unitSystem: UnitSystem
): HighlightText? {
    val exercise = highlight.exerciseId?.let { id -> rows.firstOrNull { it.exercise.id == id }?.exercise }
    return when (highlight.kind) {
        CompletionHighlightKind.RECORD -> {
            val line = highlight.record ?: return null
            val type = recordTypeOf(line.type) ?: return null
            exercise ?: return null
            val addedLoad = exercise.tracksAddedLoad
            val detail = line.previous?.let { previous ->
                liveRecordDetail(strings, LiveRecordUi(type = type, value = line.value, previous = previous, addedLoad = addedLoad), unitSystem)
            } ?: listOf(strings.get(recordLabelRes(type)), formatRecordValue(strings, type, line.value, unitSystem, addedLoad)).joinToString(" ")
            HighlightText(
                Icons.Outlined.EmojiEvents,
                strings.get(WorkoutR.string.workout_celebration_record_title),
                strings.get(R.string.recognition_title_with_detail, strings.exerciseName(exercise), detail),
                warm = true
            )
        }
        CompletionHighlightKind.MARK -> {
            val mark = highlight.mark ?: return null
            exercise ?: return null
            HighlightText(Icons.Outlined.MilitaryTech, strings.markTitle(mark), strings.exerciseName(exercise), warm = true)
        }
        CompletionHighlightKind.JUBILEE -> factHighlight(strings, facts, CompletionFactKind.JUBILEE, Icons.Outlined.Flag)
        CompletionHighlightKind.COMEBACK -> factHighlight(strings, facts, CompletionFactKind.COMEBACK, Icons.Outlined.Replay)
        CompletionHighlightKind.WEEKLY_GOAL -> factHighlight(strings, facts, CompletionFactKind.WEEKLY_GOAL, Icons.Outlined.EventAvailable)
    }
}

private fun factHighlight(strings: UiStrings, facts: CompletionFacts?, kind: CompletionFactKind, icon: ImageVector): HighlightText? {
    val text = facts?.let { strings.completionFactText(it, kind) } ?: return null
    return HighlightText(icon, text.title, text.detail, warm = false)
}

