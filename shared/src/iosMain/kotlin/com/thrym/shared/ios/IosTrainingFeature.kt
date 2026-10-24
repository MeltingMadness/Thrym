@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package com.thrym.shared.ios

import com.thrym.shared.backup.BackupExercise
import com.thrym.shared.backup.BackupMetaPlanItem
import com.thrym.shared.backup.BackupMetaTrainingPlan
import com.thrym.shared.backup.BackupPlanExercise
import com.thrym.shared.backup.BackupPayloadV1
import com.thrym.shared.backup.BackupProgressionTarget
import com.thrym.shared.backup.BackupTrainingPlan
import com.thrym.shared.backup.BackupWorkoutSession
import com.thrym.shared.backup.BackupWorkoutSet
import com.thrym.shared.analytics.SharedReadinessProjection
import com.thrym.shared.analytics.SharedTrainingAnalytics
import com.thrym.shared.model.MuscleGroup
import com.thrym.shared.readinessdata.ReadinessCheckIn
import com.thrym.shared.readinessdata.SetIntention
import com.thrym.shared.store.ProgressionDecisionResult
import com.thrym.shared.store.ProgressionLifecycle
import com.thrym.shared.store.RecoverySnapshot
import com.thrym.shared.store.SharedStatePersistence
import com.thrym.shared.store.SharedStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The iOS-facing JSON command bridge for the shared workout store.
 *
 * This class deliberately keeps the persisted graph in the common [SharedStateStore]. Swift
 * only sends small JSON commands and receives a complete, durably persisted snapshot. A store
 * that could not be loaded is reported through [currentError] and never represented as an empty
 * workout history.
 *
 * Error messages that users can see are fixed German texts. They double as keys of the iOS
 * string catalog (`Localizable.xcstrings`, extraction state "manual"), and Swift translates
 * them at the bridge, so they must not carry dynamic values. Messages that only point at a
 * programming error in the bridge (malformed command JSON, unknown operations) are English
 * diagnostics and reach the UI unchanged.
 */
