import Foundation
import SwiftUI
import UIKit

private struct IOSProgressionPosition: Hashable {
    let planID: Int64
    let exerciseID: Int64
    let orderIndex: Int
}

/// Review of the durable progression suggestions emitted by the shared store.
///
/// A suggestion is source evidence plus a proposed target. The source fields
/// are rendered read-only. SwiftUI only sends accept/reject commands while the
/// persisted status is `PENDING`; it never hides a decision locally after a
/// command, so the next snapshot remains the source of truth.
struct IOSProgressionReviewScreen: View {
    let sessionId: Int64?

    @Environment(IOSTrainingStore.self) private var store
    @EnvironmentObject private var settings: IOSSettingsViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var editingSuggestion: ILProgressionSuggestion?
    @State private var alert: IOSProgressionAlert?

    init(sessionId: Int64? = nil) {
        self.sessionId = sessionId
    }

    private var suggestions: [ILProgressionSuggestion] {
        guard let data = store.data else { return [] }
        return data.progressionSuggestions
            .filter { sessionId == nil || $0.sourceSessionId == sessionId }
            .sorted {
                if $0.status.uppercased() != $1.status.uppercased() {
                    // Pending decisions stay above historical decisions.
                    return $0.status.uppercased() == "PENDING"
                }
                return $0.createdAtEpochMillis > $1.createdAtEpochMillis
            }
    }

    private var pendingCount: Int {
        safePendingSuggestions.count
    }

