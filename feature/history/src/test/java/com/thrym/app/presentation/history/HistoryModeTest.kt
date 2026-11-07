package com.thrym.app.presentation.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryModeTest {

    @Test
    fun `mit Spielelementen zeigt der Verlauf den gewaehlten Modus und den Umschalter`() {
        assertEquals(
            HistoryModeDisplay(HistoryMode.WORKOUTS, showSwitcher = true),
            resolveHistoryMode(HistoryMode.WORKOUTS, gamificationEnabled = true)
        )
        assertEquals(
            HistoryModeDisplay(HistoryMode.ACHIEVEMENTS, showSwitcher = true),
            resolveHistoryMode(HistoryMode.ACHIEVEMENTS, gamificationEnabled = true)
        )
    }

    @Test
    fun `ohne Spielelemente bleiben nur die Trainings, auch wenn Erfolge gewaehlt waren`() {
        assertEquals(
            HistoryModeDisplay(HistoryMode.WORKOUTS, showSwitcher = false),
            resolveHistoryMode(HistoryMode.WORKOUTS, gamificationEnabled = false)
        )
        assertEquals(
            HistoryModeDisplay(HistoryMode.WORKOUTS, showSwitcher = false),
            resolveHistoryMode(HistoryMode.ACHIEVEMENTS, gamificationEnabled = false)
        )
    }

    @Test
    fun `solange die Einstellung laedt, fehlt der Umschalter und Erfolge warten`() {
        assertEquals(
            HistoryModeDisplay(HistoryMode.WORKOUTS, showSwitcher = false),
            resolveHistoryMode(HistoryMode.WORKOUTS, gamificationEnabled = null)
        )
        assertEquals(
            HistoryModeDisplay(mode = null, showSwitcher = false),
            resolveHistoryMode(HistoryMode.ACHIEVEMENTS, gamificationEnabled = null)
        )
    }
}
