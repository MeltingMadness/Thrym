package com.thrym.app.presentation.progression

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrym.app.domain.model.ProgressionConfig
import com.thrym.app.domain.model.ProgressionDecisionResult
import com.thrym.app.domain.model.ProgressionOutcome
import com.thrym.app.domain.model.ProgressionReasonCode
import com.thrym.app.domain.model.ProgressionScheme
import com.thrym.app.domain.model.ProgressionSuggestion
import com.thrym.app.domain.model.ProgressionSuggestionStatus
import com.thrym.app.domain.model.ProgressionTarget
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeightStep
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.ExerciseRepository
import com.thrym.app.domain.repository.TrainingPlanRepository
import com.thrym.app.domain.repository.ProgressionRepository
import com.thrym.app.domain.util.WeightFormatting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProgressionReviewItemUi(
    val id: Long,
    val sourceSessionId: Long,
    val planId: Long,
    val exerciseId: Long,
    /** Resolved display name; null when the exercise is unknown to the repository. */
    val exerciseName: String? = null,
    val planName: String? = null,
    val createdAtEpochMillis: Long = 0,
    val orderIndex: Int,
    val scheme: ProgressionScheme,
    val source: ProgressionTarget,
    val proposed: ProgressionTarget?,
    val countedSets: List<WorkoutSet>,
    val reasonCode: ProgressionReasonCode,
    val reasonArguments: Map<String, Double>,
    val status: ProgressionSuggestionStatus,
    val canDecide: Boolean,
    val configuredStep: WeightStep?
)

enum class ProgressionEditField { SETS, REPS, WEIGHT }

enum class ProgressionEditError { INVALID_SETS, INVALID_REPS, INVALID_WEIGHT }

enum class ProgressionReviewMessage { STALE, INVALID, ACTION_FAILED }

data class ProgressionEditDraft(
    val sets: String,
    val reps: String,
    val weight: String,
    val unitSystem: UnitSystem,
    val dirtyFields: Set<ProgressionEditField> = emptySet(),
    val errors: Map<ProgressionEditField, ProgressionEditError> = emptyMap()
)

data class ProgressionReviewUiState(
    val items: List<ProgressionReviewItemUi> = emptyList(),
    val edits: Map<Long, ProgressionEditDraft> = emptyMap(),
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val isWorking: Boolean = false,
    val message: ProgressionReviewMessage? = null,
    val isLoading: Boolean = true,
    val isSessionScoped: Boolean = false
) {
    val decisionItems: List<ProgressionReviewItemUi>
        get() = items.filter { it.canDecide && it.proposed != null }
    val noteItems: List<ProgressionReviewItemUi>
        get() = items.filter { it !in decisionItems && (isSessionScoped || it.status == ProgressionSuggestionStatus.PENDING || it.status == ProgressionSuggestionStatus.INFORMATIONAL) }
    val pastItems: List<ProgressionReviewItemUi>
        get() = if (isSessionScoped) emptyList() else items.filter { it !in decisionItems && (it.status == ProgressionSuggestionStatus.ACCEPTED || it.status == ProgressionSuggestionStatus.REJECTED || it.status == ProgressionSuggestionStatus.STALE) }
    val safePendingItems: List<ProgressionReviewItemUi>
        get() = items.filter { it.canDecide && it.proposed != null }
            .sortedByDescending { it.sourceSessionId }
            .distinctBy { Triple(it.planId, it.exerciseId, it.orderIndex) }
}

