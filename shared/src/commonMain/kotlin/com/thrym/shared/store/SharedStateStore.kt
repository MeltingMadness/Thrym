package com.thrym.shared.store

import com.thrym.shared.backup.BackupExercise
import com.thrym.shared.backup.BackupMetaPlanItem
import com.thrym.shared.backup.BackupMetaPlanSkip
import com.thrym.shared.backup.BackupMetaTrainingPlan
import com.thrym.shared.backup.BackupPayloadV1
import com.thrym.shared.backup.BackupPayloadValidator
import com.thrym.shared.backup.BackupPersonalRecord
import com.thrym.shared.backup.BackupPlanExercise
import com.thrym.shared.backup.BackupTrainingPlan
import com.thrym.shared.backup.BackupWorkoutSession
import com.thrym.shared.backup.BackupWorkoutPlanTarget
import com.thrym.shared.backup.BackupWorkoutSet
import com.thrym.shared.backup.forCurrentPlan
import com.thrym.shared.backup.CURRENT_BACKUP_SCHEMA_VERSION
import com.thrym.shared.backup.SUPPORTED_BACKUP_SET_TYPES
import com.thrym.shared.backup.WARMUP_SET_TYPE
import com.thrym.shared.readinessdata.ReadinessCheckIn
import com.thrym.shared.readinessdata.ReadinessData
import com.thrym.shared.readinessdata.ReadinessDataValidator
import com.thrym.shared.readinessdata.SetIntention
import com.thrym.shared.readinessdata.SetIntentionRecord
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

private const val MAX_WEIGHT_RECORD_TYPE = "MAX_WEIGHT"
private const val MAX_REPS_RECORD_TYPE = "MAX_REPS"
private const val MAX_E1RM_RECORD_TYPE = "MAX_E1RM"
private const val MAX_VOLUME_RECORD_TYPE = "MAX_VOLUME"
private const val PROGRESSION_STATUS_PENDING = "PENDING"
private const val PROGRESSION_STATUS_STALE = "STALE"
private val PERSONAL_RECORD_TYPES = setOf(
    MAX_WEIGHT_RECORD_TYPE,
    MAX_REPS_RECORD_TYPE,
    MAX_E1RM_RECORD_TYPE,
    MAX_VOLUME_RECORD_TYPE,
)

private fun resolveMetaRotation(
    orderedPlanIds: List<Long>,
    lastEventAtByPlanId: Map<Long, Long>,
): List<Long> = orderedPlanIds
    .withIndex()
    .sortedWith(
        compareBy<IndexedValue<Long>> { lastEventAtByPlanId[it.value] != null }
            .thenBy { lastEventAtByPlanId[it.value] ?: Long.MIN_VALUE }
            .thenBy { it.index },
    )
    .map { it.value }

private fun Long.incrementIfSafe(): Long =
    if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1L

/**
 * Canonical backup decoding shared by the durable store and the staged iOS import preview.
 *
 * Keeping this operation outside [SharedStateStore] construction is important for recovery: an
 * unreadable on-disk file means no store can be created, but Settings still has to validate a
 * candidate backup before offering it as a recovery action.
 */
private object SharedBackupPayloadCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    fun decodeAndUpgrade(serialized: String): BackupPayloadV1 {
        val decoded = decodeUnvalidated(serialized)
        validateOrThrow(decoded)
        val upgraded = upgrade(decoded)
        validateOrThrow(upgraded)
        return upgraded
    }

    /**
     * Durable-store load path: repairs duplicate personal record ids before validating.
     *
     * A state document that once received a duplicate record id fails validation forever, so the
     * app could load it in older builds but never write it again. The import paths keep using
     * [decodeAndUpgrade], which stays strict.
     */
    fun decodeAndUpgradeRepairingDuplicatePersonalRecordIds(serialized: String): BackupPayloadV1 {
        val repaired = PersonalRecordIdRepair.repair(decodeUnvalidated(serialized))
        validateOrThrow(repaired)
        val upgraded = upgrade(repaired)
        validateOrThrow(upgraded)
        return upgraded
    }

    private fun decodeUnvalidated(serialized: String): BackupPayloadV1 = runCatching {
        json.decodeFromString<BackupPayloadV1>(serialized)
    }.getOrElse { error ->
        throw IllegalArgumentException("Malformed workout state", error)
    }

    private fun upgrade(decoded: BackupPayloadV1): BackupPayloadV1 = canonicalize(
        decoded.copy(
            schemaVersion = CURRENT_BACKUP_SCHEMA_VERSION,
            appVersion = "",
            exportedAtEpochMillis = 0L,
            planExercises = decoded.planExercises.map { it.copy(progression = it.progression.forCurrentPlan()) },
            workoutSets = decoded.workoutSets.map { set ->
                set.copy(
                    setType = set.resolvedSetType(),
                    isWarmup = null,
                )
            },
        ),
    )

    fun canonicalize(payload: BackupPayloadV1): BackupPayloadV1 = payload.copy(
        formatVersion = 1,
        schemaVersion = CURRENT_BACKUP_SCHEMA_VERSION,
        appVersion = "",
        exportedAtEpochMillis = 0L,
        exercises = payload.exercises.map { it.copy() }.toList(),
        workoutSessions = payload.workoutSessions.toList(),
        workoutSets = payload.workoutSets.map { it.copy(isWarmup = null) }.toList(),
        trainingPlans = payload.trainingPlans.toList(),
        planExercises = payload.planExercises.toList(),
        personalRecords = payload.personalRecords.toList(),
        metaTrainingPlans = payload.metaTrainingPlans.toList(),
        metaPlanItems = payload.metaPlanItems.toList(),
        metaPlanSkips = payload.metaPlanSkips.toList(),
        workoutPlanTargets = payload.workoutPlanTargets.toList(),
        progressionSuggestions = payload.progressionSuggestions.toList(),
        readinessData = canonicalReadiness(payload.readinessData),
    )

    /**
     * Deterministic ordering for the readiness side channel.
     *
     * The store persists the whole payload as one JSON document; a stable order keeps repeated
     * writes byte-identical and therefore keeps the "unchanged on disk" and stale-preview
     * comparisons meaningful. Values are copied unchanged, never repaired.
     */
    fun canonicalReadiness(readinessData: ReadinessData): ReadinessData = readinessData.copy(
        checkIns = readinessData.checkIns.sortedBy { it.localDate },
        setIntentions = readinessData.setIntentions.sortedBy { it.setId },
    )

    fun validateOrThrow(payload: BackupPayloadV1) {
        val validation = BackupPayloadValidator.validate(payload, CURRENT_BACKUP_SCHEMA_VERSION)
        require(validation.isValid) {
            "Invalid workout state: ${validation.errors.joinToString("; ")}"
        }
    }

    fun encodeCanonical(payload: BackupPayloadV1): String =
        json.encodeToString(canonicalize(payload))
}

