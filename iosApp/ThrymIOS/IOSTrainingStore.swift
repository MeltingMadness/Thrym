import Foundation
import Observation
import Shared

/// The two modes of the History tab. The achievements exist only with the game elements on.
enum IOSHistoryMode: Hashable, CaseIterable {
    case trainings
    case achievements

    /// The mode History shows: with the game elements off it is always the workouts, also when
    /// the achievements were selected or requested before.
    func effective(gamificationEnabled: Bool) -> IOSHistoryMode {
        gamificationEnabled ? self : .trainings
    }
}

/// One observable projection of the durable Kotlin store for every screen.
/// A failed load stays visible; it never becomes an apparently empty training history.
@MainActor
@Observable
final class IOSTrainingStore {
    private(set) var data: ILTrainingData?
    private(set) var analytics: ILTrainingAnalytics?
    /// Neutral readiness projection (`ReadinessEngine.assess`). `nil` only while
    /// the projection cannot be read; a missing index inside it stays visible.
    private(set) var readiness: ILReadinessAssessment?
    private(set) var weeklyMuscleVolume: ILTrainingWeeklyMuscleVolume?
    private(set) var weeklyMuscleVolumeError: String?
    private(set) var isBusy = false
    private(set) var isLoading = false
    var errorMessage: String?
    var selectedTab: Int = 0
    /// Mode of the History tab. It lives here, next to the tab, so the dashboard can set it.
    var historyMode: IOSHistoryMode = .trainings
    /// Grows each time History has to return to its root, e.g. to drop an open workout detail.
    private(set) var historyRootRequest = 0
    /// Centrally bound to the settings in `RootView`. The deload mode is authoritative for every
    /// workout start, so a started session always records a concrete deload state. Defaults to
    /// `false` until the settings are bound.
    @ObservationIgnored var deloadModeActive = false

    @ObservationIgnored private let feature: IosTrainingFeature
    @ObservationIgnored private var observation: IosCloseable?
    @ObservationIgnored private var analyticsTimeZone = TimeZone.current.identifier
    @ObservationIgnored private var analyticsSundayStart = false

    init() {
        feature = IosTrainingFeature()
        errorMessage = feature.currentError().map(ilSharedMessage)
        if let json = feature.currentJson() { receive(json) }
        observation = feature.watchJson(onState: { [weak self] json in
            Task { @MainActor in self?.receive(json) }
        })
    }

    deinit { observation?.close(); feature.close() }

    @discardableResult
    private func receive(_ json: String) -> Bool {
        do {
            data = try JSONDecoder().decode(ILTrainingData.self, from: Data(json.utf8))
            refreshAnalytics(timeZoneId: analyticsTimeZone, weekStartsSunday: analyticsSundayStart)
            return true
        } catch {
            errorMessage = String(localized: "Trainingsdaten konnten nicht gelesen werden: \(error.localizedDescription)")
            return false
        }
    }

    /// Opens History at its root in the achievements mode (the level card on the dashboard).
    func showAchievements() {
        historyMode = .achievements
        historyRootRequest &+= 1
        selectedTab = 2
    }

    /// Re-read the shared projection without replacing or seeding persisted data.
    func reload() async {
        guard !isLoading else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        await Task.yield()
        if let json = feature.currentJson() {
            _ = receive(json)
        } else {
            errorMessage = feature.currentError().map(ilSharedMessage) ?? String(localized: "Trainingsdaten konnten nicht geladen werden.")
        }
    }

    func refreshAnalytics(timeZoneId: String = TimeZone.current.identifier, weekStartsSunday: Bool = false) {
        analyticsTimeZone = timeZoneId
        analyticsSundayStart = weekStartsSunday
        if let json = feature.analyticsJson(
            nowEpochMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            timeZoneId: timeZoneId,
            weekStartsSunday: weekStartsSunday
        ) {
            do {
                analytics = try JSONDecoder().decode(ILTrainingAnalytics.self, from: Data(json.utf8))
            } catch {
                analytics = nil
                errorMessage = String(localized: "Auswertung konnte nicht gelesen werden: \(error.localizedDescription)")
            }
        } else {
            analytics = nil
        }
        // Readiness uses the rotation suggestion for today's planned muscles, so it is
        // refreshed after analytics instead of before.
        refreshReadiness(timeZoneId: timeZoneId)
    }

