import Foundation
import Shared

/// One beaten record of a workout, ready for display. `value` and `previous` are kg, reps or kg
/// of volume; `previous` is the best before this workout and nil when the exercise reaches this
/// type for the first time. `set` holds the record; it is nil for `MAX_VOLUME`, which sums the
/// workout. `addedLoad` marks an exercise whose weight is an added load (`tracksAddedLoad`): its
/// weights read "Körpergewicht + 12,5 kg".
struct IOSWorkoutRecordLine: Equatable, Identifiable {
    let type: String
    let value: Double
    let previous: Double?
    let set: ILWorkoutSet?
    var addedLoad = false

    var id: String { type }
}

/// The records of one exercise. A `firstTime` exercise has no earlier work set: it has no
/// lines, and `bestSet`, the heaviest set of the workout, is its first benchmark. `addedLoad`
/// marks a bodyweight exercise whose weight is an added load (`tracksAddedLoad`).
struct IOSWorkoutExerciseRecords: Equatable, Identifiable {
    let exerciseID: Int64
    let name: String
    let firstTime: Bool
    let lines: [IOSWorkoutRecordLine]
    let bestSet: ILWorkoutSet?
    let addedLoad: Bool

    var id: Int64 { exerciseID }
}

/// The records of one workout, exercises in the order shown on screen.
struct IOSWorkoutRecordSummary: Equatable {
    let exercises: [IOSWorkoutExerciseRecords]

    /// Number of beaten records, not of exercises.
    var recordCount: Int { exercises.reduce(0) { $0 + $1.lines.count } }

    var exercisesWithRecords: [IOSWorkoutExerciseRecords] { exercises.filter { !$0.lines.isEmpty } }

    var firstTimeExercises: [IOSWorkoutExerciseRecords] { exercises.filter(\.firstTime) }
}

/// Live record notice during the workout: "1 neuer Rekord · Bankdrücken" and
/// "Gewicht 102,5 kg (+2,5 kg)". It belongs to the workout `sessionID`.
struct IOSWorkoutRecordNotice: Equatable, Identifiable {
    /// How long the notice stays, counted from `shownAt`.
    static let duration: TimeInterval = 4

    let id: UUID
    let sessionID: Int64
    let title: String
    let detail: String
    /// When the notice appeared. Every place that shows it hides it once `duration` has passed
    /// since then, so moving into or out of the pause cover does not restart the time.
    let shownAt: Date

    /// Time left until the notice hides, never negative.
    func remainingDuration(at date: Date) -> TimeInterval {
        max(0, Self.duration - date.timeIntervalSince(shownAt))
    }
}

/// Swift side of the shared record rules (`SessionRecords` in `:shared`). Android and iOS derive
/// the records of a workout and the gain over the earlier best from the logged sets alone, with
/// one rule for the live notice and the completion screen. The stored `personalRecords` stay
/// what the dashboard, statistics and history read.
enum IOSWorkoutRecords {
    /// Every set of the given exercises in all sessions. A legacy `isWarmup` set resolves to
    /// `WARMUP`; a set counts for the volume best only once its session has ended.
    static func recordInputs(exerciseIDs: Set<Int64>, data: ILTrainingData) -> [RecordSetInput] {
        let finished = Set(data.workoutSessions.filter { $0.endTime != nil }.map(\.id))
        return data.workoutSets
            .filter { exerciseIDs.contains($0.exerciseId) }
            .map { set in
                RecordSetInput(
                    id: set.id,
                    sessionId: set.sessionId,
                    exerciseId: set.exerciseId,
                    weightKg: set.weightKg,
                    reps: Int32(clamping: set.reps),
                    setType: resolvedIOSWorkoutSetType(set).rawValue,
                    completedAtEpochMillis: set.completedAt,
                    sessionFinished: finished.contains(set.sessionId)
                )
            }
    }

    static func recordInputs(exerciseID: Int64, data: ILTrainingData) -> [RecordSetInput] {
        recordInputs(exerciseIDs: [exerciseID], data: data)
    }

