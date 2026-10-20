package com.thrym.app.presentation.settings

import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.accentText
import com.thrym.feature.settings.R as UxR
import com.thrym.app.presentation.common.ThrymTopBar
import com.thrym.app.domain.util.WeightFormatting
import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.thrym.app.presentation.common.LoadingSkeleton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.thrym.core.designsystem.R
import com.thrym.feature.settings.R as SettingsR
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.ThemeMode
import com.thrym.app.domain.model.AppearanceStyle
import com.thrym.app.domain.model.ThemeScheme
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.util.DateFormatting
import com.thrym.app.presentation.common.ThrymScreenScaffold
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.rememberIs24HourFormat
import com.thrym.app.presentation.common.rememberDateTimeFormatter
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.presentation.theme.DarkCyanPrimary
import com.thrym.app.presentation.theme.DarkPrimary
import com.thrym.app.presentation.theme.DarkRedPrimary
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semantic
import androidx.compose.foundation.layout.FlowRow
import com.thrym.app.presentation.theme.PlateColors
import com.thrym.app.presentation.theme.semanticText
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("StringFormatInvalid", "LocalContextGetResourceValueCall")
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = currentLocale()
    val use24Hour = rememberIs24HourFormat()
    val snackbarHostState = remember { SnackbarHostState() }
    val dims = thrymDimens
    val recoveryBackup = state.recoveryBackup

    var incidentSummary by remember { mutableStateOf("") }
    var incidentDetails by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackup(uri)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onImportUriPicked(uri)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.updateReminderConfig(state.preferences.reminderConfig.copy(enabled = true))
        } else {
            viewModel.onNotificationPermissionDenied()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.Message -> {
                        val message = if (event.args.isEmpty()) {
                            context.getString(event.textRes)
                        } else {
                            context.getString(event.textRes, *event.args.toTypedArray())
                        }
                        snackbarHostState.showSnackbar(message)
                    }
                is SettingsEvent.ShareIncident -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, event.report.uri)
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(UxR.string.settings_incident_subject, context.getString(R.string.app_name), event.report.id))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(
                            shareIntent,
                            context.getString(R.string.settings_incident_share)
                        )
                    )
                }
            }
        }
    }

    ThrymScreenScaffold(
        topBar = {
            ThrymTopBar(
                title = { Text(stringResource(id = R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.nav_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        if (state.isBusy) {
            LoadingSkeleton(lineCount = 6, modifier = Modifier.padding(padding))
            return@ThrymScreenScaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(dims.spacingMd),
            verticalArrangement = Arrangement.spacedBy(dims.spacingLg)
        ) {
            item {
                PreferenceCard(title = stringResource(id = R.string.settings_section_appearance)) {
                    Text(
                        text = stringResource(id = R.string.settings_appearance_style_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                    ) {
                        FilterChip(
                            selected = state.preferences.appearanceStyle == AppearanceStyle.EMBER,
                            onClick = { viewModel.updateAppearanceStyle(AppearanceStyle.EMBER) },
                            label = { Text(stringResource(id = R.string.settings_appearance_ember)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.appearanceStyle == AppearanceStyle.LIQUID_GLASS,
                            onClick = { viewModel.updateAppearanceStyle(AppearanceStyle.LIQUID_GLASS) },
                            label = { Text(stringResource(id = R.string.settings_appearance_liquid_glass)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                    }

                    Spacer(modifier = Modifier.height(dims.spacingXs))

                    Text(
                        text = stringResource(id = R.string.settings_theme_mode_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                    ) {
                        FilterChip(
                            selected = state.preferences.themeMode == ThemeMode.SYSTEM,
                            onClick = { viewModel.updateThemeMode(ThemeMode.SYSTEM) },
                            label = { Text(stringResource(id = R.string.settings_theme_system)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.themeMode == ThemeMode.LIGHT,
                            onClick = { viewModel.updateThemeMode(ThemeMode.LIGHT) },
                            label = { Text(stringResource(id = R.string.settings_theme_light)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.themeMode == ThemeMode.DARK,
                            onClick = { viewModel.updateThemeMode(ThemeMode.DARK) },
                            label = { Text(stringResource(id = R.string.settings_theme_dark)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                    }

                    Spacer(modifier = Modifier.height(dims.spacingXs))

                    Text(
                        text = stringResource(id = R.string.settings_theme_scheme_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Wraps instead of scrolling so every scheme is visible at a glance.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                    ) {
                        FilterChip(
                            selected = state.preferences.themeScheme == ThemeScheme.AMBER,
                            onClick = { viewModel.updateThemeScheme(ThemeScheme.AMBER) },
                            leadingIcon = { SchemeColorDot(color = DarkPrimary) },
                            label = { Text(stringResource(id = R.string.settings_scheme_amber)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.themeScheme == ThemeScheme.DEEP_CYAN,
                            onClick = { viewModel.updateThemeScheme(ThemeScheme.DEEP_CYAN) },
                            leadingIcon = { SchemeColorDot(color = DarkCyanPrimary) },
                            label = { Text(stringResource(id = R.string.settings_scheme_deep_cyan)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.themeScheme == ThemeScheme.NEON_RED,
                            onClick = { viewModel.updateThemeScheme(ThemeScheme.NEON_RED) },
                            leadingIcon = { SchemeColorDot(color = DarkRedPrimary) },
                            label = { Text(stringResource(id = R.string.settings_scheme_neon_red)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.themeScheme == ThemeScheme.FORGE,
                            onClick = { viewModel.updateThemeScheme(ThemeScheme.FORGE) },
                            leadingIcon = { SchemeColorDot(color = Color(0xFFFF7A1A)) },
                            label = { Text(stringResource(id = R.string.settings_scheme_forge)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.themeScheme == ThemeScheme.RASTER,
                            onClick = { viewModel.updateThemeScheme(ThemeScheme.RASTER) },
                            leadingIcon = { SchemeColorDot(color = Color(0xFF3B82F6)) },
                            label = { Text(stringResource(id = R.string.settings_scheme_raster)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.themeScheme == ThemeScheme.TIDE,
                            onClick = { viewModel.updateThemeScheme(ThemeScheme.TIDE) },
                            leadingIcon = { SchemeColorDot(color = Color(0xFF00F5A0)) },
                            label = { Text(stringResource(id = R.string.settings_scheme_tide)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.themeScheme == ThemeScheme.PULSE,
                            onClick = { viewModel.updateThemeScheme(ThemeScheme.PULSE) },
                            leadingIcon = { SchemeColorDot(color = Color(0xFFFF3366)) },
                            label = { Text(stringResource(id = R.string.settings_scheme_pulse)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                    }

                    ToggleRow(
                        title = stringResource(id = R.string.settings_dynamic_color_title),
                        subtitle = stringResource(id = R.string.settings_dynamic_color_subtitle),
                        checked = state.preferences.useDynamicColor,
                        onCheckedChange = viewModel::updateUseDynamicColor
                    )
                    ToggleRow(
                        title = stringResource(id = R.string.settings_reduced_motion_title),
                        subtitle = stringResource(id = R.string.settings_reduced_motion_subtitle),
                        checked = state.preferences.reducedMotion,
                        onCheckedChange = viewModel::updateReducedMotion
                    )
                }
            }

            item {
                PreferenceCard(title = stringResource(id = R.string.settings_section_preferences)) {
                    ToggleRow(
                        title = stringResource(id = R.string.settings_unit_system_title),
                        subtitle = stringResource(id = R.string.settings_unit_system_subtitle),
                        checked = state.preferences.unitSystem == UnitSystem.IMPERIAL,
                        onCheckedChange = { enabled ->
                            viewModel.updateUnitSystem(
                                if (enabled) UnitSystem.IMPERIAL else UnitSystem.METRIC
                            )
                        },
                        checkedLabel = stringResource(id = R.string.settings_unit_imperial),
                        uncheckedLabel = stringResource(id = R.string.settings_unit_metric)
                    )

                    Spacer(modifier = Modifier.height(dims.spacingXs))

                    Text(
                        text = stringResource(id = R.string.settings_week_start_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                    ) {
                        FilterChip(
                            selected = state.preferences.weekStart == WeekStart.MONDAY,
                            onClick = { viewModel.updateWeekStart(WeekStart.MONDAY) },
                            label = { Text(stringResource(id = R.string.settings_week_start_monday)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.weekStart == WeekStart.SUNDAY,
                            onClick = { viewModel.updateWeekStart(WeekStart.SUNDAY) },
                            label = { Text(stringResource(id = R.string.settings_week_start_sunday)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                    }

                    Spacer(modifier = Modifier.height(dims.spacingXs))

                    ToggleRow(
                        title = stringResource(id = R.string.settings_default_warmup_title),
                        subtitle = stringResource(id = R.string.settings_default_warmup_subtitle),
                        checked = state.preferences.defaultWarmupFlag,
                        onCheckedChange = viewModel::updateDefaultWarmupFlag
                    )
                    ToggleRow(
                        title = stringResource(id = R.string.settings_keep_screen_on_title),
                        subtitle = stringResource(id = R.string.settings_keep_screen_on_subtitle),
                        checked = state.preferences.timerKeepScreenOn,
                        onCheckedChange = viewModel::updateTimerKeepScreenOn
                    )
                    ToggleRow(
                        title = stringResource(id = R.string.settings_beta_diagnostics_title),
                        subtitle = stringResource(id = R.string.settings_beta_diagnostics_subtitle),
                        checked = state.preferences.betaDiagnosticsOptIn,
                        onCheckedChange = viewModel::updateBetaDiagnosticsOptIn
                    )
                    ToggleRow(
                        title = stringResource(id = R.string.settings_share_weight_history_title),
                        subtitle = stringResource(id = R.string.settings_share_weight_history_subtitle),
                        checked = state.preferences.shareWeightHistoryAcrossContexts,
                        onCheckedChange = viewModel::updateShareWeightHistoryAcrossContexts
                    )

                    Spacer(modifier = Modifier.height(dims.spacingXs))

                    Text(
                        text = stringResource(id = R.string.settings_intensity_system_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                    ) {
                        FilterChip(
                            selected = state.preferences.intensitySystem == IntensitySystem.OFF,
                            onClick = { viewModel.updateIntensitySystem(IntensitySystem.OFF) },
                            label = { Text(stringResource(id = R.string.settings_intensity_off)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.intensitySystem == IntensitySystem.RPE,
                            onClick = { viewModel.updateIntensitySystem(IntensitySystem.RPE) },
                            label = { Text(stringResource(UxR.string.settings_intensity_rpe)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                        FilterChip(
                            selected = state.preferences.intensitySystem == IntensitySystem.RIR,
                            onClick = { viewModel.updateIntensitySystem(IntensitySystem.RIR) },
                            label = { Text(stringResource(UxR.string.settings_intensity_rir)) },
                            colors = ThrymInteractiveColors.filterChip()
                        )
                    }
                }
            }

            item {
                PreferenceCard(title = stringResource(SettingsR.string.settings_section_companion)) {
                    CompanionSettingsContent()
                }
            }

            item {
                PreferenceCard(title = stringResource(id = R.string.settings_section_barbell)) {
                    ToggleRow(
                        title = stringResource(id = R.string.settings_plate_calculator_title),
                        subtitle = stringResource(id = R.string.settings_plate_calculator_subtitle),
                        checked = state.preferences.plateCalculatorEnabled,
                        onCheckedChange = viewModel::updatePlateCalculatorEnabled
                    )

                    if (state.preferences.plateCalculatorEnabled) {
                        Spacer(modifier = Modifier.height(dims.spacingXs))

                        Text(
                            text = stringResource(id = R.string.settings_barbell_weight_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                        ) {
                            listOf(20.0, 15.0, 10.0, 8.0, 2.5).forEach { barWeight ->
                                val label = stringResource(UxR.string.settings_weight_kg, WeightFormatting.formatNumber(barWeight, 2))
                                FilterChip(
                                    selected = state.preferences.barbellWeightKg == barWeight,
                                    onClick = { viewModel.updateBarbellWeightKg(barWeight) },
                                    label = { Text(label) },
                                    colors = ThrymInteractiveColors.filterChip()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(dims.spacingXs))

                        Text(
                            text = stringResource(id = R.string.settings_available_plates_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(id = R.string.settings_available_plates_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(dims.spacingXs),
                            verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
                        ) {
                            val allPlateOptions = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25, 0.5)
                            val currentPlates = state.preferences.availablePlates.toSet()

                            allPlateOptions.forEach { plate ->
                                val isSelected = plate in currentPlates
                                val plateLabel = stringResource(UxR.string.settings_weight_kg, WeightFormatting.formatNumber(plate, 2))

                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val updated = if (isSelected) {
                                            currentPlates - plate
                                        } else {
                                            currentPlates + plate
                                        }
                                        viewModel.updateAvailablePlates(updated.toList())
                                    },
                                    leadingIcon = {
                                        SchemeColorDot(color = PlateColors.forWeight(plate))
                                    },
                                    label = { Text(plateLabel) },
                                    colors = ThrymInteractiveColors.filterChip()
                                )
                            }
                        }
                    }
                }
            }

            item {
                PreferenceCard(title = stringResource(id = R.string.settings_section_rest_timer)) {
                    ToggleRow(
                        title = stringResource(id = R.string.settings_auto_rest_timer_title),
                        subtitle = stringResource(id = R.string.settings_auto_rest_timer_subtitle),
                        checked = state.preferences.autoRestTimerEnabled,
                        onCheckedChange = viewModel::updateAutoRestTimerEnabled
                    )

                    if (state.preferences.autoRestTimerEnabled) {
                        Spacer(modifier = Modifier.height(dims.spacingXs))

                        Text(
                            text = stringResource(id = R.string.settings_rest_time_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                        ) {
                            REST_TIME_OPTIONS_SECONDS.forEach { option ->
                                FilterChip(
                                    selected = state.preferences.defaultRestTimeSeconds == option,
                                    onClick = { viewModel.updateDefaultRestTimeSeconds(option) },
                                    label = { Text(formatRestTimeOption(option)) },
                                    colors = ThrymInteractiveColors.filterChip()
                                )
                            }
                        }
                    }
                }
            }

            item {
                PreferenceCard(title = stringResource(id = R.string.settings_section_reminder)) {
                    val reminder = state.preferences.reminderConfig

                    ToggleRow(
                        title = stringResource(id = R.string.settings_reminder_enabled_title),
                        subtitle = stringResource(id = R.string.settings_reminder_enabled_subtitle),
                        checked = reminder.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                val needsPermission =
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED

                                if (needsPermission) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.updateReminderConfig(reminder.copy(enabled = true))
                                }
                            } else {
                                viewModel.updateReminderConfig(reminder.copy(enabled = false))
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(id = R.string.settings_reminder_time_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = {
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute ->
                                        viewModel.updateReminderConfig(
                                            reminder.copy(hour = hour, minute = minute)
                                        )
                                    },
                                    reminder.hour,
                                    reminder.minute,
                                    use24Hour
                                ).show()
                            },
                            colors = ThrymInteractiveColors.textButton()
                        ) {
                            Text(DateFormatting.formatClock(reminder.hour, reminder.minute, locale, use24Hour))
                        }
                    }

                    Text(
                        text = stringResource(id = R.string.settings_reminder_days_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                    ) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = day in reminder.daysOfWeek,
                                onClick = {
                                    val updated = reminder.daysOfWeek.toMutableSet()
                                    if (!updated.add(day)) {
                                        updated.remove(day)
                                    }
                                    viewModel.updateReminderConfig(reminder.copy(daysOfWeek = updated))
                                },
                                label = { Text(DateFormatting.dayShort(day, locale)) },
                                colors = ThrymInteractiveColors.filterChip()
                            )
                        }
                    }
                }
            }

            item {
                PreferenceCard(title = stringResource(id = R.string.settings_section_data)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)) {
                        Button(
                            onClick = {
                                val fileName = "thrym-backup-${LocalDate.now()}.json"
                                exportLauncher.launch(fileName)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isBusy
                        ) {
                            Text(stringResource(id = R.string.settings_backup_export))
                        }
                        Button(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isBusy
                        ) {
                            Text(stringResource(id = R.string.settings_backup_import))
                        }
                    }
                    Text(
                        text = state.preferences.lastSuccessfulExportEpochMillis?.let { timestamp ->
                            stringResource(
                                id = SettingsR.string.settings_backup_last_external_export,
                                formatEpochMillis(timestamp)
                            )
                        } ?: stringResource(id = SettingsR.string.settings_backup_no_external_export),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ToggleRow(
                        title = stringResource(id = SettingsR.string.settings_backup_reminder_title),
                        subtitle = stringResource(id = SettingsR.string.settings_backup_reminder_subtitle),
                        checked = state.preferences.backupReminderEnabled,
                        onCheckedChange = viewModel::updateBackupReminderEnabled
                    )
                    if (state.backupReminderDue) {
                        ThrymSurfaceCard(
                            modifier = Modifier.fillMaxWidth(),
                            tone = ThrymSurfaceTone.MUTED
                        ) {
                            Column(
                                modifier = Modifier.padding(dims.spacingSm),
                                verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
                            ) {
                                Text(
                                    text = stringResource(id = SettingsR.string.settings_backup_reminder_due_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.accentText
                                )
                                Text(
                                    text = stringResource(id = SettingsR.string.settings_backup_reminder_due_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(id = SettingsR.string.settings_backup_recovery_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (recoveryBackup != null) {
                        Text(
                            text = stringResource(
                                id = R.string.settings_recovery_backup_available,
                                formatEpochMillis(recoveryBackup.timestampMillis)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = viewModel::showRecoveryRestoreDialog,
                            enabled = !state.isBusy,
                            colors = ThrymInteractiveColors.textButton()
                        ) {
                            Text(stringResource(id = R.string.settings_backup_recovery_restore))
                        }
                    } else {
                        Text(
                            text = stringResource(id = R.string.settings_recovery_backup_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = viewModel::showResetDialog, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = R.string.settings_data_reset))
                    }
                    Text(
                        text = stringResource(
                            id = R.string.settings_build_info,
                            state.buildInfo.versionName,
                            state.buildInfo.versionCode
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                PreferenceCard(
                    title = stringResource(id = R.string.settings_section_incident),
                    tone = ThrymSurfaceTone.MUTED
                ) {
                    com.thrym.app.presentation.common.ThrymTextField(
                        value = incidentSummary,
                        onValueChange = { incidentSummary = it },
                        label = { Text(stringResource(id = R.string.settings_incident_summary_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(dims.spacingXs))
                    com.thrym.app.presentation.common.ThrymTextField(
                        value = incidentDetails,
                        onValueChange = { incidentDetails = it },
                        label = { Text(stringResource(id = R.string.settings_incident_details_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Spacer(modifier = Modifier.height(dims.spacingXs))
                    Button(
                        onClick = {
                            viewModel.createIncidentReport(
                                summary = incidentSummary,
                                details = incidentDetails,
                                currentScreen = "settings"
                            )
                            incidentSummary = ""
                            incidentDetails = ""
                        },
                        enabled = incidentSummary.isNotBlank() || incidentDetails.isNotBlank()
                    ) {
                        Text(stringResource(id = R.string.settings_incident_create))
                    }
                }
            }
        }

        val importPreview = state.importPreview
        if (state.importConfirmationVisible && importPreview != null) {
            AlertDialog(
                onDismissRequest = viewModel::cancelImport,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                title = { Text(stringResource(id = R.string.settings_import_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(dims.spacingXs)) {
                        Text(stringResource(id = R.string.settings_import_dialog_replaces_all))
                        Text(stringResource(id = R.string.settings_import_dialog_recovery_first))
                        Text(stringResource(id = R.string.settings_import_dialog_preferences_excluded))
                        Text(
                            stringResource(
                                id = R.string.settings_import_dialog_summary,
                                importPreview.appVersion.ifBlank {
                                    stringResource(id = R.string.common_unknown)
                                },
                                formatEpochMillis(importPreview.exportedAtEpochMillis),
                                importPreview.counts.exercises,
                                importPreview.counts.workoutSessions,
                                importPreview.counts.workoutSets,
                                importPreview.counts.trainingPlans,
                                importPreview.counts.personalRecords
                            )
                        )
                        if (!importPreview.isValid) {
                            Text(
                                text = stringResource(id = R.string.settings_import_dialog_invalid_header),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.semanticText.danger
                            )
                            importPreview.validationErrors.forEach { error ->
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.semanticText.danger
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = viewModel::confirmImport,
                        enabled = importPreview.isValid && !state.isBusy,
                        colors = ThrymInteractiveColors.textButton()
                    ) {
                        Text(stringResource(id = R.string.settings_import_dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelImport, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = R.string.settings_import_dialog_cancel))
                    }
                }
            )
        }

        if (state.showRecoveryRestoreDialog && recoveryBackup != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissRecoveryRestoreDialog,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                title = { Text(stringResource(id = R.string.settings_recovery_dialog_title)) },
                text = {
                    Text(
                        stringResource(
                            id = R.string.settings_recovery_dialog_text,
                            formatEpochMillis(recoveryBackup.timestampMillis)
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = viewModel::restoreLatestRecovery,
                        enabled = !state.isBusy,
                        colors = ThrymInteractiveColors.textButton()
                    ) {
                        Text(stringResource(id = R.string.settings_recovery_dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissRecoveryRestoreDialog, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = R.string.settings_recovery_dialog_cancel))
                    }
                }
            )
        }

        if (state.showResetDialog) {
            AlertDialog(
                onDismissRequest = viewModel::dismissResetDialog,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                title = { Text(stringResource(id = R.string.settings_reset_dialog_title)) },
                text = { Text(stringResource(id = R.string.settings_reset_dialog_text)) },
                confirmButton = {
                    TextButton(onClick = viewModel::resetUserData, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = R.string.settings_reset_dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissResetDialog, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = R.string.settings_reset_dialog_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun formatEpochMillis(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(rememberDateTimeFormatter())

private val REST_TIME_OPTIONS_SECONDS = listOf(30, 60, 90, 120, 180, 300, 600)

private fun formatRestTimeOption(seconds: Int): String =
    String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

@Composable
private fun SchemeColorDot(color: Color) {
    Box(
        modifier = Modifier
            .padding(end = 2.dp)
            .background(color = color, shape = CircleShape)
            .size(10.dp)
    )
}

@Composable
private fun PreferenceCard(
    title: String,
    tone: ThrymSurfaceTone = ThrymSurfaceTone.ELEVATED,
    content: @Composable ColumnScope.() -> Unit
) {
    val dims = thrymDimens

    Column {
        Text(
            text = title.uppercase(currentLocale()),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp),
            color = MaterialTheme.accentText,
            modifier = Modifier.padding(start = dims.spacingMd, end = dims.spacingMd, bottom = dims.spacingXs)
        )
        ThrymSurfaceCard(
            modifier = Modifier,
            tone = tone
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(dims.spacingMd),
                verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    checkedLabel: String? = null,
    uncheckedLabel: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (checkedLabel != null && uncheckedLabel != null) {
                Text(
                    text = if (checked) checkedLabel else uncheckedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