    /// The plan the athlete is about to train: the running session's plan, otherwise the rotation
    /// suggestion, otherwise the first available plan — the same fallback the dashboard start card
    /// offers. `nil` only when no plan exists at all, so nothing is invented.
    private var suggestedPlanId: Int64? {
        if let activePlanId = data?.activeSession?.planId { return activePlanId }
        if let suggested = analytics?.metaRotationSuggestions.first?.nextTrainingPlanId {
            return suggested
        }
        // Same visible fallback as the dashboard start card: with no suggestion the card offers
        // the first training plan, so the readiness projection must see that plan as selected too.
        return data?.trainingPlans.first?.id
    }

    /// Loads the neutral readiness assessment from the same durable snapshot.
    private func refreshReadiness(timeZoneId: String) {
        guard let json = feature.readinessJson(
            nowEpochMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            timeZoneId: timeZoneId,
            muscleWindowDays: 7,
            // `0` means "no plan selected"; the shared adapter then relies on the
            // persisted snapshot alone.
            selectedPlanId: suggestedPlanId ?? 0
        ) else {
            readiness = nil
            return
        }
        do {
            readiness = try JSONDecoder().decode(ILReadinessAssessment.self, from: Data(json.utf8))
        } catch {
            readiness = nil
            errorMessage = String(localized: "Bereitschaft konnte nicht gelesen werden: \(error.localizedDescription)")
        }
    }

    /// Load one historical muscle-volume week without replacing current analytics.
    ///
    /// The shared projection aligns the requested date to the configured week
    /// anchor and clamps future requests to the current week. Counts, readiness,
    /// records, and the eight-week trend remain owned by `analytics`.
    @discardableResult
    func weeklyMuscleVolume(
        for weekStart: String,
        timeZoneId: String = TimeZone.current.identifier,
        weekStartsSunday: Bool = false
    ) -> ILTrainingWeeklyMuscleVolume? {
        guard let json = feature.weeklyMuscleVolumeJson(
            weekStart: weekStart,
            nowEpochMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            timeZoneId: timeZoneId,
            weekStartsSunday: weekStartsSunday
        ) else {
            weeklyMuscleVolume = nil
            weeklyMuscleVolumeError = String(localized: "Wochenvolumen konnte nicht geladen werden.")
            return nil
        }

        do {
            let result = try JSONDecoder().decode(
                ILTrainingWeeklyMuscleVolume.self,
                from: Data(json.utf8)
            )
            weeklyMuscleVolume = result
            weeklyMuscleVolumeError = nil
            return result
        } catch {
            weeklyMuscleVolume = nil
            weeklyMuscleVolumeError = String(localized: "Wochenvolumen konnte nicht gelesen werden: \(error.localizedDescription)")
            return nil
        }
    }

