package com.thrym.app.data.preferences

import android.content.Context
import com.thrym.app.data.local.dao.WorkoutSessionDao
import com.thrym.app.data.local.entity.WorkoutSessionEntity
import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.ReminderConfig
import com.thrym.app.domain.model.ThemeMode
import com.thrym.app.domain.model.UnitSystem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesDataStoreTest {

    @Test
    fun `missing key falls back to default reminder days`() {
        val days = parseReminderDays(null)
        assertEquals(ReminderConfig().daysOfWeek, days)
    }

    @Test
    fun `explicitly empty selection stays empty instead of snapping back to default`() {
        val encoded = encodeReminderDays(emptySet())
        val decoded = parseReminderDays(encoded)

        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `legacy blank value keeps historical default decode`() {
        // Older builds encoded empty as "" but then decoded blank back to Mon/Wed/Fri.
        // Keep that decode so already-stored blank values do not suddenly become "no days".
        // New explicit empty selections use the "none" sentinel (covered above).
        val decoded = parseReminderDays("")
        assertEquals(ReminderConfig().daysOfWeek, decoded)
    }

    @Test
    fun `non-empty selection encodes and decodes round trip`() {
        val days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val encoded = encodeReminderDays(days)
        val decoded = parseReminderDays(encoded)

        assertEquals(days, decoded)
    }

    @Test
    fun `invalid tokens are dropped without discarding valid days`() {
        val decoded = parseReminderDays("MONDAY,NOT_A_DAY,FRIDAY")
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), decoded)
    }

    @Test
    fun `share weight history across contexts defaults to false and persists enabled flag`() = runTest {
        val repository = AppPreferencesRepositoryImpl(createContextWithTempDataStore())

        assertFalse(repository.preferences.first().shareWeightHistoryAcrossContexts)
        repository.updateShareWeightHistoryAcrossContexts(true)

        assertTrue(repository.preferences.first().shareWeightHistoryAcrossContexts)
    }

    @Test
    fun `appearance style defaults to liquid glass and persists ember`() = runTest {
        val repository = AppPreferencesRepositoryImpl(createContextWithTempDataStore())

        assertEquals(com.thrym.app.domain.model.AppearanceStyle.LIQUID_GLASS, repository.preferences.first().appearanceStyle)
        repository.updateAppearanceStyle(com.thrym.app.domain.model.AppearanceStyle.EMBER)

        assertEquals(com.thrym.app.domain.model.AppearanceStyle.EMBER, repository.preferences.first().appearanceStyle)
    }

    @Test
    fun `auto rest timer defaults to 120s disabled and persists enabled flag with duration`() = runTest {
        val repository = AppPreferencesRepositoryImpl(createContextWithTempDataStore())

        val defaults = repository.preferences.first()
        assertFalse(defaults.autoRestTimerEnabled)
        assertEquals(120, defaults.defaultRestTimeSeconds)

        repository.updateAutoRestTimerEnabled(true)
        repository.updateDefaultRestTimeSeconds(240)

        val persisted = repository.preferences.first()
        assertTrue(persisted.autoRestTimerEnabled)
        assertEquals(240, persisted.defaultRestTimeSeconds)
    }

    @Test
    fun `backup export timestamp defaults to null and reminder opt in persists`() = runTest {
        val repository = AppPreferencesRepositoryImpl(createContextWithTempDataStore())

        val defaults = repository.preferences.first()
        assertEquals(null, defaults.lastSuccessfulExportEpochMillis)
        assertFalse(defaults.backupReminderEnabled)

        repository.updateLastSuccessfulExportEpochMillis(1_700_000_000_000L)
        repository.updateBackupReminderEnabled(true)

        val persisted = repository.preferences.first()
        assertEquals(1_700_000_000_000L, persisted.lastSuccessfulExportEpochMillis)
        assertTrue(persisted.backupReminderEnabled)
    }

    @Test
    fun `clearing backup export timestamp removes stored completion`() = runTest {
        val repository = AppPreferencesRepositoryImpl(createContextWithTempDataStore())

        repository.updateLastSuccessfulExportEpochMillis(1_700_000_000_000L)
        repository.updateLastSuccessfulExportEpochMillis(null)

        assertEquals(null, repository.preferences.first().lastSuccessfulExportEpochMillis)
    }

    @Test
    fun `workout exercise order persists one entry and null removes it`() = runTest {
        val repository = AppPreferencesRepositoryImpl(createContextWithTempDataStore())

        assertEquals(null, repository.readWorkoutExerciseOrder())

        repository.writeWorkoutExerciseOrder("v1|7|1700000000000|planned-2,planned-1")
        repository.writeRestTimerState(7L, "1700000000000#")
        assertEquals("v1|7|1700000000000|planned-2,planned-1", repository.readWorkoutExerciseOrder())

        repository.writeWorkoutExerciseOrder("v1|8|1700000000001|adhoc-3")
        assertEquals("v1|8|1700000000001|adhoc-3", repository.readWorkoutExerciseOrder())

        repository.writeWorkoutExerciseOrder(null)
        assertEquals(null, repository.readWorkoutExerciseOrder())
        // The order has its own key and never removes the rest timer state.
        assertEquals("1700000000000#", repository.readRestTimerState(7L))
    }

    @Test
    fun `enabling deload mode marks the running session as a planned deload`() = runTest {
        val context = createContextWithTempDataStore()
        val sessionDao = mockk<WorkoutSessionDao>()
        coEvery { sessionDao.getActiveSession() } returns WorkoutSessionEntity(
            id = 7L,
            startTime = 1_700_000_000_000L,
            name = "Laufendes Training"
        )
        coEvery { sessionDao.update(any()) } returns Unit
        val repository = AppPreferencesRepositoryImpl(context, sessionDao)

        repository.updateDeloadMode(DeloadMode.HALVE_SET_VOLUME)

        coVerify(exactly = 1) { sessionDao.update(match { it.id == 7L && it.isDeload == true }) }
    }

    @Test
    fun `clearing deload mode never rewrites the recorded session context`() = runTest {
        val context = createContextWithTempDataStore()
        val sessionDao = mockk<WorkoutSessionDao>()
        val repository = AppPreferencesRepositoryImpl(context, sessionDao)

        repository.updateDeloadMode(null)

        coVerify(exactly = 0) { sessionDao.getActiveSession() }
        coVerify(exactly = 0) { sessionDao.update(any()) }
    }

    @Test
    fun `corrupted preferences file falls back to defaults instead of crashing`() = runTest {
        val context = createContextWithTempDataStore()
        val dataStoreDir = File(context.filesDir, "datastore")
        dataStoreDir.mkdirs()
        File(dataStoreDir, "app_preferences.preferences_pb").writeBytes(
            "definitely-not-a-valid-preferences-file".encodeToByteArray()
        )

        val repository = AppPreferencesRepositoryImpl(context)

        // A corrupt preferences file must never crash app start; the flow emits defaults.
        val prefs = repository.preferences.first()

        assertEquals(UnitSystem.METRIC, prefs.unitSystem)
        assertEquals(ThemeMode.DARK, prefs.themeMode)
        assertFalse(prefs.reminderConfig.enabled)
    }

    private fun createContextWithTempDataStore(): Context {
        val dataStoreDir = Files.createTempDirectory("thrym-datastore").toFile()
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns dataStoreDir
        return context
    }
}
