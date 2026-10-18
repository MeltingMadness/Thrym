package com.thrym.app.presentation.common

import androidx.compose.runtime.Composable
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.core.designsystem.R

/**
 * Bodyweight exercises ([ExerciseCategory.EIGENGEWICHT]) store the added load as the set
 * weight: 0 kg is a plain bodyweight set, 10 kg is bodyweight plus 10 kg.
 */
val Exercise.tracksAddedLoad: Boolean
    get() = category == ExerciseCategory.EIGENGEWICHT

/**
 * Load of a set or target as text: "82.5 kg"; 0 kg reads "Bodyweight". With
 * [addedLoad] (a bodyweight exercise) any other weight reads "Bodyweight + 10 kg".
 */
fun UiStrings.setLoadText(weightKg: Double, unitSystem: UnitSystem, addedLoad: Boolean): String = when {
    weightKg == 0.0 -> get(R.string.weight_bodyweight)
    addedLoad -> get(R.string.weight_bodyweight_plus_load, WeightFormatting.formatWeight(weightKg, unitSystem, locale))
    else -> WeightFormatting.formatWeight(weightKg, unitSystem, locale)
}

@Composable
fun setLoadText(weightKg: Double, unitSystem: UnitSystem, addedLoad: Boolean): String =
    rememberUiStrings().setLoadText(weightKg, unitSystem, addedLoad)