class ProgressionReviewViewModel(
    savedStateHandle: SavedStateHandle,
    private val progressionRepository: ProgressionRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val exerciseRepository: ExerciseRepository,
    private val trainingPlanRepository: TrainingPlanRepository
) : ViewModel() {
    private val sessionId = savedStateHandle.get<Long>(SESSION_ID_KEY)?.takeIf { it > 0L }
    private val _uiState = MutableStateFlow(ProgressionReviewUiState(isSessionScoped = sessionId != null))
    val uiState: StateFlow<ProgressionReviewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { progressionRepository.reconcileOutstandingSuggestions() }
                .onFailure { showFailure() }

            // The global review also lists past outcomes below the open ones (iOS parity);
            // a session-scoped review already contains every status of that session.
            val suggestionsFlow = if (sessionId == null) {
                combine(
                    progressionRepository.observeReviewItems(null),
                    progressionRepository.observeRecentDecisions()
                ) { pending, decided -> pending + decided.filterNot { d -> pending.any { it.id == d.id } } }
            } else {
                progressionRepository.observeReviewItems(sessionId)
            }
            combine(
                suggestionsFlow,
                appPreferencesRepository.preferences,
                exerciseRepository.getAllExercises(),
                trainingPlanRepository.getAllPlans()
            ) { suggestions, preferences, exercises, plans ->
                val exerciseNames = exercises.associate { it.id to it.name }
                val planNames = plans.associate { it.id to it.name }
                suggestions.map { suggestion ->
                    suggestion.toUi(exerciseNames[suggestion.sourceTarget.exerciseId], planNames[suggestion.sourceTarget.planId])
                } to preferences.unitSystem
            }.catch {
                _uiState.update { it.copy(isLoading = false, message = ProgressionReviewMessage.ACTION_FAILED) }
            }.collect { (items, unitSystem) ->
                _uiState.update { current ->
                    current.copy(
                        items = items,
                        isLoading = false,
                        edits = current.edits.filterKeys { id ->
                            items.any { item -> item.id == id && item.canDecide }
                        },
                        unitSystem = unitSystem
                    )
                }
            }
        }
    }

    fun beginEdit(suggestionId: Long) {
        val state = _uiState.value
        val item = state.items.firstOrNull { it.id == suggestionId && it.canDecide } ?: return
        if (suggestionId in state.edits) return
        val draft = item.newDraft(state.unitSystem) ?: return
        _uiState.update { it.copy(edits = it.edits + (suggestionId to draft)) }
    }

    fun updateEdit(
        suggestionId: Long,
        sets: String,
        reps: String,
        weight: String
    ) {
        val state = _uiState.value
        val item = state.items.firstOrNull { it.id == suggestionId && it.canDecide } ?: return
        val currentDraft = state.edits[suggestionId] ?: item.newDraft(state.unitSystem) ?: return
        val original = item.newDraft(currentDraft.unitSystem) ?: return
        val dirtyFields = buildSet {
            if (sets != original.sets) add(ProgressionEditField.SETS)
            if (reps != original.reps) add(ProgressionEditField.REPS)
            if (weight != original.weight) add(ProgressionEditField.WEIGHT)
        }
        val updated = currentDraft.copy(
            sets = sets,
            reps = reps,
            weight = weight,
            dirtyFields = dirtyFields,
            errors = emptyMap()
        )
        _uiState.update { it.copy(edits = it.edits + (suggestionId to updated)) }
    }

    fun dismissEdit(suggestionId: Long) {
        _uiState.update { it.copy(edits = it.edits - suggestionId) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun acceptOne(suggestionId: Long) {
        val state = _uiState.value
        if (state.isWorking) return
        val item = state.items.firstOrNull { it.id == suggestionId && it.canDecide } ?: return
        val proposed = item.proposed ?: return
        val draft = state.edits[suggestionId]
        val finalTarget = if (draft == null) {
            proposed
        } else {
            validateEditedTarget(item, draft) ?: return
        }
        accept(mapOf(suggestionId to finalTarget))
    }

    fun acceptAllSafe() {
        val state = _uiState.value
        // Never silently discard a user's in-progress edit through a bulk action.
        if (state.isWorking || state.edits.isNotEmpty()) return
        val selected = state.safePendingItems.associate { item ->
            item.id to requireNotNull(item.proposed)
        }
        if (selected.isEmpty()) return
        accept(selected)
    }

    fun reject(suggestionId: Long) {
        val state = _uiState.value
        if (state.isWorking || state.items.none { it.id == suggestionId && it.canDecide }) return
        setWorking(true)
        viewModelScope.launch {
            try {
                progressionRepository.rejectSuggestion(suggestionId)
                _uiState.update { it.copy(edits = it.edits - suggestionId) }
            } catch (_: Throwable) {
                showFailure()
            } finally {
                setWorking(false)
            }
        }
    }

    private fun accept(finalTargets: Map<Long, ProgressionTarget>) {
        setWorking(true)
        viewModelScope.launch {
            try {
                when (val result = progressionRepository.acceptSuggestions(finalTargets)) {
                    is ProgressionDecisionResult.Accepted -> {
                        _uiState.update { current ->
                            current.copy(edits = current.edits - result.suggestionIds)
                        }
                        progressionRepository.reconcileOutstandingSuggestions()
                    }
                    is ProgressionDecisionResult.Stale -> {
                        _uiState.update {
                            it.copy(message = ProgressionReviewMessage.STALE)
                        }
                        progressionRepository.reconcileOutstandingSuggestions()
                    }
                    is ProgressionDecisionResult.Invalid -> {
                        _uiState.update { it.copy(message = ProgressionReviewMessage.INVALID) }
                    }
                }
            } catch (_: Throwable) {
                showFailure()
            } finally {
                setWorking(false)
            }
        }
    }

    private fun validateEditedTarget(
        item: ProgressionReviewItemUi,
        draft: ProgressionEditDraft
    ): ProgressionTarget? {
        val proposed = item.proposed ?: return null
        val errors = linkedMapOf<ProgressionEditField, ProgressionEditError>()

        val sets = if (ProgressionEditField.SETS in draft.dirtyFields) {
            draft.sets.toIntOrNull()?.takeIf { it > 0 } ?: run {
                errors[ProgressionEditField.SETS] = ProgressionEditError.INVALID_SETS
                proposed.sets
            }
        } else {
            proposed.sets
        }

        val reps = if (ProgressionEditField.REPS in draft.dirtyFields) {
            draft.reps.toIntOrNull()?.takeIf { it > 0 } ?: run {
                errors[ProgressionEditField.REPS] = ProgressionEditError.INVALID_REPS
                proposed.reps
            }
        } else {
            proposed.reps
        }

        val weightKg = if (ProgressionEditField.WEIGHT in draft.dirtyFields) {
            WeightFormatting.parseInputNumber(draft.weight)
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { WeightFormatting.convertToKg(it, draft.unitSystem) }
                ?: run {
                    errors[ProgressionEditField.WEIGHT] = ProgressionEditError.INVALID_WEIGHT
                    proposed.weightKg
                }
        } else {
            proposed.weightKg
        }

        _uiState.update { current ->
            val latest = current.edits[item.id] ?: draft
            current.copy(edits = current.edits + (item.id to latest.copy(errors = errors)))
        }
        return if (errors.isEmpty()) ProgressionTarget(sets, reps, weightKg) else null
    }

    private fun setWorking(working: Boolean) {
        _uiState.update { it.copy(isWorking = working, message = if (working) null else it.message) }
    }

    private fun showFailure() {
        _uiState.update { it.copy(message = ProgressionReviewMessage.ACTION_FAILED) }
    }

    private fun ProgressionSuggestion.toUi(exerciseName: String?, planName: String?): ProgressionReviewItemUi {
        val proposed = (outcome as? ProgressionOutcome.ProposeChange)?.proposedTarget
        return ProgressionReviewItemUi(
            id = id,
            sourceSessionId = sourceTarget.sessionId,
            planId = sourceTarget.planId,
            exerciseId = sourceTarget.exerciseId,
            exerciseName = exerciseName,
            planName = planName,
            createdAtEpochMillis = createdAtEpochMillis,
            orderIndex = sourceTarget.orderIndex,
            scheme = sourceTarget.config.scheme,
            source = sourceTarget.target,
            proposed = proposed,
            countedSets = countedSets,
            reasonCode = outcome.reasonCode,
            reasonArguments = outcome.reasonArguments,
            status = status,
            canDecide = status == ProgressionSuggestionStatus.PENDING && proposed != null,
            configuredStep = sourceTarget.config.configuredStep()
        )
    }

    private fun ProgressionReviewItemUi.newDraft(unitSystem: UnitSystem): ProgressionEditDraft? {
        val target = proposed ?: return null
        return ProgressionEditDraft(
            sets = target.sets.toString(),
            reps = target.reps.toString(),
            weight = editableWeight(WeightFormatting.convertToDisplay(target.weightKg, unitSystem), unitSystem),
            unitSystem = unitSystem
        )
    }

    private fun ProgressionConfig.configuredStep(): WeightStep? = when (this) {
        is ProgressionConfig.Linear -> step
        is ProgressionConfig.DoubleProgression -> step
        is ProgressionConfig.TotalReps -> step
        is ProgressionConfig.RpeRir -> step
        is ProgressionConfig.Manual,
        is ProgressionConfig.Invalid -> null
    }

    /** Rounded like every other weight field ("220.5" lb, not "220.46226218"), UI decimal separator. */
    private fun editableWeight(displayValue: Double, unitSystem: UnitSystem): String =
        WeightFormatting.formatInputNumber(displayValue, WeightFormatting.weightDecimals(displayValue, unitSystem))

    private companion object {
        const val SESSION_ID_KEY = "sessionId"
    }
}
