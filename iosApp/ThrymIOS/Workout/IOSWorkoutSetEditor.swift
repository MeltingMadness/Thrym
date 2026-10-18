import SwiftUI

struct IOSWorkoutSetEditorContext: Identifiable {
    let id: String
    let sessionID: Int64
    let row: IOSWorkoutExerciseRow
    let existingSet: ILWorkoutSet?
    let defaultSetType: IOSWorkoutSetType
    let suggestedWeightKg: Double?
    let intention: ILSetIntention

    init(
        id: String,
        sessionID: Int64,
        row: IOSWorkoutExerciseRow,
        existingSet: ILWorkoutSet?,
        defaultSetType: IOSWorkoutSetType,
        suggestedWeightKg: Double? = nil,
        intention: ILSetIntention = .unknown
    ) {
        self.id = id
        self.sessionID = sessionID
        self.row = row
        self.existingSet = existingSet
        self.defaultSetType = defaultSetType
        self.suggestedWeightKg = suggestedWeightKg
        self.intention = intention
    }
}

struct IOSWorkoutSetEditor: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var settings: IOSSettingsViewModel

    let context: IOSWorkoutSetEditorContext
    let onSave: (ILWorkoutSet, ILSetIntention) async -> Bool

    @State private var repsText: String
    @State private var weightText: String
    @State private var intensityText: String
    @State private var setType: IOSWorkoutSetType
    @State private var intention: ILSetIntention
    @State private var errorMessage: String?
    @State private var isSaving = false
    @State private var didApplyEnvironmentDefaults = false

    init(
        context: IOSWorkoutSetEditorContext,
        onSave: @escaping (ILWorkoutSet, ILSetIntention) async -> Bool
    ) {
        self.context = context
        self.onSave = onSave

        let existing = context.existingSet
        let initialWeight = existing?.weightKg ?? context.suggestedWeightKg ?? context.row.defaultWeightKg
        let inputWeight = iosWorkoutWeightInInputUnit(
            kilograms: initialWeight,
            unitSystem: "METRIC"
        )
        _repsText = State(initialValue: existing.map { String($0.reps) } ?? String(max(1, context.row.targetReps)))
        _weightText = State(initialValue: IOSNumber.format(inputWeight))
        _intensityText = State(initialValue: existing.flatMap {
            iosWorkoutFormatIntensity(storedRPE: $0.rpe, intensitySystem: "RPE")
        } ?? "")
        _setType = State(initialValue: existing.map(resolvedIOSWorkoutSetType) ?? context.defaultSetType)
        _intention = State(initialValue: context.intention)
    }

    private var intensitySystem: String {
        iosWorkoutEffectiveIntensitySystem(
            configuredSystem: settings.state.intensitySystem,
            planTarget: context.row.target
        )
    }

    private var unitSystem: String { settings.state.unitSystem.uppercased() }

    private var isEditing: Bool { context.existingSet != nil }

    /// Bodyweight exercises enter the added load, not the body weight.
    private var weightLabel: String {
        context.row.exercise.tracksAddedLoad ? String(localized: "Zusatzgewicht") : String(localized: "Gewicht")
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section {
                        HStack {
                            Text(context.row.exercise.displayName)
                                .font(.geist(.headline))
                            Spacer()
                            if let target = context.row.displayTarget {
                                Text("Ziel \(target.reps) Wdh.")
                                    .font(.geist(.caption))
                                    .thrymSecondaryText()
                            }
                        }
                    }

                    Section("Satz") {
                        Picker("Typ", selection: $setType) {
                            ForEach(IOSWorkoutSetType.allCases) { value in
                                Text(value.displayName).tag(value)
                            }
                        }
                        .disabled(isEditing)

                        TextField("Wiederholungen", text: $repsText)
                            .keyboardType(.numberPad)
                            .textContentType(.none)
                            .accessibilityLabel("Wiederholungen")

                        HStack {
                            TextField(weightLabel, text: $weightText)
                                .keyboardType(.decimalPad)
                                .textContentType(.none)
                                .accessibilityLabel(weightLabel)
                            Text(unitSystem == "IMPERIAL" ? String(localized: "lb") : String(localized: "kg"))
                                .thrymSecondaryText()
                        }

                        if settings.state.plateCalculatorEnabled,
                           let enteredWeight = iosWorkoutParseDecimal(weightText),
                           enteredWeight.isFinite,
                           enteredWeight >= 0 {
                            IOSWorkoutPlateVisualizer(
                                targetWeightKg: iosWorkoutInputWeightToKg(enteredWeight, unitSystem: unitSystem),
                                barbellWeightKg: settings.state.barbellWeightKg,
                                availablePlates: settings.state.availablePlates,
                                unitSystem: unitSystem
                            )
                        }

                        if intensitySystem != "OFF" {
                            HStack {
                                TextField(intensitySystem, text: $intensityText)
                                    .keyboardType(.decimalPad)
                                    .textContentType(.none)
                                    .accessibilityLabel(Text(intensitySystem == "RIR" ? String(localized: "Reps in Reserve") : String(localized: "Rate of Perceived Exertion")))
                                Text(intensitySystem == "RIR" ? "0–9" : "1–10")
                                    .thrymSecondaryText()
                            }
                            Text(intensitySystem == "RIR"
                                 ? String(localized: "0 bedeutet bis zum Versagen; 10 bedeutet sehr leicht.")
                                 : String(localized: "RPE 10 entspricht maximaler Anstrengung."))
                                .font(.geist(.caption))
                                .thrymSecondaryText()
                        }
                    }

                    Section("Absicht") {
                        Picker("Absicht", selection: $intention) {
                            ForEach(ILSetIntention.allCases, id: \.self) { value in
                                Text(ilSetIntentionText(value)).tag(value)
                            }
                        }
                        Text("Optional. Beschreibt, warum der Satz so endete, und bleibt ohne Auswahl nicht angegeben.")
                            .font(.geist(.caption))
                            .thrymSecondaryText()
                    }

                    if let errorMessage {
                        Section {
                            Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                                .foregroundStyle(.red)
                        }
                    }
                }
                .thrymListRows()
            }
            .thrymScreenBackground()
            .navigationTitle(isEditing ? String(localized: "Satz bearbeiten") : String(localized: "Satz hinzufügen"))
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { applyEnvironmentDefaults() }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? String(localized: "Speichern …") : String(localized: "Speichern")) {
                        Task { await save() }
                    }
                    .disabled(isSaving)
                }
            }
        }
    }

    private func save() async {
        guard !isSaving else { return }
        guard let reps = Int(repsText.trimmingCharacters(in: .whitespacesAndNewlines)), reps > 0 else {
            errorMessage = String(localized: "Bitte gib mindestens eine Wiederholung ein.")
            return
        }
        guard let enteredWeight = iosWorkoutParseDecimal(weightText), enteredWeight.isFinite, enteredWeight >= 0 else {
            errorMessage = String(localized: "Bitte gib ein gültiges Gewicht ein.")
            return
        }

        let existing = context.existingSet
        let parsedIntensity: Double?
        do {
            parsedIntensity = try iosWorkoutStoredIntensityForSave(
                existingRPE: existing?.rpe,
                inputText: intensityText,
                configuredSystem: settings.state.intensitySystem,
                planTarget: context.row.target
            )
        } catch let error as IOSWorkoutIntensityInputError {
            errorMessage = error.message
            return
        } catch {
            errorMessage = String(localized: "Bitte gib eine gültige Intensität ein.")
            return
        }

        let set = ILWorkoutSet(
            id: existing?.id ?? 0,
            sessionId: context.sessionID,
            exerciseId: context.row.exercise.id,
            setNumber: existing?.setNumber ?? context.row.nextSetNumber,
            reps: reps,
            weightKg: iosWorkoutInputWeightToKg(enteredWeight, unitSystem: unitSystem),
            setType: existing?.setType ?? setType.rawValue,
            completedAt: existing?.completedAt ?? Int64(Date().timeIntervalSince1970 * 1_000),
            rpe: parsedIntensity,
            planTargetSnapshotId: existing?.planTargetSnapshotId ?? context.row.target?.id,
            isWarmup: nil
        )

        isSaving = true
        let success = await onSave(set, intention)
        isSaving = false
        if success {
            dismiss()
        } else {
            errorMessage = String(localized: "Der Satz konnte nicht gespeichert werden.")
        }
    }

    private func applyEnvironmentDefaults() {
        guard !didApplyEnvironmentDefaults else { return }
        didApplyEnvironmentDefaults = true

        if let kilograms = iosWorkoutParseDecimal(weightText) {
            weightText = IOSNumber.format(
                iosWorkoutWeightInInputUnit(kilograms: kilograms, unitSystem: unitSystem)
            )
        }

        if let existingRPE = context.existingSet?.rpe,
           let displayValue = iosWorkoutFormatIntensity(
               storedRPE: existingRPE,
               intensitySystem: intensitySystem
           ) {
            intensityText = displayValue
        }
    }
}