class IosTrainingFeature {
    private val scope: CoroutineScope = MainScope()
    private val commandJson = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    }
    private val snapshotEncoder = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }
    private val analyticsEncoder = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun currentJson(): String? {
        val store = IosTrainingStore.current() ?: return null
        return runCatching { snapshotEncoder.encodeToString(store.snapshot()) }
            .onFailure { error -> IosTrainingStore.recordError(error) }
            .getOrNull()
    }

    fun currentError(): String? = IosTrainingStore.currentError()

    /** Returns the read-only analytics projection for the current durable snapshot. */
    fun analyticsJson(
        nowEpochMillis: Long,
        timeZoneId: String,
        weekStartsSunday: Boolean,
    ): String? {
        val store = IosTrainingStore.current() ?: return null
        return runCatching {
            analyticsEncoder.encodeToString(
                SharedTrainingAnalytics.summarize(
                    payload = store.snapshot(),
                    nowEpochMillis = nowEpochMillis,
                    timeZoneId = timeZoneId,
                    weekStartsSunday = weekStartsSunday,
                ),
            )
        }.onFailure { error -> IosTrainingStore.recordError(error) }
            .getOrNull()
    }

    /** Returns only the requested historical muscle-volume week. */
    fun weeklyMuscleVolumeJson(
        weekStart: String,
        nowEpochMillis: Long,
        timeZoneId: String,
        weekStartsSunday: Boolean,
    ): String? {
        val store = IosTrainingStore.current() ?: return null
        return runCatching {
            analyticsEncoder.encodeToString(
                SharedTrainingAnalytics.forWeek(
                    payload = store.snapshot(),
                    weekStart = weekStart,
                    nowEpochMillis = nowEpochMillis,
                    timeZoneId = timeZoneId,
                    weekStartsSunday = weekStartsSunday,
                ),
            )
        }.onFailure { error -> IosTrainingStore.recordError(error) }
            .getOrNull()
    }

    /**
     * Returns the neutral readiness assessment for the current durable snapshot.
     *
     * The projection is a pure function of the stored graph plus the caller's clock
     * and time zone; the caller supplies "today", the trailing window and the plan the
     * athlete is about to train explicitly. `selectedPlanId` uses `0` as "no selection",
     * so the bridge stays free of a nullable boxed `Long`.
     */
    fun readinessJson(
        nowEpochMillis: Long,
        timeZoneId: String,
        muscleWindowDays: Int,
        selectedPlanId: Long,
    ): String? {
        val store = IosTrainingStore.current() ?: return null
        return runCatching {
            analyticsEncoder.encodeToString(
                SharedReadinessProjection.assess(
                    payload = store.snapshot(),
                    nowEpochMillis = nowEpochMillis,
                    timeZoneId = timeZoneId,
                    muscleWindowDays = muscleWindowDays,
                    selectedPlanId = selectedPlanId.takeIf { it > 0L },
                ),
            )
        }.onFailure { error -> IosTrainingStore.recordError(error) }
            .getOrNull()
    }

    fun watchJson(onState: (String) -> Unit): IosCloseable {
        val job = scope.launch {
            IosTrainingStore.storeFlow.collectLatest { store ->
                if (store != null) {
                    store.state.collect {
                        runCatching { snapshotEncoder.encodeToString(store.snapshot()) }
                            .onSuccess(onState)
                            .onFailure { error -> IosTrainingStore.recordError(error) }
                    }
                }
            }
        }
        return IosCloseable { job.cancel() }
    }

    /**
     * Executes one command. The callback receives `(snapshot, null)` after a successful durable
     * mutation, or `(null, error)` when validation, persistence, or command decoding fails.
     * `backup.export` is the one command whose successful value is an export payload rather than
     * the local canonical snapshot.
     */
    fun execute(commandJson: String, onResult: (String?, String?) -> Unit) {
        scope.launch {
            runCatching {
                val command = commandJson.parseCommand()
                executeCommand(command)
            }.onSuccess { result ->
                IosTrainingStore.clearError()
                onResult(result, null)
            }.onFailure { error ->
                // Validation failures are command-local. An IllegalStateException here is
                // emitted by the durable persistence boundary and should remain inspectable via
                // currentError instead of looking like a transient UI failure.
                if (error is IllegalStateException) IosTrainingStore.recordError(error)
                onResult(null, error.message ?: "Trainingsdaten konnten nicht gespeichert werden.")
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun executeCommand(command: JsonObject): String {
        val op = command.requiredString("op")
        val store = IosTrainingStore.current()

        if (op == "backup.import" && store == null) {
            // An explicit import is the recovery path for a previously unreadable file. Stage
            // and validate it in memory first; only then replace the corrupt file atomically.
            IosTrainingStore.recoverFromImport(command.requiredString("json"))
            val recoveredStore = requireNotNull(IosTrainingStore.current())
            runProgressionCatchUp(recoveredStore)
            return snapshotEncoder.encodeToString(recoveredStore.snapshot())
        }

        val activeStore = store
            ?: throw IllegalStateException(IosTrainingStore.currentError()
                ?: "Trainingsdaten konnten nicht geladen werden.")

        when (op) {
            "exercise.save" -> activeStore.saveExercise(
                command.requiredObject("exercise").decode<BackupExercise>()
            )

            "exercise.delete" -> activeStore.deleteExercise(command.requiredLong("id"))

            "plan.applyPerformedSets" -> activeStore.applyPerformedSetTargets(command.requiredLong("sessionId"))
            "plan.save" -> activeStore.saveTrainingPlan(
                plan = command.requiredObject("plan").decode<BackupTrainingPlan>(),
                exercises = command.requiredArray("exercises").decodeList<BackupPlanExercise>(),
            )

            "plan.delete" -> activeStore.deleteTrainingPlan(command.requiredLong("id"))

            "metaplan.save" -> activeStore.saveMetaTrainingPlan(
                plan = command.requiredObject("plan").decode<BackupMetaTrainingPlan>(),
                items = command.requiredArray("items").decodeList<BackupMetaPlanItem>(),
            )

            "metaplan.delete" -> activeStore.deleteMetaTrainingPlan(command.requiredLong("id"))

            "metaplan.skip" -> check(
                activeStore.skipMetaPlan(
                    metaPlanId = command.requiredLong("metaPlanId"),
                    expectedTrainingPlanId = command.requiredLong("expectedTrainingPlanId"),
                ),
            ) {
                "Die Rotation hat sich geändert oder ein Training ist aktiv. Bitte erneut prüfen."
            }

            "workout.start" -> activeStore.startWorkout(
                name = command.optionalString("name") ?: "",
                planId = command.optionalLong("planId"),
                metaPlanId = command.optionalLong("metaPlanId"),
                isDeload = command.optionalBool("isDeload"),
            )

            // Records that this session was carried out under a deload when the mode is
            // switched on mid-session. It only ever escalates to `true`.
            "workout.markDeload" -> activeStore.markSessionDeload(
                command.requiredLong("sessionId"),
            )

            "workout.finish" -> {
                val sessionId = command.requiredLong("sessionId")
                activeStore.finishWorkout(sessionId)
                val lifecycle = ProgressionLifecycle(activeStore)
                lifecycle.generateForFinishedSession(sessionId)
                // The setting lives in the iOS settings, so the app passes it with the command.
                if (command.optionalBool("autoApplyProgression") == true) {
                    lifecycle.autoApplySafeIncreases(sessionId)
                }
            }

            "workout.update" -> activeStore.updateSession(
                command.requiredObject("session").decode<BackupWorkoutSession>()
            )

            "workout.delete" -> activeStore.deleteSession(
                command.optionalLong("id") ?: command.requiredLong("sessionId"),
            )

            "workout.cancel" -> activeStore.cancelWorkout(command.requiredLong("sessionId"))

            "set.add" -> activeStore.addSet(
                set = command.requiredObject("set").decode<BackupWorkoutSet>(),
                intention = command.optionalIntention("intention") ?: SetIntention.UNKNOWN,
            )

            "set.update" -> activeStore.updateSet(
                set = command.requiredObject("set").decode<BackupWorkoutSet>(),
                intention = command.optionalIntention("intention"),
            )

            "set.delete" -> activeStore.deleteSet(command.requiredLong("id"))

            // Readiness side channel. The client supplies the local date explicitly; nothing
            // here derives "today", clamps a scale value, or invents a historical intention.
            "readiness.checkin.upsert" -> activeStore.upsertCheckIn(command.requiredCheckIn())

            "readiness.checkin.delete" -> activeStore.deleteCheckIn(
                command.requiredLocalDate("localDate"),
            )

            "readiness.setIntention.update" -> activeStore.updateSetIntention(
                setId = command.requiredLong("setId"),
                intention = command.requiredIntention("intention"),
                note = command.optionalString("note") ?: "",
            )

            "backup.import" -> {
                activeStore.importJson(command.requiredString("json"))
                runProgressionCatchUp(activeStore)
            }

            "progression.generate" -> {
                val lifecycle = ProgressionLifecycle(activeStore)
                command.optionalLong("sessionId")?.let { sessionId ->
                    lifecycle.generateForFinishedSession(sessionId)
                } ?: runProgressionCatchUp(activeStore, lifecycle)
            }

            "progression.accept" -> when (
                val result = ProgressionLifecycle(activeStore).acceptSuggestions(
                    command.requiredProgressionTargets(),
                )
            ) {
                is ProgressionDecisionResult.Accepted,
                is ProgressionDecisionResult.Rejected -> Unit
                is ProgressionDecisionResult.Stale -> throw staleProgression(result.suggestionIds)
                is ProgressionDecisionResult.Invalid -> throw IllegalArgumentException(result.message)
            }

            "progression.reject" -> when (
                val result = ProgressionLifecycle(activeStore).rejectSuggestion(
                    command.requiredLong("id"),
                )
            ) {
                is ProgressionDecisionResult.Rejected -> Unit
                is ProgressionDecisionResult.Stale -> throw staleProgression(result.suggestionIds)
                is ProgressionDecisionResult.Invalid -> throw IllegalArgumentException(result.message)
                is ProgressionDecisionResult.Accepted -> error("Unexpected accepted progression decision")
            }

            "backup.export" -> return activeStore.exportJson()

            "data.reset" -> activeStore.resetUserData()

            else -> throw IllegalArgumentException("Unknown training command: $op")
        }

        return snapshotEncoder.encodeToString(activeStore.snapshot())
    }

    /** The user sees a fixed, translatable text; the affected suggestion ids go to the log. */
    private fun staleProgression(suggestionIds: Collection<Long>): IllegalStateException {
        NSLog("Thrym: stale progression suggestions %@", suggestionIds.sorted().joinToString(", "))
        return IllegalStateException("Progressionsvorschlag ist veraltet.")
    }

    private suspend fun runProgressionCatchUp(
        store: SharedStateStore,
        lifecycle: ProgressionLifecycle = ProgressionLifecycle(store),
    ) {
        lifecycle.generateMissingOutcomes()
        lifecycle.reconcileOutstandingSuggestions()
    }

    private inline fun <reified T> JsonElement.decode(): T = commandJson.decodeFromJsonElement(this)

    private inline fun <reified T> JsonArray.decodeList(): List<T> =
        map { it.decode<T>() }

    private fun String.parseCommand(): JsonObject = commandJson.parseToJsonElement(this).jsonObject

    private fun JsonObject.requiredObject(key: String): JsonObject =
        get(key)?.jsonObject ?: throw IllegalArgumentException("Field '$key' is missing or not an object")

    private fun JsonObject.requiredArray(key: String): JsonArray =
        get(key)?.jsonArray
            ?: throw IllegalArgumentException("Field '$key' is missing or not an array")

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Field '$key' is missing or not a string")

    private fun JsonObject.optionalString(key: String): String? =
        get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredLong(key: String): Long =
        get(key)?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("Field '$key' is missing or not a number")

    private fun JsonObject.requiredInt(key: String): Int {
        val value = requiredLong(key)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "Field '$key' is outside the supported integer range"
        }
        return value.toInt()
    }

    private fun JsonObject.requiredDouble(key: String): Double =
        get(key)?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("Field '$key' is missing or not a number")

    /** Accepts the documented array form and a single-target object for one-item callers. */
    private fun JsonObject.requiredProgressionTargets(): Map<Long, BackupProgressionTarget> {
        val element = get("targets") ?: get("target")
            ?: throw IllegalArgumentException("Field 'targets' is missing")
        val targetObjects = when {
            element is JsonNull -> emptyList()
            element is JsonArray -> element.map { it.jsonObject }
            else -> listOf(element.jsonObject)
        }
        require(targetObjects.isNotEmpty()) { "At least one progression target is required" }
        val targets = targetObjects.associate { target ->
            val id = target.requiredLong("id")
            id to BackupProgressionTarget(
                sets = target.requiredInt("sets"),
                reps = target.requiredInt("reps"),
                weightKg = target.requiredDouble("weightKg"),
            )
        }
        require(targets.size == targetObjects.size) { "Duplicate progression suggestion id" }
        return targets
    }

    private fun JsonObject.optionalLong(key: String): Long? {
        val element = get(key) ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.longOrNull
            ?: throw IllegalArgumentException("Field '$key' is not a number")
    }

    private fun JsonObject.optionalInt(key: String): Int? {
        val element = get(key) ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.intOrNull
            ?: throw IllegalArgumentException("Field '$key' is not an integer")
    }

    private fun JsonObject.optionalBool(key: String): Boolean? {
        val element = get(key) ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.booleanOrNull
            ?: throw IllegalArgumentException("Field '$key' is not a boolean")
    }

    /** Parses an explicit ISO-8601 calendar day; an invalid or missing date is refused. */
    private fun JsonObject.requiredLocalDate(key: String): LocalDate =
        runCatching { LocalDate.parse(requiredString(key)) }.getOrElse {
            throw IllegalArgumentException("Field '$key' must be an ISO date (yyyy-MM-dd)")
        }

    /**
     * Parses a canonical intention name.
     *
     * Deliberately strict: a typo must fail instead of silently degrading to `UNKNOWN` and
     * clearing a previously recorded intention.
     */
    private fun JsonObject.requiredIntention(key: String): SetIntention {
        val raw = requiredString(key)
        return SetIntention.entries.firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("Unknown set intention: $raw")
    }

    private fun JsonObject.optionalIntention(key: String): SetIntention? {
        val element = get(key) ?: return null
        if (element is JsonNull) return null
        val raw = element.jsonPrimitive.contentOrNull
            ?: throw IllegalArgumentException("Field '$key' is not a string")
        return SetIntention.entries.firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("Unknown set intention: $raw")
    }

    /**
     * Builds a check-in from the `checkIn` object.
     *
     * `localDate` is mandatory and every subjective value stays optional: an unanswered
     * dimension is `null`, and an absent muscle-group key means "not reported". Out-of-range
     * values are passed through to the store so the single validation boundary rejects them
     * instead of this parser quietly clamping them.
     */
    private fun JsonObject.requiredCheckIn(): ReadinessCheckIn {
        val body = requiredObject("checkIn")
        val soreness = body.get("muscleSoreness")
            ?.takeUnless { it is JsonNull }
            ?.jsonObject
            ?.map { (groupName, value) ->
                val muscle = MuscleGroup.entries.firstOrNull { it.name == groupName }
                    ?: throw IllegalArgumentException("Unknown muscle group: $groupName")
                val scale = value.jsonPrimitive.intOrNull
                    ?: throw IllegalArgumentException("Soreness for $groupName is not an integer")
                muscle to scale
            }
            ?.toMap()
            .orEmpty()
        return ReadinessCheckIn(
            localDate = body.requiredLocalDate("localDate"),
            sleepQuality = body.optionalInt("sleepQuality"),
            energy = body.optionalInt("energy"),
            stress = body.optionalInt("stress"),
            muscleSoreness = soreness,
            recordedAtEpochMillis = body.optionalLong("recordedAtEpochMillis"),
            updatedAtEpochMillis = body.optionalLong("updatedAtEpochMillis"),
        )
    }
}

/**
 * Process-wide owner of the iOS application-support store. The singleton is intentionally kept
 * separate from [IosTrainingFeature] instances so settings, workout, and backup screens all see
 * one StateFlow and one atomic file.
 */
object IosTrainingStore {
    private val persistence = IosApplicationSupportPersistence()
    private val recoveryPersistence = IosRecoveryPersistence()
    private val mutableStore = MutableStateFlow<SharedStateStore?>(null)
    internal val storeFlow = mutableStore.asStateFlow()
    private val recoveryMutex = Mutex()
    private var store: SharedStateStore? = null
    private var loadError: String? = null

    init {
        try {
            store = SharedStateStore(persistence)
            mutableStore.value = store
        } catch (error: Throwable) {
            // Do not seed or overwrite a corrupt/unreadable file. The error remains visible to
            // Swift so the UI can offer an explicit import/recovery action.
            loadError = error.message ?: "Trainingsdaten konnten nicht geladen werden."
        }
    }

    fun current(): SharedStateStore? = store

    fun currentError(): String? = loadError

    internal fun recordError(error: Throwable) {
        loadError = error.message ?: "Trainingsdaten konnten nicht gelesen werden."
    }

    internal fun clearError() {
        loadError = null
    }

    /** Persists the exact snapshot supplied by SharedStateStore while its transaction is held. */
    internal suspend fun saveRecovery(snapshot: RecoverySnapshot): IosRecoveryBackup =
        recoveryPersistence.save(snapshot)

    suspend fun latestRecovery(): IosRecoveryBackup? = recoveryPersistence.latest()

    /**
     * Restores the most recent verified recovery graph. SharedStateStore saves the graph that is
     * currently visible before replacing it, so a failed recovery write or a stale caller cannot
     * destroy the current data.
     */
    suspend fun restoreLatestRecovery(): IosRecoveryBackup? {
        val activeStore = current()
            ?: throw IllegalStateException(
                currentError() ?: "Trainingsdaten konnten nicht geladen werden."
            )
        val loaded = recoveryPersistence.latestSnapshot() ?: return null
        activeStore.restoreWithRecovery(
            candidate = loaded.snapshot,
            expectedCurrent = activeStore.snapshot(),
            saveRecovery = { current -> recoveryPersistence.save(current) },
        )
        return loaded.metadata
    }

    /** Validates an explicit import in memory before replacing an unreadable on-disk payload. */
    suspend fun recoverFromImport(serialized: String) = recoveryMutex.withLock {
        check(store == null) { "Training data is already loaded; import into the existing store." }
        val staging = MemoryPersistence()
        val candidate = SharedStateStore(staging)
        candidate.importJson(serialized)
        persistence.writeAtomically(requireNotNull(staging.value))
        store = SharedStateStore(persistence)
        mutableStore.value = store
        loadError = null
    }
}

@Serializable
private data class IosRecoveryEnvelope(
    val backups: List<IosRecoveryRecord> = emptyList(),
)

@Serializable
private data class IosRecoveryRecord(
    val timestampMillis: Long,
    val sizeBytes: Long,
    val exerciseCount: Int,
    val workoutSessionCount: Int,
    val workoutSetCount: Int,
    val trainingPlanCount: Int,
    val planExerciseCount: Int,
    val personalRecordCount: Int,
    val metaPlanCount: Int,
    val metaPlanItemCount: Int,
    val metaPlanSkipCount: Int,
    val workoutPlanTargetCount: Int,
    val progressionSuggestionCount: Int,
    val serializedPayload: String,
) {
    fun toPublic(): IosRecoveryBackup = IosRecoveryBackup(
        timestampMillis = timestampMillis,
        sizeBytes = sizeBytes,
        exerciseCount = exerciseCount,
        workoutSessionCount = workoutSessionCount,
        workoutSetCount = workoutSetCount,
        trainingPlanCount = trainingPlanCount,
        planExerciseCount = planExerciseCount,
        personalRecordCount = personalRecordCount,
        metaPlanCount = metaPlanCount,
        metaPlanItemCount = metaPlanItemCount,
        metaPlanSkipCount = metaPlanSkipCount,
        workoutPlanTargetCount = workoutPlanTargetCount,
        progressionSuggestionCount = progressionSuggestionCount,
    )
}

private data class IosLoadedRecovery(
    val metadata: IosRecoveryBackup,
    val snapshot: RecoverySnapshot,
)

/**
 * Atomic, bounded recovery history for the iOS training graph.
 *
 * This is deliberately separate from the training-state file: it only stores exact, validated
 * snapshots captured by SharedStateStore immediately before a destructive replacement. Every
 * read validates the serialized payload and all recorded counts before it can be offered to the
 * settings UI.
 */
private class IosRecoveryPersistence {
    private val fileManager = NSFileManager.defaultManager
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }
    private val filePath: String by lazy {
        val basePath = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String
            ?: error("Application Support directory is not available")
        "$basePath/Thrym/recovery-backups.json"
    }
    private val directoryPath: String by lazy { filePath.substringBeforeLast('/') }

    suspend fun save(snapshot: RecoverySnapshot): IosRecoveryBackup = mutex.withLock {
        val decoded = SharedStateStore.decodeAndUpgradeForPreview(snapshot.serialized)
        check(decoded == snapshot.payload) {
            "Wiederherstellungspunkt ist inkonsistent oder wurde verändert."
        }
        val existing = readEnvelopeUnsafe()
        val previousTimestamp = existing?.backups?.maxOfOrNull { it.timestampMillis }
        val timestamp = if (previousTimestamp == null) {
            kotlin.time.Clock.System.now().toEpochMilliseconds()
        } else {
            val nextTimestamp = if (previousTimestamp == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                previousTimestamp + 1L
            }
            maxOf(kotlin.time.Clock.System.now().toEpochMilliseconds(), nextTimestamp)
        }
        val record = recordFor(snapshot.payload, snapshot.serialized, timestamp)
        val retained = (existing?.backups.orEmpty() + record).takeLast(MAX_RECOVERY_BACKUPS)
        writeEnvelopeUnsafe(IosRecoveryEnvelope(retained))

        // Verify the exact on-disk bytes and canonical payload after the atomic replace. If this
        // fails, the caller's SharedStateStore transaction aborts before importing anything.
        val verified = readEnvelopeUnsafe()?.backups?.lastOrNull()
        check(verified == record) {
            "Wiederherstellungspunkt konnte nicht verifiziert werden."
        }
        requireNotNull(verified).toPublic()
    }

    suspend fun latest(): IosRecoveryBackup? = mutex.withLock {
        readEnvelopeUnsafe()?.backups?.lastOrNull()?.also(::validateRecord)?.toPublic()
    }

    suspend fun latestSnapshot(): IosLoadedRecovery? = mutex.withLock {
        val record = readEnvelopeUnsafe()?.backups?.lastOrNull() ?: return@withLock null
        validateRecord(record)
        IosLoadedRecovery(
            metadata = record.toPublic(),
            snapshot = RecoverySnapshot(
                payload = SharedStateStore.decodeAndUpgradeForPreview(record.serializedPayload),
                serialized = record.serializedPayload,
            ),
        )
    }

    private fun recordFor(
        payload: BackupPayloadV1,
        serialized: String,
        timestamp: Long,
    ): IosRecoveryRecord = IosRecoveryRecord(
        timestampMillis = timestamp,
        sizeBytes = serialized.encodeToByteArray().size.toLong(),
        exerciseCount = payload.exercises.size,
        workoutSessionCount = payload.workoutSessions.size,
        workoutSetCount = payload.workoutSets.size,
        trainingPlanCount = payload.trainingPlans.size,
        planExerciseCount = payload.planExercises.size,
        personalRecordCount = payload.personalRecords.size,
        metaPlanCount = payload.metaTrainingPlans.size,
        metaPlanItemCount = payload.metaPlanItems.size,
        metaPlanSkipCount = payload.metaPlanSkips.size,
        workoutPlanTargetCount = payload.workoutPlanTargets.size,
        progressionSuggestionCount = payload.progressionSuggestions.size,
        serializedPayload = serialized,
    )

    private fun validateRecord(record: IosRecoveryRecord) {
        require(record.timestampMillis >= 0L) {
            "Wiederherstellungspunkt enthält ein ungültiges Datum."
        }
        require(record.sizeBytes == record.serializedPayload.encodeToByteArray().size.toLong()) {
            "Wiederherstellungspunkt enthält eine ungültige Dateigröße."
        }
        val payload = SharedStateStore.decodeAndUpgradeForPreview(record.serializedPayload)
        require(payload.exercises.size == record.exerciseCount)
        require(payload.workoutSessions.size == record.workoutSessionCount)
        require(payload.workoutSets.size == record.workoutSetCount)
        require(payload.trainingPlans.size == record.trainingPlanCount)
        require(payload.planExercises.size == record.planExerciseCount)
        require(payload.personalRecords.size == record.personalRecordCount)
        require(payload.metaTrainingPlans.size == record.metaPlanCount)
        require(payload.metaPlanItems.size == record.metaPlanItemCount)
        require(payload.metaPlanSkips.size == record.metaPlanSkipCount)
        require(payload.workoutPlanTargets.size == record.workoutPlanTargetCount)
        require(payload.progressionSuggestions.size == record.progressionSuggestionCount)
    }

    private fun readEnvelopeUnsafe(): IosRecoveryEnvelope? {
        if (!fileManager.fileExistsAtPath(filePath)) return null
        val serialized = NSString.stringWithContentsOfFile(
            filePath,
            encoding = NSUTF8StringEncoding,
            error = null,
        ) ?: error("Wiederherstellungspunkte können nicht gelesen werden")
        val envelope = try {
            json.decodeFromString<IosRecoveryEnvelope>(serialized)
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Wiederherstellungspunkte sind beschädigt und können nicht gelesen werden.",
                error,
            )
        }
        envelope.backups.forEach(::validateRecord)
        return envelope
    }

    private fun writeEnvelopeUnsafe(envelope: IosRecoveryEnvelope) {
        if (!fileManager.createDirectoryAtPath(
                directoryPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ) && !fileManager.fileExistsAtPath(directoryPath)
        ) {
            error("Cannot create the Application Support directory for restore points")
        }
        val serialized = json.encodeToString(envelope)
        val success = NSString.create(string = serialized).writeToFile(
            filePath,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        check(success) { "Wiederherstellungspunkt konnte nicht atomar gespeichert werden" }
    }

    private companion object {
        const val MAX_RECOVERY_BACKUPS = 3
    }
}

