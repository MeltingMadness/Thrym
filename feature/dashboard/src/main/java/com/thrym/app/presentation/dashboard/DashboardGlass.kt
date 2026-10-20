package com.thrym.app.presentation.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrym.app.domain.model.AppearanceStyle
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.PlanExercise
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.common.rememberUiStrings
import com.thrym.app.presentation.common.weekFooterText
import com.thrym.app.presentation.common.weekProgressDescription
import com.thrym.app.presentation.common.weekProgressText
import com.thrym.app.presentation.common.skeletonDateFormatter
import com.thrym.app.presentation.theme.AthleticLabel
import com.thrym.app.presentation.theme.GlassLevel
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.LocalAppearanceStyle
import com.thrym.app.presentation.theme.liquidGlass
import com.thrym.core.designsystem.R
import com.thrym.feature.dashboard.R as DashboardR
import com.thrym.shared.recognition.WeeklyGoalStatus
import java.time.LocalDate
import java.time.format.TextStyle

@Composable
internal fun isLiquidGlass(): Boolean = LocalAppearanceStyle.current == AppearanceStyle.LIQUID_GLASS

/**
 * Container of the secondary dashboard cards: translucent Ember surface, or a Liquid Glass
 * panel in that appearance. Content and behavior stay the same.
 */
@Composable
internal fun DashboardPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (isLiquidGlass()) {
        Column(modifier.liquidGlass(GlassLevel.STANDARD, RoundedCornerShape(28.dp))) { content() }
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        ) {
            Column { content() }
        }
    }
}

