package com.thrym.app.presentation.statistics

import com.thrym.feature.statistics.R as UxR
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thrym.core.designsystem.R
import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.RecordType
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.presentation.common.setLoadText
import com.thrym.app.presentation.common.tracksAddedLoad
import com.thrym.app.presentation.common.rememberDateTimeFormatter
import com.thrym.app.domain.util.DateFormatting
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.presentation.common.ThrymScreenScaffold
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.LoadingScreen
import com.thrym.app.presentation.common.StatCard
import com.thrym.app.presentation.common.WeeklyMuscleVolumeCard
import com.thrym.app.presentation.common.label
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText
import com.thrym.feature.statistics.R as StatisticsR
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseStatsScreen(
    onBack: () -> Unit,
    viewModel: ExerciseStatsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val modelProducer = remember { CartesianChartModelProducer() }
    val appPreferencesRepository: AppPreferencesRepository = koinInject()
    val preferences by appPreferencesRepository.preferences.collectAsStateWithLifecycle(
        initialValue = AppPreferences()
    )
    val dims = thrymDimens
    val liquidGlass = isLiquidGlass()

    LaunchedEffect(state.chartData, preferences.unitSystem) {
        if (state.chartData.isNotEmpty()) {
            // All metrics are stored in kg; the axis shows them in the user's unit like the labels.
            val unitSystem = preferences.unitSystem
            withContext(Dispatchers.Default) {
                modelProducer.runTransaction {
                    lineModel {
                        series(state.chartData.map { WeightFormatting.convertToDisplay(it.value.toDouble(), unitSystem) })
                    }
                }
            }
        }
    }

    ThrymScreenScaffold(
        topBar = {
            // Liquid Glass shows a round back button and the name in the content.
            if (!liquidGlass) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                title = {
                    Text(
                        state.exercise?.localizedName() ?: stringResource(id = R.string.stats_title_fallback)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.nav_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            LoadingScreen(modifier = Modifier.padding(padding))
        } else if (liquidGlass) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    GlassStatsHeader(
                        subtitle = state.exercise?.let { stringResource(UxR.string.stats_exercise_details, it.primaryMuscleGroup.label(), it.category.label()) },
                        onBack = onBack
                    )
                }
                item {
                    Text(
                        text = state.exercise?.localizedName() ?: stringResource(id = R.string.stats_title_fallback),
                        fontSize = 40.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1.4).sp
                    )
                }
                item {
                    GlassStatsHero(
                        points = state.chartData,
                        metric = state.selectedMetric,
                        unitSystem = preferences.unitSystem,
                        onMetricSelected = viewModel::onMetricSelected
                    )
                }
                if (state.records.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(id = R.string.stats_empty_records),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    item {
                        GlassRecordTiles(
                            records = state.records.associate { it.type to it.value },
                            unitSystem = preferences.unitSystem
                        )
                    }
                }
                items(state.markLadders, key = { "marks_${it.kind}" }) { ladder ->
                    ExerciseMarksCard(ladder = ladder, unitSystem = preferences.unitSystem)
                }
                state.lastWorkoutComparison?.takeIf { state.chartData.size >= 2 }?.let { comparison ->
                    item {
                        LastWorkoutComparisonCard(
                            comparison = comparison,
                            metric = state.selectedMetric,
                            unitSystem = preferences.unitSystem
                        )
                    }
                }
                if (state.recentSets.isNotEmpty()) {
                    item { RecentSetsCard(sets = state.recentSets, unitSystem = preferences.unitSystem, addedLoad = state.exercise?.tracksAddedLoad == true) }
                }
                item { WeeklyMuscleVolumeCard(volumes = state.weeklyMuscleVolume) }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(dims.spacingMd),
                verticalArrangement = Arrangement.spacedBy(dims.spacingMd)
            ) {
                item {
                    state.exercise?.let { exercise ->
                        Text(
                            text = stringResource(UxR.string.stats_exercise_details, exercise.primaryMuscleGroup.label(), exercise.category.label()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    Text(
                        text = stringResource(id = R.string.stats_records_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (state.records.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(id = R.string.stats_empty_records),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = dims.spacingXs)
                        )
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                        ) {
                            val maxWeight = state.records.find { it.type == RecordType.MAX_WEIGHT }
                            val maxReps = state.records.find { it.type == RecordType.MAX_REPS }

                            StatCard(
                                label = stringResource(id = R.string.stats_max_weight_label),
                                value = maxWeight?.let {
                                    WeightFormatting.formatWeight(it.value, preferences.unitSystem)
                                } ?: "-",
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                            StatCard(
                                label = stringResource(id = R.string.stats_max_reps_label),
                                value = maxReps?.let { "${it.value.toInt()}" } ?: "-",
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                        ) {
                            val maxE1rm = state.records.find { it.type == RecordType.MAX_E1RM }
                            val maxVolume = state.records.find { it.type == RecordType.MAX_VOLUME }

                            StatCard(
                                label = stringResource(id = R.string.stats_best_1rm_label),
                                value = maxE1rm?.let {
                                    WeightFormatting.formatWeight(it.value, preferences.unitSystem)
                                } ?: "-",
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                            StatCard(
                                label = stringResource(id = R.string.stats_max_volume_label),
                                value = maxVolume?.let {
                                    WeightFormatting.formatVolume(it.value, preferences.unitSystem)
                                } ?: "-",
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }

                items(state.markLadders, key = { "marks_${it.kind}" }) { ladder ->
                    ExerciseMarksCard(ladder = ladder, unitSystem = preferences.unitSystem)
                }

                val e1rmProgression = state.e1rmProgression
                if (e1rmProgression != null && state.chartData.size >= 2) {
                    item {
                        Text(
                            text = stringResource(id = R.string.stats_e1rm_progress_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        Text(
                            text = stringResource(id = R.string.stats_e1rm_formula_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                        ) {
                            StatCard(
                                label = stringResource(id = R.string.stats_e1rm_first_label),
                                value = WeightFormatting.formatWeight(
                                    e1rmProgression.first.toDouble(),
                                    preferences.unitSystem
                                ),
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                            StatCard(
                                label = stringResource(id = R.string.stats_e1rm_latest_label),
                                value = WeightFormatting.formatWeight(
                                    e1rmProgression.latest.toDouble(),
                                    preferences.unitSystem
                                ),
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                        ) {
                            val deltaAbs = WeightFormatting.formatWeightDelta(
                                e1rmProgression.delta.toDouble(),
                                preferences.unitSystem
                            )
                            val deltaSign = if (e1rmProgression.deltaPercent > 0f) "+" else ""
                            val deltaRel = stringResource(
                                id = R.string.common_percent_value,
                                deltaSign + e1rmProgression.deltaPercent.roundToInt()
                            )
                            StatCard(
                                label = stringResource(id = R.string.stats_e1rm_abs_label),
                                value = deltaAbs,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                            StatCard(
                                label = stringResource(id = R.string.stats_e1rm_rel_label),
                                value = deltaRel,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }

                item {
                    WeeklyMuscleVolumeCard(volumes = state.weeklyMuscleVolume)
                }

                item {
                    Text(
                        text = stringResource(id = R.string.stats_progress_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (state.chartData.size >= 2) {
                    item {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                        ) {
                            ChartMetric.entries.forEach { metric ->
                                FilterChip(
                                    selected = state.selectedMetric == metric,
                                    onClick = { viewModel.onMetricSelected(metric) },
                                    label = { Text(stringResource(id = metric.labelRes)) },
                                    colors = ThrymInteractiveColors.filterChip()
                                )
                            }
                        }
                    }

                    item {
                        ThrymSurfaceCard(
                            modifier = Modifier.fillMaxWidth(),
                            tone = ThrymSurfaceTone.ACCENT
                        ) {
                            val lineColor = if (state.selectedMetric == ChartMetric.VOLUME) {
                                MaterialTheme.semantic.teal
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                            CartesianChartHost(
                                chart = rememberCartesianChart(
                                    rememberLineCartesianLayer(
                                        lineProvider = LineCartesianLayer.LineProvider.series(
                                            LineCartesianLayer.rememberLine(
                                                fill = LineCartesianLayer.LineFill.single(Fill(lineColor))
                                            )
                                        )
                                    ),
                                    startAxis = VerticalAxis.rememberStart(
                            valueFormatter = CartesianValueFormatter { _, value, _ ->
                                WeightFormatting.formatNumber(value, 1)
                            }
                        ),
                                    bottomAxis = HorizontalAxis.rememberBottom(),
                                ),
                                modelProducer = modelProducer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(250.dp)
                                    .padding(dims.spacingSm)
                            )
                        }
                    }

                    state.lastWorkoutComparison?.let { comparison ->
                        item {
                            LastWorkoutComparisonCard(
                                comparison = comparison,
                                metric = state.selectedMetric,
                                unitSystem = preferences.unitSystem
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            text = stringResource(id = R.string.stats_chart_min_points),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = dims.spacingXs)
                        )
                    }
                }

                if (state.recentSets.isNotEmpty()) {
                    item {
                        RecentSetsCard(
                            sets = state.recentSets,
                            unitSystem = preferences.unitSystem,
                            addedLoad = state.exercise?.tracksAddedLoad == true
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(dims.spacingMd)) }
            }
        }
    }
}

@Composable
private fun LastWorkoutComparisonCard(
    comparison: ChartComparison,
    metric: ChartMetric,
    unitSystem: com.thrym.app.domain.model.UnitSystem
) {
    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.MUTED
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(thrymDimens.spacingMd),
            verticalArrangement = Arrangement.spacedBy(thrymDimens.spacingXs)
        ) {
            Text(
                text = stringResource(StatisticsR.string.stats_comparison_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(
                    StatisticsR.string.stats_comparison_previous,
                    chartPointDate(comparison.previous)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatMetricValue(comparison.previous.value, metric, unitSystem),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(
                    StatisticsR.string.stats_comparison_latest,
                    chartPointDate(comparison.latest)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatMetricValue(comparison.latest.value, metric, unitSystem),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    StatisticsR.string.stats_comparison_delta,
                    formatMetricDelta(comparison.delta, metric, unitSystem)
                ),
                style = MaterialTheme.typography.labelLarge,
                color = if (comparison.delta >= 0f) {
                    MaterialTheme.semanticText.success
                } else {
                    MaterialTheme.semanticText.danger
                }
            )
        }
    }
}

@Composable
private fun RecentSetsCard(
    sets: List<com.thrym.app.domain.model.WorkoutSet>,
    unitSystem: com.thrym.app.domain.model.UnitSystem,
    addedLoad: Boolean
) {
    val dims = thrymDimens
    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.MUTED
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.spacingMd),
            verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
        ) {
            Text(
                text = stringResource(StatisticsR.string.stats_recent_sets_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            val dateTimeFormat = rememberDateTimeFormatter()
            sets.take(12).forEach { set ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = set.completedAt.format(dateTimeFormat),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(
                            StatisticsR.string.stats_recent_set_value,
                            recentSetTypeLabel(set.setType),
                            setLoadText(set.weightKg, unitSystem, addedLoad),
                            set.reps
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = dims.spacingSm)
                    )
                }
            }
        }
    }
}

@Composable
private fun recentSetTypeLabel(type: SetType): String = stringResource(
    when (type) {
        SetType.NORMAL -> StatisticsR.string.stats_set_type_normal
        SetType.WARMUP -> StatisticsR.string.stats_set_type_warmup
        SetType.DROP_SET -> StatisticsR.string.stats_set_type_drop
        SetType.FAILURE -> StatisticsR.string.stats_set_type_failure
        SetType.BACKOFF -> StatisticsR.string.stats_set_type_backoff
    }
)

@Composable
private fun chartPointDate(point: ChartDataPoint): String {
    val dateTimeFormat = rememberDateTimeFormatter()
    return point.timestamp?.format(dateTimeFormat) ?: point.date.format(DateFormatting.dayMonth(currentLocale()))
}

private fun formatMetricValue(
    value: Float,
    metric: ChartMetric,
    unitSystem: com.thrym.app.domain.model.UnitSystem
): String = when (metric) {
    ChartMetric.VOLUME -> WeightFormatting.formatVolume(value.toDouble(), unitSystem)
    ChartMetric.WEIGHT,
    ChartMetric.E1RM -> WeightFormatting.formatWeight(value.toDouble(), unitSystem)
}

private fun formatMetricDelta(
    value: Float,
    metric: ChartMetric,
    unitSystem: com.thrym.app.domain.model.UnitSystem
): String {
    if (metric != ChartMetric.VOLUME) {
        return WeightFormatting.formatWeightDelta(value.toDouble(), unitSystem)
    }
    val sign = if (value > 0f) "+" else ""
    return sign + WeightFormatting.formatVolume(value.toDouble(), unitSystem)
}

