package com.thrym.app.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.common.LevelRing
import com.thrym.app.presentation.common.chapterAccent
import com.thrym.app.presentation.common.hammerDepthText
import com.thrym.app.presentation.common.levelTitle
import com.thrym.app.presentation.common.nextStepText
import com.thrym.core.designsystem.R
import com.thrym.shared.gamification.LevelCurve
import com.thrym.shared.gamification.LevelProgress

/** The place on the hero's journey: emblem in a ring, chapter, tier and the way to the next step. */
@Composable
internal fun LevelCard(level: LevelProgress, modifier: Modifier = Modifier) {
    DashboardPanel(modifier) {
        Row(
            Modifier.padding(16.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LevelRing(level, size = 96.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.gamification_chapter_of, level.chapter, LevelCurve.CHAPTERS).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = chapterAccent(level.chapter)
                )
                Text(levelTitle(level), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    stringResource(R.string.gamification_line, hammerDepthText(level), nextStepText(level)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
