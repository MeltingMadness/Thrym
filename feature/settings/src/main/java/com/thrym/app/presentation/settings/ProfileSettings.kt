package com.thrym.app.presentation.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.thrym.app.domain.model.ProfileSex
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.feature.settings.R as SettingsR
import com.thrym.shared.settings.profileBirthYearOptions
import java.time.LocalDate

/**
 * Optional profile answers (sex and birth year). They only scale strength comparisons,
 * stay on the device and are not part of the backup. Both can be left unanswered.
 */
@Composable
internal fun ColumnScope.ProfileSettingsContent(
    sex: ProfileSex?,
    birthYear: Int?,
    onSexChange: (ProfileSex?) -> Unit,
    onBirthYearChange: (Int?) -> Unit
) {
    val dims = thrymDimens
    val noAnswer = stringResource(SettingsR.string.settings_profile_no_answer)

    Text(
        text = stringResource(SettingsR.string.settings_profile_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text(
        text = stringResource(SettingsR.string.settings_profile_sex_title),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
    ) {
        FilterChip(
            selected = sex == ProfileSex.FEMALE,
            onClick = { onSexChange(ProfileSex.FEMALE) },
            label = { Text(stringResource(SettingsR.string.settings_profile_sex_female)) },
            colors = ThrymInteractiveColors.filterChip()
        )
        FilterChip(
            selected = sex == ProfileSex.MALE,
            onClick = { onSexChange(ProfileSex.MALE) },
            label = { Text(stringResource(SettingsR.string.settings_profile_sex_male)) },
            colors = ThrymInteractiveColors.filterChip()
        )
        FilterChip(
            selected = sex == null,
            onClick = { onSexChange(null) },
            label = { Text(noAnswer) },
            colors = ThrymInteractiveColors.filterChip()
        )
    }

    val currentYear = remember { LocalDate.now().year }
    val yearOptions = remember(currentYear) { profileBirthYearOptions(currentYear) }
    var yearMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(SettingsR.string.settings_profile_birth_year_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { yearMenuOpen = true }) {
                Text(birthYear?.toString() ?: noAnswer)
            }
            DropdownMenu(
                expanded = yearMenuOpen,
                onDismissRequest = { yearMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text(noAnswer) },
                    onClick = {
                        yearMenuOpen = false
                        onBirthYearChange(null)
                    }
                )
                yearOptions.forEach { year ->
                    DropdownMenuItem(
                        text = { Text(year.toString()) },
                        onClick = {
                            yearMenuOpen = false
                            onBirthYearChange(year)
                        }
                    )
                }
            }
        }
    }
}
