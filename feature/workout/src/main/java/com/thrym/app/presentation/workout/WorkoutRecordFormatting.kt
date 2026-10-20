package com.thrym.app.presentation.workout

import androidx.annotation.StringRes
import com.thrym.app.domain.model.RecordType
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.UiStrings
import com.thrym.app.presentation.common.markTitle
import com.thrym.app.presentation.common.setLoadText
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import com.thrym.shared.workout.RecordSetInput
import java.time.ZoneId
import kotlin.math.roundToInt

/*
 * Texts of the workout records, for the record board on the completion screen and the live
 * notice. Records are stored in kg (volume in kg × reps, reps as a count) and converted only
 * here. The functions take [UiStrings] instead of a composition, so the snackbar code can use
 * them too. With `addedLoad` (a bodyweight exercise, whose weight is the load on top of the
 * body) a load reads as the set does, "Körpergewicht + 10 kg"; such an exercise only has
 * weight and reps records.
 */

/** The set as the shared record rules see it. [sessionFinished] tells whether its workout has ended. */
internal fun WorkoutSet.toRecordInput(sessionFinished: Boolean): RecordSetInput = RecordSetInput(
    id = id,
    sessionId = sessionId,
    exerciseId = exerciseId,
    weightKg = weightKg,
    reps = reps,
    setType = setType.name,
    completedAtEpochMillis = completedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    sessionFinished = sessionFinished
)

/** The [RecordType] of a stored code from `SessionRecords`, or null for an unknown one. */
internal fun recordTypeOf(code: String): RecordType? = RecordType.entries.firstOrNull { it.name == code }

/** Short label of a record row: "Gewicht", "e1RM", "Wdh", "Volumen". */
@StringRes
internal fun recordLabelRes(type: RecordType): Int = when (type) {
    RecordType.MAX_WEIGHT -> WorkoutR.string.workout_records_label_weight
    RecordType.MAX_E1RM -> WorkoutR.string.workout_records_label_e1rm
    RecordType.MAX_REPS -> WorkoutR.string.workout_records_label_reps
    RecordType.MAX_VOLUME -> WorkoutR.string.workout_records_label_volume
}

/**
 * A record value in the user's unit: "100 kg", "116,7 kg" (an estimate keeps one decimal),
 * "4.320 kg" for volume. A reps record is the bare count ("12"): the label column already says
 * "Wdh". With [addedLoad] a weight record is the load of its set, "Körpergewicht + 12,5 kg".
 * For a weight gain it gives the unsigned form the spoken row uses ("2,5 kg mehr als bisher");
 * a gain is a plain weight and never takes [addedLoad].
 */
internal fun formatRecordValue(
    strings: UiStrings,
    type: RecordType,
    value: Double,
    unitSystem: UnitSystem,
    addedLoad: Boolean = false
): String =
    when (type) {
        RecordType.MAX_WEIGHT -> if (addedLoad) {
            strings.setLoadText(value, unitSystem, addedLoad = true)
        } else {
            WeightFormatting.formatWeight(value, unitSystem, strings.locale)
        }
        RecordType.MAX_E1RM -> WeightFormatting.formatWeight(value, unitSystem, strings.locale)
        RecordType.MAX_VOLUME -> WeightFormatting.formatVolume(value, unitSystem, strings.locale)
        RecordType.MAX_REPS -> formatRepCount(strings, value)
    }

/** The gain over the earlier best, signed: "+2,5 kg", "+1,25 kg", "+5,5 lb", "+310 kg", "+2". */
internal fun formatRecordDelta(strings: UiStrings, type: RecordType, delta: Double, unitSystem: UnitSystem): String =
    when (type) {
        RecordType.MAX_WEIGHT, RecordType.MAX_E1RM -> WeightFormatting.formatWeightDelta(delta, unitSystem, strings.locale)
        RecordType.MAX_VOLUME -> "+" + WeightFormatting.formatVolume(delta, unitSystem, strings.locale)
        RecordType.MAX_REPS -> "+" + formatRepCount(strings, delta)
    }

private fun formatRepCount(strings: UiStrings, reps: Double): String =
    WeightFormatting.formatNumber(reps.roundToInt().toDouble(), maxFractionDigits = 0, locale = strings.locale)

/**
 * What the record value leaves out about its set: the reps of a weight record ("× 5") and the
 * load of a reps record ("bei 60 kg", with [addedLoad] "bei Körpergewicht + 10 kg", nothing at
 * bodyweight). Estimate and volume have none.
 */
internal fun formatRecordContext(
    strings: UiStrings,
    type: RecordType,
    set: WorkoutSet?,
    unitSystem: UnitSystem,
    addedLoad: Boolean = false
): String? {
    set ?: return null
    return when (type) {
        RecordType.MAX_WEIGHT -> strings.get(WorkoutR.string.workout_records_context_reps, set.reps)
        RecordType.MAX_REPS -> set.weightKg.takeIf { it > 0.0 }?.let {
            strings.get(WorkoutR.string.workout_records_context_weight, strings.setLoadText(it, unitSystem, addedLoad))
        }
        RecordType.MAX_E1RM, RecordType.MAX_VOLUME -> null
    }
}

