import SwiftUI

struct IOSPlanEditorScreen: View {
    @Environment(IOSTrainingStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    let planID: Int64?
    let unitSystem: String

    @State private var name = ""
    @State private var rows: [IOSPlanExerciseDraft] = []
    @State private var hasLoaded = false
    @State private var hasEdits = false
    @State private var errorMessage: String?
    @State private var exercisePicker: IOSExercisePickerPresentation?
    @State private var progressionEditor: IOSProgressionEditorPresentation?
    @State private var isSaving = false
    @State private var isDeleting = false
    @State private var showDeleteConfirmation = false

    private var data: ILTrainingData? { store.data }
    private var existingPlan: ILTrainingPlan? { data?.trainingPlans.first(where: { $0.id == planID }) }
    private var availableExercises: [ILExercise] {
        (data?.exercises ?? [])
            .filter { !$0.isArchived }
            .sortedByDisplayName()
    }

    var body: some View {
        Form {
            Group {
                Section("Plan") {
                    TextField("Name", text: $name)
                        .textInputAutocapitalization(.words)
                        .onChange(of: name) { _, _ in hasEdits = true }

                    if let plan = existingPlan {
                        LabeledContent("Erstellt", value: plan.createdDate.formatted(date: .abbreviated, time: .omitted))
                    }
                }

                Section {
                    if rows.isEmpty {
                        ContentUnavailableView("Keine Übungen", systemImage: "figure.strengthtraining.traditional", description: Text("Füge mindestens eine Übung hinzu oder speichere den Plan zunächst ohne Ziele."))
                            .listRowBackground(Color.clear)
                    } else {
                        ForEach($rows) { row in
                            IOSPlanExerciseRow(
                                row: row,
                                exerciseName: exerciseName(for: row.wrappedValue.exerciseID),
                                addedLoad: data?.exercises.first(where: { $0.id == row.wrappedValue.exerciseID })?.tracksAddedLoad == true,
                                unitSystem: unitSystem,
                                index: rows.firstIndex(where: { $0.id == row.wrappedValue.id }) ?? 0,
                                isFirst: row.wrappedValue.id == rows.first?.id,
                                isLast: row.wrappedValue.id == rows.last?.id,
                                onMoveUp: { move(row.wrappedValue, by: -1) },
                                onMoveDown: { move(row.wrappedValue, by: 1) },
                                onRemove: { remove(row.wrappedValue) },
                                onGroupWithPrevious: { groupWithPrevious(row.wrappedValue) },
                                onUngroup: { ungroup(row.wrappedValue) },
                                onProgression: { progressionEditor = IOSProgressionEditorPresentation(rowID: row.wrappedValue.id, draft: row.wrappedValue.progression) }
                            )
                            .onChange(of: row.wrappedValue) { _, _ in hasEdits = true }
                        }
                        .onMove(perform: moveRows)
                    }

                    Button {
                        exercisePicker = IOSExercisePickerPresentation()
                    } label: {
                        Label("Übung hinzufügen", systemImage: "plus.circle.fill")
                    }
                    .disabled(availableExercises.isEmpty)
                } header: {
                    HStack {
                        Text("Übungen")
                        Spacer()
                        Text("\(rows.count)")
                            .thrymSecondaryText()
                    }
                } footer: {
                    Text("Die Reihenfolge wird im Training übernommen. Ziele werden in \(IOSWeight.label(unitSystem)) eingegeben.")
                }

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }
            }
            .thrymListRows()
        }
        .thrymScreenBackground()
        .navigationTitle(planID == nil ? String(localized: "Neuer Plan") : String(localized: "Plan bearbeiten"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Abbrechen") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(isSaving ? String(localized: "Speichert …") : String(localized: "Sichern")) {
                    Task { await save() }
                }
                .disabled(isSaving || store.isBusy || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            if planID != nil {
                ToolbarItem(placement: .bottomBar) {
                    Button("Plan löschen", systemImage: "trash", role: .destructive) {
                        showDeleteConfirmation = true
                    }
                    .disabled(isDeleting || store.isBusy)
                }
            }
        }
        .overlay {
            if isSaving || isDeleting {
                ProgressView()
                    .padding(22)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
        .task { loadIfNeeded() }
        .sheet(item: $exercisePicker) { _ in
            NavigationStack {
                IOSExercisePickerSheet(exercises: availableExercises) { exercise in
                    add(exercise)
                }
            }
        }
        .sheet(item: $progressionEditor) { presentation in
            NavigationStack {
                IOSProgressionEditorSheet(initialDraft: presentation.draft, unitSystem: unitSystem) { draft in
                    guard let index = rows.firstIndex(where: { $0.id == presentation.rowID }) else { return }
                    rows[index].progression = draft
                    hasEdits = true
                }
            }
        }
        .confirmationDialog("Plan löschen?", isPresented: $showDeleteConfirmation, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) {
                Task { await delete() }
            }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Der Plan und seine Ziele werden entfernt. Bereits aufgezeichnete Trainings bleiben erhalten.")
        }
    }

    private func loadIfNeeded() {
        guard !hasLoaded else { return }
        hasLoaded = true
        name = existingPlan?.name ?? ""
        let records = data?.planExercises
            .filter { $0.planId == (planID ?? 0) }
            .sorted { $0.orderIndex == $1.orderIndex ? $0.id < $1.id : $0.orderIndex < $1.orderIndex } ?? []
        rows = records.map { IOSPlanExerciseDraft(record: $0, unitSystem: unitSystem) }
        hasEdits = false
    }

    private func exerciseName(for id: Int64) -> String {
        data?.exercises.first(where: { $0.id == id })?.displayName ?? String(localized: "Übung nicht gefunden (ID \(id))")
    }

    private func add(_ exercise: ILExercise) {
        rows.append(IOSPlanExerciseDraft(exerciseID: exercise.id, unitSystem: unitSystem))
        hasEdits = true
    }

    private func remove(_ row: IOSPlanExerciseDraft) {
        rows.removeAll { $0.id == row.id }
        hasEdits = true
    }

    private func move(_ row: IOSPlanExerciseDraft, by offset: Int) {
        guard let index = rows.firstIndex(where: { $0.id == row.id }) else { return }
        let target = index + offset
        guard rows.indices.contains(target) else { return }
        rows.swapAt(index, target)
        hasEdits = true
    }

    private func moveRows(from offsets: IndexSet, to destination: Int) {
        rows.move(fromOffsets: offsets, toOffset: destination)
        hasEdits = true
    }

    private func groupWithPrevious(_ row: IOSPlanExerciseDraft) {
        guard let index = rows.firstIndex(where: { $0.id == row.id }), index > 0 else { return }
        let previous = rows[index - 1].supersetGroupID
        let nextGroup = previous ?? ((rows.compactMap(\.supersetGroupID).max() ?? 0) + 1)
        rows[index - 1].supersetGroupID = nextGroup
        rows[index].supersetGroupID = nextGroup
        hasEdits = true
    }

    private func ungroup(_ row: IOSPlanExerciseDraft) {
        guard let index = rows.firstIndex(where: { $0.id == row.id }) else { return }
        rows[index].supersetGroupID = nil
        hasEdits = true
    }

    private func save() async {
        guard !isSaving else { return }
        errorMessage = nil
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else {
            errorMessage = String(localized: "Bitte gib einen Namen ein.")
            return
        }

        var planExercises: [ILPlanExercise] = []
        for (index, row) in rows.enumerated() {
            guard row.setTargets.allSatisfy({ $0.reps > 0 && $0.reps <= 1000 && $0.weightKg.isFinite && $0.weightKg >= 0 }), row.setTargets.count <= 100 else {
                errorMessage = String(localized: "Bitte prüfe die einzelnen Satzvorgaben.")
                return
            }
            guard let sets = IOSNumber.parseInt(row.targetSets), sets > 0,
                  let reps = IOSNumber.parseInt(row.targetReps), reps > 0,
                  let displayWeight = row.targetWeightValue, displayWeight >= 0 else {
                errorMessage = String(localized: "Sätze, Wiederholungen und Gewicht müssen gültige Zielwerte sein.")
                return
            }
            let weightKg = IOSWeight.kilograms(value: displayWeight, unit: unitSystem)
            guard weightKg.isFinite else {
                errorMessage = String(localized: "Das Gewicht ist ungültig.")
                return
            }
            switch row.progression.validatedConfig() {
            case .failure(let validation):
                errorMessage = "\(exerciseName(for: row.exerciseID)): \(validation.message)"
                return
            case .success(let progression):
                planExercises.append(ILPlanExercise(
                    id: row.persistedID,
                    planId: planID ?? 0,
                    exerciseId: row.exerciseID,
                    orderIndex: index,
                    supersetGroupId: row.supersetGroupID,
                    targetSets: sets,
                    targetReps: reps,
                    targetWeightKg: weightKg,
                    setTargets: row.setTargets,
                    progression: progression
                ))
            }
        }

        isSaving = true
        defer { isSaving = false }
        let plan = ILTrainingPlan(id: planID ?? 0, name: trimmedName, createdAt: existingPlan?.createdAt ?? 0)
        if await store.savePlan(plan, exercises: planExercises) {
            hasEdits = false
            dismiss()
        } else {
            errorMessage = store.errorMessage ?? String(localized: "Der Plan konnte nicht gespeichert werden.")
        }
    }

    private func delete() async {
        guard let planID, !isDeleting else { return }
        isDeleting = true
        defer { isDeleting = false }
        if await store.command("plan.delete", fields: ["id": planID]) {
            dismiss()
        } else {
            errorMessage = store.errorMessage ?? String(localized: "Der Plan konnte nicht gelöscht werden.")
        }
    }
}

private struct IOSPlanExerciseRow: View {
    @Binding var row: IOSPlanExerciseDraft
    let exerciseName: String
    /// Bodyweight exercise: the weight target is the added load.
    let addedLoad: Bool
    let unitSystem: String
    let index: Int
    let isFirst: Bool
    let isLast: Bool
    let onMoveUp: () -> Void
    let onMoveDown: () -> Void
    let onRemove: () -> Void
    let onGroupWithPrevious: () -> Void
    let onUngroup: () -> Void
    let onProgression: () -> Void
    @State private var showingSetTargets = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("\(index + 1). \(exerciseName)")
                    .font(.geist(.headline))
                    .lineLimit(2)
                Spacer(minLength: 4)
                Menu {
                    Button("Nach oben", systemImage: "arrow.up", action: onMoveUp)
                        .disabled(isFirst)
                    Button("Nach unten", systemImage: "arrow.down", action: onMoveDown)
                        .disabled(isLast)
                    Divider()
                    Button("Entfernen", systemImage: "trash", role: .destructive, action: onRemove)
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                }
            }

            if let group = row.supersetGroupID {
                HStack(spacing: 6) {
                    Image(systemName: "link")
                    Text("Superset \(group)")
                    Spacer()
                    Button("Aufheben", action: onUngroup)
                        .font(.geist(.caption))
                }
                .font(.geist(.caption))
                .thrymAccentText()
            } else if !isFirst {
                Button {
                    onGroupWithPrevious()
                } label: {
                    Label("Mit vorheriger Übung gruppieren", systemImage: "link.badge.plus")
                        .font(.geist(.caption))
                }
            }

            HStack {
                Button("Einfach: \(row.targetSets) × \(row.targetReps)") { row.setTargets = [] }
                    .buttonStyle(.bordered)
                    .tint(row.setTargets.isEmpty ? .accentColor : .secondary)
                    .thrymAccentText()
                Button("Einzelne Sätze") { showingSetTargets = true }
                    .buttonStyle(.bordered)
                    .tint(row.setTargets.isEmpty ? .secondary : .accentColor)
                    .thrymAccentText()
            }
            if row.setTargets.isEmpty {
            HStack(spacing: 8) {
                IOSCompactField(title: String(localized: "Sätze"), text: $row.targetSets, keyboard: .numberPad)
                IOSCompactField(title: String(localized: "Reps"), text: $row.targetReps, keyboard: .numberPad)
                IOSCompactField(
                    title: addedLoad ? String(localized: "Zusatz (\(IOSWeight.label(unitSystem)))") : IOSWeight.label(unitSystem),
                    text: $row.targetWeight,
                    keyboard: .decimalPad
                )
            }

            } else {
                ForEach(Array(row.setTargets.enumerated()), id: \.offset) { index, target in
                    HStack {
                        Text("\(index + 1) · \(target.kind == "WARMUP" ? String(localized: "Aufwärmen") : target.kind == "BACKOFF" ? String(localized: "Backoff") : String(localized: "Arbeitssatz"))")
                        Spacer()
                        Text("\(IOSNumber.format(IOSWeight.display(kg: target.weightKg, unit: unitSystem))) × \(target.reps)").monospacedDigit()
                    }.font(.geist(.subheadline)).padding(.vertical, 6)
                }
                Button("Satzvorgaben bearbeiten") { showingSetTargets = true }
                Text("Einzelne Satzvorgaben werden manuell gesteigert.").font(.geist(.caption)).thrymSecondaryText()
            }
            Button(action: onProgression) {
                HStack {
                    Label("Progression", systemImage: row.progression.scheme == .manual ? "minus.circle" : "chart.line.uptrend.xyaxis")
                    Spacer()
                    Text(row.progression.scheme.title)
                        .thrymSecondaryText()
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .thrymSecondaryText()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!row.setTargets.isEmpty)
        }
        .sheet(isPresented: $showingSetTargets) {
            IOSIndividualSetTargetsEditor(row: $row, unitSystem: unitSystem)
        }
        .padding(.vertical, 4)
    }
}

