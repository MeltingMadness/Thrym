package com.thrym.app.presentation.workout

import androidx.lifecycle.ViewModel
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.RecordType
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.WorkoutPlanTarget
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.util.RpeAutoregulation
import com.thrym.shared.readinessdata.SetIntention
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDateTime

sealed interface WorkoutExerciseKey {
    data class Planned(val snapshotId: Long) : WorkoutExerciseKey
    data class AdHoc(val exerciseId: Long) : WorkoutExerciseKey
}

data class PreviousExerciseSessionUi(
    val sessionId: Long,
    val sessionStart: LocalDateTime,
    val sets: List<WorkoutSet>,
    val lastWorkSetWeightKg: Double?,
    val lastWorkSetReachedTarget: Boolean = false
)

data class ExerciseWithSets(
    val key: WorkoutExerciseKey,
    val exercise: Exercise,
    val sets: List<WorkoutSet>,
    val planTarget: WorkoutPlanTarget? = null,
    val originalPlanTarget: WorkoutPlanTarget? = null,
    val previousSession: PreviousExerciseSessionUi? = null
) {
    val supersetGroupId: Int?
        get() = planTarget?.supersetGroupId
}

sealed interface ActiveWorkoutSessionPhase {
    data object Loading : ActiveWorkoutSessionPhase
    data class Active(val session: WorkoutSession) : ActiveWorkoutSessionPhase
    data object Missing : ActiveWorkoutSessionPhase
}

sealed interface WorkoutFinishState {
    data object Idle : WorkoutFinishState
    data object Completing : WorkoutFinishState
    data object Generating : WorkoutFinishState
    data class ReviewReady(val sessionId: Long) : WorkoutFinishState
    data object CompletedWithoutReview : WorkoutFinishState
    data class GenerationFailed(val sessionId: Long, val message: String) : WorkoutFinishState
}

data class RestTimerUi(
    val startTime: Instant,
    val durationSeconds: Int
) {
    /**
     * Countdown shifted by [deltaSeconds] ("−15 s" / "+30 s"). A shortened timer never ends
     * before now: it ends at the latest right away. Elapsed-time timers (duration 0) have no
     * end and stay unchanged.
     */
    fun adjustedBy(deltaSeconds: Int, now: Instant): RestTimerUi {
        if (durationSeconds <= 0) return this
        val elapsedSeconds = java.time.Duration.between(startTime, now).seconds.coerceAtLeast(0L)
        val minimum = maxOf(1L, elapsedSeconds)
        val adjusted = (durationSeconds.toLong() + deltaSeconds).coerceAtLeast(minimum)
        return copy(durationSeconds = adjusted.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}

/**
 * Intra-session autoregulation hint for the next work set of an exercise,
 * derived from the RPE of the last logged work set (see [RpeAutoregulation]).
 */
data class NextSetRecommendationUi(
    val recommendedWeightKg: Double,
    val lastWeightKg: Double,
    val lastRpe: Double,
    val targetRpe: Double?,
    val backoffPercent: Double = RpeAutoregulation.DEFAULT_BACKOFF_PERCENT
) {
    val isOvershoot: Boolean
        get() = lastRpe >= RpeAutoregulation.OVERSHOOT_RPE

    /** Dedicated backoff-set load; only meaningful after an overshoot. */
    val backoffWeightKg: Double?
        get() = if (isOvershoot) {
            RpeAutoregulation.backoffSetWeightKg(lastWeightKg, backoffPercent)
        } else {
            null
        }
}

/**
 * Describes the last failed mutation so the UI can offer a real retry without
 * storing composable callbacks inside the ViewModel.
 */
sealed interface WorkoutRetryDescriptor {
    data class LogSet(
        val key: WorkoutExerciseKey,
        val exerciseId: Long,
        val reps: Int,
        val weightKg: Double,
        val setType: SetType,
        val intensity: String,
        val submissionId: Long,
        val intention: SetIntention
    ) : WorkoutRetryDescriptor

    data class UpdateSet(
        val setId: Long,
        val reps: Int,
        val weightKg: Double,
        val intensity: String,
        val intention: SetIntention?
    ) : WorkoutRetryDescriptor

    data class DeleteSet(val setId: Long) : WorkoutRetryDescriptor

    data class FinishWorkout(val discardEmptySession: Boolean) : WorkoutRetryDescriptor
}

data class WorkoutErrorUi(
    val message: String,
    val retry: WorkoutRetryDescriptor?,
    val id: Long
)

data class ActiveWorkoutUiState(
    val sessionPhase: ActiveWorkoutSessionPhase = ActiveWorkoutSessionPhase.Loading,
    /** All exercises flat in the display order of this session, the same order as [exerciseGroups]. */
    val exercisesWithSets: List<ExerciseWithSets> = emptyList(),
    /** Single exercises and superset blocks in the display order of this session. */
    val exerciseGroups: List<ExerciseRenderGroup> = emptyList(),
    val showExercisePicker: Boolean = false,
    val showFinishDialog: Boolean = false,
    val restTimers: Map<WorkoutExerciseKey, RestTimerUi> = emptyMap(),
    val nextSetRecommendations: Map<WorkoutExerciseKey, NextSetRecommendationUi> = emptyMap(),
    val error: WorkoutErrorUi? = null,
    val logInFlightByExercise: Map<WorkoutExerciseKey, Int> = emptyMap(),
    val logSuccessSubmissions: Set<Long> = emptySet(),
    val updateInFlightBySet: Map<Long, Int> = emptyMap(),
    val updateSuccessCountBySet: Map<Long, Int> = emptyMap(),
    /**
     * Why each logged set ended, keyed by durable set id. A set without a record is
     * [SetIntention.UNKNOWN]; the map never invents an answer for legacy data.
     */
    val setIntentions: Map<Long, SetIntention> = emptyMap(),
    /**
     * True once the stored intentions were read at least once. While false the picker must
     * not present [SetIntention.UNKNOWN] as if it were a stored answer.
     */
    val setIntentionsLoaded: Boolean = false,
    /**
     * True when the readiness side channel could not be read. The failure is surfaced in
     * the UI and never crashes the view model scope.
     */
    val setIntentionsFailed: Boolean = false,
    val finishState: WorkoutFinishState = WorkoutFinishState.Idle
)

internal data class ActiveWorkoutChromeState(
    val showExercisePicker: Boolean = false,
    val showFinishDialog: Boolean = false,
    val restTimers: Map<WorkoutExerciseKey, RestTimerUi> = emptyMap(),
    val error: WorkoutErrorUi? = null
)

internal data class OperationUiState(
    val logInFlightByExercise: Map<WorkoutExerciseKey, Int> = emptyMap(),
    val logSuccessSubmissions: Set<Long> = emptySet(),
    val updateInFlightBySet: Map<Long, Int> = emptyMap(),
    val updateSuccessCountBySet: Map<Long, Int> = emptyMap(),
    val finishState: WorkoutFinishState = WorkoutFinishState.Idle
)

sealed class WorkoutEvent {
    /**
     * The records one log or edit beat, in the shared type order, so the UI shows them as a
     * single message. Only records that had an earlier best come in, see `SessionRecords`.
     */
    data class NewRecords(val exerciseName: String, val records: List<LiveRecordUi>) : WorkoutEvent()

    /** A set was deleted; the UI offers to undo it. */
    data class SetDeleted(val setNumber: Int) : WorkoutEvent()
}

/** A record beaten during the workout: the new [value] and the best before this workout, in kg or reps. */
data class LiveRecordUi(
    val type: RecordType,
    val value: Double,
    val previous: Double
)
