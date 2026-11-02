package com.thrym.app.data.repository

import com.thrym.shared.progression.isProgressionWorkSet

import com.thrym.shared.progression.ProgressionAutoApply
import com.thrym.app.data.db.TransactionRunner
import com.thrym.app.data.local.dao.ProgressionDao
import com.thrym.app.data.local.dao.TrainingPlanDao
import com.thrym.app.data.local.dao.WorkoutSessionDao
import com.thrym.app.data.local.dao.WorkoutSetDao
import com.thrym.app.data.local.entity.PlanExerciseEntity
import com.thrym.app.data.local.entity.ProgressionSuggestionEntity
import com.thrym.app.data.local.entity.ProgressionTargetColumns
import com.thrym.app.data.local.entity.WorkoutPlanTargetEntity
import com.thrym.app.domain.model.PreviousProgressionOutcome
import com.thrym.app.domain.model.ProgressionConfig
import com.thrym.app.domain.model.ProgressionContext
import com.thrym.app.domain.model.ProgressionDecisionResult
import com.thrym.app.domain.model.ProgressionGenerationResult
import com.thrym.app.domain.model.ProgressionOutcome
import com.thrym.app.domain.model.ProgressionOutcomeType
import com.thrym.app.domain.model.ProgressionReasonCode
import com.thrym.app.domain.model.ProgressionSuggestion
import com.thrym.app.domain.model.ProgressionSuggestionStatus
import com.thrym.app.domain.model.ProgressionTarget
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.model.WorkoutPlanTarget
import com.thrym.app.domain.progression.ProgressionConfigValidator
import com.thrym.app.domain.progression.ProgressionEngine
import com.thrym.app.domain.repository.ProgressionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.abs

private const val WEIGHT_TOLERANCE_KG = 0.1