    /// Mirrors Android's `safePendingItems`: only pending proposals with an
    /// actual target are actionable, and only the newest source session for a
    /// plan/exercise/order position is sent in a bulk decision.
    private var safePendingSuggestions: [ILProgressionSuggestion] {
        var seen = Set<IOSProgressionPosition>()
        return suggestions
            .filter { $0.status.uppercased() == "PENDING" && $0.suggestedTarget != nil }
            .sorted { lhs, rhs in
                if lhs.sourceSessionId != rhs.sourceSessionId {
                    return lhs.sourceSessionId > rhs.sourceSessionId
                }
                return lhs.createdAtEpochMillis > rhs.createdAtEpochMillis
            }
            .filter { suggestion in
                seen.insert(
                    IOSProgressionPosition(
                        planID: suggestion.planId,
                        exerciseID: suggestion.exerciseId,
                        orderIndex: suggestion.orderIndex
                    )
                ).inserted
            }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let error = store.errorMessage, store.data == nil {
                    ContentUnavailableView("Progression nicht verfügbar", systemImage: "exclamationmark.triangle", description: Text(error))
                } else if store.data == nil {
                    ProgressView("Progression wird geladen …")
                } else {
                    reviewContent
                }
            }
            .navigationTitle("Progression prüfen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Schließen") { dismiss() }
                        .disabled(store.isBusy)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        generate()
                    } label: {
                        Label("Auswertung erzeugen", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .disabled(store.isBusy)
                    .accessibilityHint("Speichert neue Progressionsvorschläge, falls eine Auswertung möglich ist")
                }
            }
        }
        .sheet(item: $editingSuggestion) { suggestion in
            IOSProgressionEditSheet(
                suggestion: suggestion,
                unitSystem: settings.state.unitSystem,
                isWorking: store.isBusy,
                onAccept: { target in accept(suggestion, target: target) },
                onCancel: { editingSuggestion = nil }
            )
        }
        .alert(item: $alert) { item in
            Alert(title: Text(item.title), message: Text(item.message), dismissButton: .default(Text("OK")))
        }
    }

    /// Open proposals with a target: the decisions the athlete is here for.
    private var openSuggestions: [ILProgressionSuggestion] {
        suggestions.filter { $0.status.uppercased() == "PENDING" && $0.suggestedTarget != nil }
    }

    /// Results without a decision: notes of the latest evaluation.
    private var noteSuggestions: [ILProgressionSuggestion] {
        suggestions.filter { ["PENDING", "INFORMATIONAL"].contains($0.status.uppercased()) && $0.suggestedTarget == nil }
    }

    private var decidedSuggestions: [ILProgressionSuggestion] {
        suggestions.filter { !["PENDING", "INFORMATIONAL"].contains($0.status.uppercased()) }
    }

    @ViewBuilder
    private var reviewContent: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                if suggestions.isEmpty {
                    ContentUnavailableView(
                        sessionId == nil ? String(localized: "Keine Progressionshinweise") : String(localized: "Keine Hinweise für dieses Training"),
                        systemImage: "chart.line.uptrend.xyaxis",
                        description: Text(
                            sessionId == nil
                                ? String(localized: "Nach einem abgeschlossenen Training mit aktivierter Progressionsregel erscheinen neue Hinweise hier.")
                                : String(localized: "Für dieses Training wurde noch keine auswertbare Progression gespeichert.")
                        )
                    )
                    .padding(.top, 24)
                } else {
                    if !openSuggestions.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(pendingCount) Entscheidungen ausstehend")
                                .font(.geist(.title3, weight: .bold))
                            Text("Dein Plan ändert sich erst, wenn du übernimmst.")
                                .font(.geist(.subheadline))
                                .thrymSecondaryText()
                        }
                        .padding(.horizontal, 4)
                        ForEach(openSuggestions) { suggestion in
                            IOSProgressionDecisionCard(
                                suggestion: suggestion,
                                exerciseName: exerciseName(for: suggestion),
                                planName: planName(for: suggestion),
                                evidence: evidence(for: suggestion),
                                unitSystem: settings.state.unitSystem,
                                isWorking: store.isBusy,
                                onAccept: {
                                    guard let target = suggestion.suggestedTarget else { return }
                                    accept(suggestion, target: target)
                                },
                                onEdit: { editingSuggestion = suggestion },
                                onReject: { reject(suggestion) }
                            )
                        }
                    }
                    if !noteSuggestions.isEmpty {
                        IOSProgressionCompactSection(
                            title: String(localized: "Hinweise"),
                            suggestions: noteSuggestions,
                            unitSystem: settings.state.unitSystem,
                            initiallyExpanded: openSuggestions.isEmpty,
                            exerciseName: exerciseName(for:)
                        )
                    }
                    if !decidedSuggestions.isEmpty {
                        IOSProgressionCompactSection(
                            title: String(localized: "Frühere Entscheidungen"),
                            suggestions: decidedSuggestions,
                            unitSystem: settings.state.unitSystem,
                            initiallyExpanded: false,
                            exerciseName: exerciseName(for:)
                        )
                    }
                }
            }
            .padding(16)
        }
        .thrymScreenBackground(ember: Color(uiColor: .systemGroupedBackground))
        // One tap for the usual case: every open proposal is fine as it is.
        .safeAreaInset(edge: .bottom) {
            if safePendingSuggestions.count > 1 {
                Button {
                    acceptAllSafe()
                } label: {
                    Text("Alle \(safePendingSuggestions.count) übernehmen")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent).thrymButtonText()
                .disabled(store.isBusy || editingSuggestion != nil)
                .accessibilityHint("Übernimmt je Position nur den neuesten ausstehenden Vorschlag")
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(.ultraThinMaterial)
            }
        }
    }

    private func exerciseName(for suggestion: ILProgressionSuggestion) -> String? {
        store.data?.exercises.first(where: { $0.id == suggestion.exerciseId })?.displayName
    }

    private func planName(for suggestion: ILProgressionSuggestion) -> String? {
        store.data?.trainingPlans.first(where: { $0.id == suggestion.planId })?.name
    }

    private func evidence(for suggestion: ILProgressionSuggestion) -> [ILWorkoutSet] {
        guard let data = store.data else { return [] }
        let ids = Set(suggestion.countedSetIds)
        guard !ids.isEmpty else { return [] }
        return data.workoutSets
            .filter { ids.contains($0.id) }
            .sorted {
                if $0.setNumber != $1.setNumber { return $0.setNumber < $1.setNumber }
                return $0.completedAt < $1.completedAt
            }
    }

    private func generate() {
        Task { @MainActor in
            var fields: [String: Any] = [:]
            if let sessionId { fields["sessionId"] = sessionId }
            let success = await store.command("progression.generate", fields: fields)
            if !success {
                alert = IOSProgressionAlert(title: String(localized: "Auswertung fehlgeschlagen"), message: store.errorMessage ?? String(localized: "Die Progression konnte nicht ausgewertet werden."))
            }
        }
    }

    private func accept(_ suggestion: ILProgressionSuggestion, target: ILProgressionTarget) {
        guard suggestion.status.uppercased() == "PENDING", suggestion.suggestedTarget != nil else { return }
        guard target.sets > 0, target.reps > 0, target.weightKg.isFinite, target.weightKg >= 0 else {
            alert = IOSProgressionAlert(title: String(localized: "Ungültiges Ziel"), message: String(localized: "Sätze und Wiederholungen müssen positiv sein; das Gewicht darf nicht negativ sein."))
            return
        }
        Task { @MainActor in
            let targetPayload: [String: Any] = [
                "id": suggestion.id,
                "sets": target.sets,
                "reps": target.reps,
                "weightKg": target.weightKg
            ]
            let success = await store.command("progression.accept", fields: ["targets": [targetPayload]])
            if success {
                editingSuggestion = nil
            } else {
                alert = IOSProgressionAlert(title: String(localized: "Vorschlag nicht übernommen"), message: store.errorMessage ?? String(localized: "Der Vorschlag ist möglicherweise nicht mehr aktuell."))
            }
        }
    }

    private func acceptAllSafe() {
        guard !store.isBusy, editingSuggestion == nil else { return }
        let selected = safePendingSuggestions.compactMap { suggestion -> [String: Any]? in
            guard let target = suggestion.suggestedTarget else { return nil }
            return [
                "id": suggestion.id,
                "sets": target.sets,
                "reps": target.reps,
                "weightKg": target.weightKg
            ]
        }
        guard !selected.isEmpty else { return }
        Task { @MainActor in
            let success = await store.command("progression.accept", fields: ["targets": selected])
            if success {
                editingSuggestion = nil
            } else {
                alert = IOSProgressionAlert(
                    title: String(localized: "Vorschläge nicht übernommen"),
                    message: store.errorMessage ?? String(localized: "Die Vorschläge sind möglicherweise nicht mehr aktuell.")
                )
            }
        }
    }

    private func reject(_ suggestion: ILProgressionSuggestion) {
        guard suggestion.status.uppercased() == "PENDING", suggestion.suggestedTarget != nil else { return }
        Task { @MainActor in
            let success = await store.command("progression.reject", fields: ["id": suggestion.id])
            if !success {
                alert = IOSProgressionAlert(title: String(localized: "Vorschlag nicht verworfen"), message: store.errorMessage ?? String(localized: "Der Vorschlag ist möglicherweise nicht mehr aktuell."))
            }
        }
    }
}