/**
 * The heaviest set of an exercise trained for the first time, for "Erste Bestwerte":
 * "80 kg × 8 Wdh", at bodyweight "Körpergewicht × 12 Wdh", with [addedLoad]
 * "Körpergewicht + 10 kg × 8 Wdh".
 */
internal fun formatFirstBestSet(strings: UiStrings, set: WorkoutSet, unitSystem: UnitSystem, addedLoad: Boolean = false): String {
    val repsUnit = strings.getQuantity(R.plurals.common_reps_unit, set.reps)
    if (set.weightKg == 0.0 || addedLoad) {
        return strings.get(
            WorkoutR.string.workout_bodyweight_set_value,
            strings.setLoadText(set.weightKg, unitSystem, addedLoad),
            set.reps,
            repsUnit
        )
    }
    return strings.get(
        WorkoutR.string.workout_weighted_set_value,
        WeightFormatting.formatWeightNumber(set.weightKg, unitSystem, strings.locale),
        WeightFormatting.unitLabel(unitSystem),
        set.reps,
        repsUnit
    )
}

/**
 * Title of the live notice: "2 neue Rekorde · Bankdrücken". When the set also reached a round
 * mark, the mark leads instead: "100-kg-Marke · Kniebeuge (Langhantel)".
 */
internal fun liveRecordTitle(strings: UiStrings, event: WorkoutEvent.NewRecords): String =
    event.mark?.let { mark ->
        strings.get(R.string.recognition_title_with_detail, strings.markTitle(mark), event.exerciseName)
    } ?: strings.getQuantity(WorkoutR.plurals.workout_record_live_title, event.records.size, event.records.size, event.exerciseName)

/**
 * What TalkBack reads for a record row, as one sentence: "Gewicht 100 kg × 5, 2,5 kg mehr als
 * bisher". A reps row speaks the count in full words instead of the label, "12 Wiederholungen
 * bei 60 kg, 2 mehr als bisher". [delta] is null when the exercise had no value of this type
 * ("…, zum ersten Mal"). With [addedLoad] the weight reads as on screen, "Gewicht
 * Körpergewicht + 12,5 kg × 9, 2,5 kg mehr als bisher".
 */
internal fun recordRowDescription(
    strings: UiStrings,
    type: RecordType,
    value: Double,
    context: String?,
    delta: Double?,
    unitSystem: UnitSystem,
    addedLoad: Boolean = false
): String {
    if (type == RecordType.MAX_REPS) {
        val reps = value.roundToInt()
        val spoken = listOfNotNull(strings.getQuantity(WorkoutR.plurals.workout_records_reps_cd, reps, reps), context)
            .joinToString(" ")
        val gain = delta?.roundToInt() ?: return strings.get(WorkoutR.string.workout_records_reps_row_first_cd, spoken)
        return strings.getQuantity(WorkoutR.plurals.workout_records_reps_row_cd, gain, spoken, gain)
    }
    val label = strings.get(recordLabelRes(type))
    val valueWithContext = listOfNotNull(formatRecordValue(strings, type, value, unitSystem, addedLoad), context).joinToString(" ")
    delta ?: return strings.get(WorkoutR.string.workout_records_row_first_cd, label, valueWithContext)
    return strings.get(
        WorkoutR.string.workout_records_row_cd,
        label,
        valueWithContext,
        formatRecordValue(strings, type, delta, unitSystem)
    )
}

/**
 * Detail of the live notice, the first record only: "Gewicht 102,5 kg (+2,5 kg)". A reps record
 * names its unit in place of the label, "12 Wdh (+2)". The weight of an added-load exercise
 * needs no label either, its load says it: "Körpergewicht + 12,5 kg (+2,5 kg)".
 */
internal fun liveRecordDetail(strings: UiStrings, record: LiveRecordUi, unitSystem: UnitSystem): String {
    val delta = formatRecordDelta(strings, record.type, record.value - record.previous, unitSystem)
    if (record.type == RecordType.MAX_REPS) {
        val reps = record.value.roundToInt()
        return strings.get(
            WorkoutR.string.workout_record_live_detail_unlabeled,
            strings.getQuantity(R.plurals.common_record_reps, reps, reps),
            delta
        )
    }
    if (record.type == RecordType.MAX_WEIGHT && record.addedLoad) {
        return strings.get(
            WorkoutR.string.workout_record_live_detail_unlabeled,
            formatRecordValue(strings, record.type, record.value, unitSystem, addedLoad = true),
            delta
        )
    }
    return strings.get(
        WorkoutR.string.workout_record_live_detail,
        strings.get(recordLabelRes(record.type)),
        formatRecordValue(strings, record.type, record.value, unitSystem),
        delta
    )
}
