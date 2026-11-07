package com.thrym.app.presentation.history

import com.thrym.feature.history.R as UxR
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.FitnessCenter

import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.accentText
import com.thrym.app.presentation.common.HistorySkeleton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import java.time.LocalDate
import com.thrym.core.designsystem.R
import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.TrainingPlan
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.util.DateFormatting
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.EmptyStateScreen
import com.thrym.app.presentation.common.ModeSegment
import com.thrym.app.presentation.common.ThrymModeSwitcher
import com.thrym.app.presentation.common.ThrymScreenScaffold
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.thrymSharedElement
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semanticText
import com.thrym.app.presentation.theme.staggeredEntrance
import com.thrym.feature.history.R as HistoryR
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import androidx.compose.foundation.rememberScrollState

internal enum class HistoryListContentState {
    Loading,
    Empty,
    Error,
    Content
}

internal fun resolveHistoryContentState(
    refreshLoadState: LoadState,
    itemCount: Int
): HistoryListContentState {
    if (itemCount > 0) return HistoryListContentState.Content
    return when (refreshLoadState) {
        is LoadState.Loading -> HistoryListContentState.Loading
        is LoadState.Error -> HistoryListContentState.Error
        is LoadState.NotLoading -> HistoryListContentState.Empty
    }
}

/**
 * The history tab: the finished workouts and, with game elements on, the achievements behind a
 * switcher. [requestedMode] comes from outside, for example from the level card on the
 * dashboard; the screen shows it once and reports it handled through [onModeRequestHandled].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutHistoryScreen(
    onWorkoutClick: (Long) -> Unit,
    requestedMode: HistoryMode? = null,
    onModeRequestHandled: () -> Unit = {},
    viewModel: WorkoutHistoryViewModel = koinViewModel(),
    achievementsViewModel: AchievementsViewModel = koinViewModel()
) {
    val glass = isLiquidGlass()
    val pagedWorkouts = viewModel.pagedWorkouts.collectAsLazyPagingItems()
    val pagedEntries = viewModel.pagedHistoryEntries.collectAsLazyPagingItems()
    val weekSummaries by viewModel.weekSummaries.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    var deleteSessionId by remember { mutableStateOf<Long?>(null) }
    val appPreferencesRepository: AppPreferencesRepository = koinInject()
    val preferences by appPreferencesRepository.preferences.collectAsStateWithLifecycle(
        initialValue = AppPreferences()
    )
    val dims = thrymDimens
    val snackbarHostState = remember { SnackbarHostState() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val achievements by achievementsViewModel.uiState.collectAsStateWithLifecycle()

    // Kept like the plans mode: a bottom-nav return to the history shows the last mode again.
    var selectedMode by rememberSaveable { mutableStateOf(HistoryMode.WORKOUTS) }
    LaunchedEffect(requestedMode) {
        if (requestedMode != null) {
            selectedMode = requestedMode
            onModeRequestHandled()
        }
    }
    // The request already counts in the frame it arrives in, so the old mode never flashes.
    val display = resolveHistoryMode(requestedMode ?: selectedMode, achievements.gamificationEnabled)

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    ThrymScreenScaffold(
        topBar = {
            if (!glass) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                title = { Text(stringResource(id = R.string.history_title)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (glass) GlassHistoryHeader()
            if (display.showSwitcher) {
                HistoryModeSwitcher(
                    selected = display.mode ?: selectedMode,
                    onSelect = { selectedMode = it },
                    modifier = Modifier.padding(
                        horizontal = if (glass) 16.dp else dims.spacingMd,
                        vertical = dims.spacingXs
                    )
                )
            }

            when (display.mode) {
                null -> Unit
                HistoryMode.ACHIEVEMENTS -> AchievementsView(
                    content = achievements.content,
                    glass = glass,
                    onRetry = achievementsViewModel::retry,
                    modifier = Modifier.weight(1f)
                )
                HistoryMode.WORKOUTS -> {
                    if (glass) {
                        GlassHistorySearchField(
                            query = state.filter.query,
                            onQueryChange = viewModel::setSearchQuery
                        )
                    } else {
                        HistorySearchField(
                            query = state.filter.query,
                            onQueryChange = viewModel::setSearchQuery
                        )
                    }
                    HistoryFilterBar(
                        filter = state.filter,
                        plans = state.plans,
                        onPlanSelected = viewModel::setPlanFilter,
                        onTimeRangeSelected = viewModel::setTimeRange
                    )

                    when (resolveHistoryContentState(pagedWorkouts.loadState.refresh, pagedWorkouts.itemCount)) {
                        HistoryListContentState.Loading -> {
                            HistorySkeleton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }

                        HistoryListContentState.Empty -> {
                            EmptyStateScreen(
                                title = stringResource(
                                    id = if (state.filter.isActive) {
                                        HistoryR.string.history_filter_empty_title
                                    } else {
                                        R.string.history_empty_title
                                    }
                                ),
                                subtitle = stringResource(
                                    id = if (state.filter.isActive) {
                                        HistoryR.string.history_filter_empty_subtitle
                                    } else {
                                        R.string.history_empty_subtitle
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }

                        HistoryListContentState.Error -> {
                            EmptyStateScreen(
                                title = stringResource(id = R.string.history_error_title),
                                subtitle = stringResource(id = R.string.history_error_subtitle),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                action = {
                                    TextButton(onClick = { pagedWorkouts.retry() }, colors = ThrymInteractiveColors.textButton()) {
                                        Text(text = stringResource(id = R.string.common_retry))
                                    }
                                }
                            )
                        }

                        HistoryListContentState.Content -> if (glass) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = dims.spacingSm),
                                verticalArrangement = Arrangement.spacedBy(dims.spacingSm)
                            ) {
                                items(
                                    count = pagedEntries.itemCount,
                                    key = pagedEntries.itemKey { entry ->
                                        when (entry) {
                                            is HistoryListEntry.Week -> "week_${entry.weekStart}"
                                            is HistoryListEntry.Workout -> "workout_${entry.item.session.id}"
                                        }
                                    },
                                    contentType = pagedEntries.itemContentType { it::class.simpleName }
                                ) { index ->
                                    when (val entry = pagedEntries[index]) {
                                        is HistoryListEntry.Week -> GlassWeekHeader(
                                            weekStart = entry.weekStart,
                                            summary = weekSummaries[entry.weekStart],
                                            today = today
                                        )
                                        is HistoryListEntry.Workout -> SwipeToDeleteCard(
                                            item = entry.item,
                                            unitSystem = preferences.unitSystem,
                                            onClick = { onWorkoutClick(entry.item.session.id) },
                                            onDelete = { deleteSessionId = entry.item.session.id },
                                            glass = true
                                        )
                                        null -> Unit
                                    }
                                }
                                item {
                                    Spacer(modifier = Modifier.height(dims.spacingXl))
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentPadding = PaddingValues(dims.spacingMd),
                                verticalArrangement = Arrangement.spacedBy(dims.spacingSm)
                            ) {
                                items(
                                    count = pagedWorkouts.itemCount,
                                    key = pagedWorkouts.itemKey { it.session.id }
                                ) { index ->
                                    val item = pagedWorkouts[index]
                                    if (item != null) {
                                        val entranceModifier = if (index < 8) Modifier.staggeredEntrance(index) else Modifier
                                        SwipeToDeleteCard(
                                            item = item,
                                            unitSystem = preferences.unitSystem,
                                            onClick = { onWorkoutClick(item.session.id) },
                                            onDelete = { deleteSessionId = item.session.id },
                                            modifier = entranceModifier
                                        )
                                    }
                                }
                                item {
                                    Spacer(modifier = Modifier.height(dims.spacingXl))
                                }
                            }
                        }
                    }
                }
            }
        }

        deleteSessionId?.let { id ->
            AlertDialog(
                onDismissRequest = { deleteSessionId = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                title = { Text(stringResource(id = R.string.history_delete_dialog_title)) },
                text = { Text(stringResource(id = R.string.history_delete_dialog_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteSession(id)
                        deleteSessionId = null
                    },
                        colors = ThrymInteractiveColors.textButton()
                    ) {
                        Text(
                            text = stringResource(id = R.string.common_delete),
                            color = MaterialTheme.semanticText.danger
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteSessionId = null }, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = R.string.common_cancel))
                    }
                }
            )
        }
    }
}

/** "Trainings | Erfolge" at the top of the history, the same switcher as in the plans tab. */
@Composable
private fun HistoryModeSwitcher(
    selected: HistoryMode,
    onSelect: (HistoryMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = HistoryMode.entries
    ThrymModeSwitcher(
        segments = listOf(
            ModeSegment(stringResource(HistoryR.string.history_mode_workouts), Icons.Default.History),
            ModeSegment(stringResource(HistoryR.string.history_mode_achievements), Icons.Default.EmojiEvents)
        ),
        selectedIndex = modes.indexOf(selected),
        onSelect = { index -> onSelect(modes[index]) },
        modifier = modifier
    )
}

@Composable
private fun HistorySearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val dims = thrymDimens
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dims.spacingMd, vertical = dims.spacingXs),
        placeholder = { Text(stringResource(HistoryR.string.history_search_placeholder)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(HistoryR.string.history_search_clear)
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterBar(
    filter: HistoryFilter,
    plans: List<TrainingPlan>,
    onPlanSelected: (Long?) -> Unit,
    onTimeRangeSelected: (HistoryTimeRange) -> Unit
) {
    val dims = thrymDimens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dims.spacingMd, vertical = dims.spacingXs),
        verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
    ) {
        FilterRow(
            label = stringResource(HistoryR.string.history_filter_time_label),
            chips = HistoryTimeRange.entries.map { range ->
                HistoryFilterChip(
                    text = stringResource(timeRangeLabelRes(range)),
                    selected = filter.timeRange == range,
                    onClick = { onTimeRangeSelected(range) }
                )
            }
        )
        FilterRow(
            label = stringResource(HistoryR.string.history_filter_plan_label),
            chips = buildList {
                add(
                    HistoryFilterChip(
                        text = stringResource(HistoryR.string.history_filter_plan_all),
                        selected = filter.planId == null,
                        onClick = { onPlanSelected(null) }
                    )
                )
                plans.forEach { plan ->
                    add(
                        HistoryFilterChip(
                            text = plan.name,
                            selected = filter.planId == plan.id,
                            onClick = { onPlanSelected(plan.id) }
                        )
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    label: String,
    chips: List<HistoryFilterChip>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(thrymDimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = thrymDimens.spacing2)
        )
        chips.forEach { chip ->
            FilterChip(
                selected = chip.selected,
                onClick = chip.onClick,
                label = {
                    Text(
                        text = chip.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = ThrymInteractiveColors.filterChip()
            )
        }
    }
}

private data class HistoryFilterChip(
    val text: String,
    val selected: Boolean,
    val onClick: () -> Unit
)

private fun timeRangeLabelRes(range: HistoryTimeRange): Int = when (range) {
    HistoryTimeRange.ALL_TIME -> HistoryR.string.history_filter_time_all
    HistoryTimeRange.LAST_30_DAYS -> HistoryR.string.history_filter_time_30_days
    HistoryTimeRange.LAST_90_DAYS -> HistoryR.string.history_filter_time_90_days
    HistoryTimeRange.THIS_YEAR -> HistoryR.string.history_filter_time_this_year
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun SwipeToDeleteCard(
    item: WorkoutHistoryItem,
    unitSystem: UnitSystem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    glass: Boolean = false
) {
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    SwipeToDismissBox(
        modifier = modifier,
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val progress = dismissState.progress
            if (dismissState.targetValue == SwipeToDismissBoxValue.Settled && dismissState.currentValue == SwipeToDismissBoxValue.Settled) {
                Box(modifier = Modifier.fillMaxSize())
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = progress), shape = MaterialTheme.shapes.large)
                        .padding(end = thrymDimens.spacingLg),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(id = R.string.history_delete_cd),
                        tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = progress)
                    )
                }
            }
        }
    ) {
        if (glass) {
            GlassHistoryRow(
                item = item,
                unitSystem = unitSystem,
                onClick = onClick,
                modifier = Modifier.thrymSharedElement("workout_card_${item.session.id}")
            )
        } else {
            WorkoutCard(item = item, unitSystem = unitSystem, onClick = onClick)
        }
    }
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun WorkoutCard(
    item: WorkoutHistoryItem,
    unitSystem: UnitSystem,
    onClick: () -> Unit
) {
    val dims = thrymDimens

    ThrymSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .thrymSharedElement("workout_card_${item.session.id}")
            .clickable(onClick = onClick),
        tone = ThrymSurfaceTone.ELEVATED
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.spacingLg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val title = if (item.session.name.isNotBlank()) item.session.name else stringResource(id = R.string.history_title)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(dims.spacingXs))

                Text(
                    text = item.session.startTime.format(DateFormatting.dateFull(currentLocale())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.accentText,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(dims.spacingSm))

                val durationMin = item.session.durationSeconds / 60
                val statsList = mutableListOf<String>()
                statsList.add(stringResource(UxR.string.history_duration_minutes, durationMin))
                statsList.add(pluralStringResource(UxR.plurals.history_glass_exercises, item.exerciseCount, item.exerciseCount))
                if (item.totalVolume > 0) {
                    statsList.add(WeightFormatting.formatVolume(item.totalVolume, unitSystem))
                }

                Text(
                    text = statsList.joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

