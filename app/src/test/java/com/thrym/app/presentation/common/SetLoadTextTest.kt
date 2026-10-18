package com.thrym.app.presentation.common

import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.fakes.TestUiStrings
import com.thrym.core.designsystem.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bodyweight exercises store the added load as the set weight. */
class SetLoadTextTest {

    private fun exercise(category: ExerciseCategory) =
        Exercise(name = "Klimmzug", primaryMuscleGroup = MuscleGroup.RUECKEN, category = category)

    @Test
    fun `only bodyweight exercises track an added load`() {
        assertTrue(exercise(ExerciseCategory.EIGENGEWICHT).tracksAddedLoad)
        ExerciseCategory.entries.filter { it != ExerciseCategory.EIGENGEWICHT }.forEach { category ->
            assertFalse(category.name, exercise(category).tracksAddedLoad)
        }
    }

    @Test
    fun `zero kilograms reads as bodyweight`() {
        val bodyweight = TestUiStrings.get(R.string.weight_bodyweight)
        assertEquals(bodyweight, TestUiStrings.setLoadText(0.0, UnitSystem.METRIC, addedLoad = true))
        assertEquals(bodyweight, TestUiStrings.setLoadText(0.0, UnitSystem.METRIC, addedLoad = false))
    }

    @Test
    fun `added load reads as bodyweight plus load in both languages`() {
        assertEquals("Bodyweight + 10 kg", TestUiStrings.setLoadText(10.0, UnitSystem.METRIC, addedLoad = true))
        assertEquals("Bodyweight + 22 lb", TestUiStrings.setLoadText(10.0, UnitSystem.IMPERIAL, addedLoad = true))
        assertEquals("Körpergewicht + 12,5 kg", TestUiStrings.german.setLoadText(12.5, UnitSystem.METRIC, addedLoad = true))
        assertEquals("Körpergewicht", TestUiStrings.german.setLoadText(0.0, UnitSystem.METRIC, addedLoad = true))
    }

    @Test
    fun `other exercises keep the plain weight`() {
        assertEquals("82.5 kg", TestUiStrings.setLoadText(82.5, UnitSystem.METRIC, addedLoad = false))
        assertEquals("82,5 kg", TestUiStrings.german.setLoadText(82.5, UnitSystem.METRIC, addedLoad = false))
    }

    @Test
    fun `added load field labels exist in both languages`() {
        assertEquals("Added (kg)", TestUiStrings.get(R.string.weight_added_load_field_label, "kg"))
        assertEquals("Zusatz (kg)", TestUiStrings.german.get(R.string.weight_added_load_field_label, "kg"))
        assertEquals("Zusatzgewicht", TestUiStrings.german.get(R.string.weight_added_load))
    }
}