/**
 * Synchronous platform storage used by [SharedStateStore].
 *
 * The store owns transaction ordering and validation. A platform implementation only has to
 * replace the destination atomically (for iOS this is a temporary file followed by a rename),
 * so a failed write never becomes visible through [SharedStateStore.state].
 */
interface SharedStatePersistence {
    fun read(): String?

    fun writeAtomically(serialized: String)
}

/**
 * Exact canonical graph used by the backup recovery boundary.
 *
 * The serialized form is intentionally carried alongside the decoded payload.  A recovery
 * adapter must persist the bytes that were validated under the store mutex; re-encoding in the
 * platform layer could otherwise change defaults or ordering between the guard and the write.
 */
data class RecoverySnapshot(
    val payload: BackupPayloadV1,
    val serialized: String,
)

/**
 * A small, platform-neutral transactional store for the workout domain.
 *
 * The persisted graph deliberately uses [BackupPayloadV1] itself. This keeps the local iOS
 * state and the user-visible backup on one schema and preserves target snapshots and progression
 * evidence instead of introducing a second, subtly different set of records. Mutations are
 * serialized by a [Mutex]; the new JSON is written and validated before the StateFlow publishes
 * it. Consequently observers never see a state that the platform could not persist.
 *
 * Mutating methods are suspend functions because the common mutex is suspendable. The synchronous
 * persistence interface is intentional: platform file/database adapters should perform the
 * actual atomic replace in one call, while the store still serializes callers safely.
 */
