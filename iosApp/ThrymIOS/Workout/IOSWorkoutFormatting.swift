import Foundation

enum IOSWorkoutSetType: String, CaseIterable, Identifiable {
    case normal = "NORMAL"
    case warmup = "WARMUP"
    case dropSet = "DROP_SET"
    case failure = "FAILURE"
    case backoff = "BACKOFF"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .normal: return String(localized: "Arbeitsset")
        case .warmup: return String(localized: "Warmup")
        case .dropSet: return String(localized: "Drop-Set")
        case .failure: return String(localized: "Versagen")
        case .backoff: return String(localized: "Backoff-Satz")
        }
    }

    var shortName: String {
        switch self {
        case .normal: return String(localized: "Arbeit")
        case .warmup: return String(localized: "Warmup")
        case .dropSet: return String(localized: "Drop")
        case .failure: return String(localized: "Versagen")
        case .backoff: return String(localized: "Backoff")
        }
    }
}

func resolvedIOSWorkoutSetType(_ set: ILWorkoutSet) -> IOSWorkoutSetType {
    if let raw = set.setType, let value = IOSWorkoutSetType(rawValue: raw) {
        return value
    }
    return set.isWarmup == true ? .warmup : .normal
}

/// Number for display in the current locale, grouped ("1,250.5" / "1.250,5").
/// Text fields use `IOSNumber.format` instead, which never groups, so the parser
/// reads the value back unchanged.
func iosWorkoutDecimal(_ value: Double, fractionDigits: Int = 2) -> String {
    let formatter = NumberFormatter()
    formatter.locale = .current
    formatter.numberStyle = .decimal
    formatter.minimumFractionDigits = 0
    formatter.maximumFractionDigits = fractionDigits
    return formatter.string(from: NSNumber(value: value)) ?? "0"
}

func iosWorkoutParseDecimal(_ text: String) -> Double? {
    let normalized = text
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: ",", with: ".")
    guard !normalized.isEmpty else { return nil }
    return Double(normalized)
}

func iosWorkoutFormatDuration(_ seconds: Int) -> String {
    let safeSeconds = max(0, seconds)
    return String(format: "%02d:%02d", safeSeconds / 60, safeSeconds % 60)
}

/// Workout clock: "mm:ss", from one hour on "h:mm:ss". The Liquid Glass head and
/// the floating time bubble show the same value.
func iosWorkoutElapsedClock(_ seconds: Int) -> String {
    let elapsed = max(0, seconds)
    let hours = elapsed / 3600
    let minutes = (elapsed % 3600) / 60
    let rest = elapsed % 60
    return hours > 0
        ? String(format: "%d:%02d:%02d", hours, minutes, rest)
        : String(format: "%02d:%02d", minutes, rest)
}

func iosWorkoutDisplayWeight(
    kilograms: Double,
    unitSystem: String
) -> String {
    let value = unitSystem.uppercased() == "IMPERIAL" ? kilograms * 2.2046226218 : kilograms
    let unit = unitSystem.uppercased() == "IMPERIAL" ? String(localized: "lb") : String(localized: "kg")
    return "\(iosWorkoutDecimal(value)) \(unit)"
}

extension ILExercise {
    /// Bodyweight exercises store the added load as the set weight: 0 kg is a plain
    /// bodyweight set, 10 kg is bodyweight plus 10 kg. Same rule as Android's `tracksAddedLoad`.
    var tracksAddedLoad: Bool {
        category.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "EIGENGEWICHT"
    }
}

/// Load of a set or target: "82,5 kg"; 0 kg reads as bodyweight. With `addedLoad` (a
/// bodyweight exercise) any other weight reads "Körpergewicht + 10 kg", like on Android.
func iosSetLoadText(weightKg: Double, unitSystem: String, addedLoad: Bool = false) -> String {
    if weightKg == 0 { return String(localized: "Körpergewicht") }
    let weight = iosWorkoutDisplayWeight(kilograms: weightKg, unitSystem: unitSystem)
    return addedLoad ? String(localized: "Körpergewicht + \(weight)") : weight
}