    /// Return success only after Kotlin has validated and durably persisted the mutation.
    @discardableResult
    func command(_ op: String, fields: [String: Any] = [:]) async -> Bool {
        guard !isBusy else { return false }
        isBusy = true
        defer { isBusy = false }
        do {
            var body = fields
            body["op"] = op
            let bytes = try JSONSerialization.data(withJSONObject: body)
            guard let json = String(data: bytes, encoding: .utf8) else { return false }
            let result: (String?, String?) = await withCheckedContinuation { continuation in
                feature.execute(commandJson: json) { value, error in
                    continuation.resume(returning: (value, error))
                }
            }
            if let error = result.1 { errorMessage = ilSharedMessage(error); return false }
            guard let snapshot = result.0 else {
                errorMessage = String(localized: "Die Aktion hat keine bestätigten Trainingsdaten zurückgegeben.")
                return false
            }
            errorMessage = nil
            return receive(snapshot)
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func encoded<T: Encodable>(_ value: T) throws -> Any {
        try JSONSerialization.jsonObject(with: JSONEncoder().encode(value))
    }

    @discardableResult
    func saveExercise(_ exercise: ILExercise) async -> Bool {
        do { return await command("exercise.save", fields: ["exercise": try encoded(exercise)]) }
        catch { errorMessage = error.localizedDescription; return false }
    }

    @discardableResult
    func savePlan(_ plan: ILTrainingPlan, exercises: [ILPlanExercise]) async -> Bool {
        do { return await command("plan.save", fields: ["plan": try encoded(plan), "exercises": try encoded(exercises)]) }
        catch { errorMessage = error.localizedDescription; return false }
    }

    @discardableResult
    func saveMetaPlan(_ plan: ILMetaTrainingPlan, items: [ILMetaPlanItem]) async -> Bool {
        do { return await command("metaplan.save", fields: ["plan": try encoded(plan), "items": try encoded(items)]) }
        catch { errorMessage = error.localizedDescription; return false }
    }

    @discardableResult
    func startWorkout(
        name: String = "",
        planId: Int64? = nil,
        metaPlanId: Int64? = nil,
        isDeload: Bool? = nil
    ) async -> Bool {
        let fields = iosWorkoutStartFields(
            name: name,
            planId: planId,
            metaPlanId: metaPlanId,
            explicitDeload: isDeload,
            deloadModeActive: deloadModeActive
        )
        let success = await command("workout.start", fields: fields)
        if success { selectedTab = 1 }
        return success
    }

    /// Conservatively records that an already-running session happened under a
    /// deload. Only escalates to `true` and is a no-op without an active session.
    @discardableResult
    func markActiveSessionDeload() async -> Bool {
        guard let sessionId = data?.activeSession?.id else { return false }
        return await command("workout.markDeload", fields: ["sessionId": sessionId])
    }

    // MARK: - Readiness side channel

    var readinessData: ILReadinessData { data?.readinessData ?? .empty }

    func intention(forSetId setId: Int64) -> ILSetIntention {
        readinessData.intention(forSetId: setId)
    }

    /// Upserts the check-in for exactly `localDate`. Unanswered dimensions stay
    /// `nil` and an unreported muscle group keeps no key in the payload.
    @discardableResult
    func upsertCheckIn(
        localDate: String,
        sleepQuality: Int?,
        energy: Int?,
        stress: Int?,
        muscleSoreness: [String: Int],
        recordedAtEpochMillis: Int64?
    ) async -> Bool {
        var checkIn: [String: Any] = ["localDate": localDate]
        if let sleepQuality { checkIn["sleepQuality"] = sleepQuality }
        if let energy { checkIn["energy"] = energy }
        if let stress { checkIn["stress"] = stress }
        if !muscleSoreness.isEmpty { checkIn["muscleSoreness"] = muscleSoreness }
        if let recordedAtEpochMillis { checkIn["recordedAtEpochMillis"] = recordedAtEpochMillis }
        return await command("readiness.checkin.upsert", fields: ["checkIn": checkIn])
    }

    @discardableResult
    func deleteCheckIn(localDate: String) async -> Bool {
        await command("readiness.checkin.delete", fields: ["localDate": localDate])
    }

    /// `UNKNOWN` removes the record instead of storing it (shared contract).
    @discardableResult
    func updateSetIntention(
        setId: Int64,
        intention: ILSetIntention,
        note: String = ""
    ) async -> Bool {
        await command(
            "readiness.setIntention.update",
            fields: ["setId": setId, "intention": intention.rawValue, "note": note]
        )
    }

    /// Saves a set and, when supplied, its intention in the same atomic command.
    ///
    /// `nil` leaves an existing intention untouched; passing `.unknown` explicitly
    /// removes it (shared contract).
    @discardableResult
    func saveSet(_ set: ILWorkoutSet, intention: ILSetIntention? = nil) async -> Bool {
        do {
            var fields: [String: Any] = ["set": try encoded(set)]
            if let intention { fields["intention"] = intention.rawValue }
            return await command(set.id == 0 ? "set.add" : "set.update", fields: fields)
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }
}

/// Effective deload flag for a `workout.start`.
///
/// The user's deload mode is authoritative, so every start records a concrete state even when the
/// caller passes nothing. An explicit caller value can only additionally escalate to `true`, which
/// mirrors the durable store: it never downgrades an active session from deload back to normal.
func iosWorkoutDeloadFlag(explicit: Bool?, modeActive: Bool) -> Bool {
    modeActive || explicit == true
}

/// Fields for the `workout.start` command. Kept separate from the store so the central deload
/// decision is directly testable; `isDeload` is always present, `false` included.
func iosWorkoutStartFields(
    name: String,
    planId: Int64?,
    metaPlanId: Int64?,
    explicitDeload: Bool?,
    deloadModeActive: Bool
) -> [String: Any] {
    var fields: [String: Any] = ["name": name]
    if let planId { fields["planId"] = planId }
    if let metaPlanId { fields["metaPlanId"] = metaPlanId }
    fields["isDeload"] = iosWorkoutDeloadFlag(explicit: explicitDeload, modeActive: deloadModeActive)
    return fields
}
