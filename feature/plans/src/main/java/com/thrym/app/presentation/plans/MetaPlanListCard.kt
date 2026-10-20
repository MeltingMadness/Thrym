package com.thrym.app.presentation.plans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.thrym.core.designsystem.R
import com.thrym.feature.plans.R as PlansR
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText

/** Meta plan card of the plans tab: sub-plan count, next sub-plan and last training. */
@Composable
internal fun MetaPlanListCard(
    item: MetaPlanListItemUi,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dims = thrymDimens
    ThrymSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        tone = ThrymSurfaceTone.COLORED,
        semanticColor = MaterialTheme.semantic.violet,
        alpha = 0.68f
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.spacingMd),
            verticalArrangement = Arrangement.spacedBy(dims.spacing2)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.metaPlan.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(id = R.string.meta_plans_edit_cd)
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(id = R.string.common_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Text(
                text = pluralStringResource(
                    id = R.plurals.meta_plans_subplan_count,
                    count = item.subPlans.size,
                    item.subPlans.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            item.nextSubPlan?.let { nextPlan ->
                Text(
                    text = stringResource(id = R.string.plan_selection_meta_continue_with, nextPlan.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.semanticText.violet
                )
            }

            Text(
                text = when (val daysAgo = item.lastDoneDaysAgo) {
                    null -> stringResource(id = PlansR.string.meta_plans_last_done_never)
                    0L -> stringResource(id = PlansR.string.meta_plans_last_done_today)
                    1L -> stringResource(id = PlansR.string.meta_plans_last_done_yesterday)
                    else -> pluralStringResource(id = PlansR.plurals.meta_plans_last_done_days, count = daysAgo.toInt(), daysAgo.toInt())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

