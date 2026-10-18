package com.thrym.app.presentation.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.WorkoutPlanTarget
import com.thrym.shared.workout.SessionExerciseOrder
import com.thrym.shared.workout.SessionOrderRow
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.theme.ButtonSize
import com.thrym.app.presentation.theme.IconSize
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText

/**
 * One block of the workout list: a single exercise or a superset that moves as a whole.
 * [key] is the shared unit key from [SessionExerciseOrder] and stays the same when the block moves.
 */
data class ExerciseRenderGroup(
    val key: String,
    val supersetGroupId: Int?,
    val exercises: List<ExerciseWithSets>
)

/**
 * Rows of the active workout in the default order: planned snapshots by `(orderIndex, id)`, then
 * ad-hoc exercises in insertion order. Built from the eagerly held plan targets, so a move never
 * works on a stale list.
 */
internal fun sessionOrderRows(
    targets: List<WorkoutPlanTarget>,
    addedExercises: List<Exercise>
): List<SessionOrderRow> {
    val planned = targets
        .sortedWith(compareBy(WorkoutPlanTarget::orderIndex, WorkoutPlanTarget::id))
        .map { target ->
            SessionOrderRow(
                key = WorkoutExerciseKey.Planned(target.id).stableListKey(),
                supersetGroupId = target.supersetGroupId,
                planned = true
            )
        }
    val adHoc = addedExercises.distinctBy { it.id }.map { exercise ->
        SessionOrderRow(
            key = WorkoutExerciseKey.AdHoc(exercise.id).stableListKey(),
            supersetGroupId = null,
            planned = false
        )
    }
    return planned + adHoc
}

/**
 * Arranges [rowsInDefaultOrder] by the stored session order and maps the units back to list
 * groups. Superset runs are formed from the default order, so a stored order can move a block
 * but never split or merge it.
 */
internal fun arrangeExerciseGroups(
    rowsInDefaultOrder: List<ExerciseWithSets>,
    storedKeys: List<String>
): List<ExerciseRenderGroup> {
    val rowsByKey = rowsInDefaultOrder.associateBy { it.key.stableListKey() }
    val rows = rowsInDefaultOrder.map { row ->
        SessionOrderRow(
            key = row.key.stableListKey(),
            supersetGroupId = row.supersetGroupId,
            planned = row.key is WorkoutExerciseKey.Planned
        )
    }
    return SessionExerciseOrder.arrange(rows, storedKeys).map { unit ->
        ExerciseRenderGroup(
            key = unit.key,
            supersetGroupId = unit.supersetGroupId,
            exercises = unit.memberKeys.mapNotNull(rowsByKey::get)
        )
    }
}

internal fun WorkoutExerciseKey.stableListKey(): String = when (this) {
    is WorkoutExerciseKey.Planned -> "planned-$snapshotId"
    is WorkoutExerciseKey.AdHoc -> "adhoc-$exerciseId"
}

@Composable
internal fun supersetTintColor(supersetGroupId: Int?, indexInSuperset: Int): Color? {
    if (supersetGroupId == null || indexInSuperset < 0) return null
    return when (indexInSuperset % 3) {
        0 -> MaterialTheme.semantic.violet
        1 -> MaterialTheme.semantic.sky
        else -> MaterialTheme.semantic.rose
    }
}

@Composable
internal fun supersetTintTextColor(supersetGroupId: Int?, indexInSuperset: Int): Color? {
    if (supersetGroupId == null || indexInSuperset < 0) return null
    return when (indexInSuperset % 3) {
        0 -> MaterialTheme.semanticText.violet
        1 -> MaterialTheme.semanticText.sky
        else -> MaterialTheme.semanticText.rose
    }
}

/**
 * Up and down arrows of one block in the workout list. [canMoveUp] and [canMoveDown] refer to
 * all blocks, planned and ad-hoc.
 */
internal class ExerciseMoveControls(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit
)

@Composable
internal fun ExerciseMoveButtons(
    controls: ExerciseMoveControls,
    moveUpDescription: String,
    moveDownDescription: String
) {
    // No tint on the icons: the button's content color also yields the dimmed disabled state.
    val colors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    IconButton(
        onClick = controls.onMoveUp,
        enabled = controls.canMoveUp,
        colors = colors,
        modifier = Modifier.size(ButtonSize.iconButton)
    ) {
        Icon(
            imageVector = Icons.Default.ArrowUpward,
            contentDescription = moveUpDescription,
            modifier = Modifier.size(IconSize.md)
        )
    }
    IconButton(
        onClick = controls.onMoveDown,
        enabled = controls.canMoveDown,
        colors = colors,
        modifier = Modifier.size(ButtonSize.iconButton)
    ) {
        Icon(
            imageVector = Icons.Default.ArrowDownward,
            contentDescription = moveDownDescription,
            modifier = Modifier.size(IconSize.md)
        )
    }
}

@Composable
internal fun SupersetHeader(
    groupId: Int,
    exerciseCount: Int,
    exerciseNames: String,
    moveControls: ExerciseMoveControls? = null
) {
    val dims = thrymDimens
    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.ACCENT,
        border = BorderStroke(1.dp, MaterialTheme.semantic.violet.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = dims.spacingSm,
                    end = if (moveControls != null) dims.spacingXs else dims.spacingSm
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = dims.spacingXs),
                verticalArrangement = Arrangement.spacedBy(dims.spacing2)
            ) {
                Text(
                    text = pluralStringResource(
                        id = R.plurals.workout_superset_header,
                        count = exerciseCount,
                        groupId,
                        exerciseCount
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.semanticText.violet
                )
                Text(
                    text = exerciseNames,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // The arrows move the whole superset; its member cards have none.
            moveControls?.let { controls ->
                ExerciseMoveButtons(
                    controls = controls,
                    moveUpDescription = stringResource(WorkoutR.string.workout_superset_move_up_cd, groupId),
                    moveDownDescription = stringResource(WorkoutR.string.workout_superset_move_down_cd, groupId)
                )
            }
        }
    }
}