    /// Records of `session` for the trained exercises of `rows`, in the order and with the names
    /// the screen shows, so the board follows the workout and not the alphabet. Exercises that
    /// track an added load only count weight and reps.
    static func summary(
        session: ILWorkoutSession,
        rows: [IOSWorkoutExerciseRow],
        data: ILTrainingData
    ) -> IOSWorkoutRecordSummary {
        var seen = Set<Int64>()
        let exerciseIDs = rows
            .filter { row in row.sets.contains { $0.reps > 0 } }
            .map(\.exercise.id)
            .filter { seen.insert($0).inserted }
        guard !exerciseIDs.isEmpty else { return IOSWorkoutRecordSummary(exercises: []) }
        let exercises = Dictionary(rows.map { ($0.exercise.id, $0.exercise) }, uniquingKeysWith: { first, _ in first })
        let addedLoadIDs = exerciseIDs.filter { exercises[$0]?.tracksAddedLoad == true }
        let sessionSets = setsByID(data.sets(for: session))
        let result = SessionRecords.shared.summarize(
            sessionId: session.id,
            exerciseIdsInOrder: exerciseIDs.map { KotlinLong(longLong: $0) },
            sets: recordInputs(exerciseIDs: seen, data: data),
            addedLoadExerciseIds: addedLoadIDs.map { KotlinLong(longLong: $0) }
        )
        return IOSWorkoutRecordSummary(exercises: result.exercises.map { exercise in
            let addedLoad = addedLoadIDs.contains(exercise.exerciseId)
            return IOSWorkoutExerciseRecords(
                exerciseID: exercise.exerciseId,
                name: exercises[exercise.exerciseId]?.displayName ?? String(localized: "Übung"),
                firstTime: exercise.firstTime,
                lines: exercise.lines.map { line($0, sets: sessionSets, addedLoad: addedLoad) },
                bestSet: exercise.bestSetId.flatMap { sessionSets[$0.int64Value] },
                addedLoad: addedLoad
            )
        })
    }

    /// Records that saving one set beat. `before` and `after` are the `recordInputs` of its
    /// exercise around the save. The first workout of an exercise, a tie, a lowered edit and a
    /// deletion stay quiet; `previous` is the best before the workout, as on the completion screen.
    /// With `addedLoad` (the exercise's `tracksAddedLoad`) only weight and reps count.
    static func liveImprovements(
        sessionID: Int64,
        before: [RecordSetInput],
        after: [RecordSetInput],
        addedLoad: Bool,
        data: ILTrainingData
    ) -> [IOSWorkoutRecordLine] {
        let sessionSets = setsByID(data.sets(for: sessionID))
        return SessionRecords.shared.liveImprovements(sessionId: sessionID, setsBefore: before, setsAfter: after, addedLoad: addedLoad)
            .map { line($0, sets: sessionSets, addedLoad: addedLoad) }
    }

    /// The notice for live records: their number and the exercise, then the first record in type
    /// order with its gain, "Gewicht 102,5 kg (+2,5 kg)". Reps carry their unit instead of a
    /// label, "12 Wdh. (+2)", and the weight of an added load is the set load without a label,
    /// "Körpergewicht + 12,5 kg (+2,5 kg)". Nil without records.
    static func notice(
        sessionID: Int64,
        exerciseName: String,
        lines: [IOSWorkoutRecordLine],
        unitSystem: String,
        mark: StrengthMark? = nil,
        shownAt: Date = Date()
    ) -> IOSWorkoutRecordNotice? {
        guard let first = lines.first else { return nil }
        let gain = first.previous.map {
            "(\(iosRecordDeltaText(type: first.type, delta: first.value - $0, unitSystem: unitSystem)))"
        }
        let value: [String]
        switch first.type {
        case "MAX_REPS":
            value = [iosRecordRepsText(first.value)]
        case "MAX_WEIGHT" where first.addedLoad:
            value = [iosRecordValueText(type: first.type, value: first.value, unitSystem: unitSystem, addedLoad: true)]
        default:
            value = [iosRecordLabel(first.type), iosRecordValueText(type: first.type, value: first.value, unitSystem: unitSystem)]
        }
        let detail = (value + [gain])
            .compactMap { $0 }
            .joined(separator: " ")
        return IOSWorkoutRecordNotice(
            id: UUID(),
            sessionID: sessionID,
            // A round mark leads the title: "100-kg-Marke · Kniebeuge (Langhantel)".
            title: mark.map { "\(IOSRecognition.markTitle($0)) · \(exerciseName)" }
                ?? String(localized: "\(lines.count) neue Rekorde · \(exerciseName)"),
            detail: detail,
            shownAt: shownAt
        )
    }