/// What a proposal changes, reduced to the one line the athlete decides on.
struct IOSProgressionChange: Equatable {
    enum Direction { case up, down, same }

    /// Old and new value of the dimension that moves, e.g. "100 kg" and "102,5 kg".
    let from: String
    let to: String
    /// Signed difference, e.g. "+2,5 kg" or "−1 Wdh"; nil when nothing changes.
    let delta: String?
    let direction: Direction
    /// A second dimension that moves along, e.g. the reps falling back when the weight rises.
    let also: String?

    static func between(_ old: ILProgressionTarget, _ new: ILProgressionTarget, unitSystem: String) -> IOSProgressionChange {
        func weight(_ kg: Double) -> String { IOSWeightFormatter.format(kg, unitSystem: unitSystem) }
        func signed(_ difference: Double, _ text: String) -> String { (difference > 0 ? "+" : "−") + text }
        let weightDifference = new.weightKg - old.weightKg
        let repsDifference = new.reps - old.reps
        let setsDifference = new.sets - old.sets
        let repsText = repsDifference == 0 ? nil : String(localized: "\(old.reps) → \(new.reps) Wdh")
        let setsText = setsDifference == 0 ? nil : String(localized: "\(old.sets) → \(new.sets) Sätze")
        if abs(weightDifference) > 0.001 {
            return IOSProgressionChange(
                from: weight(old.weightKg), to: weight(new.weightKg),
                delta: signed(weightDifference, weight(abs(weightDifference))),
                direction: weightDifference > 0 ? .up : .down,
                also: [repsText, setsText].compactMap { $0 }.joined(separator: " · ").nilIfEmpty
            )
        }
        if repsDifference != 0 {
            return IOSProgressionChange(
                from: "\(old.reps)", to: String(localized: "\(new.reps) Wdh"),
                delta: signed(Double(repsDifference), String(localized: "\(abs(repsDifference)) Wdh")),
                direction: repsDifference > 0 ? .up : .down,
                also: setsText
            )
        }
        if setsDifference != 0 {
            return IOSProgressionChange(
                from: "\(old.sets)", to: ilSetCountText(new.sets),
                delta: signed(Double(setsDifference), ilSetCountText(abs(setsDifference))),
                direction: setsDifference > 0 ? .up : .down,
                also: nil
            )
        }
        return IOSProgressionChange(from: weight(old.weightKg), to: weight(new.weightKg), delta: nil, direction: .same, also: nil)
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

private func ilProgressionTargetText(_ target: ILProgressionTarget, unitSystem: String) -> String {
    String(localized: "\(target.sets) Sätze × \(target.reps) Wdh · \(IOSWeightFormatter.format(target.weightKg, unitSystem: unitSystem))")
}

/// One open proposal: what changes in large type, one button to take it, the reasoning on demand.
private struct IOSProgressionDecisionCard: View {
    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme

    let suggestion: ILProgressionSuggestion
    let exerciseName: String?
    let planName: String?
    let evidence: [ILWorkoutSet]
    let unitSystem: String
    let isWorking: Bool
    let onAccept: () -> Void
    let onEdit: () -> Void
    let onReject: () -> Void

    @State private var showsDetails = false

    /// Why the proposal is there. A plain increase already shows its step above, so the line
    /// says what earned it instead of repeating the number.
    private var reason: String {
        if ["LOAD_ADVANCED", "REP_TARGET_ADVANCED"].contains(suggestion.reasonCode.uppercased()),
           let counted = suggestion.reasonArguments["countedWorkSets"], counted >= 1, counted.rounded() == counted {
            return String(localized: "Ziel in allen \(Int(counted)) gewerteten Sätzen erreicht.")
        }
        return IOSProgressionReasonText.text(for: suggestion, unitSystem: unitSystem)
    }

    var body: some View {
        let palette = theme.palette(for: colorScheme)
        let target = suggestion.suggestedTarget ?? suggestion.sourceTarget
        let change = IOSProgressionChange.between(suggestion.sourceTarget, target, unitSystem: unitSystem)
        let deltaColor: Color = change.direction == .down ? .orange : palette.success
        ThrymCard(tone: .elevated) {
            HStack(alignment: .firstTextBaseline) {
                Text(exerciseName ?? String(localized: "Übung \(suggestion.exerciseId)"))
                    .font(.geist(.headline))
                Spacer(minLength: 8)
                if let planName {
                    Text(planName).font(.geist(.caption)).thrymSecondaryText().lineLimit(1)
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                if change.direction == .same {
                    Text("Ziel bleibt")
                        .font(.geist(.title2, weight: .bold))
                } else {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(change.from)
                            .font(.geist(.title3).monospacedDigit())
                            .thrymSecondaryText()
                        Image(systemName: "arrow.right")
                            .font(.subheadline.weight(.bold))
                            .thrymSecondaryText()
                        Text(change.to)
                            .font(.geist(.title, weight: .bold).monospacedDigit())
                        if let delta = change.delta {
                            Text(delta)
                                .font(.geist(.caption, weight: .bold).monospacedDigit())
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .foregroundStyle(deltaColor)
                                .background(deltaColor.opacity(0.16), in: Capsule())
                        }
                    }
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    if let also = change.also {
                        Text(also).font(.geist(.subheadline, weight: .semibold).monospacedDigit())
                    }
                }
                Text("Neues Ziel: \(ilProgressionTargetText(target, unitSystem: unitSystem))")
                    .font(.geist(.subheadline).monospacedDigit())
                    .thrymSecondaryText()
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(change.direction == .same
                ? String(localized: "Ziel bleibt")
                : String(localized: "\(change.from) → \(change.to)"))
            .accessibilityValue(String(localized: "Neues Ziel: \(ilProgressionTargetText(target, unitSystem: unitSystem))"))

            Text(reason)
                .font(.geist(.subheadline))

            Button(action: onAccept) {
                Text("Übernehmen").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).thrymButtonText()
            .disabled(isWorking)

            HStack(spacing: 8) {
                Button("Anpassen", action: onEdit)
                    .buttonStyle(.bordered)
                Button("Verwerfen", action: onReject)
                    .buttonStyle(.borderless)
                Spacer(minLength: 0)
                Button {
                    showsDetails.toggle()
                } label: {
                    Label("Details", systemImage: showsDetails ? "chevron.up" : "chevron.down")
                }
                .buttonStyle(.borderless)
                .accessibilityValue(showsDetails ? String(localized: "Geöffnet") : String(localized: "Geschlossen"))
            }
            .controlSize(.small)
            .disabled(isWorking)

            if showsDetails {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Bisher: \(ilProgressionTargetText(suggestion.sourceTarget, unitSystem: unitSystem))")
                    IOSProgressionEvidenceBlock(sets: evidence, idsWerePresent: !suggestion.countedSetIds.isEmpty, unitSystem: unitSystem)
                    if let explanation = IOSProgressionReasonText.evidence(for: suggestion, unitSystem: unitSystem) {
                        Text(explanation)
                    }
                    Text("Schema: \(suggestion.sourceProgression.schemeDisplayName)")
                    Text("Bewertung: \(IOSProgressionFormatting.date(suggestion.createdAt))")
                }
                .font(.geist(.footnote))
                .thrymSecondaryText()
            }
        }
    }
}

/// Notes and earlier decisions: one line each, folded away under the open proposals.
private struct IOSProgressionCompactSection: View {
    let title: String
    let suggestions: [ILProgressionSuggestion]
    let unitSystem: String
    let exerciseName: (ILProgressionSuggestion) -> String?
    @State private var expanded: Bool

    init(title: String, suggestions: [ILProgressionSuggestion], unitSystem: String, initiallyExpanded: Bool, exerciseName: @escaping (ILProgressionSuggestion) -> String?) {
        self.title = title
        self.suggestions = suggestions
        self.unitSystem = unitSystem
        self.exerciseName = exerciseName
        _expanded = State(initialValue: initiallyExpanded)
    }

    var body: some View {
        ThrymCard(tone: .muted) {
            Button {
                expanded.toggle()
            } label: {
                HStack {
                    Text(title).font(.geist(.headline))
                    Text(verbatim: "\(suggestions.count)").font(.geist(.subheadline).monospacedDigit()).thrymSecondaryText()
                    Spacer()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down").thrymSecondaryText()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(expanded ? String(localized: "Geöffnet") : String(localized: "Geschlossen"))

            if expanded {
                ForEach(suggestions) { suggestion in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(exerciseName(suggestion) ?? String(localized: "Übung \(suggestion.exerciseId)"))
                                .font(.geist(.subheadline, weight: .semibold))
                            Spacer(minLength: 8)
                            IOSProgressionStatusBadge(status: suggestion.status)
                        }
                        if let target = suggestion.finalTarget ?? suggestion.suggestedTarget {
                            let change = IOSProgressionChange.between(suggestion.sourceTarget, target, unitSystem: unitSystem)
                            Text(change.direction == .same
                                ? ilProgressionTargetText(target, unitSystem: unitSystem)
                                : String(localized: "\(change.from) → \(change.to)"))
                                .font(.geist(.subheadline).monospacedDigit())
                        }
                        Text(IOSProgressionReasonText.text(for: suggestion, unitSystem: unitSystem))
                            .font(.geist(.caption))
                            .thrymSecondaryText()
                        Text(suggestion.decidedAt.map { String(localized: "Entschieden: \(IOSProgressionFormatting.date($0))") }
                            ?? String(localized: "Bewertung: \(IOSProgressionFormatting.date(suggestion.createdAt))"))
                            .font(.geist(.caption))
                            .thrymSecondaryText()
                    }
                    .padding(.vertical, 4)
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }
}

private struct IOSProgressionStatusBadge: View {
    let status: String

    var body: some View {
        Text(IOSProgressionFormatting.status(status))
            .font(.geist(.caption, weight: .semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(background, in: Capsule())
            .accessibilityLabel("Status: \(IOSProgressionFormatting.status(status))")
    }

    private var background: Color {
        switch status.uppercased() {
        case "PENDING": return .orange.opacity(0.18)
        case "ACCEPTED": return .green.opacity(0.18)
        case "REJECTED": return .red.opacity(0.15)
        default: return .secondary.opacity(0.15)
        }
    }
}

private struct IOSProgressionEvidenceBlock: View {
    let sets: [ILWorkoutSet]
    let idsWerePresent: Bool
    let unitSystem: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Gewertete Sätze").font(.geist(.footnote, weight: .semibold))
            if sets.isEmpty {
                Text(idsWerePresent ? String(localized: "Die gespeicherte Evidenz ist nicht verfügbar.") : String(localized: "Keine verwertbaren Arbeitssätze gespeichert."))
            } else {
                ForEach(sets) { set in
                    HStack(spacing: 6) {
                        Text("Satz \(set.setNumber):")
                        Text("\(IOSWeightFormatter.format(set.weightKg, unitSystem: unitSystem)) × \(set.reps) Wdh")
                        if set.resolvedSetType == "FAILURE" { Text("Bis zum Versagen") }
                        if let rpe = set.rpe, rpe.isFinite {
                            Text("· RPE \(IOSProgressionFormatting.number(rpe))")
                        }
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
    }
}

private struct IOSProgressionEditSheet: View {
    let suggestion: ILProgressionSuggestion
    let unitSystem: String
    let isWorking: Bool
    let onAccept: (ILProgressionTarget) -> Void
    let onCancel: () -> Void

    @State private var setsText: String
    @State private var repsText: String
    @State private var weightText: String
    @State private var errorMessage: String?

    init(suggestion: ILProgressionSuggestion, unitSystem: String, isWorking: Bool, onAccept: @escaping (ILProgressionTarget) -> Void, onCancel: @escaping () -> Void) {
        self.suggestion = suggestion
        self.unitSystem = unitSystem
        self.isWorking = isWorking
        self.onAccept = onAccept
        self.onCancel = onCancel
        let target = suggestion.suggestedTarget ?? suggestion.sourceTarget
        _setsText = State(initialValue: "\(target.sets)")
        _repsText = State(initialValue: "\(target.reps)")
        _weightText = State(initialValue: IOSProgressionFormatting.displayedWeight(target.weightKg, unitSystem: unitSystem))
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section("Ziel bearbeiten") {
                        TextField("Sätze", text: $setsText).keyboardType(.numberPad)
                        TextField("Wiederholungen", text: $repsText).keyboardType(.numberPad)
                        TextField("Gewicht (\(IOSWeightFormatter.unitLabel(for: unitSystem)))", text: $weightText).keyboardType(.decimalPad)
                        if let errorMessage {
                            Text(errorMessage).font(.geist(.footnote)).foregroundStyle(.red)
                        }
                    }
                    Section {
                        Text("Die Quelle und ihre Evidenz bleiben unverändert. Deine Eingabe wird als finales Ziel gespeichert.")
                            .font(.geist(.footnote))
                            .thrymSecondaryText()
                    }
                }
                .thrymListRows()
            }
            .navigationTitle("Ziel bearbeiten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen", action: onCancel)
                        .disabled(isWorking)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Übernehmen", action: save)
                        .disabled(isWorking)
                }
            }
        }
    }

    private func save() {
        guard let sets = Int(setsText), sets > 0 else {
            errorMessage = String(localized: "Gib eine positive Satzzahl ein.")
            return
        }
        guard let reps = Int(repsText), reps > 0 else {
            errorMessage = String(localized: "Gib eine positive Wiederholungszahl ein.")
            return
        }
        guard let displayedWeight = IOSNumber.parse(weightText), displayedWeight >= 0 else {
            errorMessage = String(localized: "Gib ein gültiges Gewicht ein.")
            return
        }
        onAccept(
            ILProgressionTarget(
                sets: sets,
                reps: reps,
                weightKg: IOSWeightFormatter.kilograms(fromDisplayedValue: displayedWeight, unitSystem: unitSystem)
            )
        )
    }
}

private enum IOSProgressionFormatting {
    static func status(_ raw: String) -> String {
        switch raw.uppercased() {
        case "PENDING": return String(localized: "Ausstehend")
        case "INFORMATIONAL": return String(localized: "Hinweis")
        case "ACCEPTED": return String(localized: "Übernommen")
        case "REJECTED": return String(localized: "Verworfen")
        case "STALE": return String(localized: "Nicht mehr aktuell")
        default: return raw
        }
    }

    static func date(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.dateStyle = .medium
        return formatter.string(from: date)
    }

    static func number(_ value: Double) -> String {
        IOSHistoryFormatting.number(value, maximumFractionDigits: 2)
    }

    /// Text-field prefill: ungrouped, so [IOSNumber.parse] reads 1000 and more back unchanged.
    static func displayedWeight(_ kilograms: Double, unitSystem: String) -> String {
        let value = unitSystem.uppercased() == "IMPERIAL" ? kilograms * IOSWeightFormatter.poundsPerKilogram : kilograms
        return IOSNumber.format(value)
    }
}

private enum IOSProgressionReasonText {
    static func text(for suggestion: ILProgressionSuggestion, unitSystem: String) -> String {
        let args = suggestion.reasonArguments
        switch suggestion.reasonCode.uppercased() {
        case "SUCCESS_CONFIRMATION_REQUIRED":
            guard let achieved = whole(args["successfulSessions"]), let required = whole(args["requiredSuccesses"]),
                  achieved >= 1, achieved < required, (2...6).contains(required) else { return unavailable }
            return String(localized: "\(achieved) von \(required) vergleichbaren Erfolgen erreicht. Das Ziel bleibt bis zur Bestätigung unverändert.")
        case "MIXED_LOADS":
            return String(localized: "Die Arbeitssätze wurden mit unterschiedlichen Gewichten ausgeführt und bestätigen das Ziel noch nicht gemeinsam. Ziel beibehalten; diese Einheit zählt nicht als vergleichbarer Fehlversuch.")
        case "REP_TARGET_ADVANCED":
            return String(localized: "Das Wiederholungsziel wurde erreicht und wird erhöht.")
        case "LOAD_ADVANCED":
            guard let step = configuredStep(for: suggestion) else { return unavailable }
            return String(localized: "Das Zielgewicht steigt um \(IOSProgressionFormatting.number(step.value)) \(step.unit).")
        case "TOTAL_REPS_COMPLETED":
            guard let achieved = whole(args["achievedTotalReps"]), achieved >= 0,
                  let target = whole(args["targetTotalReps"]), target > 0,
                  let step = configuredStep(for: suggestion) else { return unavailable }
            return String(localized: "\(achieved) von \(target) Zielwiederholungen wurden erreicht; das Gewicht steigt um \(IOSProgressionFormatting.number(step.value)) \(step.unit).")
        case "RPE_WITHIN_TARGET":
            guard let highest = bounded(args["highestRpe"], 1...10),
                  let target = bounded(args["targetRpe"], 1...10),
                  let tolerance = bounded(args["tolerance"], 0...2),
                  let step = configuredStep(for: suggestion) else { return unavailable }
            return String(localized: "Die höchste RPE war \(IOSProgressionFormatting.number(highest)) bei Ziel \(IOSProgressionFormatting.number(target)) ± \(IOSProgressionFormatting.number(tolerance)); das Gewicht steigt um \(IOSProgressionFormatting.number(step.value)) \(step.unit).")
        case "REPEAT_TARGET":
            if let highest = bounded(args["highestRpe"], 1...10) {
                return String(localized: "Die höchste RPE war \(IOSProgressionFormatting.number(highest)). Das Wiederholungsziel bleibt gleich; das trainierte Gewicht wird beibehalten.")
            }
            if let target = whole(args["targetReps"]), target > 0,
               let actual = whole(args["actualReps"]), actual >= 0 {
                return String(localized: "Mindestens \(target) Wiederholungen waren erwartet, erreicht wurden \(actual). Das Wiederholungsziel bleibt gleich; das trainierte Gewicht wird beibehalten.")
            }
            if let achieved = whole(args["achievedTotalReps"]), achieved >= 0,
               let target = whole(args["targetTotalReps"]), target > 0 {
                return String(localized: "\(achieved) von \(target) Zielwiederholungen wurden erreicht. Das Wiederholungsziel bleibt gleich; das trainierte Gewicht wird beibehalten.")
            }
            return unavailable
        case "STALL_BACKOFF":
            guard let percent = bounded(args["backoffPercent"], 1...30) else { return unavailable }
            return String(localized: "Nach wiederholten Fehlversuchen sinkt das Gewicht um \(IOSProgressionFormatting.number(percent)) Prozent.")
        case "MANUAL_WEIGHT_DEVIATION":
            guard let expected = finite(args["expectedWeightKg"]), expected >= 0,
                  let actual = finite(args["actualWeightKg"]), actual >= 0 else { return unavailable }
            return String(localized: "Die gewerteten Sätze haben unterschiedliche Gewichte (\(IOSWeightFormatter.format(expected, unitSystem: unitSystem)) und \(IOSWeightFormatter.format(actual, unitSystem: unitSystem))); deshalb wird nichts automatisch geändert.")
        case "TOO_FEW_WORK_SETS":
            guard let target = whole(args["targetSets"]), target > 0,
                  let actual = whole(args["actualWorkSets"]), actual >= 0 else { return unavailable }
            return String(localized: "Für die Auswertung waren \(target) Arbeitssätze nötig, vorhanden waren \(actual).")
        case "RPE_MISSING": return String(localized: "Mindestens einem gewerteten Satz fehlt die RPE.")
        case "RPE_INVALID": return String(localized: "Mindestens eine RPE liegt außerhalb des gültigen Bereichs.")
        case "CONFIG_INVALID": return String(localized: "Die gespeicherte Progressionskonfiguration ist ungültig.")
        case "RULE_REVISION_UNSUPPORTED": return String(localized: "Die gespeicherte Progressionsregel wird von dieser App-Version nicht unterstützt.")
        case "MANUAL_SCHEME": return String(localized: "Für dieses Ziel ist keine automatische Progression aktiv.")
        case "SET_NUMBER_INVALID": return String(localized: "Die Satznummern lassen keine sichere Auswertung zu.")
        case "SET_VALUE_INVALID": return String(localized: "Mindestens ein Satz enthält ungültige Werte.")
        case "BACKOFF_FLOOR_REACHED":
            guard let percent = bounded(args["backoffPercent"], 1...30) else { return unavailable }
            return String(localized: "Ein Backoff um \(IOSProgressionFormatting.number(percent)) Prozent würde das Gewicht nicht weiter sicher senken.")
        default:
            return unavailable
        }
    }

    static func evidence(for suggestion: ILProgressionSuggestion, unitSystem: String) -> String? {
        let args = suggestion.reasonArguments
        guard let counted = whole(args["countedWorkSets"]), counted >= 0 else { return nil }
        let failure = whole(args["failureMarkedSets"]) ?? 0
        let warmup = whole(args["ignoredWarmupSets"]) ?? 0
        let drop = whole(args["ignoredDropSets"]) ?? 0
        let backoff = whole(args["ignoredBackoffSets"]) ?? 0
        let extra = whole(args["ignoredExtraSets"]) ?? 0
        var lines = [String(localized: "Gewertet: \(counted) Arbeitssätze, davon \(failure) bis zum Versagen. Ausgelassen: \(warmup) Aufwärm-, \(drop) Drop-, \(backoff) Backoff- und \(extra) zusätzliche Arbeitssätze.")]
        if let achieved = whole(args["successfulSessions"]), let required = whole(args["requiredSuccesses"]), suggestion.reasonCode != "SUCCESS_CONFIRMATION_REQUIRED" {
            lines.append(String(localized: "Bestätigung: \(achieved) von \(required) vergleichbaren Erfolgen."))
        }
        if let failed = whole(args["failedSessions"]), let threshold = whole(args["failureThreshold"]) {
            lines.append(String(localized: "Vergleichbare Fehlversuche: \(failed) von \(threshold) bis zum Backoff."))
        }
        if suggestion.suggestedTarget != nil, let minimum = finite(args["minWeightKg"]), let maximum = finite(args["maxWeightKg"]), maximum - minimum > 0.1 {
            lines.append(String(localized: "Alle gewerteten Sätze bestätigen mindestens \(IOSWeightFormatter.format(minimum, unitSystem: unitSystem)). Dieses Gewicht ist die Grundlage des Vorschlags."))
        }
        return lines.joined(separator: "\n")
    }

    private static let unavailable = String(localized: "Die Begründung kann wegen unvollständiger Auswertungsdaten nicht sicher angezeigt werden.")

    private static func finite(_ value: Double?) -> Double? {
        guard let value, value.isFinite else { return nil }
        return value
    }

    private static func bounded(_ value: Double?, _ range: ClosedRange<Double>) -> Double? {
        guard let value = finite(value), range.contains(value) else { return nil }
        return value
    }

    private static func whole(_ value: Double?) -> Int64? {
        guard let value = finite(value), value.rounded() == value,
              value >= Double(Int64.min), value <= Double(Int64.max) else { return nil }
        return Int64(value)
    }

    private static func configuredStep(for suggestion: ILProgressionSuggestion) -> (value: Double, unit: String)? {
        guard let argument = finite(suggestion.reasonArguments["stepOriginalValue"]), argument > 0 else { return nil }
        if let configured = suggestion.sourceProgression.incrementValue,
           configured.isFinite, configured > 0, abs(configured - argument) <= 0.000001 {
            let unit = suggestion.sourceProgression.incrementUnit?.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let unit, !unit.isEmpty else { return nil }
            return (argument, displayUnit(unit))
        }
        if let configuredKg = suggestion.sourceProgression.incrementKg,
           configuredKg.isFinite, configuredKg > 0, abs(configuredKg - argument) <= 0.000001 {
            return (argument, String(localized: "kg"))
        }
        return nil
    }

    private static func displayUnit(_ raw: String) -> String {
        switch raw.uppercased() {
        case "METRIC", "KG", "KILOGRAM", "KILOGRAMS": return String(localized: "kg")
        case "IMPERIAL", "LB", "LBS", "POUND", "POUNDS": return String(localized: "lb")
        case "PERCENT", "%": return String(localized: "Prozent")
        default: return raw
        }
    }
}

private struct IOSProgressionAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}
