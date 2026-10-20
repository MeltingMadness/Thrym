package com.thrym.app.presentation.workout

import android.annotation.SuppressLint
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.rememberCoroutineScope
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.accentText
import com.thrym.feature.workout.R as WorkoutR
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thrym.core.designsystem.R
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.presentation.common.ThrymScreenScaffold
import com.thrym.app.presentation.common.LoadingScreen
import com.thrym.shared.readinessdata.SetIntention
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import com.thrym.app.presentation.common.RestTimer
import com.thrym.app.presentation.common.WorkoutTimer
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.common.HapticFeedbackHelper
import com.thrym.app.presentation.common.rememberHapticFeedback
import com.thrym.app.presentation.common.rememberUiStrings
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.thrymMotion
import com.thrym.shared.workout.SessionOrderMove
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Screen position of a moved block: its list item [offset] when it sat at [fromIndex]. */
private data class MoveAnchor(val key: String, val fromIndex: Int, val offset: Int)

@Composable
private fun RestDockRow.glassPauseInfo(exercises: List<ExerciseWithSets>): GlassPauseInfo {
    val exercise = exercises.find { it.key == exerciseKey }
    return GlassPauseInfo(
        timer = timer,
        exerciseName = exercise?.exercise?.localizedName().orEmpty(),
        nextSetNumber = (exercise?.sets?.count { it.reps > 0 } ?: 0) + 1
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun ActiveWorkoutScreen(
    onWorkoutFinished: () -> Unit,
    onProgressionReview: (Long) -> Unit,
    onWorkoutDetails: (Long) -> Unit = {},
    onPlanEditor: (Long) -> Unit = {},
    viewModel: ActiveWorkoutViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appPreferencesRepository: AppPreferencesRepository = koinInject()
    val preferences by appPreferencesRepository.preferences.collectAsStateWithLifecycle(
        initialValue = AppPreferences()
    )
    val snackbarHostState = remember { SnackbarHostState() }
    // Record and undo messages appear at the top, so they never cover the log button below.
    // Each has its own host, records above undo, so neither waits behind the other.
    val recordSnackbarHostState = remember { SnackbarHostState() }
    val undoSnackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val uiStrings by rememberUpdatedState(rememberUiStrings())
    val dims = thrymDimens
    val haptic = rememberHapticFeedback()
    val view = LocalView.current
    // One instance that moves into the Liquid Glass pause while it is open, so the hosts keep
    // their messages and timers.
    val topNotices = remember(haptic) {
        movableContentOf { modifier: Modifier ->
            WorkoutTopNotices(recordSnackbarHostState, undoSnackbarHostState, haptic, modifier)
        }
    }
    val activeSession = (state.sessionPhase as? ActiveWorkoutSessionPhase.Active)?.session
    val liquidGlass = isLiquidGlass()
    var pauseOpen by rememberSaveable { mutableStateOf(false) }
    var pauseRowKey by rememberSaveable { mutableStateOf<String?>(null) }
    var seenRestStarts by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var restStartsInitialized by remember { mutableStateOf(false) }
    var dockHeight by remember { mutableStateOf(0.dp) }
    var listHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val currentRestTimers by rememberUpdatedState(state.restTimers)

    val exerciseGroups = state.exerciseGroups
    val reducedMotion = thrymMotion.reduced

    // The list scrolls to the finish card once, when the athlete's own log or edit completes
    // the plan. A tap arms the scroll together with the list it saw; the next list either
    // completes the plan or disarms it, so a later undo or deload switch never scrolls.
    // Plain arrays, because arming must not restart the effects below.
    val finishScrollArmed = remember { booleanArrayOf(false) }
    val finishScrollArmedFor = remember { arrayOfNulls<List<ExerciseWithSets>>(1) }

    // Record messages still showing or waiting for their host.
    val recordNotices = remember { mutableListOf<Job>() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            // Each message gets its own coroutine, because a snackbar suspends until it is
            // dismissed and the collector must keep taking events meanwhile.
            when (event) {
                is WorkoutEvent.NewRecords -> {
                    val first = event.records.firstOrNull() ?: return@collect
                    // A new record replaces the one on screen instead of queueing behind it.
                    // The haptic comes with the message, see WorkoutTopNotices.
                    recordNotices.toList().forEach { it.cancel() }
                    val notice = launch {
                        recordSnackbarHostState.showSnackbar(
                            RecordSnackbarVisuals(
                                title = liveRecordTitle(uiStrings, event),
                                detail = liveRecordDetail(uiStrings, first, preferences.unitSystem)
                            )
                        )
                    }
                    recordNotices += notice
                    notice.invokeOnCompletion { recordNotices -= notice }
                }
                is WorkoutEvent.SetDeleted -> {
                    // Cancelling dismisses the record message on screen and drops a waiting
                    // one; either may name the set that was just deleted.
                    recordNotices.toList().forEach { it.cancel() }
                    launch {
                        val result = undoSnackbarHostState.showSnackbar(
                            message = context.getString(R.string.workout_set_deleted, event.setNumber),
                            actionLabel = context.getString(R.string.common_undo),
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            finishScrollArmed[0] = false
                            viewModel.undoDeleteSet()
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = error.message,
            actionLabel = error.retry?.let { context.getString(R.string.common_retry) },
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.retryLastError()
            else -> viewModel.clearError()
        }
    }

    LaunchedEffect(state.finishState) {
        when (val finishState = state.finishState) {
            is WorkoutFinishState.ReviewReady -> Unit
            WorkoutFinishState.CompletedWithoutReview -> if (activeSession == null) onWorkoutFinished()
            else -> Unit
        }
    }

    val completionRecordInputs by viewModel.completionRecordInputs.collectAsStateWithLifecycle()
    val completionFacts by viewModel.completionFacts.collectAsStateWithLifecycle()
    LaunchedEffect(activeSession?.endTime) {
        if (activeSession?.endTime != null) viewModel.loadCompletionRecords()
    }
    if (activeSession?.endTime != null) {
        WorkoutCompletionScreen(
            session = activeSession,
            rows = state.exercisesWithSets,
            recordInputs = completionRecordInputs,
            facts = completionFacts,
            unitSystem = preferences.unitSystem,
            finishState = state.finishState,
            onClose = onWorkoutFinished,
            onDetails = { onWorkoutDetails(activeSession.id) },
            onPlanEditor = { activeSession.planId?.let(onPlanEditor) },
            onProgression = { onProgressionReview(activeSession.id) },
            onRetryProgression = viewModel::retryProgressionGeneration
        )
        return
    }

    ThrymScreenScaffold(
        topBar = {
            // Liquid Glass puts title, time and "Beenden" into GlassWorkoutHeader.
            if (!liquidGlass) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                title = {
                    val name = activeSession?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(id = R.string.workout_title_default)
                    Text(name)
                },
                actions = {
                    TextButton(onClick = viewModel::showFinishDialog, colors = ThrymInteractiveColors.textButton()) {
                        Text(
                            text = stringResource(id = R.string.workout_finish_action),
                            color = MaterialTheme.accentText,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        when (state.sessionPhase) {
            ActiveWorkoutSessionPhase.Loading -> {
                LoadingScreen(modifier = Modifier.padding(padding))
                return@ThrymScreenScaffold
            }
            ActiveWorkoutSessionPhase.Missing -> {
                MissingSessionContent(
                    onBack = { onWorkoutFinished() },
                    modifier = Modifier.padding(padding)
                )
                return@ThrymScreenScaffold
            }
            is ActiveWorkoutSessionPhase.Active -> Unit
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Focus: the first exercise (and its superset group) with open plan sets is
                // expanded; finished and upcoming planned exercises are collapsed. Ad-hoc
                // exercises have no known end and stay expanded. A tap on the card header
                // overrides the default for that exercise.
                val focusGroupIndex = exerciseGroups.indexOfFirst { group ->
                    group.exercises.any { it.planTarget != null && it.openSlotCount() > 0 }
                }
                val focusGroupKey = exerciseGroups.getOrNull(focusGroupIndex)?.key
                var expansionOverrides by rememberSaveable { mutableStateOf(mapOf<String, Boolean>()) }
                val isExpanded: (ExerciseWithSets, Int) -> Boolean = { exerciseWithSets, groupIndex ->
                    expansionOverrides[exerciseWithSets.key.stableListKey()]
                        ?: (exerciseWithSets.planTarget == null || groupIndex == focusGroupIndex)
                }
                val listState = rememberLazyListState()
                // The list holds its first visible block in place when the data changes. A block
                // moved past that one would jump away from the finger, and a quick second tap
                // would hit another control. So the moved block goes back to where it sat.
                val moveAnchor = remember { arrayOfNulls<MoveAnchor>(1) }
                moveAnchor[0]?.let { anchor ->
                    val index = exerciseGroups.indexOfFirst { it.key == anchor.key }
                    if (index < 0) {
                        moveAnchor[0] = null
                    } else if (index != anchor.fromIndex) {
                        moveAnchor[0] = anchor.copy(fromIndex = index)
                        listState.requestScrollToItem(index, -anchor.offset)
                    }
                }
                val previousFocusGroupKey = remember { arrayOfNulls<String>(1) }
                // Keyed by the focus block, not its index: moving another block above it shifts
                // the index but must not scroll the list. The list also stays put when a move
                // makes another block the focus while the previous one still has open sets; it
                // scrolls on first load and once the previous focus block is done.
                LaunchedEffect(focusGroupKey) {
                    val previousKey = previousFocusGroupKey[0]
                    previousFocusGroupKey[0] = focusGroupKey
                    val previousStillOpen = exerciseGroups.any { group ->
                        group.key == previousKey &&
                            group.exercises.any { it.planTarget != null && it.openSlotCount() > 0 }
                    }
                    if (previousStillOpen) return@LaunchedEffect
                    val index = exerciseGroups.indexOfFirst { it.key == focusGroupKey }
                    if (index > 0) listState.animateScrollToItem(index)
                }
                val plannedSetCount = state.exercisesWithSets.sumOf { it.planTarget?.loggingSlots()?.size ?: 0 }
                val openPlannedSetCount = state.exercisesWithSets.sumOf { it.openSlotCount() }
                val allPlannedDone = state.exercisesWithSets.allPlannedSetsDone()
                // Reopening a finished plan, undo or a deload switch leave the list where it is.
                val allPlannedDoneNow by rememberUpdatedState(allPlannedDone)
                val currentExercisesWithSets by rememberUpdatedState(state.exercisesWithSets)
                val armFinishScroll = {
                    if (!allPlannedDoneNow) {
                        finishScrollArmed[0] = true
                        finishScrollArmedFor[0] = currentExercisesWithSets
                    }
                }
                // A new list that leaves a slot open ends the arming: that log or edit did not
                // complete the plan.
                LaunchedEffect(state.exercisesWithSets) {
                    if (!allPlannedDone && finishScrollArmedFor[0] != state.exercisesWithSets) {
                        finishScrollArmed[0] = false
                    }
                }
                // Keyed on the result alone, so the scroll waits for the new set from the
                // database rather than for the tap.
                LaunchedEffect(allPlannedDone) {
                    if (!allPlannedDone || !finishScrollArmed[0]) return@LaunchedEffect
                    finishScrollArmed[0] = false
                    // The blocks, "Übung hinzufügen", then the card.
                    val index = exerciseGroups.size + 1
                    withTimeoutOrNull(1_000) {
                        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > index }
                    } ?: return@LaunchedEffect
                    // TalkBack hears once, with the scroll, that the plan is done.
                    view.announce(
                        uiStrings.getQuantity(WorkoutR.plurals.workout_finish_card_title, plannedSetCount, plannedSetCount)
                    )
                    if (reducedMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index)
                }
                val moveControls: (Int) -> ExerciseMoveControls = { groupIndex ->
                    val group = exerciseGroups[groupIndex]
                    val move: (SessionOrderMove) -> Unit = { direction ->
                        // A move never collapses an open card: the moved block and the focus
                        // block keep their expanded cards, even if the focus moves elsewhere.
                        val pinned = listOfNotNull(groupIndex, focusGroupIndex.takeIf { it >= 0 })
                            .flatMap { index ->
                                exerciseGroups[index].exercises.filter { isExpanded(it, index) }
                            }
                            .map { it.key.stableListKey() }
                        expansionOverrides = expansionOverrides + pinned.associateWith { true }
                        // A block that is the first visible one already stays put, and keeps
                        // the slide animation that a requested scroll would reset.
                        val visibleItems = listState.layoutInfo.visibleItemsInfo
                        moveAnchor[0] = visibleItems
                            .firstOrNull { it.key == group.key }
                            ?.takeIf { it.index != visibleItems.first().index }
                            ?.let { MoveAnchor(group.key, groupIndex, it.offset) }
                        viewModel.moveExercise(group.exercises.first().key, direction)
                    }
                    ExerciseMoveControls(
                        canMoveUp = groupIndex > 0,
                        canMoveDown = groupIndex < exerciseGroups.lastIndex,
                        onMoveUp = { move(SessionOrderMove.UP) },
                        onMoveDown = { move(SessionOrderMove.DOWN) }
                    )
                }
                val railScope = rememberCoroutineScope()
                activeSession?.let { session ->
                    val loggedSetCount = state.exercisesWithSets.sumOf { it.sets.count { set -> set.reps > 0 } }
                    val loggedVolumeKg = state.exercisesWithSets.sumOf { row -> row.sets.filter { it.reps > 0 }.sumOf { it.weightKg * it.reps } }
                    if (liquidGlass) {
                        val sessionName = session.name.takeIf { it.isNotBlank() }
                            ?: stringResource(id = R.string.workout_title_default)
                        val currentIndex = focusGroupIndex.takeIf { it >= 0 }
                        GlassWorkoutHeader(
                            label = if (currentIndex != null && exerciseGroups.isNotEmpty()) {
                                stringResource(
                                    WorkoutR.string.workout_glass_exercise_position,
                                    sessionName,
                                    currentIndex + 1,
                                    exerciseGroups.size
                                )
                            } else {
                                sessionName
                            },
                            startTime = session.startTime,
                            progressText = listOf(
                                if (plannedSetCount > 0) {
                                    pluralStringResource(
                                        R.plurals.workout_header_progress_planned,
                                        plannedSetCount,
                                        plannedSetCount - openPlannedSetCount,
                                        plannedSetCount
                                    )
                                } else {
                                    pluralStringResource(R.plurals.workout_header_progress_free, loggedSetCount, loggedSetCount)
                                },
                                WeightFormatting.formatVolume(loggedVolumeKg, preferences.unitSystem)
                            ).joinToString(" · "),
                            finishLabel = stringResource(id = R.string.workout_finish_action),
                            onFinish = viewModel::showFinishDialog
                        )
                        if (exerciseGroups.isNotEmpty()) {
                            GlassExerciseRail(
                                items = exerciseGroups.mapIndexed { index, group ->
                                    val done = group.exercises.all {
                                        it.planTarget != null && it.openSlotCount() == 0 && it.sets.any { set -> set.reps > 0 }
                                    }
                                    RailItem(
                                        key = group.key,
                                        name = group.exercises.map { it.exercise.localizedName() }.joinToString(" + "),
                                        status = when {
                                            index == focusGroupIndex -> RailStatus.CURRENT
                                            done -> RailStatus.DONE
                                            else -> RailStatus.UPCOMING
                                        }
                                    )
                                },
                                doneLabel = stringResource(WorkoutR.string.workout_glass_rail_done),
                                currentLabel = stringResource(WorkoutR.string.workout_glass_rail_current),
                                onSelect = { index ->
                                    val group = exerciseGroups[index]
                                    expansionOverrides = expansionOverrides +
                                        group.exercises.map { it.key.stableListKey() to true }
                                    railScope.launch { listState.animateScrollToItem(index) }
                                }
                            )
                        }
                        // Rest timers live in the dock and the pause screen below.
                        state.restTimers.forEach { (exerciseKey, timer) ->
                            key(exerciseKey) {
                                RestTimerCompletionWatcher(timer) { viewModel.dismissRestTimer(exerciseKey) }
                            }
                        }
                    } else WorkoutHeader(
                        startTime = session.startTime,
                        loggedSetCount = loggedSetCount,
                        plannedSetCount = plannedSetCount,
                        completedPlannedSetCount = plannedSetCount - openPlannedSetCount,
                        volumeText = WeightFormatting.formatVolume(loggedVolumeKg, preferences.unitSystem)
                    ) {
                        state.restTimers.forEach { (exerciseKey, timer) ->
                            val exerciseWithSets = state.exercisesWithSets.find { it.key == exerciseKey }
                            val group = exerciseGroups.find { it.exercises.any { ex -> ex.key == exerciseKey } }
                            val indexInSuperset = group?.exercises?.indexOfFirst { it.key == exerciseKey } ?: -1

                            RestTimer(
                                startTime = timer.startTime,
                                durationSeconds = timer.durationSeconds.toLong(),
                                onDismiss = { viewModel.dismissRestTimer(exerciseKey) },
                                onComplete = { viewModel.dismissRestTimer(exerciseKey) },
                                titleText = exerciseWithSets?.exercise?.localizedName(),
                                baseColor = supersetTintColor(group?.supersetGroupId, indexInSuperset),
                                textColor = supersetTintTextColor(group?.supersetGroupId, indexInSuperset)
                            )
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged { size -> listHeight = with(density) { size.height.toDp() } },
                    verticalArrangement = Arrangement.spacedBy(dims.spacingSm),
                    contentPadding = PaddingValues(
                        start = dims.spacingMd,
                        end = dims.spacingMd,
                        top = dims.spacingMd,
                        bottom = if (liquidGlass && state.restTimers.isNotEmpty()) {
                            dims.spacingMd + dockHeight
                        } else {
                            dims.spacingMd
                        }
                    )
                ) {
                    itemsIndexed(exerciseGroups, key = { _, group -> group.key }) { groupIndex, group ->
                        Column(
                            modifier = if (reducedMotion) Modifier else Modifier.animateItem(),
                            verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
                        ) {
                            group.supersetGroupId?.let { supersetGroupId ->
                                SupersetHeader(
                                    groupId = supersetGroupId,
                                    exerciseCount = group.exercises.size,
                                    exerciseNames = group.exercises
                                        .map { it.exercise.localizedName() }
                                        .joinToString(separator = " • "),
                                    moveControls = moveControls(groupIndex).takeIf {
                                        group.exercises.any { isExpanded(it, groupIndex) }
                                    }
                                )
                            }
                            group.exercises.forEachIndexed { indexInSuperset, exerciseWithSets ->
                                val expansionKey = exerciseWithSets.key.stableListKey()
                                val expanded = isExpanded(exerciseWithSets, groupIndex)
                                // The key keeps each card's input state with its exercise when
                                // the block moves.
                                key(expansionKey) {
                                    ExerciseCard(
                                        exerciseWithSets = exerciseWithSets,
                                        expanded = expanded,
                                        onToggleExpanded = {
                                            expansionOverrides = expansionOverrides + (expansionKey to !expanded)
                                        },
                                        nextSetRecommendation = state.nextSetRecommendations[exerciseWithSets.key],
                                        tintColor = supersetTintColor(group.supersetGroupId, indexInSuperset),
                                        defaultWarmupFlag = preferences.defaultWarmupFlag,
                                        intensitySystem = preferences.intensitySystem,
                                        unitSystem = preferences.unitSystem,
                                        plateCalculatorEnabled = preferences.plateCalculatorEnabled,
                                        availablePlates = preferences.availablePlates,
                                        barbellWeightKg = preferences.barbellWeightKg,
                                        isLogging = (state.logInFlightByExercise[exerciseWithSets.key] ?: 0) > 0,
                                        logSuccessSubmissions = state.logSuccessSubmissions,
                                        updateInFlightBySet = state.updateInFlightBySet,
                                        updateSuccessCountBySet = state.updateSuccessCountBySet,
                                        setIntentions = state.setIntentions,
                                        setIntentionsLoaded = state.setIntentionsLoaded,
                                        setIntentionsFailed = state.setIntentionsFailed,
                                        onLogSet = { reps, weight, setType, intensity, submissionId, intention ->
                                            armFinishScroll()
                                            viewModel.logSet(
                                                key = exerciseWithSets.key,
                                                exerciseId = exerciseWithSets.exercise.id,
                                                reps = reps,
                                                weightKg = weight,
                                                setType = setType,
                                                intensity = intensity,
                                                submissionId = submissionId,
                                                // A brand-new set has no stored answer to preserve:
                                                // "no deliberate choice" becomes the explicit UNKNOWN,
                                                // which stores no record.
                                                intention = intention ?: SetIntention.UNKNOWN
                                            )
                                        },
                                        onUpdateSet = { setId, reps, weight, intensity, intention ->
                                            armFinishScroll()
                                            viewModel.updateSet(setId, reps, weight, intensity, intention)
                                        },
                                        onDeleteSet = viewModel::deleteSet,
                                        haptic = haptic,
                                        // Superset members move with their block from the header.
                                        moveControls = if (group.supersetGroupId == null) {
                                            moveControls(groupIndex)
                                        } else {
                                            null
                                        }
                                    )
                                }
                            }
                        }
                    }
                    item {
                TextButton(
                    onClick = viewModel::showExercisePicker,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dims.spacingMd, vertical = dims.spacingXs),
                    colors = ThrymInteractiveColors.textButton()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(
                        text = stringResource(id = R.string.workout_add_exercise),
                        modifier = Modifier.padding(start = dims.spacingXs)
                    )
                }

                    }
                    // Last, so it never sits between the exercises; the bottom padding keeps it
                    // above the rest dock.
                    if (allPlannedDone) {
                        item(key = "finish-workout", contentType = "finish") {
                            WorkoutFinishCard(
                                plannedSetCount = plannedSetCount,
                                onFinish = viewModel::showFinishDialog,
                                modifier = (if (reducedMotion) Modifier else Modifier.animateItem())
                                    .testTag("workout_finish_card")
                            )
                        }
                    }
                }
            }
            val timerStarts = state.restTimers.map { (key, timer) ->
                key.stableListKey() to timer.startTime.toEpochMilli()
            }.sortedBy { it.first }
            LaunchedEffect(timerStarts, liquidGlass) {
                if (!liquidGlass) {
                    pauseOpen = false
                    pauseRowKey = null
                }
                // A restored ViewModel can briefly expose an empty timer map before its saved
                // timers load. Keep the saved starts through that first empty snapshot.
                if (!restStartsInitialized && timerStarts.isEmpty() && seenRestStarts.isNotEmpty()) {
                    restStartsInitialized = true
                    return@LaunchedEffect
                }
                restStartsInitialized = true
                // Two timer changes within one frame arrive as one snapshot. If B starts just before
                // A ends, B can open as if it had started alone; the window is a single frame.
                val decision = decideRestDockStart(seenRestStarts, state.restTimers)
                seenRestStarts = decision.seenStarts
                if (resolvePinnedRestRow(pauseRowKey, state.restTimers, System.currentTimeMillis()) == null) {
                    pauseOpen = false
                    pauseRowKey = null
                }
                if (liquidGlass) decision.openKey?.let { key ->
                    pauseRowKey = key.stableListKey()
                    pauseOpen = true
                }
            }
            val nowMillis = System.currentTimeMillis()
            val dockRows = if (liquidGlass) buildRestDockRows(state.restTimers, nowMillis) else emptyList()
            val pinnedRow = resolvePinnedRestRow(pauseRowKey, state.restTimers, nowMillis)
            val pauseShown = liquidGlass && dockRows.isNotEmpty() && pauseOpen && pinnedRow != null
            if (liquidGlass && dockRows.isNotEmpty()) {
                GlassRestDockStack(
                    rows = dockRows.map { it.stableKey to it.glassPauseInfo(state.exercisesWithSets) },
                    pauseLabel = stringResource(WorkoutR.string.workout_glass_pause),
                    skipLabel = stringResource(WorkoutR.string.workout_glass_pause_skip_short),
                    openLabel = stringResource(WorkoutR.string.workout_glass_pause_open),
                    onOpen = { stableKey ->
                        val row = dockRows.firstOrNull { it.stableKey == stableKey }
                        if (row != null && currentRestTimers[row.exerciseKey]?.startTime == row.timer.startTime) {
                            pauseRowKey = stableKey
                            pauseOpen = true
                        }
                    },
                    onSkip = { stableKey ->
                        val row = dockRows.firstOrNull { it.stableKey == stableKey }
                        if (row != null && currentRestTimers[row.exerciseKey]?.startTime == row.timer.startTime) {
                            viewModel.dismissRestTimer(row.exerciseKey)
                        }
                    },
                    visibleRows = visibleRestDockRows(listHeight.value, dims.spacingMd.value),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { size -> dockHeight = with(density) { size.height.toDp() } }
                        .padding(horizontal = dims.spacingMd, vertical = dims.spacingMd)
                )
                if (pauseShown) {
                    val pinnedKey = pinnedRow.exerciseKey
                    val pinnedStart = pinnedRow.timer.startTime
                    val plannedSetCount = state.exercisesWithSets.sumOf { it.planTarget?.loggingSlots()?.size ?: 0 }
                    val openPlannedSetCount = state.exercisesWithSets.sumOf { it.openSlotCount() }
                    val loggedSetCount = state.exercisesWithSets.sumOf { it.sets.count { set -> set.reps > 0 } }
                    val context = LocalContext.current
                    val resources = LocalResources.current
                    GlassPauseScreen(
                        info = pinnedRow.glassPauseInfo(state.exercisesWithSets),
                        progressText = if (plannedSetCount > 0) {
                            pluralStringResource(
                                R.plurals.workout_header_progress_planned,
                                plannedSetCount,
                                plannedSetCount - openPlannedSetCount,
                                plannedSetCount
                            )
                        } else {
                            pluralStringResource(R.plurals.workout_header_progress_free, loggedSetCount, loggedSetCount)
                        },
                        texts = GlassPauseTexts(
                            pause = stringResource(WorkoutR.string.workout_glass_pause),
                            close = stringResource(WorkoutR.string.workout_glass_pause_close),
                            minus = stringResource(WorkoutR.string.workout_glass_pause_minus),
                            plus = stringResource(WorkoutR.string.workout_glass_pause_plus),
                            minusDescription = stringResource(WorkoutR.string.workout_glass_pause_minus_cd),
                            plusDescription = stringResource(WorkoutR.string.workout_glass_pause_plus_cd),
                            next = stringResource(WorkoutR.string.workout_glass_pause_next),
                            skip = stringResource(WorkoutR.string.workout_glass_pause_skip),
                            ofTotal = { total -> context.getString(WorkoutR.string.workout_glass_pause_of_total, total) },
                            nextSet = { name, number -> context.getString(WorkoutR.string.workout_glass_pause_next_set, name, number) },
                            remainingDescription = { seconds ->
                                val minutes = (seconds / 60).toInt()
                                val rest = (seconds % 60).toInt()
                                context.getString(
                                    WorkoutR.string.workout_glass_pause_remaining_cd,
                                    resources.getQuantityString(WorkoutR.plurals.workout_glass_pause_minutes, minutes, minutes),
                                    resources.getQuantityString(WorkoutR.plurals.workout_glass_pause_seconds, rest, rest)
                                )
                            }
                        ),
                        onMinus = {
                            if (currentRestTimers[pinnedKey]?.startTime == pinnedStart) {
                                viewModel.adjustRestTimer(pinnedKey, -15)
                            }
                        },
                        onPlus = {
                            if (currentRestTimers[pinnedKey]?.startTime == pinnedStart) {
                                viewModel.adjustRestTimer(pinnedKey, 30)
                            }
                        },
                        onSkip = {
                            if (currentRestTimers[pinnedKey]?.startTime == pinnedStart) {
                                pauseOpen = false
                                pauseRowKey = null
                                viewModel.dismissRestTimer(pinnedKey)
                            }
                        },
                        onClose = { pauseOpen = false },
                        topNotice = { topNotices(Modifier) }
                    )
                }
            }
            // The open pause shows them below its top row instead, see GlassPauseScreen.
            if (!pauseShown) topNotices(Modifier.align(Alignment.TopCenter))
        }

        if (state.showExercisePicker) {
            ExercisePickerSheet(
                onDismiss = viewModel::dismissExercisePicker,
                onExerciseSelected = { exercise ->
                    viewModel.addExercise(exercise)
                    viewModel.dismissExercisePicker()
                },
                onCreationError = viewModel::reportPickerError,
                allowCreateCustomExercise = true
            )
        }

        if (state.showFinishDialog) {
            WorkoutFinishSheet(
                rows = state.exercisesWithSets,
                busy = state.finishState != WorkoutFinishState.Idle || state.logInFlightByExercise.values.any { it > 0 } || state.updateInFlightBySet.values.any { it > 0 },
                error = state.error?.message,
                onContinue = viewModel::dismissFinishDialog,
                onFinish = viewModel::finishWorkout
            )
        }

        if (state.finishState is WorkoutFinishState.GenerationFailed) {
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                ),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                title = { Text(stringResource(id = R.string.progression_review_title)) },
                text = {
                    Text(stringResource(id = R.string.progression_review_message_action_failed))
                },
                confirmButton = {
                    TextButton(onClick = viewModel::retryProgressionGeneration, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = R.string.common_retry))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onWorkoutFinished, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = R.string.common_later))
                    }
                }
            )
        }
    }
}

/**
 * Record notice above the undo message. The success haptic comes when a record notice shows,
 * not when it waits for its host.
 */
@Composable
private fun WorkoutTopNotices(
    recordHostState: SnackbarHostState,
    undoHostState: SnackbarHostState,
    haptic: HapticFeedbackHelper,
    modifier: Modifier = Modifier
) {
    val shownRecord = recordHostState.currentSnackbarData
    LaunchedEffect(shownRecord) {
        if (shownRecord != null) haptic.confirm()
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        SnackbarHost(hostState = recordHostState) { data ->
            val recordVisuals = data.visuals as? RecordSnackbarVisuals
            if (recordVisuals != null) RecordSnackbar(recordVisuals) else Snackbar(data)
        }
        SnackbarHost(hostState = undoHostState)
    }
}

/**
 * Has TalkBack say [text] once. Deprecated from API 36 in favour of live regions, but the
 * finish card is a lazy item that is composed only once the list scrolls to it, and a live
 * region on it would speak again whenever it scrolls back into view.
 */
@Suppress("DEPRECATION")
private fun View.announce(text: String) = announceForAccessibility(text)

/**
 * A live record: "1 neuer Rekord · Bankdrücken" over "Gewicht 102,5 kg (+2,5 kg)". [message]
 * holds both lines for accessibility services that read the visuals instead of the content.
 */
private class RecordSnackbarVisuals(val title: String, val detail: String) : SnackbarVisuals {
    override val message: String = "$title. $detail"
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Short
}

/** The record message: trophy, title and the record with its gain, in the host's live region. */
@Composable
private fun RecordSnackbar(visuals: RecordSnackbarVisuals) {
    Snackbar(modifier = Modifier.padding(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inversePrimary
            )
            Column {
                Text(text = visuals.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = visuals.detail,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
                )
            }
        }
    }
}

@Composable
private fun MissingSessionContent(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = thrymDimens
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(dims.spacingLg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.workout_missing_session_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(id = R.string.workout_missing_session_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = dims.spacingSm)
        )
        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(top = dims.spacingMd),
            colors = ThrymInteractiveColors.textButton()
        ) {
            Text(stringResource(id = R.string.nav_back))
        }
    }
}
