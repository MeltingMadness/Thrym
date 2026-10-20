package com.thrym.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.ReminderConfig
import java.time.DayOfWeek

val Context.appPreferencesDataStore by preferencesDataStore(name = "app_preferences")

internal object AppPreferenceKeys {
    val UNIT_SYSTEM = stringPreferencesKey("unit_system")
    val WEEK_START = stringPreferencesKey("week_start")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val THEME_SCHEME = stringPreferencesKey("theme_scheme")
    val APPEARANCE_STYLE = stringPreferencesKey("appearance_style")
    val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
    val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
    val DEFAULT_WARMUP_FLAG = booleanPreferencesKey("default_warmup_flag")
    val TIMER_KEEP_SCREEN_ON = booleanPreferencesKey("timer_keep_screen_on")
    val BETA_DIAGNOSTICS_OPT_IN = booleanPreferencesKey("beta_diagnostics_opt_in")
    val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
    val REMINDER_HOUR = intPreferencesKey("reminder_hour")
    val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
    val REMINDER_DAYS = stringPreferencesKey("reminder_days")
    val INTENSITY_SYSTEM = stringPreferencesKey("intensity_system")
    val SHARE_WEIGHT_HISTORY_ACROSS_CONTEXTS =
        booleanPreferencesKey("share_weight_history_across_contexts")
    val AUTO_REST_TIMER_ENABLED = booleanPreferencesKey("auto_rest_timer_enabled")
    val DEFAULT_REST_TIME_SECONDS = intPreferencesKey("default_rest_time_seconds")
    val DELOAD_MODE = stringPreferencesKey("deload_mode")
    val PLATE_CALCULATOR_ENABLED = booleanPreferencesKey("plate_calculator_enabled")
    val AVAILABLE_PLATES = stringPreferencesKey("available_plates")
    val BARBELL_WEIGHT_KG = doublePreferencesKey("barbell_weight_kg")
    val LAST_SUCCESSFUL_EXPORT_EPOCH_MILLIS = longPreferencesKey("last_successful_export_epoch_millis")
    val BACKUP_REMINDER_ENABLED = booleanPreferencesKey("backup_reminder_enabled")
    val WEEKLY_GOAL_ENABLED = booleanPreferencesKey("weekly_goal_enabled")
    /** Goal changes as `epochDay:goal`, comma-separated (see `WeeklyGoalRules.encode`). */
    val WEEKLY_GOAL_HISTORY = stringPreferencesKey("weekly_goal_history")
    val ACTIVE_WORKOUT_EXERCISE_ORDER = stringPreferencesKey("active_workout_exercise_order")

    fun restTimerState(sessionId: Long) =
        stringPreferencesKey("rest_timer_state_$sessionId")
}

// Sentinel stored when the user explicitly deselects every reminder day. This is required to
// distinguish "user chose no days" from "preference was never written" (raw == null), since both
// would otherwise be indistinguishable blank/empty strings and incorrectly snap back to the
// Mon/Wed/Fri default every time the app reloads preferences.
private const val REMINDER_DAYS_NONE_SENTINEL = "none"

internal fun parseReminderDays(raw: String?): Set<DayOfWeek> {
    if (raw == null) {
        // Key has never been written — fall back to the default for first-time users.
        return ReminderConfig().daysOfWeek
    }

    if (raw == REMINDER_DAYS_NONE_SENTINEL) {
        // Explicitly persisted empty selection — respect it instead of resetting to the default.
        return emptySet()
    }

    if (raw.isBlank()) {
        // Legacy encode used "" for empty, which historically decoded to the default.
        // Keep that behavior for already-stored blank values.
        return ReminderConfig().daysOfWeek
    }

    return raw.split(',')
        .mapNotNull { token ->
            runCatching { DayOfWeek.valueOf(token.trim()) }.getOrNull()
        }
        .toSet()
}

internal fun encodeReminderDays(days: Set<DayOfWeek>): String {
    if (days.isEmpty()) return REMINDER_DAYS_NONE_SENTINEL
    return days.sortedBy { it.value }.joinToString(",") { it.name }
}

internal val DEFAULT_AVAILABLE_PLATES = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)
internal const val DEFAULT_BARBELL_WEIGHT_KG = 20.0
private const val AVAILABLE_PLATES_NONE_SENTINEL = "none"

internal fun parseAvailablePlates(raw: String?): List<Double> {
    if (raw == null) return DEFAULT_AVAILABLE_PLATES
    if (raw == AVAILABLE_PLATES_NONE_SENTINEL || raw.isBlank()) return emptyList()
    return raw.split(',')
        .mapNotNull { it.trim().toDoubleOrNull() }
        .filter { it > 0.0 }
        .distinct()
        .sortedDescending()
}

internal fun encodeAvailablePlates(plates: List<Double>): String {
    if (plates.isEmpty()) return AVAILABLE_PLATES_NONE_SENTINEL
    return plates.filter { it > 0.0 }.distinct().sortedDescending().joinToString(",") { it.toString() }
}

internal fun Preferences.stringOrNull(key: Preferences.Key<String>): String? = this[key]
