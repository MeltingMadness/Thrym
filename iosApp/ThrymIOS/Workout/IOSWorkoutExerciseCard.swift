import SwiftUI

struct IOSWorkoutExerciseCard: View {
    let row: IOSWorkoutExerciseRow
    let unitSystem: String
    let intensitySystem: String
    let supersetTint: Color?
    let onAddSet: () -> Void
    let onApplyCoachWeight: (Double) -> Void
    let onEditSet: (ILWorkoutSet) -> Void
    let onDeleteSet: (ILWorkoutSet) -> Void
    let sessionID: Int64
    let onSaveSet: (ILWorkoutSet, ILSetIntention) async -> Bool
    /// Moving a single exercise; nil hides the arrows (superset members move with their header).
    var canMoveUp = false
    var canMoveDown = false
    var onMoveUp: (() -> Void)?
    var onMoveDown: (() -> Void)?

    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymAppearance) private var appearance

    var body: some View {
        let palette = theme.palette(for: colorScheme)
        let effectiveIntensitySystem = iosWorkoutEffectiveIntensitySystem(
            configuredSystem: intensitySystem,
            planTarget: row.target
        )

        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(row.exercise.displayName)
                            .font(.geist(.headline, weight: .semibold))
                        if row.target != nil {
                            Text("Plan")
                                .font(.geist(.caption, weight: .bold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(palette.primary.opacity(0.12), in: Capsule())
                                .thrymAccentText()
                        } else {
                            Text("Ad-hoc")
                                .font(.geist(.caption, weight: .bold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(palette.secondary.opacity(0.12), in: Capsule())
                                .thrymAccentText()
                        }
                    }
                    Text("\(row.exercise.primaryMuscleGroupDisplayName) · \(row.exercise.categoryDisplayName)")
                        .font(.geist(.caption))
                        .thrymSecondaryText()
                }
                Spacer(minLength: 8)
                if let onMoveUp, let onMoveDown {
                    IOSWorkoutMoveControls(
                        upLabel: String(localized: "\(row.exercise.displayName) nach oben verschieben"),
                        downLabel: String(localized: "\(row.exercise.displayName) nach unten verschieben"),
                        canMoveUp: canMoveUp,
                        canMoveDown: canMoveDown,
                        onMoveUp: onMoveUp,
                        onMoveDown: onMoveDown
                    )
                }
                Menu {
                    Button("Satz hinzufügen", systemImage: "plus") { onAddSet() }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                }
                .accessibilityLabel("Aktionen für \(row.exercise.displayName)")
            }

            if row.target != nil, let displayTarget = row.displayTarget {
                HStack(spacing: 12) {
                    Label("Ziel \(displayTarget.sets) × \(displayTarget.reps)", systemImage: "target")
                    // No weight target reads as bodyweight, as on Android: the weight is left out.
                    if displayTarget.weightKg > 0 {
                        Text("@ \(iosSetLoadText(weightKg: displayTarget.weightKg, unitSystem: unitSystem, addedLoad: row.exercise.tracksAddedLoad))")
                    }
                    Spacer()
                    Text("\(row.completedNormalSets)/\(displayTarget.sets)")
                        .font(.geist(.subheadline, weight: .semibold).monospacedDigit())
                        .foregroundStyle(row.isComplete ? palette.success : palette.textSecondary)
                }
                .font(.geist(.caption))
                .thrymSecondaryText()
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Ziel und Fortschritt")
                .accessibilityValue("\(row.completedNormalSets) von \(displayTarget.sets) Arbeitssätzen")
            }

            if let previousWeight = row.previousWorkWeightKg {
                Label("Letztes passendes Training: \(iosSetLoadText(weightKg: previousWeight, unitSystem: unitSystem, addedLoad: row.exercise.tracksAddedLoad))", systemImage: "clock.arrow.circlepath")
                    .font(.geist(.caption))
                    .foregroundStyle(palette.information)
                    .accessibilityLabel("Letztes passendes Gewicht \(iosSetLoadText(weightKg: previousWeight, unitSystem: unitSystem, addedLoad: row.exercise.tracksAddedLoad))")
            }

            if let recommendation = row.nextSetRecommendation {
                IOSWorkoutNextSetRecommendationView(
                    recommendation: recommendation,
                    unitSystem: unitSystem,
                    onApplyWeight: onApplyCoachWeight,
                    onApplyBackoff: onApplyCoachWeight
                )
            }

            if row.sets.isEmpty {
                Text("Noch kein Satz geloggt.")
                    .font(.geist(.subheadline))
                    .thrymSecondaryText()
                    .padding(.vertical, 4)
            } else {
                VStack(spacing: 0) {
                    ForEach(row.sets) { set in
                        IOSWorkoutSetRow(
                            set: set,
                            unitSystem: unitSystem,
                            addedLoad: row.exercise.tracksAddedLoad,
                            intensitySystem: effectiveIntensitySystem,
                            onEdit: { onEditSet(set) },
                            onDelete: { onDeleteSet(set) }
                        )
                        if set.id != row.sets.last?.id {
                            Divider()
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(palette.separator, lineWidth: 1)
                }
            }

            if row.target == nil || !row.isComplete {
                IOSWorkoutInlineSetEntry(row: row, sessionID: sessionID, unitSystem: unitSystem, onSave: onSaveSet)
                    .id("\(row.id)-\(row.nextSetNumber)-\(unitSystem)")
            } else {
                Label("Alle geplanten Sätze bestätigt", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(palette.success).font(.geist(.subheadline))
            }
            Button("Zusätzlichen Satz hinzufügen", action: onAddSet).font(.geist(.subheadline))

        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .modifier(IOSWorkoutCardSurface(
            liquidGlass: appearance == .liquidGlass,
            open: row.target == nil || !row.isComplete,
            ember: palette.surface
        ))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(
                    supersetTint?.opacity(0.72) ?? palette.separator,
                    lineWidth: supersetTint == nil ? 1 : 1.5
                )
        }
        .overlay(alignment: .leading) {
            if let supersetTint {
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(supersetTint)
                    .frame(width: 4)
                    .padding(.vertical, 5)
                    .padding(.leading, 1)
            }
        }
    }
}

/// Up and down for one block of the workout: a single exercise or, in its header, a whole
/// superset. At an edge the button is disabled; no foreground style is forced, so it dims.
struct IOSWorkoutMoveControls: View {
    let upLabel: String
    let downLabel: String
    let canMoveUp: Bool
    let canMoveDown: Bool
    let onMoveUp: () -> Void
    let onMoveDown: () -> Void

    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymAppearance) private var appearance

    var body: some View {
        let controls = HStack(spacing: 0) {
            arrow("chevron.up", label: upLabel, enabled: canMoveUp, action: onMoveUp)
            arrow("chevron.down", label: downLabel, enabled: canMoveDown, action: onMoveDown)
        }
        if appearance == .liquidGlass {
            controls.liquidGlass(in: Capsule())
        } else {
            controls.overlay {
                Capsule().stroke(theme.palette(for: colorScheme).separator, lineWidth: 1)
            }
        }
    }

    private func arrow(_ systemImage: String, label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// Ember: plain surface. Liquid Glass: glass, the open exercise on the strong level.
private struct IOSWorkoutCardSurface: ViewModifier {
    let liquidGlass: Bool
    let open: Bool
    let ember: Color

    func body(content: Content) -> some View {
        if liquidGlass {
            content.liquidGlass(open ? .strong : .standard, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        } else {
            content.background(ember, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
    }
}

private struct IOSWorkoutSetRow: View {
    let set: ILWorkoutSet
    let unitSystem: String
    /// Bodyweight exercise: the weight is the added load ("Körpergewicht + 10 kg").
    let addedLoad: Bool
    let intensitySystem: String
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Text("\(set.setNumber)")
                .font(.geist(.subheadline, weight: .semibold).monospacedDigit())
                .frame(width: 24, alignment: .leading)
                .thrymSecondaryText()

            Text(resolvedIOSWorkoutSetType(set).shortName)
                .font(.geist(.caption, weight: .semibold))
                .foregroundStyle(setTypeColor)
                .frame(width: 58, alignment: .leading)

            VStack(alignment: .leading, spacing: 2) {
                Text(iosSetValueText(weightKg: set.weightKg, reps: set.reps, unitSystem: unitSystem, addedLoad: addedLoad))
                    .font(.geist(.body, weight: .medium))
                if let intensity = iosWorkoutFormatIntensity(storedRPE: set.rpe, intensitySystem: intensitySystem) {
                    Text("\(intensitySystem.uppercased()) \(intensity)")
                        .font(.geist(.caption))
                        .thrymSecondaryText()
                }
            }

            Spacer(minLength: 4)

            Menu {
                Button("Bearbeiten", systemImage: "pencil") { onEdit() }
                Button("Löschen", systemImage: "trash", role: .destructive) { onDelete() }
            } label: {
                Image(systemName: "ellipsis")
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Satz \(set.setNumber) bearbeiten oder löschen")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Satz \(set.setNumber), \(resolvedIOSWorkoutSetType(set).displayName)")
        .accessibilityValue("\(set.reps) Wiederholungen, \(iosSetLoadText(weightKg: set.weightKg, unitSystem: unitSystem, addedLoad: addedLoad))")
        .contextMenu {
            Button("Bearbeiten", systemImage: "pencil") { onEdit() }
            Button("Löschen", systemImage: "trash", role: .destructive) { onDelete() }
        }
    }

    private var setTypeColor: Color {
        switch resolvedIOSWorkoutSetType(set) {
        case .normal: return .primary
        case .warmup: return .orange
        case .dropSet: return .purple
        case .failure: return .red
        case .backoff: return .secondary
        }
    }
}

private struct IOSWorkoutInlineSetEntry: View {
    @EnvironmentObject private var settings: IOSSettingsViewModel
    @Environment(\.thrymAppearance) private var appearance
    let row: IOSWorkoutExerciseRow
    let sessionID: Int64
    let unitSystem: String
    let onSave: (ILWorkoutSet, ILSetIntention) async -> Bool
    @State private var weight: String
    @State private var reps: String
    @State private var intensity = ""
    @State private var intention: ILSetIntention = .unknown
    @State private var kind: IOSWorkoutSetType
    @State private var busy = false
    @State private var error: String?

    init(row: IOSWorkoutExerciseRow, sessionID: Int64, unitSystem: String, onSave: @escaping (ILWorkoutSet, ILSetIntention) async -> Bool) {
        self.row = row; self.sessionID = sessionID; self.unitSystem = unitSystem; self.onSave = onSave
        _weight = State(initialValue: IOSNumber.format(IOSWeight.display(kg: row.defaultWeightKg, unit: unitSystem)))
        _reps = State(initialValue: row.targetReps > 0 ? String(row.targetReps) : "")
        _kind = State(initialValue: IOSWorkoutSetType(rawValue: row.nextPlannedSet?.kind ?? "NORMAL") ?? .normal)
    }
    private var source: String {
        if row.target?.setTargets.isEmpty == false { return String(localized: "Satzvorgabe aus dem Plan · editierbar") }
        if row.sets.contains(where: { resolvedIOSWorkoutSetType($0) == .normal }) { return String(localized: "Werte aus dem letzten Satz übernommen · editierbar") }
        if (row.displayTarget?.weightKg ?? 0) <= 0, row.previousWorkWeightKg != nil { return String(localized: "Gewicht aus dem letzten Training · editierbar") }
        return row.target == nil ? String(localized: "Werte für diesen Satz") : String(localized: "Werte aus dem Plan · editierbar")
    }
    var body: some View {
        if appearance == .liquidGlass {
            glassBody
        } else {
            emberBody
        }
    }

    private var intensityScale: String {
        iosWorkoutEffectiveIntensitySystem(configuredSystem: settings.state.intensitySystem, planTarget: row.target)
    }

    private var weightStep: Double { unitSystem.uppercased() == "IMPERIAL" ? 5 : 2.5 }

    private func adjustWeight(_ delta: Double) {
        let current = IOSNumber.parse(weight) ?? 0
        weight = IOSNumber.format(max(0, ((current + delta) * 100).rounded() / 100))
    }

    private func adjustReps(_ delta: Int) {
        let next = max(0, (IOSNumber.parseInt(reps) ?? 0) + delta)
        reps = next > 0 ? String(next) : ""
    }

    /// Liquid Glass: large steppers, intensity as a visible bar, bright confirm pill.
    /// Same state and the same `save()` as the Ember entry.
    private var glassBody: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text("Satz \(row.nextSetNumber)").font(.geist(.headline))
                Spacer()
                Text(source).font(.geist(.caption)).thrymSecondaryText().lineLimit(1)
            }
            HStack(spacing: 10) {
                let stepText = IOSNumber.format(weightStep)
                IOSWorkoutGlassStepper(
                    label: row.exercise.tracksAddedLoad ? String(localized: "Zusatzgewicht") : String(localized: "Gewicht"),
                    text: $weight,
                    unit: IOSWeight.label(unitSystem),
                    keyboard: .decimalPad,
                    decreaseLabel: String(localized: "Gewicht um \(stepText) \(IOSWeight.label(unitSystem)) verringern"),
                    increaseLabel: String(localized: "Gewicht um \(stepText) \(IOSWeight.label(unitSystem)) erhöhen"),
                    onDecrease: { adjustWeight(-weightStep) },
                    onIncrease: { adjustWeight(weightStep) }
                )
                IOSWorkoutGlassStepper(
                    label: String(localized: "Wiederholungen"),
                    text: $reps,
                    unit: nil,
                    keyboard: .numberPad,
                    decreaseLabel: String(localized: "Wiederholungen verringern"),
                    increaseLabel: String(localized: "Wiederholungen erhöhen"),
                    onDecrease: { adjustReps(-1) },
                    onIncrease: { adjustReps(1) }
                )
            }
            if intensityScale != "OFF" {
                VStack(alignment: .leading, spacing: 8) {
                    Text("ANSTRENGUNG · \(intensityScale)")
                        .font(.geist(.caption, weight: .bold))
                        .tracking(0.8)
                        .thrymSecondaryText()
                    // Input strings in the locale's format ("7.5" / "7,5"); the parser takes both.
                    IOSWorkoutGlassIntensityBar(
                        options: (intensityScale == "RIR" ? [0, 1, 2, 3, 4] : [7, 7.5, 8, 8.5, 9, 9.5, 10])
                            .map { IOSNumber.format($0, fractionDigits: 1) },
                        selection: $intensity
                    )
                }
            }
            DisclosureGroup("Satztyp · Absicht") {
                VStack(alignment: .leading, spacing: 12) {
                    Picker("Satztyp", selection: $kind) {
                        ForEach(IOSWorkoutSetType.allCases) { kind in Text(kind.displayName).tag(kind) }
                    }
                    Picker("Absicht", selection: $intention) {
                        Text("Nicht angegeben").tag(ILSetIntention.unknown)
                        Text("Geplant bis zum Versagen").tag(ILSetIntention.plannedFailure)
                        Text("Ziel unerwartet verfehlt").tag(ILSetIntention.unexpectedTargetMiss)
                    }
                    if settings.state.plateCalculatorEnabled, let value = IOSNumber.parse(weight) {
                        IOSWorkoutPlateVisualizer(targetWeightKg: IOSWeight.kilograms(value: value, unit: unitSystem), barbellWeightKg: settings.state.barbellWeightKg, availablePlates: settings.state.availablePlates, unitSystem: unitSystem)
                    }
                }.padding(.top, 8)
            }.font(.geist(.subheadline))
            if let error { Text(error).font(.geist(.caption)).foregroundStyle(.red) }
            Button { Task { await save() } } label: {
                Text(busy ? String(localized: "Speichert …") : String(localized: "Satz \(row.nextSetNumber) loggen"))
            }
            .buttonStyle(IOSWorkoutGlassPrimaryButtonStyle())
            .disabled(busy)
            if row.loggingSlots.count > 0 {
                let open = max(0, (row.remainingPlannedSets ?? 0) - 1)
                if open > 0 { Text("\(open) weitere geplante Sätze offen").font(.geist(.caption)).thrymSecondaryText() }
            }
        }
    }

    private var emberBody: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Satz \(row.nextSetNumber)").font(.geist(.headline))
            Text(source).font(.geist(.caption)).thrymAccentText()
            HStack(spacing: 12) {
                field(row.exercise.tracksAddedLoad
                      ? String(localized: "Zusatz (\(IOSWeight.label(unitSystem)))")
                      : String(localized: "Gewicht (\(IOSWeight.label(unitSystem)))"), text: $weight, keyboard: .decimalPad)
                field(String(localized: "Wiederholungen"), text: $reps, keyboard: .numberPad)
            }
            DisclosureGroup("RPE / RIR · Satztyp · Absicht") {
                VStack(alignment: .leading, spacing: 12) {
                    Picker("Satztyp", selection: $kind) {
                        ForEach(IOSWorkoutSetType.allCases) { kind in Text(kind.displayName).tag(kind) }
                    }
                    let scale = iosWorkoutEffectiveIntensitySystem(configuredSystem: settings.state.intensitySystem, planTarget: row.target)
                    if scale != "OFF" { field(scale, text: $intensity, keyboard: .decimalPad) }
                    Picker("Absicht", selection: $intention) {
                        Text("Nicht angegeben").tag(ILSetIntention.unknown)
                        Text("Geplant bis zum Versagen").tag(ILSetIntention.plannedFailure)
                        Text("Ziel unerwartet verfehlt").tag(ILSetIntention.unexpectedTargetMiss)
                    }
                    if settings.state.plateCalculatorEnabled, let value = IOSNumber.parse(weight) {
                        IOSWorkoutPlateVisualizer(targetWeightKg: IOSWeight.kilograms(value: value, unit: unitSystem), barbellWeightKg: settings.state.barbellWeightKg, availablePlates: settings.state.availablePlates, unitSystem: unitSystem)
                    }
                }.padding(.top, 8)
            }.font(.geist(.subheadline))
            if let error { Text(error).font(.geist(.caption)).foregroundStyle(.red) }
            Button { Task { await save() } } label: {
                Label(busy ? String(localized: "Speichert …") : String(localized: "Satz \(row.nextSetNumber) bestätigen"), systemImage: "checkmark")
                    .frame(maxWidth: .infinity).padding(.vertical, 6)
            }.buttonStyle(.borderedProminent).thrymButtonText().disabled(busy)
            if row.loggingSlots.count > 0 {
                let open = max(0, (row.remainingPlannedSets ?? 0) - 1)
                if open > 0 { Text("\(open) weitere geplante Sätze offen").font(.geist(.caption)).thrymSecondaryText() }
            }
        }.padding(14)
        .background(Color.accentColor.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
        .overlay { RoundedRectangle(cornerRadius: 14).stroke(Color.accentColor.opacity(0.6)) }
    }
    private func field(_ title: String, text: Binding<String>, keyboard: UIKeyboardType) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.geist(.caption)).thrymSecondaryText()
            TextField(title, text: text).keyboardType(keyboard).textFieldStyle(.roundedBorder)
                .font(.geist(.title3).monospacedDigit()).frame(minHeight: 44).accessibilityLabel(title)
        }
    }
    private func save() async {
        guard !busy, let count = IOSNumber.parseInt(reps), count > 0,
              let value = IOSNumber.parse(weight), value >= 0 else { error = String(localized: "Bitte Gewicht und Wiederholungen prüfen."); return }
        let kg = IOSWeight.kilograms(value: value, unit: unitSystem)
        guard kg.isFinite else { error = String(localized: "Gewicht ist ungültig."); return }
        let rpe: Double?
        do {
            rpe = try iosWorkoutStoredIntensityForSave(existingRPE: nil, inputText: intensity, configuredSystem: settings.state.intensitySystem, planTarget: row.target)
        } catch { self.error = String(localized: "Bitte Intensität unter RPE / RIR prüfen."); return }
        busy = true
        defer { busy = false }
        let set = ILWorkoutSet(sessionId: sessionID, exerciseId: row.exercise.id, setNumber: row.nextSetNumber, reps: count, weightKg: kg,
            setType: kind.rawValue, completedAt: Int64(Date().timeIntervalSince1970 * 1000), rpe: rpe, planTargetSnapshotId: row.targetID)
        if !(await onSave(set, intention)) { error = String(localized: "Satz konnte nicht gespeichert werden. Deine Eingaben bleiben erhalten.") }
    }
}