class ProgressionRepositoryImpl(
    private val progressionDao: ProgressionDao,
    private val sessionDao: WorkoutSessionDao,
    private val setDao: WorkoutSetDao,
    private val trainingPlanDao: TrainingPlanDao,
    private val engine: ProgressionEngine,
    private val transactionRunner: TransactionRunner,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : ProgressionRepository {
    private val mapper = ProgressionEntityMapper()

    override fun observeTargetsForSession(sessionId: Long): Flow<List<WorkoutPlanTarget>> =
        progressionDao.observeTargetsForSession(sessionId).map { rows -> rows.map(mapper::toDomain) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeReviewItems(sessionId: Long?): Flow<List<ProgressionSuggestion>> {
        val rows = if (sessionId == null) {
            progressionDao.observePendingSuggestions()
        } else {
            progressionDao.observeSuggestionsForSession(sessionId)
        }
        return rows.mapLatest(::hydrate)
    }

    override fun observePendingCount(): Flow<Int> = progressionDao.observePendingCount()

    override fun observeRecentDecisions(limit: Int): Flow<List<ProgressionSuggestion>> =
        progressionDao.observeRecentDecisions(limit).mapLatest(::hydrate)

    override suspend fun generateOutcomesForSession(sessionId: Long): ProgressionGenerationResult {
        val sourceSession = sessionDao.getSessionById(sessionId)
            ?: return EMPTY_GENERATION_RESULT
        val sourceEndTime = sourceSession.endTime ?: return EMPTY_GENERATION_RESULT
        if (sourceSession.planId == null) return EMPTY_GENERATION_RESULT

        progressionDao.getCompletedSessionIdsWithMissingOutcomesBefore(sourceEndTime, sessionId)
            .forEach { missingSessionId -> generateSingleSession(missingSessionId) }
        return generateSingleSession(sessionId)
    }

    override suspend fun generateMissingOutcomes(): Int {
        val sessions = progressionDao.getCompletedSessionIdsWithMissingOutcomes()
        if (sessions.isEmpty()) {
            transactionRunner.runInTransaction { expireOutdatedHistory() }
        }
        return sessions.sumOf { sessionId -> generateSingleSession(sessionId).insertedCount }
    }

    override suspend fun reconcileOutstandingSuggestions(): Set<Long> = transactionRunner.runInTransaction {
        val pendingRows = progressionDao.getPendingSuggestions()
        val staleRows = pendingRows.filter { suggestion ->
            val current = trainingPlanDao.getPlanExerciseAt(
                suggestion.planId,
                suggestion.exerciseId,
                suggestion.orderIndex
            )
            current == null || !current.matches(suggestion) || hasOutdatedHistory(suggestion, pendingRows)
        }
        staleRows.forEach { row ->
            progressionDao.updateSuggestion(
                row.copy(
                    status = ProgressionSuggestionStatus.STALE.name,
                    decidedAtEpochMillis = nowEpochMillis()
                )
            )
        }
        staleRows.mapTo(linkedSetOf()) { it.id }
    }

    override suspend fun acceptSuggestions(
        finalTargetsBySuggestionId: Map<Long, ProgressionTarget>
    ): ProgressionDecisionResult = transactionRunner.runInTransaction {
        if (finalTargetsBySuggestionId.isEmpty()) {
            return@runInTransaction ProgressionDecisionResult.Invalid(
                "Select at least one suggestion"
            )
        }

        val rows = progressionDao.getSuggestionsByIds(finalTargetsBySuggestionId.keys)
        if (rows.size != finalTargetsBySuggestionId.size ||
            rows.any { it.status != ProgressionSuggestionStatus.PENDING.name }
        ) {
            return@runInTransaction ProgressionDecisionResult.Invalid(
                "Every selected suggestion must still be PENDING"
            )
        }
        if (rows.any {
                it.outcomeType != ProgressionOutcomeType.PROPOSE_CHANGE.name ||
                    it.suggestedTarget == null
            }
        ) {
            return@runInTransaction ProgressionDecisionResult.Invalid(
                "Every selected suggestion must contain a proposed target"
            )
        }
        if (rows.groupBy { Triple(it.planId, it.exerciseId, it.orderIndex) }
                .any { it.value.size > 1 }
        ) {
            return@runInTransaction ProgressionDecisionResult.Invalid(
                "Select only one suggestion per plan position"
            )
        }

        val currentRowsBySuggestionId = rows.associate { row ->
            row.id to trainingPlanDao.getPlanExerciseAt(
                row.planId,
                row.exerciseId,
                row.orderIndex
            )
        }
        val staleIds = rows.filterTo(mutableListOf()) { row ->
            val current = currentRowsBySuggestionId[row.id]
            current == null || !current.matches(row)
        }.mapTo(linkedSetOf()) { it.id }
        if (staleIds.isNotEmpty()) {
            rows.filter { it.id in staleIds }.forEach { row ->
                progressionDao.updateSuggestion(
                    row.copy(
                        status = ProgressionSuggestionStatus.STALE.name,
                        decidedAtEpochMillis = nowEpochMillis()
                    )
                )
            }
            return@runInTransaction ProgressionDecisionResult.Stale(staleIds)
        }

        // Newer completed work supersedes the old advice even when its evaluation
        // only says "repeat", was rejected, or has not been generated yet.
        val pendingRows = progressionDao.getPendingSuggestions()
        val supersededIds = rows
            .filter { row -> hasOutdatedHistory(row, pendingRows) }
            .mapTo(linkedSetOf()) { it.id }
        if (supersededIds.isNotEmpty()) {
            rows.filter { it.id in supersededIds }.forEach { row ->
                progressionDao.updateSuggestion(
                    row.copy(
                        status = ProgressionSuggestionStatus.STALE.name,
                        decidedAtEpochMillis = nowEpochMillis()
                    )
                )
            }
            return@runInTransaction ProgressionDecisionResult.Stale(supersededIds)
        }

        val validationErrors = rows.flatMap { row ->
            val finalTarget = requireNotNull(finalTargetsBySuggestionId[row.id])
            ProgressionConfigValidator.validationErrors(
                finalTarget,
                row.sourceProgression.toDomain()
            )
        }.distinct()
        if (validationErrors.isNotEmpty()) {
            return@runInTransaction ProgressionDecisionResult.Invalid(
                validationErrors.joinToString()
            )
        }

        rows.forEach { row ->
            val finalTarget = requireNotNull(finalTargetsBySuggestionId[row.id])
            val current = requireNotNull(currentRowsBySuggestionId[row.id])
            check(
                trainingPlanDao.updatePlanExerciseTargetsById(
                    current.id,
                    finalTarget.sets,
                    finalTarget.reps,
                    finalTarget.weightKg
                ) == 1
            ) { "Expected to update exactly one plan exercise ${current.id}" }
            val proposedTarget = requireNotNull(row.suggestedTarget).toDomain()
            progressionDao.updateSuggestion(
                row.copy(
                    status = ProgressionSuggestionStatus.ACCEPTED.name,
                    wasEdited = finalTarget != proposedTarget,
                    finalTarget = ProgressionTargetColumns.fromDomain(finalTarget),
                    decidedAtEpochMillis = nowEpochMillis()
                )
            )
            // Once the newest row is accepted, older pending rows for that
            // position can no longer be applied independently.  Keep their
            // history visible but mark it stale, matching the shared
            // lifecycle's supersession rule.
            pendingRows
                .filter { candidate ->
                    candidate.id != row.id &&
                        candidate.status == ProgressionSuggestionStatus.PENDING.name &&
                        samePlanPosition(candidate, row)
                }
                .forEach { candidate ->
                    progressionDao.updateSuggestion(
                        candidate.copy(
                            status = ProgressionSuggestionStatus.STALE.name,
                            decidedAtEpochMillis = nowEpochMillis()
                        )
                    )
                }
        }
        ProgressionDecisionResult.Accepted(rows.mapTo(linkedSetOf()) { it.id })
    }

    override suspend fun getAcceptedIncreaseSessionIds(): List<Long> =
        progressionDao.getAcceptedOutcomes()
            .filter { ProgressionAutoApply.isSafeIncrease(it.outcomeType, it.reasonCode) }
            .map { it.sourceSessionId }

    override suspend fun rejectSuggestion(suggestionId: Long) {
        val row = progressionDao.getSuggestionsByIds(setOf(suggestionId)).singleOrNull() ?: return
        if (row.status != ProgressionSuggestionStatus.PENDING.name) return
        progressionDao.updateSuggestion(
            row.copy(
                status = ProgressionSuggestionStatus.REJECTED.name,
                decidedAtEpochMillis = nowEpochMillis()
            )
        )
    }

    /** Called only inside the same transaction as generation or a user decision. */
    private suspend fun expireOutdatedHistory() {
        val pending = progressionDao.getPendingSuggestions()
        pending.filter { hasOutdatedHistory(it, pending) }.forEach { row ->
            progressionDao.updateSuggestion(row.copy(
                status = ProgressionSuggestionStatus.STALE.name,
                decidedAtEpochMillis = nowEpochMillis()
            ))
        }
    }

    private suspend fun hasOutdatedHistory(
        suggestion: ProgressionSuggestionEntity,
        pendingRows: List<ProgressionSuggestionEntity>
    ): Boolean {
        val sourceSession = sessionDao.getSessionById(suggestion.sourceSessionId)
            ?: return true
        val sourceEndTime = sourceSession.endTime ?: return true
        if (sourceSession.isDeload == true) return true
        if (progressionDao.hasNewerCompletedWork(
                suggestion.planId, suggestion.exerciseId, suggestion.orderIndex,
                sourceEndTime, suggestion.sourceSessionId
            )) return true
        return pendingRows.any { candidate ->
            candidate.id != suggestion.id &&
                candidate.status == ProgressionSuggestionStatus.PENDING.name &&
                candidate.planId == suggestion.planId &&
                candidate.exerciseId == suggestion.exerciseId &&
                candidate.orderIndex == suggestion.orderIndex &&
                isNewerSession(candidate.sourceSessionId, sourceEndTime, suggestion.sourceSessionId)
        }
    }

    private suspend fun isNewerSession(
        candidateSessionId: Long,
        sourceEndTime: Long,
        sourceSessionId: Long
    ): Boolean {
        val candidateSession = sessionDao.getSessionById(candidateSessionId) ?: return false
        val candidateEndTime = candidateSession.endTime ?: return false
        if (candidateSession.isDeload == true) return false
        return candidateEndTime > sourceEndTime ||
            (candidateEndTime == sourceEndTime && candidateSession.id > sourceSessionId)
    }

    private fun samePlanPosition(
        left: ProgressionSuggestionEntity,
        right: ProgressionSuggestionEntity
    ): Boolean = left.planId == right.planId &&
        left.exerciseId == right.exerciseId &&
        left.orderIndex == right.orderIndex

    private suspend fun generateSingleSession(sessionId: Long): ProgressionGenerationResult =
        transactionRunner.runInTransaction {
            val session = sessionDao.getSessionById(sessionId)
                ?: return@runInTransaction EMPTY_GENERATION_RESULT
            val sourceEndTime = session.endTime
                ?: return@runInTransaction EMPTY_GENERATION_RESULT
            if (session.planId == null) return@runInTransaction EMPTY_GENERATION_RESULT
            if (session.isDeload == true) {
                expireOutdatedHistory()
                return@runInTransaction EMPTY_GENERATION_RESULT
            }

            val targets = progressionDao.getTargetsForSession(sessionId)
            val sets = setDao.getSetsForSessionList(sessionId)
            val targetsById = targets.associateBy(WorkoutPlanTargetEntity::id)
            check(targetsById.size == targets.size) {
                "Duplicate progression target snapshot id in session $sessionId"
            }
            targets.forEach { target ->
                check(target.id > 0L && target.sessionId == sessionId) {
                    "Progression target ${target.id} does not belong to session $sessionId"
                }
            }
            sets.forEach { set ->
                val snapshotId = set.planTargetSnapshotId ?: return@forEach
                val target = checkNotNull(targetsById[snapshotId]) {
                    "Dangling progression target snapshot $snapshotId for workout set ${set.id}"
                }
                check(
                    set.sessionId == sessionId &&
                        target.sessionId == set.sessionId &&
                        target.exerciseId == set.exerciseId
                ) {
                    "Workout set ${set.id} is cross-linked to progression target $snapshotId"
                }
            }

            val existingRowsByTargetId = if (targets.isEmpty()) {
                emptyMap()
            } else {
                progressionDao.getSuggestionsForTargetIds(targets.map { it.id })
                    .groupBy { it.sourceTargetSnapshotId }
            }
            var insertedCount = 0
            targets.forEach { target ->
                val sourceTarget = mapper.toDomain(target)
                if (sourceTarget.config is ProgressionConfig.Manual) return@forEach

                val setsForTarget = sets
                    .filter { it.planTargetSnapshotId == target.id }
                    .map { it.toDomain() }
                val previousOutcomes = loadPreviousComparableOutcomes(
                    target = target,
                    sourceEndTime = sourceEndTime,
                    sourceSessionId = sessionId,
                    currentSets = setsForTarget
                )
                val outcome = engine.evaluate(
                    ProgressionContext(
                        sourceTarget = sourceTarget,
                        setsForTarget = setsForTarget,
                        previousComparableOutcomesNewestFirst = previousOutcomes
                    )
                )
                require(outcome.sourceTarget == sourceTarget.target) {
                    "Engine outcome source does not match progression target ${target.id}"
                }
                validateEvidenceProvenance(outcome, sourceTarget, setsForTarget)
                val status = when (outcome) {
                    is ProgressionOutcome.ProposeChange -> ProgressionSuggestionStatus.PENDING
                    is ProgressionOutcome.KeepTarget,
                    is ProgressionOutcome.InsufficientData -> ProgressionSuggestionStatus.INFORMATIONAL
                    is ProgressionOutcome.NotApplicable -> null
                }
                if (status != null) {
                    val existingRows = existingRowsByTargetId[target.id].orEmpty()
                    val matchingRows = existingRows.filter { row ->
                        row.sourceProgression.ruleRevision == target.progression.ruleRevision
                    }
                    if (matchingRows.size == 1) {
                        repairLegacySuggestionIfSafe(
                            row = matchingRows.single(),
                            source = target,
                            sourceTarget = sourceTarget,
                            outcome = outcome,
                            status = status,
                            setsForTarget = setsForTarget
                        )
                    } else if (matchingRows.isEmpty()) {
                        val insertedId = progressionDao.insertSuggestion(
                            mapper.toEntity(
                                source = target,
                                outcome = outcome,
                                status = status,
                                createdAtEpochMillis = nowEpochMillis()
                            )
                        )
                        if (insertedId > 0L) insertedCount += 1
                    }
                }
            }

            expireOutdatedHistory()
            val rows = if (targets.isEmpty()) {
                emptyList()
            } else {
                progressionDao.getSuggestionsForTargetIds(targets.map { it.id })
            }
            val statuses = rows.map { enumValueOf<ProgressionSuggestionStatus>(it.status) }
            ProgressionGenerationResult(
                insertedCount = insertedCount,
                reviewItemCount = statuses.count {
                    it == ProgressionSuggestionStatus.PENDING
                },
                pendingCount = statuses.count { it == ProgressionSuggestionStatus.PENDING }
            )
        }

    private fun validateEvidenceProvenance(
        outcome: ProgressionOutcome,
        sourceTarget: WorkoutPlanTarget,
        setsForTarget: List<WorkoutSet>
    ) {
        val evidenceIds = outcome.countedSetIds
        check(evidenceIds.all { it > 0L } && evidenceIds.distinct().size == evidenceIds.size) {
            "Engine evidence for progression target ${sourceTarget.id} must contain positive unique set ids"
        }
        val setsById = setsForTarget.groupBy(WorkoutSet::id)
        check(evidenceIds.all { setsById[it]?.size == 1 }) {
            "Engine evidence does not belong uniquely to progression target ${sourceTarget.id}"
        }

        val orderedWorkSets = setsForTarget
            .filter { isProgressionWorkSet(it.setType.name, sourceTarget.config.ruleRevision) }
            .sortedWith(compareBy(WorkoutSet::setNumber, WorkoutSet::completedAt, WorkoutSet::id))
        val availableEvidenceIds = orderedWorkSets
            .map(WorkoutSet::id)
            .filter { it > 0L }
            .distinct()
        fun countedEvidenceIds(): List<Long> {
            check(sourceTarget.target.sets >= 0) {
                "Engine returned counted evidence for invalid progression target ${sourceTarget.id}"
            }
            return orderedWorkSets.take(sourceTarget.target.sets).map(WorkoutSet::id)
        }

        val expectedEvidenceIds = when (outcome) {
            is ProgressionOutcome.ProposeChange,
            is ProgressionOutcome.KeepTarget -> countedEvidenceIds()
            is ProgressionOutcome.InsufficientData -> when (outcome.reasonCode) {
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
                        "for progression target ${sourceTarget.id}"
                )
            }
            is ProgressionOutcome.NotApplicable -> emptyList()
        }
        check(evidenceIds == expectedEvidenceIds) {
            "Engine evidence order or completeness differs from progression target ${sourceTarget.id}"
        }
    }

    /**
     * Repairs only the two legacy outcomes produced by the old weight gate.
     *
     * These rows are deliberately updated in place. Their row id, source
     * snapshot and persisted evidence are historical provenance and must stay
     * stable. Any ambiguity in that provenance makes the repair a no-op.
     */
    private suspend fun repairLegacySuggestionIfSafe(
        row: ProgressionSuggestionEntity,
        source: WorkoutPlanTargetEntity,
        sourceTarget: WorkoutPlanTarget,
        outcome: ProgressionOutcome,
        status: ProgressionSuggestionStatus,
        setsForTarget: List<WorkoutSet>
    ) {
        if (!isLegacyRepairCandidate(row, source, sourceTarget, setsForTarget)) return

        val rewritten = runCatching {
            mapper.toEntity(
                source = source,
                outcome = outcome,
                status = status,
                createdAtEpochMillis = row.createdAtEpochMillis
            )
        }.getOrNull() ?: return

        // Do not silently replace the evidence captured by the old result.
        // The current engine must reach the same counted sets before a row is
        // eligible for an in-place repair.
        if (rewritten.countedSetIdsJson != row.countedSetIdsJson) return

        progressionDao.updateSuggestion(
            row.copy(
                outcomeType = rewritten.outcomeType,
                reasonCode = rewritten.reasonCode,
                reasonArgumentsJson = rewritten.reasonArgumentsJson,
                streakEffect = rewritten.streakEffect,
                suggestedTarget = rewritten.suggestedTarget,
                status = rewritten.status
            )
        )
    }

    private fun isLegacyRepairCandidate(
        row: ProgressionSuggestionEntity,
        source: WorkoutPlanTargetEntity,
        sourceTarget: WorkoutPlanTarget,
        setsForTarget: List<WorkoutSet>
    ): Boolean {
        if (row.status != ProgressionSuggestionStatus.INFORMATIONAL.name || row.wasEdited) {
            return false
        }
        if (row.decidedAtEpochMillis != null || row.finalTarget != null || row.suggestedTarget != null) {
            return false
        }
        if (row.sourceTargetSnapshotId != source.id ||
            row.sourceTarget.weightKg != 0.0 ||
            sourceTarget.target.weightKg != 0.0 ||
            !row.hasExactDuplicatedSource(source)
        ) {
            return false
        }

        val legacyKind = when {
            row.outcomeType == ProgressionOutcomeType.INSUFFICIENT_DATA.name &&
                row.reasonCode == ProgressionReasonCode.MANUAL_WEIGHT_DEVIATION.name -> true

            row.outcomeType == ProgressionOutcomeType.KEEP_TARGET.name &&
                row.reasonCode == ProgressionReasonCode.REPEAT_TARGET.name -> {
                // A current REPEAT_TARGET records its actual load. Rows from
                // the old target-weight path do not and are the only repeat
                // rows that may be re-evaluated.
                reasonArgumentsWithoutActualWeight(row)
            }

            else -> false
        }
        if (!legacyKind) return false
        if (sourceTarget.target.sets <= 0) return false

        val normalSets = setsForTarget
            .filter { it.setType == SetType.NORMAL }
            .sortedWith(compareBy(WorkoutSet::setNumber, WorkoutSet::completedAt, WorkoutSet::id))
        val countedSets = normalSets.take(sourceTarget.target.sets)
        if (countedSets.size != sourceTarget.target.sets || countedSets.any { it.id <= 0L }) return false
        if (countedSets.any { !it.weightKg.isFinite() || it.weightKg <= 0.0 }) return false
        val actualWeight = countedSets.first().weightKg
        if (countedSets.drop(1).any { abs(it.weightKg - actualWeight) > WEIGHT_TOLERANCE_KG }) {
            return false
        }

        val countedIds = runCatching { mapper.decodeCountedSetIds(row) }.getOrNull() ?: return false
        val expectedIds = countedSets.map(WorkoutSet::id)
        if (countedIds != expectedIds) return false

        if (legacyKind && row.reasonCode == ProgressionReasonCode.MANUAL_WEIGHT_DEVIATION.name) {
            val arguments = runCatching {
                REPAIR_JSON.decodeFromString<Map<String, Double>>(row.reasonArgumentsJson)
            }.getOrNull() ?: return false
            val expectedWeightKg = arguments["expectedWeightKg"] ?: return false
            val recordedActualWeightKg = arguments["actualWeightKg"] ?: return false
            if (expectedWeightKg != sourceTarget.target.weightKg ||
                abs(recordedActualWeightKg - actualWeight) > WEIGHT_TOLERANCE_KG
            ) {
                return false
            }
        }
        return true
    }

    private fun reasonArgumentsWithoutActualWeight(row: ProgressionSuggestionEntity): Boolean {
        val arguments = runCatching {
            REPAIR_JSON.decodeFromString<Map<String, Double>>(row.reasonArgumentsJson)
        }.getOrNull() ?: return false
        return "actualWeightKg" !in arguments
    }

    private suspend fun loadPreviousComparableOutcomes(
        target: WorkoutPlanTargetEntity,
        sourceEndTime: Long,
        sourceSessionId: Long,
        currentSets: List<WorkoutSet>
    ): List<PreviousProgressionOutcome> {
        val comparableTargets = progressionDao.getPreviousTargets(
            planId = target.planId,
            exerciseId = target.exerciseId,
            orderIndex = target.orderIndex,
            sourceEndTime = sourceEndTime,
            sourceSessionId = sourceSessionId
        ).takeWhile { previous ->
            previous.target == target.target && previous.progression == target.progression
        }
        if (comparableTargets.isEmpty()) return emptyList()

        val rows = progressionDao.getSuggestionsForTargetIds(comparableTargets.map { it.id })
        val validatedRows = comparableTargets.map { previousTarget ->
            val matchingRows = rows.filter { row ->
                row.sourceTargetSnapshotId == previousTarget.id &&
                    row.sourceProgression.ruleRevision == previousTarget.progression.ruleRevision
            }
            check(matchingRows.size == 1) {
                "Expected one progression outcome for target ${previousTarget.id} at revision ${previousTarget.progression.ruleRevision}"
            }
            val row = matchingRows.single()
            check(row.hasExactDuplicatedSource(previousTarget)) {
                "Progression outcome ${row.id} source differs from target ${previousTarget.id}"
            }
            previousTarget to row
        }
        val currentActualWeight = evaluatedActualWeight(currentSets, target.target.sets, target.progression.ruleRevision)
            ?: return emptyList()
        val outcomes = mutableListOf<PreviousProgressionOutcome>()
        for ((previousTarget, row) in validatedRows) {
            val previousActualWeight = persistedActualWeight(row, previousTarget)
                ?: break
            if (abs(previousActualWeight - currentActualWeight) > WEIGHT_TOLERANCE_KG) break
            outcomes += mapper.toPreviousOutcome(row)
        }
        return outcomes
    }

    private suspend fun persistedActualWeight(
        row: ProgressionSuggestionEntity,
        target: WorkoutPlanTargetEntity
    ): Double? {
        val countedIds = runCatching { mapper.decodeCountedSetIds(row) }.getOrNull() ?: return null
        if (target.target.sets <= 0 || countedIds.size != target.target.sets ||
            countedIds.distinct().size != countedIds.size) return null
        val sets = runCatching { setDao.getSetsByIds(countedIds) }.getOrNull() ?: return null
        if (sets.size != countedIds.size) return null
        if (sets.any { set ->
                set.id <= 0L ||
                    !isProgressionWorkSet(set.setType, target.progression.ruleRevision) ||
                    set.sessionId != target.sessionId ||
                    set.exerciseId != target.exerciseId ||
                    set.planTargetSnapshotId != target.id ||
                    !set.weightKg.isFinite() ||
                    set.weightKg < 0.0
            }) {
            return null
        }
        val orderedSets = sets.sortedWith(compareBy({ it.setNumber }, { it.completedAt }, { it.id }))
        if (orderedSets.map { it.id } != countedIds) return null
        if (target.progression.ruleRevision == 2) return orderedSets.minOf { it.weightKg }
        val actualWeight = orderedSets.first().weightKg
        return actualWeight.takeIf {
            orderedSets.drop(1).all { set -> abs(set.weightKg - actualWeight) <= WEIGHT_TOLERANCE_KG }
        }
    }

    private fun evaluatedActualWeight(sets: List<WorkoutSet>, targetSets: Int, ruleRevision: Int): Double? {
        if (targetSets <= 0) return null
        val countedSets = sets
            .filter { isProgressionWorkSet(it.setType.name, ruleRevision) }
            .sortedWith(compareBy(WorkoutSet::setNumber, WorkoutSet::completedAt, WorkoutSet::id))
            .take(targetSets)
        if (countedSets.size != targetSets ||
            countedSets.any { it.id <= 0L } ||
            countedSets.map(WorkoutSet::id).distinct().size != countedSets.size ||
            countedSets.any { !it.weightKg.isFinite() || it.weightKg < 0.0 }
        ) {
            return null
        }
        if (ruleRevision == 2) return countedSets.minOf { it.weightKg }
        val actualWeight = countedSets.first().weightKg
        return actualWeight.takeIf {
            countedSets.drop(1).all { set -> abs(set.weightKg - actualWeight) <= WEIGHT_TOLERANCE_KG }
        }
    }

    private suspend fun hydrate(rows: List<ProgressionSuggestionEntity>): List<ProgressionSuggestion> {
        val rowsWithIds = rows.mapNotNull { row ->
            runCatching { row to mapper.decodeCountedSetIds(row) }.getOrNull()
        }
        val allIds = rowsWithIds.flatMap { (_, ids) -> ids }.distinct()
        val setsById = if (allIds.isEmpty()) {
            emptyMap()
        } else {
            setDao.getSetsByIds(allIds).associateBy { it.id }
        }
        return rowsWithIds.mapNotNull { (row, expectedIds) ->
            val countedSets = expectedIds.mapNotNull { id -> setsById[id]?.toDomain() }
            runCatching { mapper.toDomain(row, countedSets) }.getOrNull()
        }
    }

    private fun ProgressionSuggestionEntity.hasExactDuplicatedSource(
        target: WorkoutPlanTargetEntity
    ): Boolean =
        sourceTargetSnapshotId == target.id &&
            sourceSessionId == target.sessionId &&
            planId == target.planId &&
            exerciseId == target.exerciseId &&
            orderIndex == target.orderIndex &&
            supersetGroupId == target.supersetGroupId &&
            sourceTarget == target.target &&
            sourceProgression == target.progression

    private fun PlanExerciseEntity.matches(source: ProgressionSuggestionEntity): Boolean =
        setTargetsJson == "[]" &&
        planId == source.planId &&
            exerciseId == source.exerciseId &&
            orderIndex == source.orderIndex &&
            supersetGroupId == source.supersetGroupId &&
            targetSets == source.sourceTarget.sets &&
            targetReps == source.sourceTarget.reps &&
            targetWeightKg == source.sourceTarget.weightKg &&
            progression == source.sourceProgression

    private companion object {
        val REPAIR_JSON = Json {}

        val EMPTY_GENERATION_RESULT = ProgressionGenerationResult(
            insertedCount = 0,
            reviewItemCount = 0,
            pendingCount = 0
        )
    }
}