private struct IOSCompactField: View {
    let title: String
    @Binding var text: String
    let keyboard: UIKeyboardType

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.geist(.caption))
                .thrymSecondaryText()
            TextField(title, text: $text)
                .keyboardType(keyboard)
                .textFieldStyle(.roundedBorder)
                .frame(minWidth: 62)
                .multilineTextAlignment(.center)
                .accessibilityLabel(title)
        }
    }
}

private struct IOSExercisePickerPresentation: Identifiable {
    let id = UUID()
}

private struct IOSExercisePickerSheet: View {
    let exercises: [ILExercise]
    let onSelect: (ILExercise) -> Void
    var body: some View {
        IOSWorkoutExercisePicker(exercises: exercises, excludedIDs: [], onSelect: onSelect)
    }
}

private struct IOSProgressionEditorPresentation: Identifiable {
    let id: UUID
    let rowID: UUID
    let draft: IOSProgressionDraft

    init(rowID: UUID, draft: IOSProgressionDraft) {
        id = rowID
        self.rowID = rowID
        self.draft = draft
    }
}

private struct IOSProgressionEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    let initialDraft: IOSProgressionDraft
    let unitSystem: String
    let onApply: (IOSProgressionDraft) -> Void
    @State private var draft: IOSProgressionDraft
    @State private var validationMessage: String?

    init(initialDraft: IOSProgressionDraft, unitSystem: String, onApply: @escaping (IOSProgressionDraft) -> Void) {
        self.initialDraft = initialDraft
        self.unitSystem = unitSystem
        self.onApply = onApply
        _draft = State(initialValue: initialDraft)
    }

    var body: some View {
        Form {
            Group {
                Section("Schema") {
                    Picker("Regel", selection: $draft.scheme) {
                        ForEach(IOSProgressionScheme.allCases) { scheme in
                            Text(scheme.title).tag(scheme)
                        }
                    }
                    .pickerStyle(.navigationLink)

                    if draft.scheme == .manual {
                        Text("Manuelle Ziele werden ohne automatische Änderung fortgeführt.")
                            .font(.geist(.footnote))
                            .thrymSecondaryText()
                    }
                }

                if draft.scheme != .manual {
                    Section("Schritt") {
                        HStack {
                            TextField("Schritt", text: $draft.stepValue)
                                .keyboardType(.decimalPad)
                            // German still reads "Einheit"; the key keeps kg/lb apart from sessions.
                            Picker("Maßeinheit", selection: $draft.stepUnit) {
                                Text("kg").tag("KG")
                                Text("lb").tag("LB")
                            }
                            .pickerStyle(.segmented)
                            .frame(maxWidth: 150)
                        }

                        switch draft.scheme {
                        case .manual:
                            EmptyView()
                        case .linear:
                            TextField("Erfolgreiche Einheiten vor der Steigerung", text: $draft.successThreshold)
                                .keyboardType(.numberPad)
                            Text("Alle Arbeitssätze müssen das Ziel erreichen. Deloads werden übersprungen; andere Lasten oder Ziele beginnen eine neue Bestätigung.")
                                .font(.geist(.footnote))
                                .thrymSecondaryText()
                        case .double:
                            TextField("Min-Reps", text: $draft.minReps)
                                .keyboardType(.numberPad)
                            TextField("Max-Reps", text: $draft.maxReps)
                                .keyboardType(.numberPad)
                        case .totalReps:
                            TextField("Ziel-Gesamtwiederholungen", text: $draft.totalReps)
                                .keyboardType(.numberPad)
                        case .rpeRir:
                            TextField("Ziel-RPE", text: $draft.targetRpe)
                                .keyboardType(.decimalPad)
                            TextField("RPE-Toleranz", text: $draft.rpeTolerance)
                                .keyboardType(.decimalPad)
                        }
                    }

                    Section("Fehlerbehandlung") {
                        TextField("Fehlerlimit", text: $draft.stallThreshold)
                            .keyboardType(.numberPad)
                        TextField("Backoff in %", text: $draft.backoffPercent)
                            .keyboardType(.decimalPad)
                        Text(IOSWeight.isImperial(unitSystem)
                            ? String(localized: "Auch 1,5-lb-Schritte sind möglich, etwa 4 → 5,5 → 7 lb.")
                            : String(localized: "Auch 1,5-kg-Schritte sind möglich, etwa 4 → 5,5 → 7 kg."))
                            .font(.geist(.footnote))
                            .thrymSecondaryText()
                    }
                }

                if let validationMessage {
                    Section {
                        Label(validationMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }
            }
            .thrymListRows()
        }
        .thrymScreenBackground()
        .navigationTitle("Progression")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Abbrechen") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Übernehmen") {
                    switch draft.validatedConfig() {
                    case .failure(let validation): validationMessage = validation.message
                    case .success:
                        onApply(draft)
                        dismiss()
                    }
                }
            }
        }
    }
}