    private static func setsByID(_ sets: [ILWorkoutSet]) -> [Int64: ILWorkoutSet] {
        Dictionary(sets.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    private static func line(_ line: SessionRecordLine, sets: [Int64: ILWorkoutSet], addedLoad: Bool) -> IOSWorkoutRecordLine {
        IOSWorkoutRecordLine(
            type: line.type,
            value: line.value,
            previous: line.previous?.doubleValue,
            set: line.setId.flatMap { sets[$0.int64Value] },
            addedLoad: addedLoad
        )
    }
}

// MARK: - Formatting (stored kg, lb only for display)

/// Short record label: "Gewicht", "e1RM", "Wdh.", "Volumen".
func iosRecordLabel(_ type: String) -> String {
    switch type {
    case "MAX_WEIGHT": return String(localized: "Gewicht")
    case "MAX_E1RM": return String(localized: "e1RM")
    case "MAX_REPS": return String(localized: "Wdh.")
    case "MAX_VOLUME": return String(localized: "Volumen")
    default: return ilRecordTypeText(type)
    }
}

/// Record value on the board: "100 kg", "116,7 kg", "4.320 kg"; reps as the bare number "12",
/// since the label names them. With `addedLoad` a weight record is the set load,
/// "Körpergewicht + 12,5 kg".
func iosRecordValueText(type: String, value: Double, unitSystem: String, addedLoad: Bool = false, locale: Locale = .current) -> String {
    switch type {
    case "MAX_REPS": return iosRecordNumber(Double(iosRecordReps(value)), fractionDigits: 0, locale: locale)
    case "MAX_VOLUME": return iosRecordVolumeText(value, unitSystem: unitSystem, locale: locale)
    case "MAX_WEIGHT" where addedLoad: return iosRecordLoadText(value, unitSystem: unitSystem, addedLoad: true, locale: locale)
    default: return iosRecordWeightText(value, unitSystem: unitSystem, locale: locale)
    }
}

/// Reps with their unit for the live notice: "12 Wdh.", "12 reps".
func iosRecordRepsText(_ value: Double) -> String {
    String(localized: "\(iosRecordReps(value)) Wdh.")
}

/// Gain over the earlier best: "+2,5 kg", "+310 kg"; reps without a unit, "+2". Without
/// `signed` the plus goes, for sentences such as "2,5 kg mehr als bisher".
func iosRecordDeltaText(type: String, delta: Double, unitSystem: String, signed: Bool = true, locale: Locale = .current) -> String {
    switch type {
    case "MAX_REPS":
        let reps = iosRecordReps(delta)
        let sign = signed && reps > 0 ? "+" : ""
        return sign + iosRecordNumber(Double(reps), fractionDigits: 0, locale: locale)
    case "MAX_VOLUME": return iosRecordVolumeText(delta, unitSystem: unitSystem, signed: signed, locale: locale)
    default: return iosRecordWeightText(delta, unitSystem: unitSystem, signed: signed, locale: locale)
    }
}

/// The set behind a record: "× 5" for weight, "bei 60 kg" for reps (nothing at bodyweight);
/// an added load reads "bei Körpergewicht + 10 kg". e1RM and volume have none.
func iosRecordContext(_ line: IOSWorkoutRecordLine, unitSystem: String, locale: Locale = .current) -> String? {
    guard let set = line.set else { return nil }
    switch line.type {
    case "MAX_WEIGHT":
        return "× \(set.reps)"
    case "MAX_REPS":
        guard set.weightKg > 0 else { return nil }
        return String(localized: "bei \(iosRecordLoadText(set.weightKg, unitSystem: unitSystem, addedLoad: line.addedLoad, locale: locale))")
    default:
        return nil
    }
}

/// One record row as VoiceOver reads it: "Gewicht 100 kg × 5, 2,5 kg mehr als bisher" or
/// "…, zum ersten Mal". Reps read in full words without a label: "12 Wiederholungen bei
/// 60 kg, 2 mehr als bisher". An added load reads as on the board, "Gewicht Körpergewicht +
/// 12,5 kg × 9". The gain is spelled out, so color is never the only signal.
func iosRecordAccessibilityText(_ line: IOSWorkoutRecordLine, unitSystem: String) -> String {
    if line.type == "MAX_REPS" {
        let reps = [ilRepCountText(iosRecordReps(line.value)), iosRecordContext(line, unitSystem: unitSystem)]
            .compactMap { $0 }
            .joined(separator: " ")
        guard let previous = line.previous else { return String(localized: "\(reps), zum ersten Mal") }
        return String(localized: "\(reps), \(iosRecordReps(line.value - previous)) mehr als bisher")
    }
    let label = iosRecordLabel(line.type)
    let value = [
        iosRecordValueText(type: line.type, value: line.value, unitSystem: unitSystem, addedLoad: line.addedLoad),
        iosRecordContext(line, unitSystem: unitSystem)
    ]
        .compactMap { $0 }
        .joined(separator: " ")
    guard let previous = line.previous else { return String(localized: "\(label) \(value), zum ersten Mal") }
    let gain = iosRecordDeltaText(type: line.type, delta: line.value - previous, unitSystem: unitSystem, signed: false)
    return String(localized: "\(label) \(value), \(gain) mehr als bisher")
}

/// Record weight like Android's `WeightFormatting.formatWeight`: two decimals only for real
/// quarter steps in kg (1.25 kg plates), otherwise one, so an e1RM reads "116,7 kg"; pounds
/// one. Set lines (`iosWorkoutDisplayWeight`) keep their own format.
func iosRecordWeightText(_ kilograms: Double, unitSystem: String, signed: Bool = false, locale: Locale = .current) -> String {
    let value = ilWeightValue(kilograms, unitSystem: unitSystem)
    let quarters = value * 4
    let fractionDigits = unitSystem.uppercased() == "IMPERIAL" || abs(quarters - quarters.rounded()) >= 1e-6 ? 1 : 2
    let sign = signed && value > 0 ? "+" : ""
    return sign + iosRecordNumber(value, fractionDigits: fractionDigits, locale: locale) + " " + ilWeightUnit(unitSystem)
}

/// Load of a record set like `iosSetLoadText`, but in the record weight format, so it matches
/// the gain beside it and Android's `setLoadText`: "60 kg"; with `addedLoad` (a bodyweight
/// exercise) "Körpergewicht + 10 kg". 0 kg reads as bodyweight.
func iosRecordLoadText(_ kilograms: Double, unitSystem: String, addedLoad: Bool, locale: Locale = .current) -> String {
    if kilograms == 0 { return String(localized: "Körpergewicht") }
    let weight = iosRecordWeightText(kilograms, unitSystem: unitSystem, locale: locale)
    return addedLoad ? String(localized: "Körpergewicht + \(weight)") : weight
}

/// The first benchmark of an exercise trained for the first time, its best set: "80 kg × 8 Wdh",
/// "Körpergewicht + 10 kg × 8 Wdh", "Körpergewicht × 12 Wdh". Nil without a set.
func iosRecordBestSetText(_ exercise: IOSWorkoutExerciseRecords, unitSystem: String) -> String? {
    exercise.bestSet.map {
        iosSetValueText(weightKg: $0.weightKg, reps: $0.reps, unitSystem: unitSystem, addedLoad: exercise.addedLoad)
    }
}

/// Record volume without decimals and grouped, like Android's `formatVolume`: "4.320 kg".
func iosRecordVolumeText(_ kilograms: Double, unitSystem: String, signed: Bool = false, locale: Locale = .current) -> String {
    let value = ilWeightValue(kilograms, unitSystem: unitSystem)
    let sign = signed && value > 0 ? "+" : ""
    return sign + iosRecordNumber(value, fractionDigits: 0, locale: locale) + " " + ilWeightUnit(unitSystem)
}

private func iosRecordReps(_ value: Double) -> Int {
    Int(exactly: value.rounded()) ?? 0
}

/// Grouped number, rounded half up like Android's `DecimalFormat`, without trailing zeros.
private func iosRecordNumber(_ value: Double, fractionDigits: Int, locale: Locale) -> String {
    let formatter = NumberFormatter()
    formatter.locale = locale
    formatter.numberStyle = .decimal
    formatter.minimumFractionDigits = 0
    formatter.maximumFractionDigits = fractionDigits
    formatter.roundingMode = .halfUp
    return formatter.string(from: NSNumber(value: value == 0 ? 0 : value)) ?? "0"
}
