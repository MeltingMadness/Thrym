package com.thrym.app.presentation.workout

import com.thrym.feature.workout.R as UxR
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.accentText
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.repository.ExerciseRepository
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.presentation.common.exerciseName
import com.thrym.app.presentation.common.filterAndSortByDisplayName
import com.thrym.app.presentation.common.label
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.common.rememberUiStrings
import com.thrym.app.presentation.common.toUserDetail
import com.thrym.app.presentation.common.userMessageOrNull
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semantic
import com.thrym.core.designsystem.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private data class ExerciseCreateState(
    val name: String = "",
    val primaryMuscleGroup: MuscleGroup = MuscleGroup.BRUST,
    val secondaryMuscleGroups: Set<MuscleGroup> = emptySet(),
    val category: ExerciseCategory = ExerciseCategory.LANGHANTEL,
    val notes: String = "",
    // A failed creation is shown inside the dialog: the screen behind the modal sheet would hide it.
    val errorMessage: String? = null,
    val isSaving: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    onDismiss: () -> Unit,
    onExerciseSelected: (Exercise) -> Unit,
    allowCreateCustomExercise: Boolean = false,
    onCreationError: ((String) -> Unit)? = null,
    exerciseRepository: ExerciseRepository = koinInject()
) {
    val dims = thrymDimens
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectedExercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<MuscleGroup?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createState by remember { mutableStateOf(ExerciseCreateState()) }

    val strings = rememberUiStrings()
    val locale = currentLocale()

    val activeExercisesFlow = remember(strings, exerciseRepository, onCreationError) {
        exerciseRepository.getAllExercises()
            .map { exercises -> exercises.filterNot { it.isArchived } }
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                onCreationError?.invoke(
                    strings.get(UxR.string.workout_picker_load_failed, throwable.toUserDetail(strings))
                )
                emit(emptyList())
            }
    }
    val activeExercises by activeExercisesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Search and sort on the localized name; the database only knows the stored
    // (German canonical) names of the built-in exercises.
    val exercises = remember(activeExercises, searchQuery, selectedGroup, strings, locale) {
        activeExercises
            .filter { selectedGroup == null || it.primaryMuscleGroup == selectedGroup }
            .filterAndSortByDisplayName(searchQuery, displayName = { strings.exerciseName(it) }, locale = locale)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = dims.spacingMd)
        ) {
            com.thrym.app.presentation.common.ThrymTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(id = R.string.workout_picker_search)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(id = R.string.workout_picker_search)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dims.spacingMd),
                singleLine = true
            )

            if (allowCreateCustomExercise) {
                TextButton(
                    onClick = {
                        createState = ExerciseCreateState()
                        showCreateDialog = true
                    },
                    modifier = Modifier.padding(horizontal = dims.spacingMd),
                    colors = ThrymInteractiveColors.textButton()
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(id = R.string.workout_picker_create_custom)
                    )
                    Text(
                        text = stringResource(id = R.string.workout_picker_create_custom),
                        modifier = Modifier.padding(start = dims.spacingXs)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = dims.spacingMd, vertical = dims.spacingXs),
                horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
            ) {
                FilterChip(
                    selected = selectedGroup == null,
                    onClick = { selectedGroup = null },
                    label = { Text(stringResource(id = R.string.common_all)) },
                    colors = ThrymInteractiveColors.filterChip()
                )
                MuscleGroup.entries.forEach { group ->
                    FilterChip(
                        selected = selectedGroup == group,
                        onClick = { selectedGroup = group },
                        label = { Text(group.label()) },
                        colors = ThrymInteractiveColors.filterChip()
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = dims.spacingMd), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(UxR.string.workout_picker_selected_count, selectedExercises.size), color = MaterialTheme.accentText)
                TextButton(onClick = { selectedExercises = emptyList() }, colors = ThrymInteractiveColors.textButton()) { Text(stringResource(UxR.string.workout_picker_clear_selection)) }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = dims.spacingMd, vertical = dims.spacingXs),
                verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
            ) {
                items(exercises, key = { it.id }) { exercise ->
                    ThrymSurfaceCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedExercises = if (selectedExercises.any { it.id == exercise.id }) selectedExercises.filterNot { it.id == exercise.id } else selectedExercises + exercise }
                            .semantics { role = Role.Button },
                        tone = ThrymSurfaceTone.ACCENT,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selectedExercises.any { it.id == exercise.id }) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        ListItem(
                            headlineContent = { Text(exercise.localizedName()) },
                            trailingContent = { androidx.compose.material3.Checkbox(checked = selectedExercises.any { it.id == exercise.id }, onCheckedChange = null) },
                            supportingContent = {
                                Text(stringResource(UxR.string.workout_picker_exercise_details, exercise.primaryMuscleGroup.label(), exercise.category.label()))
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
            androidx.compose.material3.Button(
                onClick = { selectedExercises.forEach(onExerciseSelected); onDismiss() },
                enabled = selectedExercises.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = dims.spacingMd)
            ) { Text(pluralStringResource(UxR.plurals.workout_picker_add_selected, selectedExercises.size, selectedExercises.size)) }
        }
    }

    if (showCreateDialog) {
        CreateExerciseDialog(
            state = createState,
            onStateChange = { createState = it },
            // While a save runs the dialog stays open: its result belongs to this dialog, and a
            // reopened one must not get the late error or lose its own double-tap guard.
            onDismiss = { if (!createState.isSaving) showCreateDialog = false },
            onConfirm = {
                // The name check runs in the repository, so a second tap while the first insert is
                // still running could create the same exercise twice.
                if (createState.isSaving) return@CreateExerciseDialog
                val request = createState
                createState = request.copy(isSaving = true, errorMessage = null)
                scope.launch {
                    runCatching {
                        val exerciseId = exerciseRepository.addCustomExercise(
                            Exercise(
                                name = request.name,
                                primaryMuscleGroup = request.primaryMuscleGroup,
                                secondaryMuscleGroups = request.secondaryMuscleGroups.toList(),
                                category = request.category,
                                isCustom = true,
                                notes = request.notes
                            )
                        )
                        exerciseRepository.getExerciseById(exerciseId) ?: Exercise(
                            id = exerciseId,
                            name = request.name.trim(),
                            primaryMuscleGroup = request.primaryMuscleGroup,
                            secondaryMuscleGroups = request.secondaryMuscleGroups.toList(),
                            category = request.category,
                            isCustom = true,
                            notes = request.notes.trim()
                        )
                    }.onSuccess { exercise ->
                        showCreateDialog = false
                        selectedExercises = selectedExercises + exercise
                    }.onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        createState = createState.copy(
                            isSaving = false,
                            errorMessage = throwable.userMessageOrNull(strings)
                                ?: strings.get(UxR.string.workout_picker_create_failed)
                        )
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateExerciseDialog(
    state: ExerciseCreateState,
    onStateChange: (ExerciseCreateState) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val dims = thrymDimens
    var groupExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        title = { Text(stringResource(id = R.string.exercises_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dims.spacingSm)) {
                com.thrym.app.presentation.common.ThrymTextField(
                    value = state.name,
                    onValueChange = { onStateChange(state.copy(name = it, errorMessage = null)) },
                    label = { Text(stringResource(id = R.string.exercises_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }

                Box {
                    com.thrym.app.presentation.common.ThrymTextField(
                        value = state.primaryMuscleGroup.label(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(id = R.string.exercises_muscle_group_label)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { groupExpanded = true }
                    )
                    DropdownMenu(
                        expanded = groupExpanded,
                        onDismissRequest = { groupExpanded = false }
                    ) {
                        MuscleGroup.entries.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.label()) },
                                onClick = {
                                    onStateChange(
                                        state.copy(
                                            primaryMuscleGroup = group,
                                            secondaryMuscleGroups = state.secondaryMuscleGroups - group
                                        )
                                    )
                                    groupExpanded = false
                                }
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(dims.spacingXs)) {
                    Text(
                        text = stringResource(id = R.string.exercises_secondary_muscle_groups_label)
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
                    ) {
                        MuscleGroup.entries
                            .filter { it != state.primaryMuscleGroup }
                            .forEach { group ->
                                val selected = group in state.secondaryMuscleGroups
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val next = when {
                                            selected -> state.secondaryMuscleGroups - group
                                            state.secondaryMuscleGroups.size < 3 -> state.secondaryMuscleGroups + group
                                            else -> state.secondaryMuscleGroups
                                        }
                                        onStateChange(state.copy(secondaryMuscleGroups = next))
                                    },
                                    label = { Text(group.label()) },
                                    colors = ThrymInteractiveColors.filterChip()
                                )
                            }
                    }
                    Text(
                        text = stringResource(id = R.string.exercises_secondary_muscle_groups_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box {
                    com.thrym.app.presentation.common.ThrymTextField(
                        value = state.category.label(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(id = R.string.exercises_category_label)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { categoryExpanded = true }
                    )
                    DropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        ExerciseCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.label()) },
                                onClick = {
                                    onStateChange(state.copy(category = category))
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }

                com.thrym.app.presentation.common.ThrymTextField(
                    value = state.notes,
                    onValueChange = { onStateChange(state.copy(notes = it)) },
                    label = { Text(stringResource(id = R.string.exercises_notes_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = state.name.isNotBlank() && !state.isSaving,
                colors = ThrymInteractiveColors.textButton()
            ) {
                Text(stringResource(id = R.string.common_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSaving, colors = ThrymInteractiveColors.textButton()) {
                Text(stringResource(id = R.string.common_cancel))
            }
        }
    )
}

