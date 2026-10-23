package com.thrym.shared.store

import com.thrym.shared.backup.BackupPayloadV1
import com.thrym.shared.backup.BackupPayloadValidator
import com.thrym.shared.backup.BackupPlanExercise
import com.thrym.shared.backup.BackupProgressionConfig
import com.thrym.shared.backup.BackupProgressionSuggestion
import com.thrym.shared.backup.BackupProgressionTarget
import com.thrym.shared.backup.BackupWorkoutPlanTarget
import com.thrym.shared.backup.BackupWorkoutSet
import com.thrym.shared.backup.CURRENT_BACKUP_SCHEMA_VERSION
import com.thrym.shared.backup.NORMAL_SET_TYPE
import com.thrym.shared.backup.WARMUP_SET_TYPE
import com.thrym.shared.progression.isProgressionWorkSet
import com.thrym.shared.progression.isSuccessfulProgression
import com.thrym.shared.progression.BackupProgressionFacade
import com.thrym.shared.progression.BackupProgressionHistory
import com.thrym.shared.progression.ProgressionAutoApply
import com.thrym.shared.progression.ProgressionOutcomeType
import com.thrym.shared.progression.ProgressionReasonCode
import com.thrym.shared.progression.ProgressionStreakEffect
import com.thrym.shared.progression.SharedProgressionConfigValidator
import com.thrym.shared.progression.ProgressionConfigInput
import com.thrym.shared.progression.ProgressionTargetInput
import kotlin.math.abs
import kotlin.time.Clock

private const val WEIGHT_TOLERANCE_KG = 0.1

/** The result of evaluating one completed session. */
data class ProgressionGenerationResult(
    val insertedCount: Int = 0,
    val reviewItemCount: Int = 0,
    val pendingCount: Int = 0,
)

/** Result of an explicit user decision in the progression review. */
sealed interface ProgressionDecisionResult {
    data class Accepted(val suggestionIds: Set<Long>) : ProgressionDecisionResult

    data class Rejected(val suggestionIds: Set<Long>) : ProgressionDecisionResult

    data class Stale(val suggestionIds: Set<Long>) : ProgressionDecisionResult

    data class Invalid(val message: String) : ProgressionDecisionResult
}

/**
 * Transactional lifecycle for progression outcomes stored in [SharedStateStore].
 *
 * The service deliberately stores the complete backup DTO graph.  A generated outcome copies the
 * immutable workout target, progression configuration and evidence IDs into its suggestion row.
 * Generation therefore never changes a plan.  Only an explicit acceptance updates the current
 * plan exercise, and that update is made in the same transaction as the decision row.
 */
