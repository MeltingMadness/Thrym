import Foundation

enum IOSProgressionScheme: String, CaseIterable, Identifiable {
    case manual = "MANUAL"
    case linear = "LINEAR"
    case double = "DOUBLE"
    case totalReps = "TOTAL_REPS"
    case rpeRir = "RPE_RIR"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .manual: String(localized: "Manuell")
        case .linear: String(localized: "Linear")
        case .double: String(localized: "Doppelprogression")
        case .totalReps: String(localized: "Gesamtwiederholungen")
        case .rpeRir: String(localized: "RPE/RIR")
        }
    }
}

struct IOSProgressionDraft: Equatable {
    var scheme: IOSProgressionScheme = .manual
    private var unsupportedScheme: String?
    var stepValue = IOSNumber.format(2.5)
    var stepUnit = "KG"
    var minReps = "8"
    var maxReps = "12"
    var totalReps = "30"
    var targetRpe = "8"
    var rpeTolerance = "1"
    var stallThreshold = "2"
    var backoffPercent = "10"
    var successThreshold = "1"

    init(record: ILProgressionConfig = ILProgressionConfig(), unitSystem: String = "METRIC") {
        if let decodedScheme = IOSProgressionScheme(rawValue: record.scheme) {
            scheme = decodedScheme
            unsupportedScheme = nil
        } else {
            scheme = .manual
            unsupportedScheme = record.scheme
        }
        let sourceUnit = record.incrementUnit?.uppercased() ?? (IOSWeight.isImperial(unitSystem) ? "LB" : "KG")
        stepUnit = IOSWeight.isImperial(unitSystem) ? "LB" : "KG"
        if let kg = record.incrementKg {
            stepValue = IOSNumber.format(IOSWeight.display(kg: kg, unit: stepUnit))
        } else if let value = record.incrementValue {
            let sourceKg = IOSWeight.kilograms(value: value, unit: sourceUnit)
            stepValue = IOSNumber.format(IOSWeight.display(kg: sourceKg, unit: stepUnit))
        }
        if let value = record.minReps { minReps = String(value) }
        if let value = record.maxReps { maxReps = String(value) }
        if let value = record.targetTotalReps { totalReps = String(value) }
        if let value = record.targetRpe { targetRpe = IOSNumber.format(value) }
        if let value = record.rpeTolerance { rpeTolerance = IOSNumber.format(value) }
        successThreshold = String(record.successThreshold)
        stallThreshold = String(record.stallThreshold)
        backoffPercent = IOSNumber.format(record.backoffPercent)
    }

    func validatedConfig() -> Result<ILProgressionConfig, IOSProgressionValidationError> {
        if let unsupportedScheme {
            return .failure(.init(String(localized: "Unbekanntes Progressionsschema \(unsupportedScheme); bitte zuerst manuell auswählen.")))
        }
        if scheme == .manual { return .success(ILProgressionConfig()) }
        let stall = IOSNumber.parseInt(stallThreshold)
        let backoff = IOSNumber.parse(backoffPercent)
        guard let stall, (1...6).contains(stall) else { return .failure(.init(String(localized: "Das Fehlerlimit muss zwischen 1 und 6 liegen."))) }
        guard let backoff, backoff.isFinite, backoff >= 1, backoff <= 30 else { return .failure(.init(String(localized: "Der Backoff muss zwischen 1 und 30 % liegen."))) }

        let successes = scheme == .linear ? IOSNumber.parseInt(successThreshold) : 1
        guard let successes, (1...6).contains(successes) else {
            return .failure(.init(String(localized: "Wähle 1 bis 6 vergleichbare Erfolge in Folge.")))
        }

        guard let step = IOSNumber.parse(stepValue), step > 0 else {
            return .failure(.init(String(localized: "Der Progressionsschritt muss größer als 0 sein.")))
        }
        let stepKg = IOSWeight.kilograms(value: step, unit: stepUnit)

        var config = ILProgressionConfig(
            scheme: scheme.rawValue,
            incrementValue: step,
            incrementUnit: IOSWeight.isImperial(stepUnit) ? "IMPERIAL" : "METRIC",
            incrementKg: stepKg,
            stallThreshold: stall,
            backoffPercent: backoff,
            ruleRevision: 2,
            successThreshold: successes
        )

        switch scheme {
        case .manual, .linear:
            break
        case .double:
            guard let min = IOSNumber.parseInt(minReps), min > 0,
                  let max = IOSNumber.parseInt(maxReps), max >= min else {
                return .failure(.init(String(localized: "Min-/Max-Reps sind für die Doppelprogression ungültig.")))
            }
            config.minReps = min
            config.maxReps = max
        case .totalReps:
            guard let total = IOSNumber.parseInt(totalReps), total > 0 else {
                return .failure(.init(String(localized: "Das Gesamtziel muss größer als 0 sein.")))
            }
            config.targetTotalReps = Int64(total)
        case .rpeRir:
            guard let target = IOSNumber.parse(targetRpe), target >= 1, target <= 10,
                  let tolerance = IOSNumber.parse(rpeTolerance), tolerance >= 0, tolerance <= 2 else {
                return .failure(.init(String(localized: "Das RPE-Ziel muss zwischen 1 und 10, die Toleranz zwischen 0 und 2 liegen.")))
            }
            config.targetRpe = target
            config.rpeTolerance = tolerance
        }
        return .success(config)
    }
}

