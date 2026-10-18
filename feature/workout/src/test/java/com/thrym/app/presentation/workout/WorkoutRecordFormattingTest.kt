package com.thrym.app.presentation.workout

import com.thrym.app.domain.model.RecordType
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.presentation.common.UiStrings
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutRecordFormattingTest {

    private val german = RecordingStrings(Locale.GERMAN)
    private val english = RecordingStrings(Locale.US)

    @Test
    fun `weights keep two decimals only for quarter steps and pounds one`() {
        assertEquals("100 kg", formatRecordValue(german, RecordType.MAX_WEIGHT, 100.0, UnitSystem.METRIC))
        assertEquals("82,25 kg", formatRecordValue(german, RecordType.MAX_WEIGHT, 82.25, UnitSystem.METRIC))
        assertEquals("82.25 kg", formatRecordValue(english, RecordType.MAX_WEIGHT, 82.25, UnitSystem.METRIC))
        // An estimate is no plate step and keeps one decimal.
        assertEquals("116,7 kg", formatRecordValue(german, RecordType.MAX_E1RM, 100.0 * (1 + 5 / 30.0), UnitSystem.METRIC))
        assertEquals("220.5 lb", formatRecordValue(english, RecordType.MAX_WEIGHT, 100.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun `weight gains are signed in the user's unit`() {
        assertEquals("+2,5 kg", formatRecordDelta(german, RecordType.MAX_WEIGHT, 2.5, UnitSystem.METRIC))
        assertEquals("+1,25 kg", formatRecordDelta(german, RecordType.MAX_WEIGHT, 1.25, UnitSystem.METRIC))
        assertEquals("+1.25 kg", formatRecordDelta(english, RecordType.MAX_WEIGHT, 1.25, UnitSystem.METRIC))
        assertEquals("+2,9 kg", formatRecordDelta(german, RecordType.MAX_E1RM, 116.6667 - 113.75, UnitSystem.METRIC))
        assertEquals("+5,5 lb", formatRecordDelta(german, RecordType.MAX_WEIGHT, 2.5, UnitSystem.IMPERIAL))
    }

    @Test
    fun `volume has no decimals but grouping`() {
        assertEquals("4.320 kg", formatRecordValue(german, RecordType.MAX_VOLUME, 4320.4, UnitSystem.METRIC))
        assertEquals("4,320 kg", formatRecordValue(english, RecordType.MAX_VOLUME, 4320.4, UnitSystem.METRIC))
        assertEquals("+310 kg", formatRecordDelta(german, RecordType.MAX_VOLUME, 310.0, UnitSystem.METRIC))
        assertEquals("+683 lb", formatRecordDelta(english, RecordType.MAX_VOLUME, 310.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun `a reps record is the bare count and its gain has no unit`() {
        // The label column says "Wdh", so the value and the chip leave the unit out.
        assertEquals("12", formatRecordValue(german, RecordType.MAX_REPS, 12.0, UnitSystem.METRIC))
        assertEquals("1.000", formatRecordValue(german, RecordType.MAX_REPS, 1000.0, UnitSystem.METRIC))
        assertEquals("+2", formatRecordDelta(german, RecordType.MAX_REPS, 2.0, UnitSystem.METRIC))
        assertEquals("+2", formatRecordDelta(english, RecordType.MAX_REPS, 2.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun `a spoken reps row names the count in words instead of the label`() {
        val context = "bei 60 kg"
        assertEquals(
            "plurals:${WorkoutR.plurals.workout_records_reps_row_cd}:2:" +
                "[plurals:${WorkoutR.plurals.workout_records_reps_cd}:12:[12] bei 60 kg, 2]",
            recordRowDescription(german, RecordType.MAX_REPS, 12.0, context, 2.0, UnitSystem.METRIC)
        )
        // One more than before picks the singular; bodyweight has no load to name.
        assertEquals(
            "plurals:${WorkoutR.plurals.workout_records_reps_row_cd}:1:" +
                "[plurals:${WorkoutR.plurals.workout_records_reps_cd}:1:[1], 1]",
            recordRowDescription(german, RecordType.MAX_REPS, 1.0, null, 1.0, UnitSystem.METRIC)
        )
        assertEquals(
            "string:${WorkoutR.string.workout_records_reps_row_first_cd}:" +
                "[plurals:${WorkoutR.plurals.workout_records_reps_cd}:12:[12] bei 60 kg]",
            recordRowDescription(german, RecordType.MAX_REPS, 12.0, context, null, UnitSystem.METRIC)
        )
    }

    @Test
    fun `a spoken weight row keeps its label and the unsigned gain`() {
        val label = "string:${WorkoutR.string.workout_records_label_weight}:[]"
        assertEquals(
            "string:${WorkoutR.string.workout_records_row_cd}:[$label, 100 kg × 5, 2,5 kg]",
            recordRowDescription(german, RecordType.MAX_WEIGHT, 100.0, "× 5", 2.5, UnitSystem.METRIC)
        )
        assertEquals(
            "string:${WorkoutR.string.workout_records_row_first_cd}:[string:${WorkoutR.string.workout_records_label_volume}:[], 4.320 kg]",
            recordRowDescription(german, RecordType.MAX_VOLUME, 4320.0, null, null, UnitSystem.METRIC)
        )
    }

    @Test
    fun `context gives the reps of a weight record and the load of a reps record`() {
        val set = set(weightKg = 60.0, reps = 5)
        assertEquals(
            "string:${WorkoutR.string.workout_records_context_reps}:[5]",
            formatRecordContext(german, RecordType.MAX_WEIGHT, set, UnitSystem.METRIC)
        )
        assertEquals(
            "string:${WorkoutR.string.workout_records_context_weight}:[132.3 lb]",
            formatRecordContext(english, RecordType.MAX_REPS, set, UnitSystem.IMPERIAL)
        )
        // Bodyweight, estimate and volume have no context.
        assertNull(formatRecordContext(german, RecordType.MAX_REPS, set(weightKg = 0.0, reps = 12), UnitSystem.METRIC))
        assertNull(formatRecordContext(german, RecordType.MAX_E1RM, set, UnitSystem.METRIC))
        assertNull(formatRecordContext(german, RecordType.MAX_VOLUME, set, UnitSystem.METRIC))
        assertNull(formatRecordContext(german, RecordType.MAX_WEIGHT, null, UnitSystem.METRIC))
    }

    @Test
    fun `the live notice counts the records and details the first one`() {
        val event = WorkoutEvent.NewRecords(
            exerciseName = "Bankdrücken",
            records = listOf(
                LiveRecordUi(RecordType.MAX_WEIGHT, value = 102.5, previous = 100.0),
                LiveRecordUi(RecordType.MAX_E1RM, value = 119.6, previous = 116.7)
            )
        )
        assertEquals(
            "plurals:${WorkoutR.plurals.workout_record_live_title}:2:[2, Bankdrücken]",
            liveRecordTitle(german, event)
        )
        assertEquals(
            "string:${WorkoutR.string.workout_record_live_detail}:" +
                "[string:${WorkoutR.string.workout_records_label_weight}:[], 102,5 kg, +2,5 kg]",
            liveRecordDetail(german, event.records.first(), UnitSystem.METRIC)
        )
    }

    @Test
    fun `the live notice names the reps with their unit in place of the label`() {
        assertEquals(
            "string:${WorkoutR.string.workout_record_live_detail_reps}:" +
                "[plurals:${R.plurals.common_record_reps}:12:[12], +2]",
            liveRecordDetail(german, LiveRecordUi(RecordType.MAX_REPS, value = 12.0, previous = 10.0), UnitSystem.METRIC)
        )
    }

    @Test
    fun `a set becomes a record input with its stored type code and epoch time`() {
        val completedAt = LocalDateTime.of(2026, 10, 18, 9, 30)
        val input = set(weightKg = 50.0, reps = 10, type = SetType.WARMUP, completedAt = completedAt)
            .toRecordInput(sessionFinished = true)
        assertEquals("WARMUP", input.setType)
        assertEquals(completedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), input.completedAtEpochMillis)
        assertEquals(true, input.sessionFinished)
        assertEquals(listOf(7L, 3L, 4L, 50.0, 10), listOf(input.id, input.sessionId, input.exerciseId, input.weightKg, input.reps))
        assertEquals(RecordType.MAX_E1RM, recordTypeOf("MAX_E1RM"))
        assertNull(recordTypeOf("MAX_SOMETHING"))
    }

    private fun set(
        weightKg: Double,
        reps: Int,
        type: SetType = SetType.NORMAL,
        completedAt: LocalDateTime = LocalDateTime.of(2026, 10, 18, 9, 0)
    ) = WorkoutSet(
        id = 7L,
        sessionId = 3L,
        exerciseId = 4L,
        setNumber = 1,
        reps = reps,
        weightKg = weightKg,
        setType = type,
        completedAt = completedAt
    )

    /** Records which resource was asked for with which arguments; numbers follow [locale]. */
    private class RecordingStrings(override val locale: Locale) : UiStrings {
        override fun get(resourceId: Int, vararg args: Any): String = "string:$resourceId:${args.toList()}"
        override fun getQuantity(resourceId: Int, quantity: Int, vararg args: Any): String =
            "plurals:$resourceId:$quantity:${args.toList()}"
    }
}