private class MemoryPersistence : SharedStatePersistence {
    var value: String? = null
        private set

    override fun read(): String? = value

    override fun writeAtomically(serialized: String) {
        value = serialized
    }
}

/** Foundation-backed application-support persistence used by [IosTrainingStore]. */
private class IosApplicationSupportPersistence : SharedStatePersistence {
    private val fileManager = NSFileManager.defaultManager
    private val filePath: String by lazy {
        val basePath = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String
            ?: error("Application Support directory is not available")
        "$basePath/Thrym/training-state.json"
    }
    private val directoryPath: String by lazy {
        filePath.substringBeforeLast('/')
    }

    override fun read(): String? {
        if (!fileManager.fileExistsAtPath(filePath)) return null
        return NSString.stringWithContentsOfFile(
            filePath,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
            ?: error("Trainingsdaten-Datei kann nicht gelesen werden")
    }

    override fun writeAtomically(serialized: String) {
        if (!fileManager.createDirectoryAtPath(
                directoryPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ) && !fileManager.fileExistsAtPath(directoryPath)
        ) {
            error("Cannot create the Application Support directory")
        }
        val success = NSString.create(string = serialized).writeToFile(
            filePath,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        check(success) { "Trainingsdaten konnten nicht atomar gespeichert werden" }
    }
}