struct IOSProgressionValidationError: Error, Equatable {
    let message: String

    init(_ message: String) {
        self.message = message
    }
}

struct IOSPlanExerciseDraft: Identifiable, Equatable {
    let id: UUID
    var persistedID: Int64
    var exerciseID: Int64
    var setTargets: [ILPlannedSet] = []
    var targetSets: String
    var targetReps: String
    var targetWeight: String
    var supersetGroupID: Int?
    var progression: IOSProgressionDraft

    init(record: ILPlanExercise, unitSystem: String) {
        id = UUID()
        persistedID = record.id
        exerciseID = record.exerciseId
        setTargets = record.setTargets
        targetSets = String(record.targetSets)
        targetReps = String(record.targetReps)
        targetWeight = IOSNumber.format(IOSWeight.display(kg: record.targetWeightKg, unit: unitSystem))
        supersetGroupID = record.supersetGroupId
        progression = IOSProgressionDraft(record: record.progression, unitSystem: unitSystem)
    }

    /// Target weight in the input unit. An empty field means no weight (0), as on Android, so a
    /// bodyweight exercise can stay without load and get one later.
    var targetWeightValue: Double? {
        targetWeight.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0 : IOSNumber.parse(targetWeight)
    }

    init(exerciseID: Int64, unitSystem: String) {
        id = UUID()
        persistedID = 0
        self.exerciseID = exerciseID
        targetSets = "3"
        targetReps = "10"
        targetWeight = "0"
        supersetGroupID = nil
        progression = IOSProgressionDraft(unitSystem: unitSystem)
    }
}

struct IOSMetaPlanItemDraft: Identifiable, Equatable {
    let id: UUID
    var persistedID: Int64
    var trainingPlanID: Int64

    init(record: ILMetaPlanItem) {
        id = UUID()
        persistedID = record.id
        trainingPlanID = record.trainingPlanId
    }

    init(trainingPlanID: Int64) {
        id = UUID()
        persistedID = 0
        self.trainingPlanID = trainingPlanID
    }
}

enum IOSNumber {
    static func parse(_ value: String) -> Double? {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
        guard !normalized.isEmpty, let parsed = Double(normalized), parsed.isFinite else { return nil }
        return parsed
    }

    static func parseInt(_ value: String) -> Int? {
        guard let parsed = Int(value.trimmingCharacters(in: .whitespacesAndNewlines)), parsed >= 0 else { return nil }
        return parsed
    }

    /// Number for a text field: no grouping, at most `fractionDigits` decimals and the
    /// decimal separator of the current locale ("82.5" in English, "82,5" in German).
    /// Digits stay ASCII and [parse] accepts comma and dot, so every prefilled value
    /// reads back unchanged, also 1000 and more.
    static func format(_ value: Double, fractionDigits: Int = 2) -> String {
        guard value.isFinite else { return "0" }
        let plain = value.formatted(
            .number
                .precision(.fractionLength(0...max(0, fractionDigits)))
                .grouping(.never)
                .locale(Locale(identifier: "en_US_POSIX"))
        )
        return Locale.current.decimalSeparator == "," ? plain.replacingOccurrences(of: ".", with: ",") : plain
    }
}

enum IOSWeight {
    static let poundsPerKilogram = 2.2046226218
    static let kilogramsPerPound = 1 / poundsPerKilogram

    static func isImperial(_ unit: String) -> Bool {
        let value = unit.uppercased()
        return value.contains("IMPERIAL") || value == "LB" || value.contains("POUND")
    }

    static func display(kg: Double, unit: String) -> Double {
        isImperial(unit) ? kg * poundsPerKilogram : kg
    }

    static func kilograms(value: Double, unit: String) -> Double {
        isImperial(unit) ? value * kilogramsPerPound : value
    }

    static func label(_ unit: String) -> String {
        isImperial(unit) ? String(localized: "lb") : String(localized: "kg")
    }
}