class ProgressionLifecycle(
    private val store: SharedStateStore,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /**
     * Generates missing outcomes in completion order.  This is used after import and as a retry
     * sweep; already decided or informational rows are left untouched except for the narrowly
     * defined legacy repair below.
     */
    suspend fun generateMissingOutcomes(): Int {
        var inserted = 0
        store.transact { initial ->
            var current = initial
            val sessions = completedPlanSessions(current)
                .filter { sessionNeedsGeneration(current, it.id) }
            for (session in sessions) {
                val generated = generateForSession(current, session)
                current = generated.payload
                inserted += generated.insertedCount
            }
            expireOutdatedHistory(current)
        }
        return inserted
    }

    /**
     * Generates the requested completed session after catching up older completed sessions.  The
     * catch-up is intentionally ordered by `(endTime, id)` so a delayed retry cannot change a
     * later failure streak.  The returned counts describe the requested session, matching the
     * Android repository contract; older catch-up inserts are intentionally not mixed into it.
     */
    suspend fun generateOutcomesForSession(sessionId: Long): ProgressionGenerationResult {
        var result = ProgressionGenerationResult()
        store.transact { initial ->
            val requested = initial.workoutSessions.firstOrNull { it.id == sessionId }
            if (requested == null || requested.endTime == null || requested.planId == null) {
                return@transact initial
            }

            var current = initial
            val requestedEnd = requireNotNull(requested.endTime)
            val older = completedPlanSessions(initial)
                .filter { candidate ->
                    candidate.id != sessionId &&
                        candidate.endTime != null &&
                        (candidate.endTime!! < requestedEnd ||
                            (candidate.endTime == requestedEnd && candidate.id < sessionId)) &&
                        sessionNeedsGeneration(current, candidate.id)
                }

            for (session in older) {
                val generated = generateForSession(current, session)
                current = generated.payload
            }

            val generated = generateForSession(current, requested)
            current = expireOutdatedHistory(generated.payload)
            result = generationResultForSession(current, sessionId, generated.insertedCount)
            current
        }
        return result
    }

    /** Alias used by Swift-facing callers that describe the operation by its trigger. */
    suspend fun generateForFinishedSession(sessionId: Long): ProgressionGenerationResult =
        generateOutcomesForSession(sessionId)

    /**
     * Marks pending suggestions stale when the current plan row no longer equals their immutable
     * source target/configuration, or completed work has made their advice obsolete.
     * Accepted, rejected, informational and already stale rows are never rewritten.
     */
    suspend fun reconcileOutstandingSuggestions(): Set<Long> {
        val staleIds = linkedSetOf<Long>()
        store.transact { state ->
            val now = { nowEpochMillis().coerceAtLeast(0L) }
            val updated = state.progressionSuggestions.map { suggestion ->
                if (suggestion.status == STATUS_PENDING &&
                    (!currentPlanMatches(state, suggestion) || hasOutdatedHistory(state, suggestion))
                ) {
                    staleIds += suggestion.id
                    suggestion.copy(
                        status = STATUS_STALE,
                        decidedAtEpochMillis = now(),
                    )
                } else {
                    suggestion
                }
            }
            state.copy(progressionSuggestions = updated)
        }
        return staleIds
    }

    /**
     * Accepts one or more user-confirmed targets atomically.  Invalid values and stale rows leave
     * all selected rows and plan rows unchanged, except that stale selected rows are marked stale
     * as an explicit concurrency result.  Older pending rows for an accepted plan position are
     * superseded as stale so a later review cannot apply an obsolete history entry.
     */
    suspend fun acceptSuggestions(
        finalTargetsBySuggestionId: Map<Long, BackupProgressionTarget>,
    ): ProgressionDecisionResult {
        var result: ProgressionDecisionResult = ProgressionDecisionResult.Invalid(
            "Select at least one suggestion",
        )
        store.transact { state ->
            if (finalTargetsBySuggestionId.isEmpty()) {
                return@transact state
            }

            val selectedIds = finalTargetsBySuggestionId.keys
            val selected = state.progressionSuggestions.filter { it.id in selectedIds }
            if (selected.size != selectedIds.size ||
                selected.any { it.status != STATUS_PENDING }
            ) {
                result = ProgressionDecisionResult.Invalid(
                    "Every selected suggestion must still be PENDING",
                )
                return@transact state
            }
            if (selected.any {
                    it.outcomeType != ProgressionOutcomeType.PROPOSE_CHANGE.name ||
                        it.suggestedTarget == null
                }
            ) {
                result = ProgressionDecisionResult.Invalid(
                    "Every selected suggestion must contain a proposed target",
                )
                return@transact state
            }
            if (selected.groupBy { it.planId to (it.exerciseId to it.orderIndex) }
                    .any { it.value.size > 1 }
            ) {
                result = ProgressionDecisionResult.Invalid(
                    "Select only one suggestion per plan position",
                )
                return@transact state
            }

            val staleIds = selected.filter { suggestion ->
                !currentPlanMatches(state, suggestion) || hasOutdatedHistory(state, suggestion)
            }.mapTo(linkedSetOf()) { it.id }
            if (staleIds.isNotEmpty()) {
                result = ProgressionDecisionResult.Stale(staleIds)
                return@transact state.copy(
                    progressionSuggestions = state.progressionSuggestions.map { suggestion ->
                        if (suggestion.id in staleIds) {
                            suggestion.copy(
                                status = STATUS_STALE,
                                decidedAtEpochMillis = nowEpochMillis().coerceAtLeast(0L),
                            )
                        } else {
                            suggestion
                        }
                    },
                )
            }

            val validationErrors = selected.flatMap { suggestion ->
                val finalTarget = requireNotNull(finalTargetsBySuggestionId[suggestion.id])
                validateFinalTarget(state, suggestion, finalTarget)
            }.distinct()
            if (validationErrors.isNotEmpty()) {
                result = ProgressionDecisionResult.Invalid(validationErrors.joinToString("; "))
                return@transact state
            }

            var updated = state
            val acceptedIds = linkedSetOf<Long>()
            for (suggestion in selected) {
                val finalTarget = requireNotNull(finalTargetsBySuggestionId[suggestion.id])
                val proposedTarget = requireNotNull(suggestion.suggestedTarget)
                val decisionTime = nowEpochMillis().coerceAtLeast(0L)
                val updatedSuggestions = updated.progressionSuggestions.map { candidate ->
                    when {
                        candidate.id == suggestion.id -> candidate.copy(
                            status = STATUS_ACCEPTED,
                            wasEdited = finalTarget != proposedTarget,
                            finalTarget = finalTarget,
                            decidedAtEpochMillis = decisionTime,
                        )

                        candidate.status == STATUS_PENDING &&
                            samePlanPosition(candidate, suggestion) -> candidate.copy(
                            status = STATUS_STALE,
                            decidedAtEpochMillis = decisionTime,
                        )

                        else -> candidate
                    }
                }
                val currentPlan = findCurrentPlanExercise(updated, suggestion)
                    ?: error("Plan exercise disappeared during progression acceptance")
                updated = updated.copy(
                    planExercises = updated.planExercises.map { planExercise ->
                        if (planExercise.id == currentPlan.id) {
                            planExercise.copy(
                                targetSets = finalTarget.sets,
                                targetReps = finalTarget.reps,
                                targetWeightKg = finalTarget.weightKg,
                            )
                        } else {
                            planExercise
                        }
                    },
                    progressionSuggestions = updatedSuggestions,
                )
                acceptedIds += suggestion.id
            }
            result = ProgressionDecisionResult.Accepted(acceptedIds)
            updated
        }
        return result
    }

    suspend fun acceptSuggestion(
        suggestionId: Long,
        finalTarget: BackupProgressionTarget,
    ): ProgressionDecisionResult = acceptSuggestions(mapOf(suggestionId to finalTarget))

    /**
     * With "Steigerungen automatisch übernehmen" on: accepts the pending increases of [sessionId]
     * ([ProgressionAutoApply]) as "Alle sicheren übernehmen" would, one per plan position. A stale
     * or invalid selection changes no plan row; those rows then wait in the review as usual.
     */
    suspend fun autoApplySafeIncreases(sessionId: Long): Set<Long> {
        val selected = store.snapshot().progressionSuggestions
            .filter { suggestion ->
                suggestion.sourceSessionId == sessionId &&
                    suggestion.status == STATUS_PENDING &&
                    suggestion.suggestedTarget != null &&
                    ProgressionAutoApply.isSafeIncrease(suggestion.outcomeType, suggestion.reasonCode)
            }
            .distinctBy { Triple(it.planId, it.exerciseId, it.orderIndex) }
        if (selected.isEmpty()) return emptySet()
        val result = acceptSuggestions(selected.associate { it.id to requireNotNull(it.suggestedTarget) })
        return (result as? ProgressionDecisionResult.Accepted)?.suggestionIds.orEmpty()
    }

    /** Rejects a pending suggestion without ever touching its current plan row. */
    suspend fun rejectSuggestion(suggestionId: Long): ProgressionDecisionResult {
        var result: ProgressionDecisionResult = ProgressionDecisionResult.Invalid(
            "Progression suggestion $suggestionId does not exist or is no longer pending",
        )
        store.transact { state ->
            val suggestion = state.progressionSuggestions.firstOrNull { it.id == suggestionId }
            if (suggestion == null || suggestion.status != STATUS_PENDING) {
                return@transact state
            }
            result = ProgressionDecisionResult.Rejected(setOf(suggestionId))
            state.copy(
                progressionSuggestions = state.progressionSuggestions.map {
                    if (it.id == suggestionId) {
                        it.copy(
                            status = STATUS_REJECTED,
                            decidedAtEpochMillis = nowEpochMillis().coerceAtLeast(0L),
                        )
                    } else {
                        it
                    }
                },
            )
        }
        return result
    }

    private fun completedPlanSessions(state: BackupPayloadV1) = state.workoutSessions
        .filter { it.endTime != null && it.planId != null && it.isDeload != true }
        .sortedWith(compareBy({ it.endTime }, { it.id }))

    private fun sessionNeedsGeneration(state: BackupPayloadV1, sessionId: Long): Boolean {
        val targets = state.workoutPlanTargets
            .filter { it.sessionId == sessionId }
        return targets.any { target ->
            if (target.progression.scheme == SCHEME_MANUAL) return@any false
            val rows = state.progressionSuggestions.filter {
                it.sourceTargetSnapshotId == target.id &&
                    it.sourceProgression.ruleRevision == target.progression.ruleRevision
            }
            when (rows.size) {
                0 -> true
                1 -> isLegacyRepairCandidate(
                    state = state,
                    row = rows.single(),
                    source = target,
                    setsForTarget = state.workoutSets.filter { set ->
                        set.sessionId == target.sessionId &&
                            set.planTargetSnapshotId == target.id
                    },
                )
                else -> error("Multiple progression suggestions for target ${target.id}")
            }
        }
    }

    private fun generateForSession(
        state: BackupPayloadV1,
        session: com.thrym.shared.backup.BackupWorkoutSession,
    ): GeneratedSession {
        if (session.endTime == null || session.planId == null || session.isDeload == true) {
            return GeneratedSession(state, 0)
        }

        val targets = state.workoutPlanTargets
            .filter { it.sessionId == session.id }
            .sortedBy { it.orderIndex }
        val targetsById = targets.associateBy { it.id }
        if (targetsById.size != targets.size) {
            error("Duplicate progression target snapshot in session ${session.id}")
        }

        val sessionSets = state.workoutSets.filter { it.sessionId == session.id }
        sessionSets.forEach { set ->
            val snapshotId = set.planTargetSnapshotId ?: return@forEach
            val target = targetsById[snapshotId]
                ?: error("Dangling progression target snapshot $snapshotId for workout set ${set.id}")
            check(
                target.sessionId == set.sessionId && target.exerciseId == set.exerciseId,
            ) {
                "Workout set ${set.id} is cross-linked to progression target $snapshotId"
            }
        }

        var current = state
        var insertedCount = 0
        for (target in targets) {
            if (target.progression.scheme == SCHEME_MANUAL) continue

            val setsForTarget = sessionSets.filter { it.planTargetSnapshotId == target.id }
            val previousOutcomes = loadPreviousComparableOutcomes(
                state = current,
                target = target,
                sourceEndTime = requireNotNull(session.endTime),
                sourceSessionId = session.id,
                currentSets = setsForTarget,
            )
            val outcome = BackupProgressionFacade.evaluate(
                target = target,
                sets = setsForTarget,
                previousOutcomesNewestFirst = previousOutcomes,
            )
            validateOutcomeProvenance(outcome, target, setsForTarget)

            val existing = current.progressionSuggestions.filter {
                it.sourceTargetSnapshotId == target.id &&
                    it.sourceProgression.ruleRevision == target.progression.ruleRevision
            }
            if (existing.size > 1) {
                error("Multiple progression suggestions for target ${target.id}")
            }
            if (existing.size == 1) {
                val repaired = repairLegacySuggestionIfSafe(
                    state = current,
                    row = existing.single(),
                    source = target,
                    outcome = outcome,
                    setsForTarget = setsForTarget,
                )
                if (repaired != null) current = repaired
                continue
            }

            if (outcome.outcomeType == ProgressionOutcomeType.NOT_APPLICABLE) continue
            val status = if (outcome.outcomeType == ProgressionOutcomeType.PROPOSE_CHANGE) {
                STATUS_PENDING
            } else {
                STATUS_INFORMATIONAL
            }
            val suggestion = BackupProgressionSuggestion(
                id = nextId(current.progressionSuggestions.map { it.id }),
                sourceSessionId = target.sessionId,
                sourceTargetSnapshotId = target.id,
                planId = target.planId,
                exerciseId = target.exerciseId,
                orderIndex = target.orderIndex,
                supersetGroupId = target.supersetGroupId,
                sourceTarget = target.target,
                sourceProgression = target.progression,
                outcomeType = outcome.outcomeType.name,
                reasonCode = outcome.reasonCode.name,
                reasonArguments = outcome.reasonArguments.toCanonicalReasonArguments(),
                countedSetIds = outcome.countedSetIds,
                streakEffect = outcome.streakEffect.name,
                suggestedTarget = outcome.proposedTarget,
                status = status,
                createdAtEpochMillis = nowEpochMillis().coerceAtLeast(0L),
            )
            current = current.copy(
                progressionSuggestions = current.progressionSuggestions + suggestion,
            )
            insertedCount += 1
        }
        return GeneratedSession(current, insertedCount)
    }

    private fun generationResultForSession(
        state: BackupPayloadV1,
        sessionId: Long,
        insertedCount: Int,
    ): ProgressionGenerationResult {
        val pending = state.progressionSuggestions.count {
            it.sourceSessionId == sessionId && it.status == STATUS_PENDING
        }
        return ProgressionGenerationResult(
            insertedCount = insertedCount,
            reviewItemCount = pending,
            pendingCount = pending,
        )
    }

    private fun loadPreviousComparableOutcomes(
        state: BackupPayloadV1,
        target: BackupWorkoutPlanTarget,
        sourceEndTime: Long,
        sourceSessionId: Long,
        currentSets: List<BackupWorkoutSet>,
    ): List<BackupProgressionHistory> {
        val previousTargets = state.workoutPlanTargets
            .filter { previous ->
                previous.id != target.id &&
                    previous.planId == target.planId &&
                    previous.exerciseId == target.exerciseId &&
                    previous.orderIndex == target.orderIndex &&
                    previous.sessionId != sourceSessionId
            }
            .mapNotNull { previous ->
                val session = state.workoutSessions.firstOrNull { it.id == previous.sessionId }
                    ?: return@mapNotNull null
                if (session.isDeload == true) return@mapNotNull null
                val end = session.endTime ?: return@mapNotNull null
                if (end < sourceEndTime || (end == sourceEndTime && session.id < sourceSessionId)) {
                    PreviousTarget(previous, end, session.id)
                } else {
                    null
                }
            }
            .sortedWith(compareByDescending<PreviousTarget> { it.endTime }.thenByDescending { it.sessionId })
            .map { it.target }
            .takeWhile { previous ->
                previous.target == target.target && previous.progression == target.progression
            }

        if (previousTargets.isEmpty()) return emptyList()
        val validatedRows = previousTargets.map { previous ->
            val rows = state.progressionSuggestions.filter {
                it.sourceTargetSnapshotId == previous.id &&
                    it.sourceProgression.ruleRevision == previous.progression.ruleRevision
            }
            check(rows.size == 1) {
                "Expected one progression outcome for target ${previous.id} at revision " +
                    previous.progression.ruleRevision
            }
            val row = rows.single()
            check(row.hasExactSource(previous)) {
                "Progression outcome ${row.id} source differs from target ${previous.id}"
            }
            previous to row
        }

        // Android evaluates the current actual load only after validating the complete
        // comparable history. This preserves fail-closed provenance checks even when the
        // current session has malformed or non-uniform weights.
        val currentActualWeight = evaluatedActualWeight(currentSets, target.target.sets, target.progression.ruleRevision)
            ?: return emptyList()
        val outcomes = mutableListOf<BackupProgressionHistory>()
        for ((previous, row) in validatedRows) {
            val previousActualWeight = persistedActualWeight(state, row, previous)
                ?: break
            if (abs(previousActualWeight - currentActualWeight) > WEIGHT_TOLERANCE_KG) break
            outcomes += BackupProgressionHistory(
                streakEffect = enumValueOf<ProgressionStreakEffect>(row.streakEffect),
                sourceTarget = row.sourceTarget,
                successful = isSuccessfulProgression(row.reasonCode, row.streakEffect),
            )
        }
        return outcomes
    }

    private fun validateOutcomeProvenance(
        outcome: com.thrym.shared.progression.BackupProgressionResult,
        source: BackupWorkoutPlanTarget,
        setsForTarget: List<BackupWorkoutSet>,
    ) {
        check(outcome.sourceTarget == source.target) {
            "Engine outcome source does not match progression target ${source.id}"
        }
        val evidenceIds = outcome.countedSetIds
        check(evidenceIds.all { it > 0L } && evidenceIds.distinct().size == evidenceIds.size) {
            "Engine evidence for progression target ${source.id} must contain positive unique set ids"
        }
        val setsById = setsForTarget.groupBy { it.id }
        check(evidenceIds.all { setsById[it]?.size == 1 }) {
            "Engine evidence does not belong uniquely to progression target ${source.id}"
        }

        val orderedWorkSets = setsForTarget
            .filter { isProgressionWorkSet(it.resolvedSetType(), source.progression.ruleRevision) }
            .sortedWith(compareBy({ it.setNumber }, { it.completedAt }, { it.id }))
        val availableEvidenceIds = orderedWorkSets.map { it.id }.filter { it > 0L }.distinct()
        fun countedEvidenceIds(): List<Long> = orderedWorkSets.take(source.target.sets).map { it.id }

        val expected = when (outcome.outcomeType) {
            ProgressionOutcomeType.PROPOSE_CHANGE,
            ProgressionOutcomeType.KEEP_TARGET -> countedEvidenceIds()
            ProgressionOutcomeType.INSUFFICIENT_DATA -> when (outcome.reasonCode) {
                ProgressionReasonCode.CONFIG_INVALID,
                ProgressionReasonCode.RULE_REVISION_UNSUPPORTED,
                ProgressionReasonCode.TOO_FEW_WORK_SETS,
                ProgressionReasonCode.SET_NUMBER_INVALID,
                ProgressionReasonCode.SET_VALUE_INVALID -> availableEvidenceIds
                ProgressionReasonCode.MANUAL_WEIGHT_DEVIATION,
                ProgressionReasonCode.RPE_MISSING,
                ProgressionReasonCode.RPE_INVALID -> countedEvidenceIds()
                else -> error(
                    "Unsupported insufficient-data evidence contract ${outcome.reasonCode} " +
                        "for progression target ${source.id}",
                )
            }
            ProgressionOutcomeType.NOT_APPLICABLE -> emptyList()
        }
        check(evidenceIds == expected) {
            "Engine evidence order or completeness differs from progression target ${source.id}"
        }
        if (outcome.outcomeType == ProgressionOutcomeType.PROPOSE_CHANGE) {
            check(outcome.proposedTarget != null) {
                "Proposed progression outcome ${source.id} has no target"
            }
        } else {
            check(outcome.proposedTarget == null) {
                "Non-proposed progression outcome ${source.id} has a proposed target"
            }
        }
    }

    private fun repairLegacySuggestionIfSafe(
        state: BackupPayloadV1,
        row: BackupProgressionSuggestion,
        source: BackupWorkoutPlanTarget,
        outcome: com.thrym.shared.progression.BackupProgressionResult,
        setsForTarget: List<BackupWorkoutSet>,
    ): BackupPayloadV1? {
        if (!isLegacyRepairCandidate(state, row, source, setsForTarget)) return null
        if (outcome.countedSetIds != row.countedSetIds) return null
        return state.copy(
            progressionSuggestions = state.progressionSuggestions.map { candidate ->
                if (candidate.id != row.id) {
                    candidate
                } else {
                    candidate.copy(
                        outcomeType = outcome.outcomeType.name,
                        reasonCode = outcome.reasonCode.name,
                        reasonArguments = outcome.reasonArguments.toCanonicalReasonArguments(),
                        streakEffect = outcome.streakEffect.name,
                        suggestedTarget = outcome.proposedTarget,
                        status = when (outcome.outcomeType) {
                            ProgressionOutcomeType.PROPOSE_CHANGE -> STATUS_PENDING
                            else -> STATUS_INFORMATIONAL
                        },
                    )
                }
            },
        )
    }

    private fun isLegacyRepairCandidate(
        state: BackupPayloadV1,
        row: BackupProgressionSuggestion,
        source: BackupWorkoutPlanTarget,
        setsForTarget: List<BackupWorkoutSet>,
    ): Boolean {
        if (row.status != STATUS_INFORMATIONAL || row.wasEdited) return false
        if (row.decidedAtEpochMillis != null || row.finalTarget != null || row.suggestedTarget != null) {
            return false
        }
        if (row.sourceTargetSnapshotId != source.id ||
            row.sourceTarget.weightKg != 0.0 ||
            source.target.weightKg != 0.0 ||
            !row.hasExactSource(source)
        ) {
            return false
        }
        val legacyKind = when {
            row.outcomeType == ProgressionOutcomeType.INSUFFICIENT_DATA.name &&
                row.reasonCode == ProgressionReasonCode.MANUAL_WEIGHT_DEVIATION.name -> true
            row.outcomeType == ProgressionOutcomeType.KEEP_TARGET.name &&
                row.reasonCode == ProgressionReasonCode.REPEAT_TARGET.name ->
                "actualWeightKg" !in row.reasonArguments
            else -> false
        }
        if (!legacyKind || source.target.sets <= 0) return false

        val normalSets = setsForTarget
            .filter { it.resolvedSetType() == NORMAL_SET_TYPE }
            .sortedWith(compareBy({ it.setNumber }, { it.completedAt }, { it.id }))
        val countedSets = normalSets.take(source.target.sets)
        if (countedSets.size != source.target.sets || countedSets.any { it.id <= 0L }) return false
        if (countedSets.any { !it.weightKg.isFinite() || it.weightKg <= 0.0 }) return false
        val actualWeight = countedSets.first().weightKg
        if (countedSets.drop(1).any { abs(it.weightKg - actualWeight) > WEIGHT_TOLERANCE_KG }) {
            return false
        }
        if (countedSets.map { it.id } != row.countedSetIds) return false

        if (row.reasonCode == ProgressionReasonCode.MANUAL_WEIGHT_DEVIATION.name) {
            val expected = row.reasonArguments["expectedWeightKg"] ?: return false
            val recorded = row.reasonArguments["actualWeightKg"] ?: return false
            if (expected != source.target.weightKg || abs(recorded - actualWeight) > WEIGHT_TOLERANCE_KG) {
                return false
            }
        }
        return state.workoutPlanTargets.any { it.id == source.id }
    }

    private fun persistedActualWeight(
        state: BackupPayloadV1,
        row: BackupProgressionSuggestion,
        target: BackupWorkoutPlanTarget,
    ): Double? {
        if (target.target.sets <= 0 ||
            row.countedSetIds.size != target.target.sets ||
            row.countedSetIds.distinct().size != row.countedSetIds.size
        ) return null
        val setsById = state.workoutSets.associateBy { it.id }
        val sets = row.countedSetIds.map { setsById[it] ?: return null }
        if (sets.any { set ->
                set.id <= 0L ||
                    !isProgressionWorkSet(set.resolvedSetType(), target.progression.ruleRevision) ||
                    set.sessionId != target.sessionId ||
                    set.exerciseId != target.exerciseId ||
                    set.planTargetSnapshotId != target.id ||
                    !set.weightKg.isFinite() ||
                    set.weightKg < 0.0
            }
        ) return null
        val ordered = sets.sortedWith(compareBy({ it.setNumber }, { it.completedAt }, { it.id }))
        if (ordered.map { it.id } != row.countedSetIds) return null
        if (target.progression.ruleRevision == 2) return ordered.minOf { it.weightKg }
        val actual = ordered.first().weightKg
        return actual.takeIf { ordered.drop(1).all { set -> abs(set.weightKg - actual) <= WEIGHT_TOLERANCE_KG } }
    }

    private fun evaluatedActualWeight(
        sets: List<BackupWorkoutSet>,
        targetSets: Int,
        ruleRevision: Int,
    ): Double? {
        if (targetSets <= 0) return null
        val counted = sets
            .filter { isProgressionWorkSet(it.resolvedSetType(), ruleRevision) }
            .sortedWith(compareBy({ it.setNumber }, { it.completedAt }, { it.id }))
            .take(targetSets)
        if (counted.size != targetSets ||
            counted.any { it.id <= 0L } ||
            counted.map { it.id }.distinct().size != counted.size ||
            counted.any { !it.weightKg.isFinite() || it.weightKg < 0.0 }
        ) return null
        if (ruleRevision == 2) return counted.minOf { it.weightKg }
        val actual = counted.first().weightKg
        return actual.takeIf { counted.drop(1).all { set -> abs(set.weightKg - actual) <= WEIGHT_TOLERANCE_KG } }
    }

    private fun validateFinalTarget(
        state: BackupPayloadV1,
        suggestion: BackupProgressionSuggestion,
        finalTarget: BackupProgressionTarget,
    ): List<String> {
        val targetInput = ProgressionTargetInput(
            sets = finalTarget.sets,
            reps = finalTarget.reps,
            weightKg = finalTarget.weightKg,
        )
        val config = suggestion.sourceProgression.toProgressionConfigInput()
        val errors = SharedProgressionConfigValidator.validationErrors(targetInput, config).toMutableList()

        // The backup validator contains the stricter schema checks (manual revision/default
        // policy and cross-field requirements). Validate a candidate graph without publishing it.
        val current = findCurrentPlanExercise(state, suggestion)
        if (current == null) {
            errors += "planExercise"
        } else {
            val candidate = state.copy(
                planExercises = state.planExercises.map { row ->
                    if (row.id == current.id) {
                        row.copy(
                            targetSets = finalTarget.sets,
                            targetReps = finalTarget.reps,
                            targetWeightKg = finalTarget.weightKg,
                        )
                    } else {
                        row
                    }
                },
            )
            val schemaErrors = BackupPayloadValidator
                .validate(candidate, CURRENT_BACKUP_SCHEMA_VERSION)
                .errors
            errors += schemaErrors.filter {
                it.contains("plan exercise ${current.id}", ignoreCase = true)
            }
        }
        return errors.distinct()
    }

    private fun BackupProgressionConfig.toProgressionConfigInput() = ProgressionConfigInput(
        scheme = scheme,
        incrementValue = incrementValue,
        incrementUnit = incrementUnit,
        incrementKg = incrementKg,
        minReps = minReps,
        maxReps = maxReps,
        targetTotalReps = targetTotalReps,
        targetRpe = targetRpe,
        rpeTolerance = rpeTolerance,
        stallThreshold = stallThreshold,
        backoffPercent = backoffPercent,
        ruleRevision = ruleRevision,
        successThreshold = successThreshold,
    )

    /**
     * Keeps exported reason arguments deterministic without relying on JVM-only sorted-map APIs.
     * Backup JSON preserves insertion order, so a linked map is required here.
     */
    private fun Map<String, Double>.toCanonicalReasonArguments(): Map<String, Double> =
        linkedMapOf<String, Double>().also { canonical ->
            entries.sortedBy { it.key }.forEach { (key, value) -> canonical[key] = value }
        }

    private fun currentPlanMatches(
        state: BackupPayloadV1,
        suggestion: BackupProgressionSuggestion,
    ): Boolean = findCurrentPlanExercise(state, suggestion)?.let { current ->
        current.setTargets.isEmpty() && current.targetSets == suggestion.sourceTarget.sets &&
            current.targetReps == suggestion.sourceTarget.reps &&
            current.targetWeightKg == suggestion.sourceTarget.weightKg &&
            current.progression == suggestion.sourceProgression
    } == true

    private fun findCurrentPlanExercise(
        state: BackupPayloadV1,
        suggestion: BackupProgressionSuggestion,
    ): BackupPlanExercise? = state.planExercises.singleOrNull { current ->
        current.planId == suggestion.planId &&
            current.exerciseId == suggestion.exerciseId &&
            current.orderIndex == suggestion.orderIndex &&
            current.supersetGroupId == suggestion.supersetGroupId
    }

    private fun expireOutdatedHistory(state: BackupPayloadV1): BackupPayloadV1 = state.copy(
        progressionSuggestions = state.progressionSuggestions.map { suggestion ->
            if (suggestion.status == STATUS_PENDING && hasOutdatedHistory(state, suggestion)) {
                suggestion.copy(status = STATUS_STALE, decidedAtEpochMillis = nowEpochMillis().coerceAtLeast(0L))
            } else {
                suggestion
            }
        },
    )

    /** Validity depends on completed work, even if its outcome is missing or informational. */
    private fun hasOutdatedHistory(
        state: BackupPayloadV1,
        suggestion: BackupProgressionSuggestion,
    ): Boolean {
        val sourceSession = state.workoutSessions.firstOrNull { it.id == suggestion.sourceSessionId }
            ?: return true
        val sourceEnd = sourceSession.endTime ?: return true
        if (sourceSession.isDeload == true) return true
        val newerWork = state.workoutPlanTargets.any { target ->
            target.planId == suggestion.planId && target.exerciseId == suggestion.exerciseId &&
                target.orderIndex == suggestion.orderIndex &&
                isSessionNewer(state, target.sessionId, sourceEnd, suggestion.sourceSessionId) &&
                state.workoutSets.any { set ->
                    set.planTargetSnapshotId == target.id && set.sessionId == target.sessionId &&
                        set.exerciseId == target.exerciseId && set.resolvedSetType() != WARMUP_SET_TYPE
                }
        }
        if (newerWork) return true
        return state.progressionSuggestions.any { other ->
            other.id != suggestion.id &&
                other.status == STATUS_PENDING &&
                samePlanPosition(other, suggestion) &&
                isSessionNewer(state, other.sourceSessionId, sourceEnd, suggestion.sourceSessionId)
        }
    }

    private fun isSessionNewer(
        state: BackupPayloadV1,
        candidateSessionId: Long,
        sourceEnd: Long,
        sourceSessionId: Long,
    ): Boolean {
        val candidate = state.workoutSessions.firstOrNull { it.id == candidateSessionId } ?: return false
        val candidateEnd = candidate.endTime ?: return false
        if (candidate.isDeload == true) return false
        return candidateEnd > sourceEnd || (candidateEnd == sourceEnd && candidate.id > sourceSessionId)
    }

    private fun samePlanPosition(
        left: BackupProgressionSuggestion,
        right: BackupProgressionSuggestion,
    ): Boolean = left.planId == right.planId &&
        left.exerciseId == right.exerciseId &&
        left.orderIndex == right.orderIndex

    private fun BackupProgressionSuggestion.hasExactSource(target: BackupWorkoutPlanTarget): Boolean =
        sourceTargetSnapshotId == target.id &&
            sourceSessionId == target.sessionId &&
            planId == target.planId &&
            exerciseId == target.exerciseId &&
            orderIndex == target.orderIndex &&
            supersetGroupId == target.supersetGroupId &&
            sourceTarget == target.target &&
            sourceProgression == target.progression

    private data class GeneratedSession(
        val payload: BackupPayloadV1,
        val insertedCount: Int,
    )

    private data class PreviousTarget(
        val target: BackupWorkoutPlanTarget,
        val endTime: Long,
        val sessionId: Long,
    )

    private fun nextId(ids: Collection<Long>): Long {
        val maximum = ids.maxOrNull() ?: 0L
        check(maximum < Long.MAX_VALUE) { "No free progression suggestion id remains" }
        return maximum + 1L
    }

    private companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_STALE = "STALE"
        const val STATUS_INFORMATIONAL = "INFORMATIONAL"
        const val SCHEME_MANUAL = "MANUAL"
    }
}