/** Date, greeting and a round glass settings button; replaces the top bar in Liquid Glass. */
@Composable
internal fun GlassGreetingHeader(
    greeting: String,
    today: LocalDate,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // "MONDAY · FEBRUARY 10" (en) / "MONTAG · 10. FEBRUAR" (de)
            val locale = currentLocale()
            Text(
                text = remember(today, locale) {
                    today.format(skeletonDateFormatter(locale, skeleton = "MMMMd", prefix = "EEEE · "))
                }.uppercase(locale),
                style = AthleticLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        val settingsLabel = stringResource(R.string.settings_title)
        Box(
            modifier = Modifier
                .size(48.dp)
                .liquidGlass(GlassLevel.STANDARD, CircleShape)
                .clickable(role = Role.Button, onClickLabel = settingsLabel, onClick = onOpenSettings)
                .semantics { contentDescription = settingsLabel },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Hero card in Liquid Glass: suggested plan with its first three exercises and targets, and the
 * start button as a pill with a colored play circle. Same actions as the Ember card.
 */
@Composable
internal fun GlassCommandCenterCard(
    hasActiveSession: Boolean,
    activeSessionName: String?,
    recommended: DashboardRecommendedPlan?,
    unitSystem: UnitSystem,
    planExercises: Map<Long, Exercise>,
    onStartWorkout: () -> Unit,
    onChoosePlan: () -> Unit,
    onContinueWorkout: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val divider = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.10f else 0.08f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(GlassLevel.STRONG, RoundedCornerShape(34.dp))
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .shadow(6.dp, CircleShape, ambientColor = accent, spotColor = accent)
                    .background(accent, CircleShape)
            )
            val tag = when {
                hasActiveSession -> stringResource(DashboardR.string.dashboard_glass_running)
                recommended?.metaPlan != null -> stringResource(
                    DashboardR.string.dashboard_glass_up_next_from,
                    recommended.metaPlan.metaPlanName
                )
                else -> stringResource(DashboardR.string.dashboard_glass_up_next)
            }
            Text(
                text = tag.uppercase(currentLocale()),
                style = AthleticLabel,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val title = when {
                hasActiveSession -> activeSessionName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.workout_title_default)
                recommended != null -> recommended.plan.name
                else -> stringResource(R.string.dashboard_command_title)
            }
            Text(
                text = title,
                fontSize = 44.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.5).sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = when {
                hasActiveSession -> stringResource(R.string.dashboard_command_subtitle_active)
                recommended != null -> {
                    val count = recommended.plan.exercises.size
                    val lastDone = when (val days = recommended.lastDoneDaysAgo) {
                        null -> stringResource(R.string.dashboard_hero_last_done_never)
                        0L -> stringResource(R.string.dashboard_hero_last_done_today)
                        1L -> stringResource(R.string.dashboard_hero_last_done_yesterday)
                        else -> pluralStringResource(R.plurals.dashboard_hero_last_done_days, days.toInt(), days.toInt())
                    }
                    listOf(pluralStringResource(R.plurals.plans_exercise_count, count, count), lastDone)
                        .joinToString(" · ")
                }
                else -> stringResource(R.string.dashboard_command_subtitle_idle)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!hasActiveSession && recommended != null) {
            val exercises = recommended.plan.exercises.sortedBy { it.orderIndex }
            if (exercises.isNotEmpty()) {
                Column {
                    exercises.take(3).forEach { exercise ->
                        HorizontalDivider(color = divider)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = exercise.exerciseName.ifBlank { planExercises[exercise.exerciseId]?.localizedName().orEmpty() },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = exerciseTarget(exercise, unitSystem),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                    val more = exercises.size - 3
                    if (more > 0) {
                        Text(
                            text = pluralStringResource(DashboardR.plurals.dashboard_glass_more_exercises, more, more),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        GlassStartButton(
            text = if (hasActiveSession) {
                stringResource(R.string.dashboard_hero_continue)
            } else {
                stringResource(DashboardR.string.dashboard_glass_start)
            },
            dark = dark,
            onClick = if (hasActiveSession) onContinueWorkout else onStartWorkout
        )

        if (!hasActiveSession && recommended != null) {
            TextButton(onClick = onChoosePlan, modifier = Modifier.align(Alignment.CenterHorizontally), colors = ThrymInteractiveColors.textButton()) {
                Text(
                    text = stringResource(R.string.dashboard_hero_choose_other),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "3 × 8 · 82,5 kg"; without a target weight only sets and reps. */
private fun exerciseTarget(exercise: PlanExercise, unitSystem: UnitSystem): String {
    val volume = "${exercise.targetSets} × ${exercise.targetReps}"
    return if (exercise.targetWeightKg > 0.0) {
        "$volume · ${WeightFormatting.formatWeight(exercise.targetWeightKg, unitSystem)}"
    } else {
        volume
    }
}

@Composable
private fun GlassStartButton(text: String, dark: Boolean, onClick: () -> Unit) {
    val container = if (dark) Color.White else Color(0xFF0B0D12)
    val content = if (dark) Color(0xFF0B0D12) else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .shadow(14.dp, RoundedCornerShape(30.dp), clip = false)
            .clip(RoundedCornerShape(30.dp))
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
        }
        Text(
            text = text,
            color = content,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/**
 * Week strip: one lens per day of the current week (trained, today, open) with the number of
 * workouts and the week's volume. Replaces the week and month tiles in Liquid Glass.
 */
@Composable
internal fun GlassWeekStrip(
    days: List<DashboardWeekDay>,
    workoutsThisWeek: Int,
    volumeKg: Double,
    unitSystem: UnitSystem,
    weeklyGoal: WeeklyGoalStatus? = null
) {
    if (days.isEmpty()) return
    val strings = rememberUiStrings()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val done = if (dark) Color.White else Color(0xFF0B0D12)
    val onDone = if (dark) Color(0xFF0B0D12) else Color.White
    val open = if (dark) Color.White.copy(alpha = 0.08f) else Color(0xFF0B0D12).copy(alpha = 0.06f)
    val accent = MaterialTheme.colorScheme.primary
    val trainedLabel = stringResource(DashboardR.string.dashboard_glass_day_trained)
    val todayLabel = stringResource(DashboardR.string.dashboard_glass_day_today)
    val openLabel = stringResource(DashboardR.string.dashboard_glass_day_open)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(GlassLevel.STANDARD, RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val weekDescription = weeklyGoal?.let { strings.weekProgressDescription(it) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (weekDescription != null) {
                Modifier.semantics(mergeDescendants = true) { contentDescription = weekDescription }
            } else {
                Modifier
            }
        ) {
            Text(
                text = stringResource(DashboardR.string.dashboard_ui_this_week).uppercase(currentLocale()),
                style = AthleticLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = listOf(
                    weeklyGoal?.let { strings.weekProgressText(it) }
                        ?: pluralStringResource(DashboardR.plurals.dashboard_ui_training_count, workoutsThisWeek, workoutsThisWeek),
                    WeightFormatting.formatVolume(volumeKg, unitSystem)
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold
            )
        }
        val locale = currentLocale()
        Row(modifier = Modifier.fillMaxWidth()) {
            days.forEach { day ->
                val weekday = day.date.dayOfWeek
                val short = weekday.getDisplayName(TextStyle.SHORT, locale).removeSuffix(".")
                val state = when {
                    day.trained -> trainedLabel
                    day.isToday -> todayLabel
                    else -> openLabel
                }
                val description = "${weekday.getDisplayName(TextStyle.FULL, locale)}, $state"
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = short,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when {
                        day.trained -> Box(
                            Modifier.size(34.dp).background(done, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = onDone, modifier = Modifier.size(16.dp))
                        }
                        day.isToday -> Box(
                            Modifier
                                .size(34.dp)
                                // A shadow would show through the open center as a blot; a faint fill glows instead.
                                .background(accent.copy(alpha = 0.16f), CircleShape)
                                .border(2.dp, accent, CircleShape)
                        )
                        else -> Box(Modifier.size(34.dp).background(open, CircleShape))
                    }
                }
            }
        }
        weeklyGoal?.let(strings::weekFooterText)?.let { footer ->
            WeekGoalFooter(footer)
        }
    }
}

/**
 * Quiet line under the week: calendar symbol and "7 Wochen in Folge" (or "Wochenziel erreicht").
 * The header already speaks it for TalkBack, so the line itself stays silent.
 */
@Composable
internal fun WeekGoalFooter(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.EventAvailable,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
