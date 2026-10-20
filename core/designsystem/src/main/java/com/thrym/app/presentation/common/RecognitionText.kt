package com.thrym.app.presentation.common

import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeekStart
import com.thrym.core.designsystem.R
import com.thrym.shared.recognition.CompletionFactKind
import com.thrym.shared.recognition.CompletionFacts
import com.thrym.shared.recognition.StrengthMark
import com.thrym.shared.recognition.StrengthMarkKind
import com.thrym.shared.recognition.WeeklyGoalStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Unit label of a mark: its own unit, not the current setting, so a stored mark keeps its word. */
private fun StrengthMark.unitLabel(): String = if (imperial) "lb" else "kg"

/** "100-kg-Marke", "+20-kg-Marke", "20-Wdh-Marke": the mark as a title. */
fun UiStrings.markTitle(mark: StrengthMark): String = when (mark.kind) {
    StrengthMarkKind.LOAD -> get(R.string.recognition_mark_load, mark.value, mark.unitLabel())
    StrengthMarkKind.ADDED_LOAD -> get(R.string.recognition_mark_added_load, mark.value, mark.unitLabel())
    StrengthMarkKind.REPS -> get(R.string.recognition_mark_reps, mark.value)
}

/** "100 kg", "+20 kg", "20 Wdh": the value of a mark on the record board. */
fun UiStrings.markValue(mark: StrengthMark): String = when (mark.kind) {
    StrengthMarkKind.LOAD -> "${mark.value} ${mark.unitLabel()}"
    StrengthMarkKind.ADDED_LOAD -> get(R.string.recognition_mark_value_added_load, mark.value, mark.unitLabel())
    StrengthMarkKind.REPS -> getQuantity(R.plurals.common_record_reps, mark.value, mark.value)
}

/** Unit word of a ladder for the statistics header: "kg", "+kg", "Wdh". */
fun UiStrings.markLadderUnit(kind: StrengthMarkKind, unitSystem: UnitSystem): String {
    val unit = if (unitSystem == UnitSystem.IMPERIAL) "lb" else "kg"
    return when (kind) {
        StrengthMarkKind.LOAD -> unit
        StrengthMarkKind.ADDED_LOAD -> "+$unit"
        StrengthMarkKind.REPS -> get(R.string.recognition_marks_reps_unit)
    }
}

/** "7 Wochen in Folge", with "· 1 Woche Puffer" when buffer weeks are in stock. */
fun UiStrings.streakText(status: WeeklyGoalStatus): String = listOfNotNull(
    getQuantity(R.plurals.recognition_streak_weeks, status.streakWeeks, status.streakWeeks),
    status.buffers.takeIf { it > 0 }?.let { getQuantity(R.plurals.recognition_buffer_weeks, it, it) },
).joinToString(" · ")

/**
 * The line under the week: the streak from two weeks on ("1 Woche in Folge" says little), else
 * "Wochenziel erreicht" once the running week is met, else nothing. Never "0 Wochen".
 */
fun UiStrings.weekFooterText(status: WeeklyGoalStatus): String? = when {
    status.streakWeeks >= 2 -> streakText(status)
    status.goalMetThisWeek -> get(R.string.recognition_weekly_goal_met)
    else -> null
}

/** "2 von 3" for the week strip. */
fun UiStrings.weekProgressText(status: WeeklyGoalStatus): String =
    get(R.string.recognition_week_progress, status.completedThisWeek, status.goal)

/** What TalkBack reads for the week: "2 von 3 Trainings diese Woche, 7 Wochen in Folge". */
fun UiStrings.weekProgressDescription(status: WeeklyGoalStatus): String = listOfNotNull(
    getQuantity(R.plurals.recognition_week_progress_cd, status.goal, status.completedThisWeek, status.goal),
    weekFooterText(status),
).joinToString(", ")

/** A fact row of the completion screen: title and detail. */
data class CompletionFactText(val kind: CompletionFactKind, val title: String, val detail: String)

/** The completion facts as text, at most two rows (see [CompletionFacts.rows]). */
fun UiStrings.completionFactTexts(facts: CompletionFacts): List<CompletionFactText> = facts.rows.mapNotNull { kind ->
    when (kind) {
        CompletionFactKind.WEEKLY_GOAL -> facts.weeklyGoal?.let { goal ->
            CompletionFactText(
                kind = kind,
                title = get(R.string.recognition_weekly_goal_met),
                detail = listOfNotNull(
                    get(R.string.recognition_week_progress, goal.completedThisWeek, goal.goal),
                    streakText(goal).takeIf { goal.streakWeeks >= 2 },
                ).joinToString(" · ")
            )
        }
        CompletionFactKind.JUBILEE -> CompletionFactText(
            kind = kind,
            title = get(R.string.recognition_jubilee_title, facts.trainingNumber),
            detail = get(
                R.string.recognition_jubilee_since,
                LocalDate.ofEpochDay(facts.firstTrainingEpochDay)
                    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
            )
        )
        CompletionFactKind.COMEBACK -> facts.pauseDays?.let { days ->
            CompletionFactText(
                kind = kind,
                title = get(R.string.recognition_comeback_title),
                detail = getQuantity(R.plurals.recognition_comeback_pause, days, days)
            )
        }
    }
}

fun WeekStart.toSharedWeekStart(): com.thrym.shared.model.WeekStart = com.thrym.shared.model.WeekStart.valueOf(name)

fun UnitSystem.toSharedUnit(): com.thrym.shared.model.UnitSystem = com.thrym.shared.model.UnitSystem.valueOf(name)
