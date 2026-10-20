package com.thrym.app.presentation.dashboard

import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.thrym.app.presentation.common.rememberUiStrings
import com.thrym.app.presentation.common.weekFooterText
import com.thrym.app.presentation.common.weekProgressDescription
import com.thrym.app.presentation.common.weekProgressText
import com.thrym.shared.recognition.WeeklyGoalStatus
import com.thrym.feature.dashboard.R as UxR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.accentText
import com.thrym.app.presentation.theme.AthleticHero
import com.thrym.app.presentation.theme.AthleticNumber
import com.thrym.app.presentation.theme.AthleticLabel
import com.thrym.app.presentation.common.DashboardSkeleton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import com.thrym.core.designsystem.R
import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.RecordType
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.ThrymScreenScaffold
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.thrymMotion
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText
import com.thrym.app.presentation.theme.staggeredEntrance
import com.thrym.app.presentation.common.WeeklyMuscleVolumeCard
import com.thrym.app.presentation.common.label
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.common.rememberDateTimeFormatter
import com.thrym.feature.dashboard.R as DashboardR
import org.koin.androidx.compose.koinViewModel

/** Abstand, in dem eine sichtbare Dashboard-Seite den Kalendertag prüft. */
private const val DAY_ROLLOVER_CHECK_INTERVAL_MS = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onStartWorkout: (Long, Long?, Long?) -> Unit,
    onContinueWorkout: (Long, Long?, Long?) -> Unit,
    onOpenProgressionReview: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DashboardViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val dims = thrymDimens
    val liquidGlass = isLiquidGlass()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshCheckInDay()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Solange die Seite sichtbar ist, den Tageswechsel auch ohne Resume prüfen.
    // Die Schleife endet, sobald das Dashboard die Komposition verlässt.
    LaunchedEffect(Unit) {
        while (true) {
            delay(DAY_ROLLOVER_CHECK_INTERVAL_MS)
            viewModel.refreshCheckInDay()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    ThrymScreenScaffold(
        topBar = {
            // Liquid Glass shows date, greeting and settings in the list header instead.
            if (!liquidGlass) TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(id = R.string.settings_title)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (state.isLoading) {
            DashboardSkeleton(modifier = Modifier.padding(padding))
            return@ThrymScreenScaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(dims.spacingMd),
            verticalArrangement = Arrangement.spacedBy(dims.spacingMd)
        ) {
            item {
                if (liquidGlass) {
                    GlassGreetingHeader(
                        greeting = stringResource(id = greetingRes()),
                        today = LocalDate.now(),
                        onOpenSettings = onOpenSettings
                    )
                } else {
                    GreetingHeader()
                }
            }

            item {
                val isFirstTimeUser = state.lastWorkout == null && state.recentRecords.isEmpty()
                val recommended = recommendedPlan(state.metaPlanOptions, state.trainingPlans)
                val previewExercises = recommended?.plan?.exercises
                    ?.map { it.exerciseName.ifBlank { state.planExercises[it.exerciseId]?.localizedName().orEmpty() } }
                    ?.filter { it.isNotBlank() }?.take(4) ?: emptyList()

                val startRecommended = {
                    // Start the suggested plan directly; without one, offer the choice.
                    when {
                        recommended == null -> viewModel.showPlanSelectionSheet()
                        recommended.metaPlan != null ->
                            viewModel.startNewWorkoutWithMetaPlan(recommended.metaPlan.metaPlanId, onStartWorkout)
                        else -> viewModel.startNewWorkoutWithPlan(recommended.plan) { sessionId, planId ->
                            onStartWorkout(sessionId, planId, null)
                        }
                    }
                }
                val continueActive = {
                    state.activeSession?.let { session ->
                        onContinueWorkout(session.id, session.planId, session.metaPlanId)
                    }
                    Unit
                }

                if (liquidGlass) {
                    GlassCommandCenterCard(
                        hasActiveSession = state.activeSession != null,
                        activeSessionName = state.activeSession?.name,
                        recommended = recommended,
                        unitSystem = state.unitSystem,
                        planExercises = state.planExercises,
                        onStartWorkout = startRecommended,
                        onChoosePlan = { viewModel.showPlanSelectionSheet() },
                        onContinueWorkout = continueActive
                    )
                } else {
                    CommandCenterCard(
                        hasActiveSession = state.activeSession != null,
                        isFirstTimeUser = isFirstTimeUser,
                        recommended = recommended,
                        previewExercises = previewExercises,
                        onStartWorkout = startRecommended,
                        onChoosePlan = { viewModel.showPlanSelectionSheet() },
                        onContinueWorkout = continueActive
                    )
                }
            }

            if (liquidGlass) {
                item(key = "week_strip") {
                    GlassWeekStrip(
                        days = state.weekDays,
                        workoutsThisWeek = state.workoutsThisWeek,
                        volumeKg = state.volumeThisWeekKg,
                        unitSystem = state.unitSystem,
                        weeklyGoal = state.weeklyGoal
                    )
                }
            }

            item(key = "training_trend") {
                TrainingTrendCard(
                    trend = state.trainingTrend,
                    onRetry = viewModel::reloadTrainingTrend,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item(key = "daily_check_in") {
                DailyCheckInCard(
                    checkIn = state.checkIn,
                    notice = state.checkInNotice,
                    onStartEdit = viewModel::startCheckInEdit,
                    onCancelEdit = viewModel::cancelCheckInEdit,
                    onSleepChange = viewModel::updateCheckInSleepQuality,
                    onEnergyChange = viewModel::updateCheckInEnergy,
                    onStressChange = viewModel::updateCheckInStress,
                    onSorenessChange = viewModel::updateCheckInSoreness,
                    onSave = viewModel::saveCheckIn,
                    onDelete = viewModel::deleteCheckIn,
                    onDismissNotice = viewModel::dismissCheckInNotice
                )
            }

            // Der Muskelkontext stammt aus derselben Bewertung wie der Trend,
            // damit "letzte Belastung" relativ zum Auswertungszeitpunkt gilt.
            state.trainingTrend.assessment?.let { assessment ->
                if (assessment.muscleGroups.isNotEmpty()) {
                    item(key = "muscle_context") {
                        MuscleContextCard(
                            muscleGroups = assessment.muscleGroups,
                            nowEpochMillis = assessment.generatedAtEpochMillis
                        )
                    }
                }
            }
            if (!liquidGlass) {
                item {
                    WorkoutStreakBentoCard(
                        workoutsThisWeek = state.workoutsThisWeek,
                        workoutsThisMonth = state.workoutsThisMonth,
                        weeklyGoal = state.weeklyGoal,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Die automatische Deload-Empfehlung kommt ausschließlich aus dem
            // neuen Trainingstrend. Die alte Ermüdungs-Heuristik wird nicht
            // mehr angezeigt; ein aktiver manueller Modus bleibt bedienbar.
            val deloadSuggested =
                state.trainingTrend.assessment?.trainingTrend?.deloadSuggested == true
            val pauseDays = state.pauseDays
            if (pauseDays != null && state.deloadMode == null && state.activeSession == null) {
                item(key = "comeback_card") {
                    ComebackCard(
                        pauseDays = pauseDays,
                        isUpdating = state.isDeloadModeUpdating,
                        onEaseBackIn = { viewModel.activateDeloadMode(DeloadMode.HALVE_SET_VOLUME) }
                    )
                }
            }
            if (deloadSuggested || state.deloadMode != null) {
                item(key = "deload_card") {
                    DeloadCard(
                        activeMode = state.deloadMode,
                        isUpdating = state.isDeloadModeUpdating,
                        onActivate = viewModel::activateDeloadMode,
                        onDeactivate = viewModel::deactivateDeloadMode
                    )
                }
            }

            if (state.pendingProgressionCount > 0) {
                item(key = "pending_progressions") {
                    PendingProgressionCard(
                        count = state.pendingProgressionCount,
                        onOpenProgressionReview = onOpenProgressionReview
                    )
                }
            }

            item(key = "weekly_muscle_volume") {
                val weeklyMuscleVolume = state.weeklyMuscleVolume
                WeeklyMuscleVolumeCard(
                    volumes = weeklyMuscleVolume.volumes,
                    collapsible = true,
                    weekStart = weeklyMuscleVolume.weekStart,
                    currentWeekStart = weeklyMuscleVolume.currentWeekStart,
                    completedWorkoutCount = weeklyMuscleVolume.completedWorkoutCount,
                    isLoading = weeklyMuscleVolume.isLoading,
                    error = weeklyMuscleVolume.error,
                    onRetry = viewModel::reloadWeeklyMuscleVolume,
                    onPreviousWeek = viewModel::showPreviousMuscleVolumeWeek,
                    onNextWeek = viewModel::showNextMuscleVolumeWeek
                )
            }

            if (state.lastWorkout == null && state.recentRecords.isEmpty()) {
                item {
                    OnboardingCard()
                }
            } else {

                item {
                    SectionTitle(text = stringResource(id = R.string.dashboard_recent_records))
                }

                if (state.recentRecords.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(
                                id = if (state.lastWorkout != null) {
                                    R.string.dashboard_no_records_with_history
                                } else {
                                    R.string.dashboard_no_records
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    item {
                        androidx.compose.foundation.lazy.LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(dims.spacingSm)
                        ) {
                            items(
                                items = state.recentRecords,
                                key = { (record, _) -> "${record.exerciseId}-${record.type.name}-${record.achievedAt}" }
                            ) { (record, exercise) ->
                                val index = state.recentRecords.indexOfFirst {
                                    it.first.id == record.id && it.first.achievedAt == record.achievedAt
                                }
                                RecordCard(
                                    exerciseName = exercise?.localizedName()
                                        ?: stringResource(UxR.string.dashboard_unknown_exercise),
                                    recordType = record.type.label(),
                                    recordValue = formatRecordValue(record.type, record.value, state.unitSystem),
                                    modifier = Modifier.staggeredEntrance(index)
                                )
                            }
                        }
                    }
                }

                item {
                    WeeklyVolumeCard(weeklyVolume = state.weeklyVolume, unitSystem = state.unitSystem)
                }

                state.lastWorkout?.let { workout ->
                    item {
                        SectionTitle(text = stringResource(id = R.string.dashboard_last_workout))
                    }
                    item {
                        LastWorkoutCard(
                            dateTime = workout.startTime.format(rememberDateTimeFormatter()),
                            durationMin = (workout.durationSeconds / 60).toInt(),
                            exerciseCount = state.lastWorkoutExerciseCount
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(dims.spacingXl))
            }
        }

        if (state.showPlanSelectionSheet) {
            PlanSelectionSheet(
                plans = state.trainingPlans,
                metaPlanOptions = state.metaPlanOptions,
                skippingMetaPlanId = state.skippingMetaPlanId,
                onDismiss = viewModel::dismissPlanSelectionSheet,
                onPlanSelected = { plan -> 
                    viewModel.dismissPlanSelectionSheet()
                    viewModel.startNewWorkoutWithPlan(plan) { sessionId, planId ->
                        onStartWorkout(sessionId, planId, null)
                    }
                },
                onMetaPlanSelected = { metaPlanId ->
                    viewModel.dismissPlanSelectionSheet()
                    viewModel.startNewWorkoutWithMetaPlan(metaPlanId, onStartWorkout)
                },
                onSkipMetaPlan = viewModel::skipCurrentMetaSubPlan,
                onFreeWorkoutSelected = { 
                    viewModel.dismissPlanSelectionSheet()
                    viewModel.startNewWorkout { sessionId, planId ->
                        onStartWorkout(sessionId, planId, null)
                    }
                }
            )
        }
    }
}

/**
 * Deload-Karte.
 *
 * Die automatische Empfehlung stammt ausschließlich aus dem neuen
 * Trainingstrend; die frühere Ermüdungs-Heuristik wird hier nicht mehr gezeigt,
 * damit keine widersprüchlichen Zahlen nebeneinander stehen. Ein bereits aktiv
 * gewählter Modus bleibt unabhängig davon bedienbar und jederzeit beendbar.
 */
@Composable
private fun DeloadCard(
    activeMode: DeloadMode?,
    isUpdating: Boolean,
    onActivate: (DeloadMode) -> Unit,
    onDeactivate: () -> Unit
) {
    val dims = thrymDimens

    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.COLORED,
        semanticColor = MaterialTheme.semantic.warning
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.spacingMd),
            verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
        ) {
            Text(
                text = stringResource(
                    id = if (activeMode != null) {
                        DashboardR.string.dashboard_audit_deload_title_active
                    } else {
                        DashboardR.string.dashboard_audit_deload_title_recommended
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (activeMode == null) {
                Text(
                    text = stringResource(DashboardR.string.dashboard_trend_deload_suggested),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(
                        id = when (activeMode) {
                            DeloadMode.HALVE_SET_VOLUME -> R.string.deload_active_hint_volume
                            DeloadMode.REDUCE_INTENSITY_BY_15_PERCENT -> R.string.deload_active_hint_intensity
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = dims.spacingXs),
                horizontalArrangement = Arrangement.spacedBy(dims.spacingSm),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                if (activeMode == null) {
                    Button(
                        onClick = { onActivate(DeloadMode.HALVE_SET_VOLUME) },
                        modifier = Modifier.weight(1f),
                        enabled = !isUpdating
                    ) {
                        Text(stringResource(id = R.string.deload_mode_halve_volume))
                    }
                    TextButton(
                        onClick = { onActivate(DeloadMode.REDUCE_INTENSITY_BY_15_PERCENT) },
                        modifier = Modifier.weight(1f),
                        enabled = !isUpdating,
                        colors = ThrymInteractiveColors.textButton()
                    ) {
                        Text(stringResource(id = R.string.deload_mode_reduce_intensity))
                    }
                } else {
                    Text(
                        text = stringResource(id = R.string.deload_mode_active),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.semanticText.success
                    )
                    TextButton(onClick = onDeactivate, enabled = !isUpdating, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = R.string.deload_mode_end))
                    }
                }
                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingProgressionCard(
    count: Int,
    onOpenProgressionReview: () -> Unit
) {
    val dims = thrymDimens

    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.MUTED
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.spacingMd, vertical = dims.spacingXs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = pluralStringResource(
                    id = R.plurals.dashboard_pending_progressions,
                    count = count,
                    count
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onOpenProgressionReview, colors = ThrymInteractiveColors.textButton()) {
                Text(stringResource(R.string.dashboard_review_progressions))
            }
        }
    }
}

@Composable
private fun greetingRes(): Int {
    val hour = remember { java.time.LocalDateTime.now().hour }
    return when (hour) {
        in 5..11 -> R.string.dashboard_greeting_morning
        in 12..17 -> R.string.dashboard_greeting_day
        in 18..21 -> R.string.dashboard_greeting_evening
        else -> R.string.dashboard_greeting_late
    }
}

@Composable
private fun GreetingHeader() {
    val greetingRes = greetingRes()
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            text = stringResource(UxR.string.dashboard_welcome_back),
            style = AthleticLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(id = greetingRes),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CommandCenterCard(
    hasActiveSession: Boolean,
    isFirstTimeUser: Boolean,
    recommended: DashboardRecommendedPlan? = null,
    previewExercises: List<String> = emptyList(),
    onStartWorkout: () -> Unit,
    onChoosePlan: () -> Unit,
    onContinueWorkout: () -> Unit
) {
    val dims = thrymDimens
    val motion = thrymMotion

    val scale = if (isFirstTimeUser && !hasActiveSession && !motion.reduced) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        animatedScale
    } else {
        1f
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val recommendedPlanName = recommended?.plan?.name
            val heroTag = if (hasActiveSession) {
                stringResource(id = R.string.dashboard_hero_active_tag)
            } else {
                stringResource(id = R.string.dashboard_hero_tag)
            }

            Text(
                text = heroTag.uppercase(),
                style = AthleticLabel,
                color = MaterialTheme.accentText,
                fontWeight = FontWeight.ExtraBold
            )

            val heroTitle = if (hasActiveSession) {
                stringResource(id = R.string.dashboard_command_title)
            } else if (recommendedPlanName != null) {
                recommendedPlanName
            } else {
                stringResource(id = R.string.dashboard_command_title)
            }

            Text(
                text = heroTitle,
                style = AthleticHero,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Black
            )

            val heroSubtitle = if (hasActiveSession) {
                stringResource(id = R.string.dashboard_command_subtitle_active)
            } else if (recommended != null) {
                val exerciseCount = recommended.plan.exercises.size
                val lastDone = when (val days = recommended.lastDoneDaysAgo) {
                    null -> stringResource(R.string.dashboard_hero_last_done_never)
                    0L -> stringResource(R.string.dashboard_hero_last_done_today)
                    1L -> stringResource(R.string.dashboard_hero_last_done_yesterday)
                    else -> pluralStringResource(R.plurals.dashboard_hero_last_done_days, days.toInt(), days.toInt())
                }
                listOfNotNull(
                    recommended.metaPlan?.let { stringResource(R.string.dashboard_hero_from_meta, it.metaPlanName) },
                    pluralStringResource(R.plurals.plans_exercise_count, exerciseCount, exerciseCount),
                    lastDone
                ).joinToString(" · ")
            } else {
                stringResource(id = R.string.dashboard_command_subtitle_idle)
            }

            Text(
                text = heroSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (previewExercises.isNotEmpty() && !hasActiveSession) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    previewExercises.forEach { exName ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Text(
                                text = exName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            val buttonAction = if (hasActiveSession) onContinueWorkout else onStartWorkout
            Button(
                onClick = buttonAction,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .scale(scale)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasActiveSession) {
                        stringResource(id = R.string.dashboard_hero_continue)
                    } else {
                        stringResource(id = R.string.dashboard_hero_start)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!hasActiveSession && recommended != null) {
                TextButton(
                    onClick = onChoosePlan,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    colors = ThrymInteractiveColors.textButton()
                ) {
                    Text(stringResource(id = R.string.dashboard_hero_choose_other))
                }
            }
        }
    }
}


/**
 * Offer after a pause of three weeks or more: the existing deload mode, so the first workouts
 * back are lighter. Names the pause as a fact, never as a reproach.
 */
@Composable
private fun ComebackCard(pauseDays: Int, isUpdating: Boolean, onEaseBackIn: () -> Unit) {
    val dims = thrymDimens
    ThrymSurfaceCard(modifier = Modifier.fillMaxWidth(), tone = ThrymSurfaceTone.ELEVATED) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.spacingMd),
            verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
        ) {
            Text(
                text = pluralStringResource(R.plurals.recognition_comeback_pause, pauseDays, pauseDays),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.recognition_comeback_offer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onEaseBackIn,
                enabled = !isUpdating,
                modifier = Modifier.padding(top = dims.spacingXs),
                colors = ThrymInteractiveColors.outlinedButton()
            ) {
                Text(stringResource(R.string.recognition_comeback_action))
            }
        }
    }
}

@Composable
private fun WorkoutStreakBentoCard(
    workoutsThisWeek: Int,
    workoutsThisMonth: Int,
    weeklyGoal: WeeklyGoalStatus?,
    modifier: Modifier = Modifier
) {
    val strings = rememberUiStrings()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(DashboardR.string.dashboard_ui_this_week), style = MaterialTheme.typography.labelMedium)
                if (weeklyGoal != null) {
                    val description = strings.weekProgressDescription(weeklyGoal)
                    Column(
                        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(strings.weekProgressText(weeklyGoal), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        strings.weekFooterText(weeklyGoal)?.let { WeekGoalFooter(it) }
                    }
                } else {
                    Text(pluralStringResource(DashboardR.plurals.dashboard_ui_training_count, workoutsThisWeek, workoutsThisWeek), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(DashboardR.string.dashboard_ui_this_month), style = MaterialTheme.typography.labelMedium)
                Text(pluralStringResource(DashboardR.plurals.dashboard_ui_training_count, workoutsThisMonth, workoutsThisMonth), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RecordCard(
    exerciseName: String,
    recordType: String,
    recordValue: String,
    modifier: Modifier = Modifier
) {
    val dims = thrymDimens

    ThrymSurfaceCard(
        modifier = modifier
            .width(140.dp)
            .heightIn(min = 120.dp),
        tone = ThrymSurfaceTone.COLORED,
        semanticColor = MaterialTheme.semantic.warning
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.spacingSm),
            verticalArrangement = Arrangement.spacedBy(dims.spacingSm)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = exerciseName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
                Text(
                    text = recordType,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = recordValue,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFeatureSettings = "tnum"
                ),
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.semanticText.warning,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LastWorkoutCard(
    dateTime: String,
    durationMin: Int,
    exerciseCount: Int
) {
    val dims = thrymDimens

    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.ACCENT
    ) {
        Column(modifier = Modifier.padding(dims.spacingSm)) {
            Text(
                text = dateTime,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = pluralStringResource(
                    id = R.plurals.dashboard_last_workout_meta,
                    count = exerciseCount,
                    durationMin,
                    exerciseCount
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold
    )
}

/** Record value in the user's unit (kg or lb), formatted like the statistics screen. */
@Composable
private fun formatRecordValue(type: RecordType, value: Double, unitSystem: UnitSystem): String {
    return when (type) {
        RecordType.MAX_WEIGHT,
        RecordType.MAX_E1RM -> WeightFormatting.formatWeight(value, unitSystem)
        RecordType.MAX_REPS -> pluralStringResource(id = R.plurals.common_record_reps, count = value.toInt(), value.toInt())
        RecordType.MAX_VOLUME -> WeightFormatting.formatVolume(value, unitSystem)
    }
}

@Composable
private fun OnboardingCard() {
    val dims = thrymDimens

    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.ACCENT
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.spacingLg),
            verticalArrangement = Arrangement.spacedBy(dims.spacingMd)
        ) {
            Text(
                text = stringResource(id = R.string.dashboard_onboarding_title, stringResource(id = R.string.app_name)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(id = R.string.dashboard_onboarding_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