/// Logged set as "82,5 kg × 8 Wdh"; 0 kg reads as bodyweight, like on Android.
func iosSetValueText(weightKg: Double, reps: Int, unitSystem: String, addedLoad: Bool = false) -> String {
    let weight = iosSetLoadText(weightKg: weightKg, unitSystem: unitSystem, addedLoad: addedLoad)
    return String(localized: "\(weight) × \(reps) Wdh")
}

func iosWorkoutInputWeightToKg(
    _ value: Double,
    unitSystem: String
) -> Double {
    unitSystem.uppercased() == "IMPERIAL" ? value / 2.2046226218 : value
}

func iosWorkoutWeightInInputUnit(
    kilograms: Double,
    unitSystem: String
) -> Double {
    unitSystem.uppercased() == "IMPERIAL" ? kilograms * 2.2046226218 : kilograms
}

func iosWorkoutFormatIntensity(
    storedRPE: Double?,
    intensitySystem: String
) -> String? {
    guard let storedRPE, intensitySystem.uppercased() != "OFF" else { return nil }
    let value = intensitySystem.uppercased() == "RIR" ? 10 - storedRPE : storedRPE
    // Also the prefill of the intensity field, so it uses the input format.
    return IOSNumber.format(value, fractionDigits: 1)
}

func iosWorkoutStoredRPE(
    inputValue: Double,
    intensitySystem: String
) -> Double {
    intensitySystem.uppercased() == "RIR" ? 10 - inputValue : inputValue
}

/// Returns the intensity scale that is actually editable for one workout row.
///
/// The global preference can disable intensity input for free/manual rows. An RPE/RIR plan is
/// different: its progression contract needs an RPE value even when the global preference is
/// OFF, so the row falls back to the canonical RPE scale just like Android.
func iosWorkoutEffectiveIntensitySystem(
    configuredSystem: String,
    planTarget: ILWorkoutPlanTarget?
) -> String {
    let configured = configuredSystem.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    guard configured == "OFF",
          planTarget?.progression.scheme.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "RPE_RIR"
    else {
        return configured
    }
    return "RPE"
}

enum IOSWorkoutIntensityInputError: Error, Equatable {
    case invalidRPE
    case invalidRIR

    var message: String {
        switch self {
        case .invalidRPE: return String(localized: "RPE muss zwischen 1 und 10 liegen.")
        case .invalidRIR: return String(localized: "RIR muss zwischen 0 und 9 liegen.")
        }
    }
}

/// Converts the visible editor value to canonical RPE while preserving a value from a hidden
/// OFF field. A blank visible value remains an intentional clear and therefore returns `nil`.
func iosWorkoutStoredIntensityForSave(
    existingRPE: Double?,
    inputText: String,
    configuredSystem: String,
    planTarget: ILWorkoutPlanTarget?
) throws -> Double? {
    let effectiveSystem = iosWorkoutEffectiveIntensitySystem(
        configuredSystem: configuredSystem,
        planTarget: planTarget
    )
    if effectiveSystem == "OFF" {
        return existingRPE
    }

    let trimmed = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
        return nil
    }
    guard let value = iosWorkoutParseDecimal(trimmed), value.isFinite else {
        throw effectiveSystem == "RIR"
            ? IOSWorkoutIntensityInputError.invalidRIR
            : IOSWorkoutIntensityInputError.invalidRPE
    }

    let lowerBound = effectiveSystem == "RIR" ? 0.0 : 1.0
    let upperBound = effectiveSystem == "RIR" ? 9.0 : 10.0
    guard value >= lowerBound, value <= upperBound else {
        throw effectiveSystem == "RIR"
            ? IOSWorkoutIntensityInputError.invalidRIR
            : IOSWorkoutIntensityInputError.invalidRPE
    }
    return iosWorkoutStoredRPE(inputValue: value, intensitySystem: effectiveSystem)
}
