package com.thrym.app.presentation.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.common.markTitle
import com.thrym.app.presentation.common.markValue
import com.thrym.app.presentation.common.rememberUiStrings
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import com.thrym.shared.workout.SessionSetBadge

/**
 * The quiet record marker of a set and of its exercise: a small trophy with "PR", or with the
 * mark the set reached ("100 kg"), in the warning tone. It replaces the record message of the
 * running workout. TalkBack reads "neuer Rekord" or "100-kg-Marke, zum ersten Mal erreicht".
 */
@Composable
internal fun RecordMarker(badge: SessionSetBadge, modifier: Modifier = Modifier) {
    val strings = rememberUiStrings()
    val mark = badge.mark
    val label = mark?.let { strings.markValue(it) } ?: strings.get(WorkoutR.string.workout_record_marker_pr)
    val description = mark?.let { strings.get(R.string.recognition_mark_row_cd, strings.markTitle(it)) }
        ?: strings.get(WorkoutR.string.workout_record_marker_cd)
    val tone = MaterialTheme.semanticText.warning
    Row(
        modifier = modifier
            .background(MaterialTheme.semantic.warning.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = tone, modifier = Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tone)
    }
}
