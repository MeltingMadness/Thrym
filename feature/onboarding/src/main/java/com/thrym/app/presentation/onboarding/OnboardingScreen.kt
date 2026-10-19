package com.thrym.app.presentation.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.accentText
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.thrymMotion
import com.thrym.feature.onboarding.R
import org.koin.androidx.compose.koinViewModel
import com.thrym.core.designsystem.R as DesignR

/**
 * Short introduction on the first start: what Thrym is, the weight unit, and how to begin
 * (fresh or with an IronLog backup). Every step can be skipped.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onImportBackup: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel()
) {
    val stepIndex by viewModel.stepIndex.collectAsStateWithLifecycle()
    val unitSystem by viewModel.unitSystem.collectAsStateWithLifecycle()
    val steps = viewModel.steps
    val step = steps[stepIndex]
    val dims = thrymDimens
    val motion = thrymMotion

    BackHandler(enabled = stepIndex > 0) { viewModel.back() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = dims.spacingXl, vertical = dims.spacingLg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (stepIndex > 0) {
                IconButton(onClick = { viewModel.back() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.onboarding_back)
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (step != OnboardingStep.START) {
                TextButton(
                    onClick = viewModel::skipToStart,
                    colors = ThrymInteractiveColors.textButton()
                ) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
        }

        AnimatedContent(
            targetState = stepIndex,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            transitionSpec = {
                if (motion.reduced) {
                    fadeIn(animationSpec = snap()) togetherWith fadeOut(animationSpec = snap())
                } else {
                    fadeIn(animationSpec = tween(motion.normalMillis)) togetherWith
                        fadeOut(animationSpec = tween(motion.fastMillis))
                }
            },
            label = "onboarding_step"
        ) { index ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = dims.spacingXl),
                verticalArrangement = Arrangement.spacedBy(dims.spacingLg)
            ) {
                when (steps[index]) {
                    OnboardingStep.WELCOME -> WelcomeStep()
                    OnboardingStep.UNITS -> UnitsStep(
                        selected = unitSystem,
                        onSelect = viewModel::selectUnitSystem
                    )
                    OnboardingStep.START -> StartStep()
                }
            }
        }

        StepIndicator(
            count = steps.size,
            current = stepIndex,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = dims.spacingLg)
        )

        if (step == OnboardingStep.START) {
            Button(
                onClick = { viewModel.finish(onFinished) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_start_button),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(dims.spacingSm))
            OutlinedButton(
                onClick = { viewModel.finish(onImportBackup) },
                colors = ThrymInteractiveColors.outlinedButton(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(dims.spacingSm))
                Text(stringResource(R.string.onboarding_import_button))
            }
        } else {
            Button(
                onClick = viewModel::next,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_next),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    val dims = thrymDimens
    StepIcon(Icons.Default.FitnessCenter)
    StepTitle(stringResource(R.string.onboarding_welcome_title, stringResource(DesignR.string.app_name)))
    Text(
        text = stringResource(R.string.onboarding_welcome_subtitle),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.ELEVATED
    ) {
        Column(
            modifier = Modifier.padding(dims.spacingLg),
            verticalArrangement = Arrangement.spacedBy(dims.spacingLg)
        ) {
            FeatureRow(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.onboarding_welcome_private_title),
                body = stringResource(R.string.onboarding_welcome_private_body)
            )
            FeatureRow(
                icon = Icons.Default.FitnessCenter,
                title = stringResource(R.string.onboarding_welcome_log_title),
                body = stringResource(R.string.onboarding_welcome_log_body)
            )
            FeatureRow(
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                title = stringResource(R.string.onboarding_welcome_progress_title),
                body = stringResource(R.string.onboarding_welcome_progress_body)
            )
        }
    }
}

@Composable
private fun UnitsStep(selected: UnitSystem, onSelect: (UnitSystem) -> Unit) {
    val dims = thrymDimens
    StepTitle(stringResource(R.string.onboarding_units_title))
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(dims.spacingMd)
    ) {
        UnitOption(
            short = stringResource(R.string.onboarding_units_metric_short),
            label = stringResource(R.string.onboarding_units_metric),
            selected = selected == UnitSystem.METRIC,
            onClick = { onSelect(UnitSystem.METRIC) }
        )
        UnitOption(
            short = stringResource(R.string.onboarding_units_imperial_short),
            label = stringResource(R.string.onboarding_units_imperial),
            selected = selected == UnitSystem.IMPERIAL,
            onClick = { onSelect(UnitSystem.IMPERIAL) }
        )
    }
    Text(
        text = stringResource(R.string.onboarding_units_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StartStep() {
    val dims = thrymDimens
    StepIcon(Icons.Default.PlayArrow)
    StepTitle(stringResource(R.string.onboarding_start_title))
    Text(
        text = stringResource(R.string.onboarding_start_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.MUTED
    ) {
        Column(
            modifier = Modifier.padding(dims.spacingLg),
            verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
        ) {
            Text(
                text = stringResource(R.string.onboarding_import_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.onboarding_import_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    val dims = thrymDimens
    Row(horizontalArrangement = Arrangement.spacedBy(dims.spacingMd)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = dims.spacing2)
                .size(24.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(dims.spacing2)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UnitOption(short: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val dims = thrymDimens
    ThrymSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        tone = if (selected) ThrymSurfaceTone.ACCENT else ThrymSurfaceTone.MUTED
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dims.spacingLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dims.spacingLg)
        ) {
            Text(
                text = short,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.accentText else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(40.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.onboarding_progress, current + 1, count)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(thrymDimens.spacingSm)
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .size(width = if (active) 24.dp else 8.dp, height = 8.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}