class SharedStateStore(
    private val persistence: SharedStatePersistence,
    seedExercises: List<BackupExercise> = DefaultExerciseCatalog.exercises(),
    private val appVersion: String = "0.1.0",
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    companion object {
        /** Decodes and upgrades a backup without opening the local persistence adapter. */
        fun decodeAndUpgradeForPreview(serialized: String): BackupPayloadV1 =
            SharedBackupPayloadCodec.decodeAndUpgrade(serialized)
    }

    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }
    private val seedExercises = seedExercises.map(::copyExercise).toList()
    private val mutableState = MutableStateFlow(loadInitialState())

    /** The last successfully persisted canonical snapshot. */
    val state: StateFlow<BackupPayloadV1> = mutableState.asStateFlow()

    /** Returns the current immutable snapshot without starting a coroutine. */
    fun snapshot(): BackupPayloadV1 = mutableState.value

    /**
     * Applies one graph transformation atomically.
     *
     * The callback must return the complete next [BackupPayloadV1]. It runs while the store
     * transaction is held, so callers should keep it deterministic and free of suspension. The
     * result is canonicalized, validated, persisted, then published.
     */
    suspend fun transact(transform: (BackupPayloadV1) -> BackupPayloadV1): BackupPayloadV1 =
        mutex.withLock {
            val candidate = canonicalize(transform(snapshot()))
            persistAndPublish(candidate)
        }

    /**
     * Exports a validated payload with user-facing metadata. Export does not mutate local state;
     * the canonical in-app snapshot keeps empty metadata so repeated writes stay deterministic.
     */
    fun exportJson(): String {
        val export = snapshot().copy(
            appVersion = appVersion,
            exportedAtEpochMillis = nowEpochMillis(),
        )
        validateOrThrow(export)
        return json.encodeToString(export)
    }

    /**
     * Imports and upgrades a V1 JSON payload. Legacy schema 11 warmup flags are resolved to the
     * current setType field before the payload becomes local state. The imported exercise catalog
     * is kept exact: a backup is authoritative and does not silently gain local seed rows.
     */
    suspend fun importJson(serialized: String): BackupPayloadV1 =
        mutex.withLock {
            persistAndPublish(decodeAndUpgradeForPreview(serialized))
        }

    /**
     * Imports a validated backup only if the local graph is still the snapshot the caller
     * inspected. The equality check and the write are part of the same mutex transaction, so a
     * workout mutation cannot slip between a settings preview and its destructive replacement.
     *
     * A mismatch is reported before any write and leaves both the in-memory StateFlow and the
     * persistence adapter untouched. Callers should surface this as a stale preview and ask the
     * user to inspect the backup again.
     */
    suspend fun importJsonIfUnchanged(
        serialized: String,
        expectedSnapshot: BackupPayloadV1,
    ): BackupPayloadV1 = mutex.withLock {
        val upgraded = decodeAndUpgradeForPreview(serialized)
        check(snapshot() == expectedSnapshot) {
            "Trainingsdaten wurden zwischen Vorschau und Import geändert. Bitte Backup erneut prüfen."
        }
        persistAndPublish(upgraded)
    }

    /**
     * Saves an exact recovery copy of the inspected local graph before replacing it with an
     * imported backup.  Decoding, the preview guard, recovery callback and import write all run
     * under this store's transaction mutex.  A callback failure therefore leaves the local graph
     * untouched and the imported candidate is never published.
     *
     * [saveRecovery] must only persist the supplied snapshot and must not call back into this
     * store; re-entering the mutex would deadlock.  The callback is intentionally suspendable so
     * an iOS file adapter can perform its verified atomic replace before the destructive import.
     */
    suspend fun importJsonIfUnchangedWithRecovery(
        serialized: String,
        expectedSnapshot: BackupPayloadV1,
        saveRecovery: suspend (RecoverySnapshot) -> Unit,
    ): BackupPayloadV1 = mutex.withLock {
        val upgraded = decodeAndUpgradeForPreview(serialized)
        check(snapshot() == expectedSnapshot) {
            "Trainingsdaten wurden zwischen Vorschau und Import geändert. Bitte Backup erneut prüfen."
        }
        saveRecovery(recoverySnapshot(snapshot()))
        persistAndPublish(upgraded)
    }

    /**
     * Restores a previously persisted recovery graph while first safeguarding the graph currently
     * visible to the caller.  The candidate bytes are decoded and compared with the payload
     * carried by [RecoverySnapshot] so a platform adapter cannot silently restore a different
     * document than the one it inspected.  A stale current graph or a failed recovery write
     * leaves this store unchanged.
     */
    suspend fun restoreWithRecovery(
        candidate: RecoverySnapshot,
        expectedCurrent: BackupPayloadV1,
        saveRecovery: suspend (RecoverySnapshot) -> Unit,
    ): BackupPayloadV1 = mutex.withLock {
        val decodedCandidate = decodeAndUpgradeForPreview(candidate.serialized)
        check(decodedCandidate == candidate.payload) {
            "Recovery-Snapshot ist inkonsistent oder wurde verändert."
        }
        check(snapshot() == expectedCurrent) {
            "Trainingsdaten wurden seit der Recovery-Vorschau geändert. Bitte erneut prüfen."
        }
        saveRecovery(recoverySnapshot(snapshot()))
        persistAndPublish(decodedCandidate)
    }

    /**
     * Deletes user-created data while restoring the supplied initial exercise catalog. This
     * mirrors Android's reset semantics: built-in exercises survive, all workout and plan rows
     * (including progression evidence) are removed.
     */
    suspend fun resetUserData(): BackupPayloadV1 = mutex.withLock {
        val reset = emptyPayload(seedExercises)
        validateOrThrow(reset)
        persistAndPublish(reset)
    }

    suspend fun saveExercise(exercise: BackupExercise): Long = mutex.withLock {
        require(exercise.name.isNotBlank()) { "Exercise name must not be blank" }
        val id = exercise.id.takeIf { it > 0L } ?: nextId(snapshot().exercises.map { it.id })
        val current = snapshot()
        if (exercise.id > 0L && current.exercises.none { it.id == exercise.id }) {
            throw IllegalArgumentException("Exercise ${exercise.id} does not exist")
        }
        val saved = copyExercise(exercise).copy(id = id)
        val exercises = current.exercises
            .filterNot { it.id == id }
            .plus(saved)
            .sortedBy { it.id }
        persistAndPublish(current.copy(exercises = exercises))
        id
    }

    suspend fun deleteExercise(exerciseId: Long) = mutex.withLock {
        require(exerciseId > 0L) { "Exercise id must be positive" }
        val current = snapshot()
        require(current.exercises.any { it.id == exerciseId }) {
            "Exercise $exerciseId does not exist"
        }
        require(
            current.planExercises.none { it.exerciseId == exerciseId } &&
                current.workoutSets.none { it.exerciseId == exerciseId } &&
                current.workoutPlanTargets.none { it.exerciseId == exerciseId } &&
                current.personalRecords.none { it.exerciseId == exerciseId } &&
                current.progressionSuggestions.none { it.exerciseId == exerciseId },
        ) { "Exercise $exerciseId is still referenced" }
        persistAndPublish(current.copy(exercises = current.exercises.filterNot { it.id == exerciseId }))
    }

    /** Saves a plan and replaces its ordered exercise rows in one transaction. */
    suspend fun saveTrainingPlan(
        plan: BackupTrainingPlan,
        exercises: List<BackupPlanExercise>,
    ): Long = mutex.withLock {
        val current = snapshot()
        val planId = when {
            plan.id == 0L -> nextId(current.trainingPlans.map { it.id })
            current.trainingPlans.any { it.id == plan.id } -> plan.id
            else -> throw IllegalArgumentException("Training plan ${plan.id} does not exist")
        }
        require(plan.name.isNotBlank()) { "Training plan name must not be blank" }
        val createdAt = if (plan.id == 0L) {
            plan.createdAt.takeIf { it > 0L } ?: nowEpochMillis()
        } else {
            current.trainingPlans.first { it.id == plan.id }.createdAt
        }
        val usedPlanExerciseIds = current.planExercises.map { it.id }.toMutableSet()
        val normalizedExercises = exercises.mapIndexed { index, exercise ->
            require(exercise.exerciseId in current.exercises.map { it.id }) {
                "Plan exercise references missing exercise ${exercise.exerciseId}"
            }
            val exerciseId = if (exercise.id > 0L) {
                when {
                    current.planExercises.any { it.id == exercise.id && it.planId == planId } ->
                        exercise.id
                    current.planExercises.any { it.id == exercise.id } ->
                        throw IllegalArgumentException("Plan exercise ${exercise.id} belongs to another plan")
                    else ->
                        throw IllegalArgumentException("Plan exercise ${exercise.id} does not exist")
                }
            } else {
                nextId(usedPlanExerciseIds.toList()).also(usedPlanExerciseIds::add)
            }
            usedPlanExerciseIds.add(exerciseId)
            exercise.copy(
                id = exerciseId,
                planId = planId,
                orderIndex = index,
            )
        }
        val updated = current.copy(
            trainingPlans = current.trainingPlans
                .filterNot { it.id == planId }
                .plus(BackupTrainingPlan(planId, plan.name, createdAt))
                .sortedBy { it.id },
            planExercises = current.planExercises
                .filterNot { it.planId == planId }
                .plus(normalizedExercises),
        )
        persistAndPublish(updated)
        planId
    }

    suspend fun applyPerformedSetTargets(sessionId: Long) = mutex.withLock {
        val current = snapshot()
        val session = current.workoutSessions.firstOrNull { it.id == sessionId } ?: error("Training nicht gefunden")
        check(session.endTime != null) { "Bitte Training zuerst beenden" }
        val targets = current.workoutPlanTargets.filter { it.sessionId == sessionId }
        check(targets.isNotEmpty()) { "Kein Plan für dieses Training vorhanden" }
        val updates = targets.mapNotNull { target ->
            val recorded = current.workoutSets.filter { it.planTargetSnapshotId == target.id && it.reps > 0 }.sortedWith(compareBy({ it.setNumber }, { it.id }))
            if (recorded.isEmpty()) return@mapNotNull null
            val plan = current.planExercises.firstOrNull { it.planId == target.planId && it.exerciseId == target.exerciseId && it.orderIndex == target.orderIndex }
                ?: error("Plan wurde geändert. Bitte im Editor prüfen.")
            check(plan.targetSets == target.target.sets && plan.targetReps == target.target.reps && plan.targetWeightKg == target.target.weightKg &&
                plan.setTargets == target.setTargets && plan.progression == target.progression && plan.supersetGroupId == target.supersetGroupId) {
                "Plan wurde seit dem Training geändert. Bitte im Editor prüfen."
            }
            val original = target.setTargets.ifEmpty { List(target.target.sets) { com.thrym.shared.plans.PlannedSet(reps = target.target.reps, weightKg = target.target.weightKg) } }
            val merged = com.thrym.shared.plans.PlannedSets.mergePerformed(original, recorded.map {
                com.thrym.shared.plans.PlannedSet(it.setType ?: if (it.isWarmup == true) "WARMUP" else "NORMAL", it.reps, it.weightKg)
            })
            if (merged == original) return@mapNotNull null
            val work = merged.filter { it.kind != "WARMUP" }
            plan.copy(setTargets = merged, targetSets = work.size, targetReps = work.first().reps, targetWeightKg = work.first().weightKg,
                progression = com.thrym.shared.backup.BackupProgressionConfig())
        }.associateBy { it.id }
        persistAndPublish(current.copy(planExercises = current.planExercises.map { updates[it.id] ?: it }))
    }

    suspend fun deleteTrainingPlan(planId: Long) = mutex.withLock {
        val current = snapshot()
        require(current.trainingPlans.any { it.id == planId }) {
            "Training plan $planId does not exist"
        }
        val targetIds = current.workoutPlanTargets
            .filter { it.planId == planId }
            .map { it.id }
            .toSet()
        val updated = current.copy(
            trainingPlans = current.trainingPlans.filterNot { it.id == planId },
            planExercises = current.planExercises.filterNot { it.planId == planId },
            metaPlanItems = current.metaPlanItems.filterNot { it.trainingPlanId == planId },
            metaPlanSkips = current.metaPlanSkips.filterNot { it.trainingPlanId == planId },
            workoutSessions = current.workoutSessions.map { session ->
                if (session.planId == planId) session.copy(planId = null) else session
            },
            workoutSets = current.workoutSets.map { set ->
                if (set.planTargetSnapshotId?.let(targetIds::contains) == true) {
                    set.copy(planTargetSnapshotId = null)
                } else {
                    set
                }
            },
            workoutPlanTargets = current.workoutPlanTargets.filterNot { it.planId == planId },
            progressionSuggestions = current.progressionSuggestions.filterNot {
                it.planId == planId || it.sourceTargetSnapshotId in targetIds
            },
        )
        persistAndPublish(updated)
    }

    /** Saves a meta-plan and replaces its ordered sub-plan rows in one transaction. */
    suspend fun saveMetaTrainingPlan(
        plan: BackupMetaTrainingPlan,
        items: List<BackupMetaPlanItem>,
    ): Long = mutex.withLock {
        val current = snapshot()
        val metaPlanId = when {
            plan.id == 0L -> nextId(current.metaTrainingPlans.map { it.id })
            current.metaTrainingPlans.any { it.id == plan.id } -> plan.id
            else -> throw IllegalArgumentException("Meta-training plan ${plan.id} does not exist")
        }
        require(plan.name.isNotBlank()) { "Meta-training plan name must not be blank" }
        val createdAt = if (plan.id == 0L) {
            plan.createdAt.takeIf { it > 0L } ?: nowEpochMillis()
        } else {
            current.metaTrainingPlans.first { it.id == plan.id }.createdAt
        }
        val usedIds = current.metaPlanItems.map { it.id }.toMutableSet()
        val normalizedItems = items.mapIndexed { index, item ->
            require(item.trainingPlanId in current.trainingPlans.map { it.id }) {
                "Meta-plan item references missing training plan ${item.trainingPlanId}"
            }
            val itemId = item.id.takeIf { it > 0L } ?: nextId(usedIds.toList()).also(usedIds::add)
            if (item.id > 0L && current.metaPlanItems.any { it.id == item.id && it.metaPlanId != metaPlanId }) {
                throw IllegalArgumentException("Meta-plan item ${item.id} belongs to another meta-plan")
            }
            item.copy(id = itemId, metaPlanId = metaPlanId, orderIndex = index)
        }
        val updated = current.copy(
            metaTrainingPlans = current.metaTrainingPlans
                .filterNot { it.id == metaPlanId }
                .plus(BackupMetaTrainingPlan(metaPlanId, plan.name, createdAt))
                .sortedBy { it.id },
            metaPlanItems = current.metaPlanItems
                .filterNot { it.metaPlanId == metaPlanId }
                .plus(normalizedItems),
        )
        persistAndPublish(updated)
        metaPlanId
    }

    suspend fun deleteMetaTrainingPlan(metaPlanId: Long) = mutex.withLock {
        val current = snapshot()
        require(current.metaTrainingPlans.any { it.id == metaPlanId }) {
            "Meta-training plan $metaPlanId does not exist"
        }
        persistAndPublish(
            current.copy(
                metaTrainingPlans = current.metaTrainingPlans.filterNot { it.id == metaPlanId },
                metaPlanItems = current.metaPlanItems.filterNot { it.metaPlanId == metaPlanId },
                metaPlanSkips = current.metaPlanSkips.filterNot { it.metaPlanId == metaPlanId },
                workoutSessions = current.workoutSessions.map { session ->
                    if (session.metaPlanId == metaPlanId) session.copy(metaPlanId = null) else session
                },
            ),
        )
    }

    /**
     * Records a rotation skip only when the expected sub-plan is still current.
     *
     * The complete read/resolve/insert operation runs under the same mutex as every other state
     * mutation. This mirrors Android's Room transaction: stale dashboard state, a concurrent
     * plan edit, a second tap, and an active workout all fail without writing a skip row.
     * Rotation ordering intentionally follows [SharedTrainingAnalytics]: completed sessions with
     * future start times are ignored, future skip timestamps are ignored, plans without events
     * come first, then the oldest event and finally the current item order.
     */
    suspend fun skipMetaPlan(
        metaPlanId: Long,
        expectedTrainingPlanId: Long,
    ): Boolean = mutex.withLock {
        val current = snapshot()
        if (current.workoutSessions.any { it.endTime == null }) return@withLock false

        val metaPlan = current.metaTrainingPlans.firstOrNull { it.id == metaPlanId }
            ?: return@withLock false
        val plansById = current.trainingPlans.associateBy { it.id }
        val orderedPlanIds = current.metaPlanItems
            .asSequence()
            .filter { it.metaPlanId == metaPlan.id }
            .sortedBy { it.orderIndex }
            .mapNotNull { plansById[it.trainingPlanId]?.id }
            .distinct()
            .toList()
        if (orderedPlanIds.size < 2 || expectedTrainingPlanId !in orderedPlanIds) {
            return@withLock false
        }

        val now = nowEpochMillis().coerceAtLeast(0L)
        val lastEventAtByPlanId = mutableMapOf<Long, Long>()
        current.workoutSessions
            .asSequence()
            .filter {
                it.endTime != null &&
                    it.startTime <= now &&
                    it.metaPlanId == metaPlan.id &&
                    it.planId != null
            }
            .forEach { session ->
                val planId = requireNotNull(session.planId)
                lastEventAtByPlanId[planId] = maxOf(
                    lastEventAtByPlanId[planId] ?: Long.MIN_VALUE,
                    session.startTime,
                )
            }
        current.metaPlanSkips
            .asSequence()
            .filter { it.metaPlanId == metaPlan.id && it.skippedAt <= now }
            .forEach { skip ->
                lastEventAtByPlanId[skip.trainingPlanId] = maxOf(
                    lastEventAtByPlanId[skip.trainingPlanId] ?: Long.MIN_VALUE,
                    skip.skippedAt,
                )
            }

        val currentPlanId = resolveMetaRotation(orderedPlanIds, lastEventAtByPlanId).firstOrNull()
            ?: return@withLock false
        if (currentPlanId != expectedTrainingPlanId) return@withLock false

        val greatestAnchor = lastEventAtByPlanId.values.maxOrNull()
        val effectiveSkippedAt = if (greatestAnchor != null && now <= greatestAnchor) {
            greatestAnchor.incrementIfSafe()
        } else {
            now
        }
        val skip = BackupMetaPlanSkip(
            id = nextId(current.metaPlanSkips.map { it.id }),
            metaPlanId = metaPlan.id,
            trainingPlanId = expectedTrainingPlanId,
            skippedAt = effectiveSkippedAt,
        )
        persistAndPublish(current.copy(metaPlanSkips = current.metaPlanSkips + skip))
        true
    }

    /**
     * Starts or reuses the single active session, including immutable plan target snapshots.
     *
     * A caller without plan context is the generic "resume" action and keeps returning the
     * active session. A caller that names a plan or meta-plan may resume it only when every
     * supplied identifier still describes that session. Starting a different requested workout
     * while one is active is rejected before any mutation so a stale iOS command cannot silently
     * switch the user into another plan.
     */
    suspend fun startWorkout(
        name: String = "",
        planId: Long? = null,
        metaPlanId: Long? = null,
        isDeload: Boolean? = null,
    ): Long = mutex.withLock {
        val current = snapshot()
        if (planId != null) {
            require(current.trainingPlans.any { it.id == planId }) {
                "Training plan $planId does not exist"
            }
        }
        if (metaPlanId != null) {
            require(current.metaTrainingPlans.any { it.id == metaPlanId }) {
                "Meta-training plan $metaPlanId does not exist"
            }
        }
        current.workoutSessions.firstOrNull { it.endTime == null }?.let { active ->
            val requestedWorkoutMatches =
                (planId == null || active.planId == planId) &&
                    (metaPlanId == null || active.metaPlanId == metaPlanId)
            check(requestedWorkoutMatches) {
                "Es ist bereits ein anderes Training aktiv. Bitte setze es fort oder beende es zuerst."
            }
            // Resuming keeps the session's own record. A caller that now knows a
            // deload is active may only ever escalate null/false to true.
            if (isDeload == true && active.isDeload != true) {
                persistAndPublish(
                    current.copy(
                        workoutSessions = current.workoutSessions.map {
                            if (it.id == active.id) it.copy(isDeload = true) else it
                        },
                    ),
                )
            }
            return@withLock active.id
        }
        val sessionId = nextId(current.workoutSessions.map { it.id })
        val start = nowEpochMillis().coerceAtLeast(0L)
        val session = com.thrym.shared.backup.BackupWorkoutSession(
            id = sessionId,
            startTime = start,
            endTime = null,
            durationSeconds = 0L,
            name = name,
            notes = "",
            planId = planId,
            metaPlanId = metaPlanId,
            isDeload = isDeload,
        )
        val planExercises = planId?.let { id ->
            current.planExercises
                .filter { it.planId == id }
                .sortedBy { it.orderIndex }
        }.orEmpty()
        var nextTargetId = nextId(current.workoutPlanTargets.map { it.id })
        val targets = planExercises.map { exercise ->
            BackupWorkoutPlanTarget(
                id = nextTargetId++,
                sessionId = sessionId,
                planId = requireNotNull(planId),
                exerciseId = exercise.exerciseId,
                orderIndex = exercise.orderIndex,
                supersetGroupId = exercise.supersetGroupId,
                target = com.thrym.shared.backup.BackupProgressionTarget(
                    sets = exercise.targetSets,
                    reps = exercise.targetReps,
                    weightKg = exercise.targetWeightKg,
                ),
                progression = exercise.progression,
                setTargets = exercise.setTargets,
            )
        }
        persistAndPublish(
            current.copy(
                workoutSessions = current.workoutSessions + session,
                workoutPlanTargets = current.workoutPlanTargets + targets,
            ),
        )
        sessionId
    }

    /**
     * Conservatively records that a session happened under a deload.
     *
     * Only `true` is written: activating the deload mode after the session started
     * must not lose that context, while deactivating it later must not rewrite
     * history. Legacy sessions without the flag are upgraded from `null` to `true`
     * only through this explicit call, never from current settings.
     */
    suspend fun markSessionDeload(sessionId: Long) = mutex.withLock {
        val current = snapshot()
        val session = current.workoutSessions.firstOrNull { it.id == sessionId }
            ?: throw IllegalArgumentException("Workout session $sessionId does not exist")
        if (session.isDeload == true) return@withLock
        persistAndPublish(
            current.copy(
                workoutSessions = current.workoutSessions.map {
                    if (it.id == sessionId) it.copy(isDeload = true) else it
                },
            ),
        )
    }

    /** Finishing is idempotent; a retry cannot move the completion timestamp. */
    suspend fun finishWorkout(sessionId: Long) = mutex.withLock {
        val current = snapshot()
        val session = current.workoutSessions.firstOrNull { it.id == sessionId }
            ?: return@withLock
        if (session.endTime != null) return@withLock
        val end = nowEpochMillis().coerceAtLeast(session.startTime)
        val duration = ((end - session.startTime) / 1_000L).coerceAtLeast(0L)
        val updated = current.copy(
            workoutSessions = current.workoutSessions.map {
                if (it.id == sessionId) it.copy(endTime = end, durationSeconds = duration) else it
            },
        )
        val affectedExerciseIds = current.workoutSets
            .filter { it.sessionId == sessionId }
            .map { it.exerciseId }
            .toSet()
        persistAndPublish(rebuildPersonalRecords(updated, affectedExerciseIds))
    }

    /**
     * Updates only the editable session fields. Identity, timing and plan references are
     * intentionally immutable once the session exists, so a Swift caller cannot move history
     * between plans by sending a stale full DTO.
     */
    suspend fun updateSession(session: BackupWorkoutSession) = mutex.withLock {
        val current = snapshot()
        val stored = current.workoutSessions.firstOrNull { it.id == session.id }
            ?: throw IllegalArgumentException("Workout session ${session.id} does not exist")
        require(
            session.startTime == stored.startTime &&
                session.endTime == stored.endTime &&
                session.durationSeconds == stored.durationSeconds &&
                session.planId == stored.planId &&
                session.metaPlanId == stored.metaPlanId,
        ) { "Workout session identity, timing and plan fields cannot be changed" }
        persistAndPublish(
            current.copy(
                workoutSessions = current.workoutSessions.map {
                    if (it.id == session.id) it.copy(name = session.name, notes = session.notes) else it
                },
            ),
        )
    }

    /** Deletes a completed session and all graph rows whose provenance is that session. */
    suspend fun deleteSession(sessionId: Long) = mutex.withLock {
        val current = snapshot()
        val session = current.workoutSessions.firstOrNull { it.id == sessionId }
            ?: throw IllegalArgumentException("Workout session $sessionId does not exist")
        require(session.endTime != null) {
            "Active workout session $sessionId must be cancelled instead of deleted"
        }
        persistAndPublish(removeSession(current, sessionId))
    }

    /**
     * Cancels the active session. The session, its sets, immutable target snapshots and
     * suggestions are removed together, while plans themselves remain intact. The UI must ask
     * for confirmation before invoking this destructive operation.
     */
    suspend fun cancelWorkout(sessionId: Long) = mutex.withLock {
        val current = snapshot()
        val session = current.workoutSessions.firstOrNull { it.id == sessionId }
            ?: throw IllegalArgumentException("Workout session $sessionId does not exist")
        require(session.endTime == null) {
            "Completed workout session $sessionId cannot be cancelled"
        }
        persistAndPublish(removeSession(current, sessionId))
    }

    /**
     * Adds a work set and, when supplied, its intention in the same transaction.
     *
     * The intention lives in the readiness side channel keyed by the durable set id, so it is
     * persisted together with the set instead of in a second, separately observable write.
     * [SetIntention.UNKNOWN] records nothing: a legacy set stays unknown rather than pretending
     * to carry an answer.
     */
    suspend fun addSet(
        set: BackupWorkoutSet,
        intention: SetIntention = SetIntention.UNKNOWN,
    ): Long = mutex.withLock {
        val current = snapshot()
        require(set.id == 0L) { "New workout set must have id 0" }
        validateSetValues(set)
        require(current.workoutSessions.any { it.id == set.sessionId && it.endTime == null }) {
            "Workout session ${set.sessionId} is not active"
        }
        validateTargetLink(current, set)
        val id = nextId(current.workoutSets.map { it.id })
        val updated = current.copy(
            workoutSets = current.workoutSets + set.copy(id = id),
            readinessData = current.readinessData.withIntention(id, intention, note = ""),
        )
        persistAndPublish(rebuildPersonalRecords(updated, setOf(set.exerciseId)))
        id
    }

    /**
     * Updates an existing set's editable values.
     *
     * [intention] is tri-state: `null` leaves the stored intention untouched (the default, used
     * by callers that only edit reps/weight), otherwise the record is set - or removed for
     * [SetIntention.UNKNOWN].
     */
    suspend fun updateSet(
        set: BackupWorkoutSet,
        intention: SetIntention? = null,
    ) = mutex.withLock {
        val current = snapshot()
        require(set.id > 0L) { "Workout set id must be positive" }
        val stored = current.workoutSets.firstOrNull { it.id == set.id }
            ?: throw IllegalArgumentException("Workout set ${set.id} does not exist")
        val owner = current.workoutSessions.firstOrNull { it.id == stored.sessionId }
            ?: throw IllegalArgumentException("Workout session ${stored.sessionId} does not exist")
        validateSetValues(set)
        require(
            set.sessionId == stored.sessionId &&
                set.exerciseId == stored.exerciseId &&
                set.setNumber == stored.setNumber &&
                set.setType == stored.setType &&
                set.completedAt == stored.completedAt &&
                set.planTargetSnapshotId == stored.planTargetSnapshotId,
        ) { "Workout set identity fields cannot be changed" }
        validateTargetLink(current, set)
        val updated = current.copy(
            workoutSets = current.workoutSets.map { if (it.id == set.id) set else it },
            readinessData = intention?.let { current.readinessData.withIntention(set.id, it, "") }
                ?: current.readinessData,
        )
        val changed = set != stored
        val reconciled = if (owner.endTime != null && changed) {
            markPendingProgressionSuggestionsStale(updated, owner.id)
        } else {
            updated
        }
        persistAndPublish(rebuildPersonalRecords(reconciled, setOf(set.exerciseId)))
    }

    suspend fun deleteSet(setId: Long) = mutex.withLock {
        val current = snapshot()
        val stored = current.workoutSets.firstOrNull { it.id == setId }
            ?: return@withLock
        val owner = current.workoutSessions.firstOrNull { it.id == stored.sessionId }
            ?: throw IllegalArgumentException("Workout session ${stored.sessionId} does not exist")
        // The set's intention is keyed by set id and would otherwise dangle; drop it in the same
        // transaction so no orphaned readiness row survives a deletion.
        val updated = current.copy(
            workoutSets = current.workoutSets.filterNot { it.id == setId },
            readinessData = current.readinessData.copy(
                setIntentions = current.readinessData.setIntentions.filterNot { it.setId == setId },
            ),
        )
        val reconciled = if (owner.endTime != null) {
            markPendingProgressionSuggestionsStale(updated, owner.id)
        } else {
            updated
        }
        persistAndPublish(rebuildPersonalRecords(reconciled, setOf(stored.exerciseId)))
    }

    /**
     * Creates or replaces the check-in for exactly one local date.
     *
     * The date is part of the identity, so an "edit" is an upsert for that day; there is no
     * implicit "today". Values are validated by the backup boundary inside [persistAndPublish]:
     * scale 1..5, no duplicates, no negative timestamps, and nothing is clamped or invented.
     *
     * An entirely empty check-in is rejected: it carries no information, and an emptied form must
     * call [deleteCheckIn] instead so the stored day is removed rather than replaced by an
     * unanswered shell.
     */
    suspend fun upsertCheckIn(checkIn: ReadinessCheckIn) = mutex.withLock {
        require(checkIn.hasAnyAnswer()) {
            "An entirely empty check-in is not useful; delete the check-in for " +
                "${checkIn.localDate} instead."
        }
        val current = snapshot()
        val existing = current.readinessData.checkIns.firstOrNull { it.localDate == checkIn.localDate }
        val normalized = checkIn.copy(
            recordedAtEpochMillis = checkIn.recordedAtEpochMillis
                ?: existing?.recordedAtEpochMillis
                ?: nowEpochMillis().coerceAtLeast(0L),
            updatedAtEpochMillis = if (existing == null) {
                checkIn.updatedAtEpochMillis
            } else {
                nowEpochMillis().coerceAtLeast(existing.recordedAtEpochMillis ?: 0L)
            },
        )
        persistAndPublish(
            current.copy(
                readinessData = current.readinessData.copy(
                    checkIns = current.readinessData.checkIns
                        .filterNot { it.localDate == checkIn.localDate }
                        .plus(normalized),
                ),
            ),
        )
    }

    /** Removes the check-in for exactly one local date; an absent date is a no-op. */
    suspend fun deleteCheckIn(localDate: LocalDate) = mutex.withLock {
        val current = snapshot()
        if (current.readinessData.checkIns.none { it.localDate == localDate }) return@withLock
        persistAndPublish(
            current.copy(
                readinessData = current.readinessData.copy(
                    checkIns = current.readinessData.checkIns.filterNot { it.localDate == localDate },
                ),
            ),
        )
    }

    /**
     * Sets or clears the intention of an existing set.
     *
     * [SetIntention.UNKNOWN] deletes the record instead of storing a meaningless row, so legacy
     * and unclassified sets are byte-identical to sets that were never touched.
     */
    suspend fun updateSetIntention(
        setId: Long,
        intention: SetIntention,
        note: String = "",
    ) = mutex.withLock {
        val current = snapshot()
        require(current.workoutSets.any { it.id == setId }) {
            "Workout set $setId does not exist"
        }
        val readiness = current.readinessData.withIntention(setId, intention, note)
        if (readiness == current.readinessData) return@withLock
        persistAndPublish(current.copy(readinessData = readiness))
    }

    /**
     * Pure intention mutation used by the set and check-in entry points.
     *
     * [SetIntention.UNKNOWN] removes the record. A known value replaces any previous record for
     * the same set, so there is never more than one row per set.
     */
    private fun ReadinessData.withIntention(
        setId: Long,
        intention: SetIntention,
        note: String,
    ): ReadinessData {
        val withoutSet = setIntentions.filterNot { it.setId == setId }
        return when (intention) {
            SetIntention.UNKNOWN -> copy(setIntentions = withoutSet)
            else -> copy(
                setIntentions = withoutSet + SetIntentionRecord(
                    setId = setId,
                    intention = intention,
                    recordedAtEpochMillis = nextIntentionTimestamp(),
                    note = note,
                ),
            )
        }
    }

    /** Monotonic, collision-free timestamp for intention rows written in one session. */
    private fun nextIntentionTimestamp(): Long {
        val now = nowEpochMillis().coerceAtLeast(0L)
        val greatest = snapshot().readinessData.setIntentions
            .mapNotNull { it.recordedAtEpochMillis }
            .maxOrNull()
        return if (greatest != null && now <= greatest) greatest.incrementIfSafe() else now
    }

    /**
     * A completed session is immutable evidence for an already reviewed progression outcome.
     * Editing its sets invalidates only unresolved review rows; accepted, rejected and
     * informational rows remain historical records.  Their source target/configuration snapshots
     * and evidence ids are deliberately retained, even when a deleted set is no longer present.
     */
    private fun markPendingProgressionSuggestionsStale(
        state: BackupPayloadV1,
        sessionId: Long,
    ): BackupPayloadV1 {
        val decisionTime = nowEpochMillis().coerceAtLeast(0L)
        return state.copy(
            progressionSuggestions = state.progressionSuggestions.map { suggestion ->
                if (suggestion.sourceSessionId == sessionId &&
                    suggestion.status == PROGRESSION_STATUS_PENDING
                ) {
                    suggestion.copy(
                        status = PROGRESSION_STATUS_STALE,
                        decidedAtEpochMillis = maxOf(decisionTime, suggestion.createdAtEpochMillis),
                    )
                } else {
                    suggestion
                }
            },
        )
    }

    /** Removes a session graph and rebuilds records for every exercise touched by its sets. */
    private fun removeSession(state: BackupPayloadV1, sessionId: Long): BackupPayloadV1 {
        val targetIds = state.workoutPlanTargets
            .filter { it.sessionId == sessionId }
            .map { it.id }
            .toSet()
        val removedSetIds = state.workoutSets
            .filter { it.sessionId == sessionId }
            .map { it.id }
            .toSet()
        val affectedExerciseIds = state.workoutSets
            .filter { it.sessionId == sessionId }
            .map { it.exerciseId }
            .toSet()
        val remaining = state.copy(
            workoutSessions = state.workoutSessions.filterNot { it.id == sessionId },
            workoutSets = state.workoutSets.filterNot { it.sessionId == sessionId },
            workoutPlanTargets = state.workoutPlanTargets.filterNot { it.sessionId == sessionId },
            progressionSuggestions = state.progressionSuggestions.filterNot {
                it.sourceSessionId == sessionId || it.sourceTargetSnapshotId in targetIds
            },
            // Intentions are keyed by set id; removing their sets would otherwise leave
            // dangling readiness rows behind.
            readinessData = state.readinessData.copy(
                setIntentions = state.readinessData.setIntentions.filterNot { it.setId in removedSetIds },
            ),
        )
        return rebuildPersonalRecords(remaining, affectedExerciseIds)
    }

    /**
     * Rebuilds the Android-compatible PR set for affected exercises from the remaining graph.
     * Warmup sets are ignored. Weight, reps and E1RM consider live and completed work sets;
     * volume considers only completed sessions. Existing record IDs are retained where possible
     * so a delete/update does not create needless identity churn in observers.
     */
    private fun rebuildPersonalRecords(
        state: BackupPayloadV1,
        affectedExerciseIds: Set<Long>,
    ): BackupPayloadV1 {
        if (affectedExerciseIds.isEmpty()) return state

        val retainedRecords = state.personalRecords
            .filterNot { it.exerciseId in affectedExerciseIds && it.type in PERSONAL_RECORD_TYPES }
            .toMutableList()
        val oldRecords = state.personalRecords
            .filter { it.exerciseId in affectedExerciseIds && it.type in PERSONAL_RECORD_TYPES }
            .associateBy { it.exerciseId to it.type }
        val usedRecordIds = retainedRecords.map { it.id }.toMutableSet()
        val sessionsById = state.workoutSessions.associateBy { it.id }
        val setsByExercise = state.workoutSets
            .asSequence()
            .filter { it.exerciseId in affectedExerciseIds }
            .filter { it.resolvedSetType() != WARMUP_SET_TYPE }
            .filter { it.reps >= 0 && it.weightKg.isFinite() && it.weightKg >= 0.0 }
            .groupBy { it.exerciseId }
        val rebuilt = mutableListOf<BackupPersonalRecord>()

        affectedExerciseIds.sorted().forEach { exerciseId ->
            val sets = setsByExercise[exerciseId].orEmpty()
            val bestWeight = sets.maxWithOrNull(
                compareBy<BackupWorkoutSet> { it.weightKg }
                    .thenBy { it.completedAt }
                    .thenBy { it.id },
            )
            appendRecord(
                records = rebuilt,
                oldRecords = oldRecords,
                usedIds = usedRecordIds,
                exerciseId = exerciseId,
                type = MAX_WEIGHT_RECORD_TYPE,
                value = bestWeight?.weightKg,
                achievedAt = bestWeight?.completedAt,
            )

            val bestReps = sets.maxWithOrNull(
                compareBy<BackupWorkoutSet> { it.reps }
                    .thenBy { it.completedAt }
                    .thenBy { it.id },
            )
            appendRecord(
                records = rebuilt,
                oldRecords = oldRecords,
                usedIds = usedRecordIds,
                exerciseId = exerciseId,
                type = MAX_REPS_RECORD_TYPE,
                value = bestReps?.reps?.toDouble(),
                achievedAt = bestReps?.completedAt,
            )

            val bestE1rm = sets
                .mapNotNull { set ->
                    val value = set.weightKg * (1.0 + set.reps.toDouble() / 30.0)
                    value.takeIf(Double::isFinite)?.let { value to set }
                }
                .maxWithOrNull(
                    compareBy<Pair<Double, BackupWorkoutSet>> { it.first }
                        .thenBy { it.second.completedAt }
                        .thenBy { it.second.id },
                )
            appendRecord(
                records = rebuilt,
                oldRecords = oldRecords,
                usedIds = usedRecordIds,
                exerciseId = exerciseId,
                type = MAX_E1RM_RECORD_TYPE,
                value = bestE1rm?.first,
                achievedAt = bestE1rm?.second?.completedAt,
            )

            val completedSessionIds = sessionsById.values
                .filter { it.endTime != null }
                .map { it.id }
                .toSet()
            val volumeBySession = sets
                .filter { it.sessionId in completedSessionIds }
                .groupBy { it.sessionId }
                .mapNotNull { (sessionId, sessionSets) ->
                    val volume = sessionSets.fold(0.0) { total, set ->
                        val setVolume = set.weightKg * set.reps.toDouble()
                        val next = total + setVolume
                        if (setVolume.isFinite() && next.isFinite()) next else total
                    }
                    volume.takeIf(Double::isFinite)?.let { sessionId to it }
                }
            val bestVolume = volumeBySession.maxWithOrNull(
                compareBy<Pair<Long, Double>> { it.second }.thenBy { it.first },
            )
            val volumeAchievedAt = bestVolume?.first?.let { sessionId ->
                sets.filter { it.sessionId == sessionId }.maxOfOrNull { it.completedAt }
            }
            appendRecord(
                records = rebuilt,
                oldRecords = oldRecords,
                usedIds = usedRecordIds,
                exerciseId = exerciseId,
                type = MAX_VOLUME_RECORD_TYPE,
                value = bestVolume?.second,
                achievedAt = volumeAchievedAt,
            )
        }

        return state.copy(
            personalRecords = (retainedRecords + rebuilt).sortedBy { it.id },
        )
    }

    private fun appendRecord(
        records: MutableList<BackupPersonalRecord>,
        oldRecords: Map<Pair<Long, String>, BackupPersonalRecord>,
        usedIds: MutableSet<Long>,
        exerciseId: Long,
        type: String,
        value: Double?,
        achievedAt: Long?,
    ) {
        val recordValue = value?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val timestamp = achievedAt ?: return
        val old = oldRecords[exerciseId to type]
        // Reusing the previous id keeps identity stable for observers, but only while that id is
        // actually free. Handing out a taken id produces a duplicate, and the validator rejects
        // the whole payload, which leaves the user unable to save anything.
        val id = old?.id?.takeIf { it !in usedIds } ?: nextUnusedId(usedIds)
        usedIds += id
        records += BackupPersonalRecord(
            id = id,
            exerciseId = exerciseId,
            type = type,
            value = recordValue,
            achievedAt = timestamp,
        )
    }

    private fun nextUnusedId(usedIds: Set<Long>): Long {
        val next = nextId(usedIds)
        require(next !in usedIds) { "No free personal record id remains" }
        return next
    }

    private fun loadInitialState(): BackupPayloadV1 {
        require(seedExercises.isNotEmpty()) { "At least one seed exercise is required" }
        val persisted = persistence.read()
        if (persisted == null) {
            val empty = emptyPayload(seedExercises)
            validateOrThrow(empty)
            // Seed the first launch through the same atomic path as all later mutations. This
            // keeps a crash between app startup and the first user action from losing the catalog.
            persistence.writeAtomically(encodeCanonical(empty))
            return empty
        }
        val upgraded = loadPersistedState(persisted)
        val upgradedSerialized = encodeCanonical(upgraded)
        if (upgradedSerialized != persisted) {
            persistence.writeAtomically(upgradedSerialized)
        }
        return upgraded
    }

    /**
     * Loads the stored document, falling back to the duplicate-record-id repair.
     *
     * A document that older builds wrote with duplicate record ids is loadable but not writable,
     * so the user is locked out of saving anything. The repair rescues exactly that state; every
     * other validation error is reported unchanged.
     */
    private fun loadPersistedState(persisted: String): BackupPayloadV1 = try {
        decodeAndUpgradeForPreview(persisted)
    } catch (error: IllegalArgumentException) {
        runCatching {
            decodeAndUpgradeRepairingDuplicatePersonalRecordIds(persisted)
        }.getOrElse { throw error }
    }

    private fun emptyPayload(exercises: List<BackupExercise>): BackupPayloadV1 = BackupPayloadV1(
        formatVersion = 1,
        schemaVersion = CURRENT_BACKUP_SCHEMA_VERSION,
        appVersion = "",
        exportedAtEpochMillis = 0L,
        exercises = exercises.map(::copyExercise),
        workoutSessions = emptyList(),
        workoutSets = emptyList(),
        trainingPlans = emptyList(),
        planExercises = emptyList(),
        personalRecords = emptyList(),
        metaTrainingPlans = emptyList(),
        metaPlanItems = emptyList(),
        metaPlanSkips = emptyList(),
        workoutPlanTargets = emptyList(),
        progressionSuggestions = emptyList(),
    )

    private fun canonicalize(payload: BackupPayloadV1): BackupPayloadV1 =
        SharedBackupPayloadCodec.canonicalize(payload)

    private fun validateOrThrow(payload: BackupPayloadV1) =
        SharedBackupPayloadCodec.validateOrThrow(payload)

    private fun persistAndPublish(candidate: BackupPayloadV1): BackupPayloadV1 {
        val canonical = canonicalize(candidate)
        validateOrThrow(canonical)
        persistence.writeAtomically(encodeCanonical(canonical))
        mutableState.value = canonical
        return canonical
    }

    private fun encodeCanonical(payload: BackupPayloadV1): String =
        SharedBackupPayloadCodec.encodeCanonical(payload)

    private fun decodeAndUpgradeRepairingDuplicatePersonalRecordIds(payload: String): BackupPayloadV1 =
        SharedBackupPayloadCodec.decodeAndUpgradeRepairingDuplicatePersonalRecordIds(payload)

    private fun recoverySnapshot(payload: BackupPayloadV1): RecoverySnapshot {
        val canonical = canonicalize(payload)
        validateOrThrow(canonical)
        return RecoverySnapshot(
            payload = canonical,
            serialized = encodeCanonical(canonical),
        )
    }

    private fun validateSetValues(set: BackupWorkoutSet) {
        require(set.sessionId > 0L) { "Workout set session id must be positive" }
        require(set.exerciseId > 0L) { "Workout set exercise id must be positive" }
        require(set.setNumber > 0) { "Workout set number must be positive" }
        require(set.reps >= 0) { "Workout set reps must be non-negative" }
        require(set.weightKg.isFinite() && set.weightKg >= 0.0) {
            "Workout set weight must be finite and non-negative"
        }
        require(set.completedAt >= 0L) { "Workout set timestamp must be non-negative" }
        require(set.setType != null && set.setType in SUPPORTED_BACKUP_SET_TYPES) {
            "Workout set type must be one of ${SUPPORTED_BACKUP_SET_TYPES.sorted()}"
        }
        require(set.isWarmup == null) { "Legacy warmup flag is not accepted for local state" }
        if (set.setType == WARMUP_SET_TYPE) {
            require(set.reps >= 0) { "Warmup reps must be non-negative" }
        }
    }

    private fun validateTargetLink(state: BackupPayloadV1, set: BackupWorkoutSet) {
        set.planTargetSnapshotId?.let { targetId ->
            val target = state.workoutPlanTargets.firstOrNull { it.id == targetId }
                ?: throw IllegalArgumentException("Plan target $targetId does not exist")
            require(target.sessionId == set.sessionId && target.exerciseId == set.exerciseId) {
                "Plan target $targetId does not belong to session ${set.sessionId} and exercise ${set.exerciseId}"
            }
        }
        require(state.exercises.any { it.id == set.exerciseId }) {
            "Exercise ${set.exerciseId} does not exist"
        }
    }

    private fun nextId(ids: Collection<Long>): Long {
        val maximum = ids.maxOrNull() ?: 0L
        require(maximum < Long.MAX_VALUE) { "No free id remains" }
        return maximum + 1L
    }

    private fun copyExercise(exercise: BackupExercise): BackupExercise = exercise.copy(
        secondaryMuscleGroups = exercise.secondaryMuscleGroups,
        notes = exercise.notes,
    )

}
