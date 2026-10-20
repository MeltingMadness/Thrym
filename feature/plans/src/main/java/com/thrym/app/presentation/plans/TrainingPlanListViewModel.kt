package com.thrym.app.presentation.plans

import com.thrym.app.presentation.common.UiStrings
import com.thrym.feature.plans.R as UxR
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrym.app.domain.error.toAppError
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.TrainingPlan
import com.thrym.app.domain.repository.ExerciseRepository
import com.thrym.app.domain.repository.TrainingPlanRepository
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.app.presentation.common.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class PlanListItem(
    val plan: TrainingPlan,
    /**
     * The plan's exercises in order, `null` for one that no longer exists. Names are resolved
     * in the UI, so a runtime language switch shows at once.
     */
    val exercises: List<Exercise?>,
    /** Days since the last completed session of this plan, `null` if never trained. */
    val lastDoneDaysAgo: Long? = null
)

data class PlanListUiState(
    val plans: List<PlanListItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class TrainingPlanListViewModel(
    private val planRepository: TrainingPlanRepository,
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
    private val strings: UiStrings
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanListUiState())
    val uiState: StateFlow<PlanListUiState> = _uiState

    init {
        loadPlans()
    }

    private fun loadPlans() {
        viewModelScope.launch {
            combine(
                planRepository.getAllPlans(),
                exerciseRepository.getAllExercises(),
                workoutRepository.observeLastSessionPerPlan()
            ) { plans, exercises, lastSessions ->
                val exerciseById = exercises.associateBy { it.id }
                val lastStartByPlanId = lastSessions.associateBy({ it.planId }, { it.lastStartTime })
                val today = LocalDate.now()
                plans.map { plan ->
                    PlanListItem(
                        plan = plan,
                        exercises = plan.exercises.map { exerciseById[it.exerciseId] },
                        lastDoneDaysAgo = lastStartByPlanId[plan.id]?.let { millis ->
                            ChronoUnit.DAYS.between(
                                Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate(),
                                today
                            )
                        }
                    )
                }
            }.collect { items ->
                _uiState.value = _uiState.value.copy(
                    plans = items,
                    isLoading = false,
                    error = null
                )
            }
        }
    }

    fun deletePlan(planId: Long) {
        viewModelScope.launch {
            try {
                planRepository.deletePlan(planId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.toAppError().toUserMessage(strings.get(UxR.string.plan_action_delete), strings)
                )
            }
        }
    }

    fun startPlanWorkout(plan: TrainingPlan, onSessionCreated: (Long, Long) -> Unit) {
        viewModelScope.launch {
            try {
                val activeSession = workoutRepository.getActiveSession()
                if (activeSession != null) {
                    if (activeSession.planId == plan.id) {
                        onSessionCreated(activeSession.id, plan.id)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            error = strings.get(UxR.string.plan_workout_already_active)
                        )
                    }
                    return@launch
                }

                val sessionId = workoutRepository.startWorkout(
                    name = plan.name,
                    planId = plan.id,
                    metaPlanId = null
                )
                onSessionCreated(sessionId, plan.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.toAppError().toUserMessage(strings.get(UxR.string.plan_action_start), strings)
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
