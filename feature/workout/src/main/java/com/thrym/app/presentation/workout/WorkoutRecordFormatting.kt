package com.thrym.app.presentation.workout

import androidx.annotation.StringRes
import com.thrym.app.domain.model.RecordType
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.UiStrings
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import com.thrym.shared.workout.RecordSetInput
import java.time.ZoneId
import kotlin.math.roundToInt

/*
 * Texts of the workout records, for the record board on the completion screen and the live
 * notice. Records are stored in kg (volume in kg × reps, reps as a count) and converted only
 * here. The functions take [UiStrings] instead of a composition, so the snackbar code can use
 * them too.
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
 * "4.320 kg" for volume, "12 Wdh". For a gain it gives the unsigned form the spoken row uses
 * ("2,5 kg mehr als bisher").
 */
internal fun formatRecordValue(strings: UiStrings, type: RecordType, value: Double, unitSystem: UnitSystem): String =
    when (type) {
        RecordType.MAX_WEIGHT, RecordType.MAX_E1RM -> WeightFormatting.formatWeight(value, unitSystem, strings.locale)
        RecordType.MAX_VOLUME -> WeightFormatting.formatVolume(value, unitSystem, strings.locale)
        RecordType.MAX_REPS -> value.roundToInt().let { strings.getQuantity(R.plurals.common_record_reps, it, it) }
    }

/** The gain over the earlier best, signed: "+2,5 kg", "+1,25 kg", "+5,5 lb", "+310 kg", "+2 Wdh". */
internal fun formatRecordDelta(strings: UiStrings, type: RecordType, delta: Double, unitSystem: UnitSystem): String =
    when (type) {
        RecordType.MAX_WEIGHT, RecordType.MAX_E1RM -> WeightFormatting.formatWeightDelta(delta, unitSystem, strings.locale)
        RecordType.MAX_VOLUME -> "+" + WeightFormatting.formatVolume(delta, unitSystem, strings.locale)
        RecordType.MAX_REPS -> delta.roundToInt().let { strings.getQuantity(WorkoutR.plurals.workout_records_delta_reps, it, it) }
    }

/**
 * What the record value leaves out about its set: the reps of a weight record ("× 5") and the
 * load of a reps record ("bei 60 kg", nothing at bodyweight). Estimate and volume have none.
 */
internal fun formatRecordContext(strings: UiStrings, type: RecordType, set: WorkoutSet?, unitSystem: UnitSystem): String? {
    set ?: return null
    return when (type) {
        RecordType.MAX_WEIGHT -> strings.get(WorkoutR.string.workout_records_context_reps, set.reps)
        RecordType.MAX_REPS -> set.weightKg.takeIf { it > 0.0 }?.let {
            strings.get(WorkoutR.string.workout_records_context_weight, WeightFormatting.formatWeight(it, unitSystem, strings.locale))
        }
        RecordType.MAX_E1RM, RecordType.MAX_VOLUME -> null
    }
}

/** Title of the live notice: "2 neue Rekorde · Bankdrücken". */
internal fun liveRecordTitle(strings: UiStrings, event: WorkoutEvent.NewRecords): String =
    strings.getQuantity(WorkoutR.plurals.workout_record_live_title, event.records.size, event.records.size, event.exerciseName)

/** Detail of the live notice, the first record only: "Gewicht 102,5 kg (+2,5 kg)". */
internal fun liveRecordDetail(strings: UiStrings, record: LiveRecordUi, unitSystem: UnitSystem): String =
    strings.get(
        WorkoutR.string.workout_record_live_detail,
        strings.get(recordLabelRes(record.type)),
        formatRecordValue(strings, record.type, record.value, unitSystem),
        formatRecordDelta(strings, record.type, record.value - record.previous, unitSystem)
    )
