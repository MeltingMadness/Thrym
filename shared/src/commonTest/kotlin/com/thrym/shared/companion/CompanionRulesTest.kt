package com.thrym.shared.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionRulesTest {

    @Test
    fun autoFollowsTheProfile() {
        assertEquals(CompanionCharacter.ODIN, CompanionRules.resolve(CompanionChoice.AUTO, profileFemale = false))
        assertEquals(CompanionCharacter.VALKYRIE, CompanionRules.resolve(CompanionChoice.AUTO, profileFemale = true))
    }

    @Test
    fun explicitChoiceIgnoresTheProfile() {
        assertEquals(CompanionCharacter.ODIN, CompanionRules.resolve(CompanionChoice.ODIN, profileFemale = true))
        assertEquals(CompanionCharacter.VALKYRIE, CompanionRules.resolve(CompanionChoice.VALKYRIE, profileFemale = false))
        assertNull(CompanionRules.resolve(CompanionChoice.OFF, profileFemale = true))
    }

    @Test
    fun unknownStoredChoiceFallsBackToAuto() {
        assertEquals(CompanionChoice.AUTO, CompanionRules.parseChoice(null))
        assertEquals(CompanionChoice.AUTO, CompanionRules.parseChoice("LOKI"))
        assertEquals(CompanionChoice.OFF, CompanionRules.parseChoice("OFF"))
    }

    @Test
    fun momentFollowsTheHistory() {
        assertEquals(CompanionMoment.FIRST_STEPS, CompanionRules.moment(null))
        assertEquals(CompanionMoment.TRAINED_TODAY, CompanionRules.moment(0))
        assertEquals(CompanionMoment.EVERYDAY, CompanionRules.moment(1))
        assertEquals(CompanionMoment.EVERYDAY, CompanionRules.moment(CompanionRules.WELCOME_BACK_AFTER_DAYS - 1))
        assertEquals(CompanionMoment.WELCOME_BACK, CompanionRules.moment(CompanionRules.WELCOME_BACK_AFTER_DAYS))
    }

    @Test
    fun firstStepsAndComebackAlwaysGetAGreeting() {
        for (day in 20_000L until 20_060L) {
            val first = appearance(day, daysSinceLastWorkout = null)
            assertEquals(CompanionLine.FIRST_STEPS_WELCOME, first?.line)
            val back = appearance(day, daysSinceLastWorkout = 30)
            assertEquals(CompanionLine.WELCOME_BACK_ONE_SET, back?.line)
        }
    }

    @Test
    fun ordinaryDaysGetAnIrregularShareOfAppearances() {
        val days = 20_000L until 21_000L
        val shown = days.filter { appearance(it, daysSinceLastWorkout = 3) != null }
        // Roughly one in four, never every day and never not at all.
        assertTrue(shown.size in 180..320, "shown on ${shown.size} of 1000 days")
        // Irregular: not a fixed rhythm of every fourth day.
        val gaps = shown.zipWithNext { a, b -> b - a }.toSet()
        assertTrue(gaps.size > 3, "gaps $gaps")
        shown.forEach { day ->
            assertTrue(appearance(day, 3)!!.line in CompanionRules.lines(CompanionMoment.EVERYDAY))
        }
    }

    @Test
    fun trainingDaysShowUpMoreOftenThanOrdinaryDays() {
        val days = 20_000L until 21_000L
        val training = days.count { appearance(it, daysSinceLastWorkout = 0) != null }
        val ordinary = days.count { appearance(it, daysSinceLastWorkout = 3) != null }
        assertTrue(training > ordinary, "training $training, ordinary $ordinary")
    }

    @Test
    fun appearanceIsStableWithinADay() {
        for (day in 20_000L until 20_100L) {
            assertEquals(appearance(day, 3), appearance(day, 3))
        }
    }

    @Test
    fun everydayLinesRotate() {
        val lines = (20_000L until 20_200L).map { CompanionRules.lineFor(CompanionMoment.EVERYDAY, it) }.toSet()
        assertEquals(CompanionRules.lines(CompanionMoment.EVERYDAY).toSet(), lines)
    }

    @Test
    fun hidingKeepsTheCompanionAwayForTheRestOfTheDay() {
        val day = 20_000L
        assertNotNull(appearance(day, daysSinceLastWorkout = null))
        assertNull(appearance(day, daysSinceLastWorkout = null, dismissedEpochDay = day))
        assertNotNull(appearance(day + 1, daysSinceLastWorkout = null, dismissedEpochDay = day))
    }

    @Test
    fun switchedOffNeverAppears() {
        for (day in 20_000L until 20_050L) {
            assertNull(
                CompanionRules.homeAppearance(
                    character = null,
                    epochDay = day,
                    daysSinceLastWorkout = null,
                    hiddenEpochDay = null
                )
            )
        }
    }

    @Test
    fun appearanceUsesTheGivenCharacter() {
        val valkyrie = CompanionRules.homeAppearance(
            character = CompanionCharacter.VALKYRIE,
            epochDay = 20_000L,
            daysSinceLastWorkout = null,
            hiddenEpochDay = null
        )
        assertEquals(CompanionCharacter.VALKYRIE, valkyrie?.character)
    }

    private fun appearance(
        day: Long,
        daysSinceLastWorkout: Long?,
        dismissedEpochDay: Long? = null
    ): CompanionAppearance? = CompanionRules.homeAppearance(
        character = CompanionCharacter.ODIN,
        epochDay = day,
        daysSinceLastWorkout = daysSinceLastWorkout,
        hiddenEpochDay = dismissedEpochDay
    )
}