private struct IOSSetTargetDraft: Identifiable {
    let id = UUID()
    var kind: String
    var weight: String
    var reps: String
}

private struct IOSIndividualSetTargetsEditor: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var row: IOSPlanExerciseDraft
    let unitSystem: String
    @State private var drafts: [IOSSetTargetDraft] = []
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section("Satz / Typ · Gewicht · Wiederholungen") {
                        ForEach($drafts) { $draft in
                            VStack(alignment: .leading) {
                                Picker("Typ", selection: $draft.kind) {
                                    Text("Aufwärmen").tag("WARMUP")
                                    Text("Arbeitssatz").tag("NORMAL")
                                    Text("Backoff").tag("BACKOFF")
                                }
                                HStack {
                                    IOSCompactField(title: IOSWeight.label(unitSystem), text: $draft.weight, keyboard: .decimalPad)
                                    IOSCompactField(title: String(localized: "Wdh."), text: $draft.reps, keyboard: .numberPad)
                                }
                            }
                        }.onDelete { drafts.remove(atOffsets: $0) }
                        Button("+ Arbeitssatz") { drafts.append(.init(kind: "NORMAL", weight: drafts.last?.weight ?? "0", reps: drafts.last?.reps ?? "10")) }.disabled(drafts.count >= 100)
                        Button("+ Aufwärmsatz") { drafts.insert(.init(kind: "WARMUP", weight: "0", reps: "10"), at: 0) }.disabled(drafts.count >= 100)
                    }
                    Section { Text("Progression: Manuell · Jede Satzvorgabe bleibt einzeln editierbar.") }
                    if let error { Text(error).foregroundStyle(.red) }
                }
                .thrymListRows()
            }
            .thrymScreenBackground()
            .navigationTitle("Satzvorgaben")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Übernehmen", action: apply) }
            }
            .onAppear {
                guard drafts.isEmpty else { return }
                let targets = row.setTargets.isEmpty
                    ? Array(repeating: ILPlannedSet(reps: Int(row.targetReps) ?? 10, weightKg: IOSWeight.kilograms(value: IOSNumber.parse(row.targetWeight) ?? 0, unit: unitSystem)), count: min(100, max(1, Int(row.targetSets) ?? 3)))
                    : row.setTargets
                drafts = targets.map { .init(kind: $0.kind, weight: IOSNumber.format(IOSWeight.display(kg: $0.weightKg, unit: unitSystem)), reps: String($0.reps)) }
            }
        }
    }
    private func apply() {
        let targets = drafts.compactMap { draft -> ILPlannedSet? in
            guard let reps = IOSNumber.parseInt(draft.reps), reps > 0, reps <= 1000,
                  let weight = IOSNumber.parse(draft.weight), weight >= 0 else { return nil }
            let kg = IOSWeight.kilograms(value: weight, unit: unitSystem)
            guard kg.isFinite else { return nil }
            return ILPlannedSet(kind: draft.kind, reps: reps, weightKg: kg)
        }
        let work = targets.filter { $0.kind != "WARMUP" }
        guard targets.count == drafts.count, let first = work.first else { error = String(localized: "Bitte gültige Werte und mindestens einen Arbeitssatz angeben."); return }
        row.setTargets = targets
        row.targetSets = String(work.count)
        row.targetReps = String(first.reps)
        row.targetWeight = IOSNumber.format(IOSWeight.display(kg: first.weightKg, unit: unitSystem))
        row.progression = IOSProgressionDraft(unitSystem: unitSystem)
        dismiss()
    }
}
