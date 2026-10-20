package com.thrym.app.presentation.plans

import com.thrym.app.presentation.common.ThrymTopBar
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thrym.core.designsystem.R
import com.thrym.feature.plans.R as PlansR
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.common.ThrymScreenScaffold
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semanticText
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrainingPlanListScreen(
    onCreatePlan: () -> Unit,
    onEditPlan: (Long) -> Unit,
    onStartWorkout: (Long, Long) -> Unit,
    onCreateMetaPlan: () -> Unit,
    onEditMetaPlan: (Long) -> Unit,
    onOpenProgressionReview: () -> Unit,
    viewModel: TrainingPlanListViewModel = koinViewModel(),
    metaPlanViewModel: MetaPlanListViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val metaState by metaPlanViewModel.uiState.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(PlansMode.PLANS) }
    var deletePlanId by remember { mutableStateOf<Long?>(null) }
    var deletePlanName by remember { mutableStateOf("") }
    var deleteMetaPlan by remember { mutableStateOf<MetaPlanListItemUi?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val dims = thrymDimens

    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    LaunchedEffect(metaState.error) {
        metaState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            metaPlanViewModel.clearError()
        }
    }

    ThrymScreenScaffold(
        topBar = {
            ThrymTopBar(
                title = { Text(stringResource(id = R.string.plans_title)) },
                actions = {
                    // Open and past progression outcomes, also when nothing is pending.
                    IconButton(onClick = onOpenProgressionReview) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = stringResource(id = R.string.plans_progression_review_cd)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = if (mode == PlansMode.PLANS) onCreatePlan else onCreateMetaPlan,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(
                        id = if (mode == PlansMode.PLANS) R.string.plans_add_cd else R.string.meta_plans_add_cd
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        if (state.isLoading || metaState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(dims.spacingMd),
                verticalArrangement = Arrangement.spacedBy(dims.spacingSm)
            ) {
                item(key = "mode_switcher") {
                    PlansModeSwitcher(
                        selected = mode,
                        planCount = state.plans.size,
                        metaPlanCount = metaState.items.size,
                        onSelect = { mode = it },
                        modifier = Modifier.padding(bottom = dims.spacingXs)
                    )
                }
                when (mode) {
                    PlansMode.PLANS -> if (state.plans.isEmpty()) {
                        item(key = "plans_empty") {
                            PlansEmptyCard(
                                title = stringResource(id = R.string.plans_empty_title),
                                subtitle = stringResource(id = R.string.plans_empty_subtitle)
                            )
                        }
                    } else {
                        items(state.plans, key = { it.plan.id }) { item ->
                            SwipeToDeletePlanCard(
                                item = item,
                                onStart = {
                                    viewModel.startPlanWorkout(item.plan) { sessionId, planId ->
                                        onStartWorkout(sessionId, planId)
                                    }
                                },
                                onClick = { onEditPlan(item.plan.id) },
                                onDelete = {
                                    deletePlanId = item.plan.id
                                    deletePlanName = item.plan.name
                                }
                            )
                        }
                    }

                    PlansMode.META_PLANS -> if (metaState.items.isEmpty()) {
                        item(key = "meta_empty") {
                            PlansEmptyCard(
                                title = stringResource(id = R.string.meta_plans_empty_title),
                                subtitle = stringResource(id = R.string.meta_plans_empty_subtitle)
                            )
                        }
                    } else {
                        items(metaState.items, key = { "meta_${it.metaPlan.id}" }) { item ->
                            MetaPlanListCard(
                                item = item,
                                onEdit = { onEditMetaPlan(item.metaPlan.id) },
                                onDelete = { deleteMetaPlan = item }
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(dims.spacingXl)) }
            }
        }

        deletePlanId?.let { id ->
            AlertDialog(
                onDismissRequest = { deletePlanId = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                title = { Text(stringResource(id = R.string.plans_delete_title)) },
                text = { Text(stringResource(id = R.string.plans_delete_text, deletePlanName)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deletePlan(id)
                        deletePlanId = null
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
                    TextButton(onClick = { deletePlanId = null }, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = R.string.common_cancel))
                    }
                }
            )
        }

        deleteMetaPlan?.let { item ->
            AlertDialog(
                onDismissRequest = { deleteMetaPlan = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                title = { Text(stringResource(id = R.string.meta_plans_delete_title)) },
                text = { Text(stringResource(id = R.string.meta_plans_delete_text, item.metaPlan.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            metaPlanViewModel.deleteMetaPlan(item.metaPlan.id)
                            deleteMetaPlan = null
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
                    TextButton(onClick = { deleteMetaPlan = null }, colors = ThrymInteractiveColors.textButton()) {
                        Text(text = stringResource(id = R.string.common_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun PlansEmptyCard(title: String, subtitle: String) {
    val dims = thrymDimens
    ThrymSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = dims.spacingLg),
        tone = ThrymSurfaceTone.ELEVATED
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.spacingLg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(dims.spacingXs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeletePlanCard(
    item: PlanListItem,
    onStart: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    SwipeToDismissBox(
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
                        contentDescription = stringResource(id = R.string.common_delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = progress)
                    )
                }
            }
        }
    ) {
        PlanCard(item = item, onStart = onStart, onClick = onClick, onLongClick = onDelete)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanCard(
    item: PlanListItem,
    onStart: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dims = thrymDimens
    val glass = isLiquidGlass()
    val exerciseCount = item.plan.exercises.size
    val lastDone = when (val days = item.lastDoneDaysAgo) {
        null -> stringResource(R.string.dashboard_hero_last_done_never)
        0L -> stringResource(R.string.dashboard_hero_last_done_today)
        1L -> stringResource(R.string.dashboard_hero_last_done_yesterday)
        else -> pluralStringResource(R.plurals.dashboard_hero_last_done_days, days.toInt(), days.toInt())
    }

    ThrymSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        tone = ThrymSurfaceTone.ELEVATED
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (glass) dims.spacingLg else dims.spacingMd)
        ) {
            Text(
                text = item.plan.name,
                style = if (glass) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (glass) FontWeight.ExtraBold else FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = listOf(
                    pluralStringResource(R.plurals.plans_exercise_count, exerciseCount, exerciseCount),
                    lastDone
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (item.exercises.isNotEmpty()) {
                val unknownExercise = stringResource(PlansR.string.plan_unknown_exercise)
                Spacer(modifier = Modifier.height(dims.spacingSm))
                Text(
                    text = item.exercises.map { it?.localizedName() ?: unknownExercise }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(dims.spacingSm))
            if (glass) {
                GlassStartPill(
                    text = stringResource(id = R.string.dashboard_start_workout),
                    onClick = onStart,
                    modifier = Modifier.align(Alignment.End)
                )
            } else FilledTonalButton(
                onClick = onStart,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(dims.spacingXs))
                Text(text = stringResource(id = R.string.dashboard_start_workout))
            }
        }
    }
}
