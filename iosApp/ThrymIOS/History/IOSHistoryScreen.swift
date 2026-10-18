import SwiftUI
import UIKit

/// Completed workout history. Active sessions are intentionally excluded so a
/// half-finished workout cannot appear as a completed result or be deleted from
/// this surface.
struct IOSHistoryScreen: View {
    @Environment(IOSTrainingStore.self) private var store
    @EnvironmentObject private var settings: IOSSettingsViewModel
    @Environment(\.thrymAppearance) private var appearance

    @State private var searchText = ""
    @State private var openedSessionID: Int64?
    @State private var dateFilter: IOSHistoryDateFilter = .all
    @State private var selectedPlanID: Int64?
    @State private var sessionToDelete: ILWorkoutSession?
    @State private var alert: IOSHistoryAlert?

    private var hasSearchFilter: Bool {
        !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var completedSessions: [ILWorkoutSession] {
        guard let data = store.data else { return [] }
        let now = Date()
        return data.workoutSessions
            .filter { session in
                guard session.endTime != nil, dateFilter.includes(session.startDate, now: now) else {
                    return false
                }
                guard let selectedPlanID else { return true }
                return session.planId == selectedPlanID
            }
            .filter { session in
                guard !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    return true
                }
                return session.iosSearchText(in: data)
                    .localizedCaseInsensitiveContains(searchText.trimmingCharacters(in: .whitespacesAndNewlines))
            }
            .sorted { $0.startTime > $1.startTime }
    }

    private var summary: IOSHistorySummary {
        guard let data = store.data else { return .empty }
        return IOSHistorySummary(sessions: completedSessions, data: data)
    }

    private var availablePlans: [ILTrainingPlan] {
        (store.data?.trainingPlans ?? []).sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private var selectedPlanName: String? {
        guard let selectedPlanID else { return nil }
        return availablePlans.first(where: { $0.id == selectedPlanID })?.name
    }

    var body: some View {
        NavigationStack {
            Group {
                if let error = store.errorMessage, store.data == nil {
                    ContentUnavailableView(
                        "Verlauf nicht verfügbar",
                        systemImage: "exclamationmark.triangle",
                        description: Text(error)
                    )
                } else if store.data == nil {
                    ProgressView("Verlauf wird geladen …")
                } else if completedSessions.isEmpty {
                    ContentUnavailableView(
                        !hasSearchFilter && dateFilter == .all ? String(localized: "Noch keine abgeschlossenen Trainings") : String(localized: "Keine passenden Trainings"),
                        systemImage: "clock.arrow.circlepath",
                        description: Text(
                            !hasSearchFilter && dateFilter == .all
                                ? String(localized: "Abgeschlossene Sessions erscheinen hier. Eine aktive Session bleibt im Training-Bereich.")
                                : String(localized: "Passe Suche oder Zeitraum an.")
                        )
                    )
                } else if appearance == .liquidGlass {
                    glassHistoryList
                } else {
                    historyList
                }
            }
            .thrymScreenBackground()
            .navigationTitle("Verlauf")
            .searchable(text: $searchText, prompt: "Training, Notiz oder Übung")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Zeitraum", selection: $dateFilter) {
                            ForEach(IOSHistoryDateFilter.allCases) { filter in
                                Text(filter.title).tag(filter)
                            }
                        }
                    } label: {
                        Label("Zeitraum", systemImage: "calendar")
                    }
                    .accessibilityLabel("Zeitraum filtern")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            selectedPlanID = nil
                        } label: {
                            Label("Alle Pläne", systemImage: selectedPlanID == nil ? "checkmark" : "rectangle.stack")
                        }
                        if !availablePlans.isEmpty {
                            Divider()
                            ForEach(availablePlans) { plan in
                                Button {
                                    selectedPlanID = plan.id
                                } label: {
                                    Label(
                                        plan.name,
                                        systemImage: selectedPlanID == plan.id ? "checkmark" : "rectangle.stack"
                                    )
                                }
                            }
                        }
                    } label: {
                        Label(selectedPlanName ?? String(localized: "Plan: Alle"), systemImage: "rectangle.stack")
                    }
                    .accessibilityLabel("Plan filtern")
                    .accessibilityValue(selectedPlanName ?? String(localized: "Alle Pläne"))
                }
            }
            .navigationDestination(item: $openedSessionID) { sessionID in
                IOSHistoryDetailScreen(sessionID: sessionID)
            }
            .alert(item: $alert) { item in
                Alert(
                    title: Text(item.title),
                    message: Text(item.message),
                    dismissButton: .default(Text("OK"))
                )
            }
            .confirmationDialog(
                "Training löschen?",
                isPresented: Binding(
                    get: { sessionToDelete != nil },
                    set: { if !$0 { sessionToDelete = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("Training löschen", role: .destructive) {
                    guard let session = sessionToDelete else { return }
                    sessionToDelete = nil
                    delete(session)
                }
                Button("Abbrechen", role: .cancel) { sessionToDelete = nil }
            } message: {
                Text("Alle Sätze, Notizen und Fortschrittsdaten dieses Trainings werden entfernt.")
            }
        }
    }

    private var historyList: some View {
        List {
            Group {
                Section {
                    IOSHistorySummaryCard(summary: summary, unitSystem: settings.state.unitSystem)
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                        .listRowBackground(Color.clear)
                }

                Section("Abgeschlossen") {
                    ForEach(completedSessions) { session in
                        NavigationLink {
                            IOSHistoryDetailScreen(sessionID: session.id)
                        } label: {
                            IOSHistorySessionRow(
                                session: session,
                                data: store.data,
                                unitSystem: settings.state.unitSystem
                            )
                        }
                        .accessibilityHint("Öffnet die Details dieses Trainings")
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                sessionToDelete = session
                            } label: {
                                Label("Löschen", systemImage: "trash")
                            }
                        }
                    }
                }
            }
            .thrymListRows()
        }
        .listStyle(.insetGrouped)
    }

    /// Liquid Glass: one glass header per week with mini bars, the trainings of that week
    /// below as glass rows. Search, filters and swipe to delete stay the same.
    private var glassHistoryList: some View {
        List {
            Group {
                ForEach(IOSHistoryWeek.group(visible: completedSessions, all: store.data?.workoutSessions ?? [])) { week in
                    IOSHistoryGlassWeekHeader(week: week)
                        .listRowInsets(EdgeInsets(top: 14, leading: 16, bottom: 4, trailing: 16))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                    ForEach(week.sessions) { session in
                        Button {
                            openedSessionID = session.id
                        } label: {
                            IOSHistoryGlassRow(session: session, data: store.data, unitSystem: settings.state.unitSystem)
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Öffnet die Details dieses Trainings")
                        .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                sessionToDelete = session
                            } label: {
                                Label("Löschen", systemImage: "trash")
                            }
                        }
                    }
                }
            }
            .thrymListRows()
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func delete(_ session: ILWorkoutSession) {
        Task { @MainActor in
            let success = await store.command("workout.delete", fields: ["id": session.id])
            guard !success else { return }
            alert = IOSHistoryAlert(
                title: String(localized: "Löschen fehlgeschlagen"),
                message: store.errorMessage ?? String(localized: "Das Training konnte nicht gelöscht werden.")
            )
        }
    }
}

/// Alias kept for callers that use the Android feature's terminology.
struct IOSWorkoutHistoryScreen: View {
    var body: some View { IOSHistoryScreen() }
}

private struct IOSHistorySummary {
    let sessionCount: Int
    let setCount: Int
    let volumeKg: Double
    let durationSeconds: Int64

    static let empty = IOSHistorySummary(sessions: [], data: ILTrainingData())

    init(sessions: [ILWorkoutSession], data: ILTrainingData) {
        sessionCount = sessions.count
        durationSeconds = sessions.reduce(into: Int64(0)) { result, session in
            result += session.iosDurationSeconds
        }
        let visibleSets = sessions.flatMap { data.sets(for: $0) }.filter(\.iosVisibleInHistory)
        setCount = visibleSets.count
        volumeKg = visibleSets
            .filter(\.iosCountsAsWorkSet)
            .reduce(0) { $0 + max(0, $1.weightKg) * Double(max(0, $1.reps)) }
    }
}

private struct IOSHistorySummaryCard: View {
    let summary: IOSHistorySummary
    let unitSystem: String

    var body: some View {
        ThrymCard(title: String(localized: "Zusammenfassung"), subtitle: String(localized: "Gefilterte abgeschlossene Trainings")) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 20) { metrics }
                VStack(alignment: .leading, spacing: 10) { metrics }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                "\(ilWorkoutCountText(summary.sessionCount)), \(ilSetCountText(summary.setCount)), " +
                    "\(IOSWeightFormatter.format(summary.volumeKg, unitSystem: unitSystem)), " +
                    "\(IOSHistoryFormatting.duration(seconds: summary.durationSeconds))"
            )
        }
    }

    @ViewBuilder private var metrics: some View {
        IOSHistoryMetric(title: String(localized: "Trainings"), value: "\(summary.sessionCount)", systemImage: "figure.strengthtraining.traditional")
        IOSHistoryMetric(title: String(localized: "Sätze"), value: "\(summary.setCount)", systemImage: "list.number")
        IOSHistoryMetric(title: String(localized: "Volumen"), value: IOSWeightFormatter.format(summary.volumeKg, unitSystem: unitSystem), systemImage: "scalemass")
        IOSHistoryMetric(title: String(localized: "Zeit"), value: IOSHistoryFormatting.duration(seconds: summary.durationSeconds), systemImage: "timer")
    }
}

private struct IOSHistoryMetric: View {
    let title: String
    let value: String
    let systemImage: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(value).font(.geist(.headline).monospacedDigit())
                Text(title).font(.geist(.caption)).thrymSecondaryText()
            }
        } icon: {
            Image(systemName: systemImage).foregroundStyle(.tint)
        }
    }
}

private struct IOSHistorySessionRow: View {
    let session: ILWorkoutSession
    let data: ILTrainingData?
    let unitSystem: String

    private var planName: String? {
        guard let planID = session.planId else { return nil }
        return data?.trainingPlans.first(where: { $0.id == planID })?.name
    }

    private var visibleSets: [ILWorkoutSet] {
        guard let data else { return [] }
        return data.sets(for: session).filter(\.iosVisibleInHistory)
    }

    private var volumeKg: Double {
        visibleSets.filter(\.iosCountsAsWorkSet).reduce(0) { $0 + max(0, $1.weightKg) * Double(max(0, $1.reps)) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .firstTextBaseline) {
                Text(session.displayName(planName: planName))
                    .font(.geist(.headline))
                    .lineLimit(2)
                Spacer(minLength: 8)
                Text(IOSHistoryFormatting.date(session.startDate))
                    .font(.geist(.caption))
                    .thrymSecondaryText()
                    .multilineTextAlignment(.trailing)
            }
            HStack(spacing: 14) {
                Label(IOSHistoryFormatting.duration(seconds: session.iosDurationSeconds), systemImage: "timer")
                Label(ilSetCountText(visibleSets.count), systemImage: "list.number")
                if volumeKg > 0 {
                    Label(IOSWeightFormatter.format(volumeKg, unitSystem: unitSystem), systemImage: "scalemass")
                }
            }
            .font(.geist(.caption))
            .thrymSecondaryText()
            if !session.notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(session.notes)
                    .font(.geist(.caption))
                    .thrymSecondaryText()
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4)
    }
}

/// Details for one completed workout, including notes, records and editable
/// set rows. Every mutation is sent to the shared store and the editor only
/// dismisses after the bridge reports durable success.
struct IOSHistoryDetailScreen: View {
    let sessionID: Int64

    @Environment(IOSTrainingStore.self) private var store
    @EnvironmentObject private var settings: IOSSettingsViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var editingSet: ILWorkoutSet?
    @State private var pendingSetDelete: ILWorkoutSet?
    @State private var showNotesEditor = false
    @State private var showNotesDelete = false
    @State private var showSessionDelete = false
    @State private var alert: IOSHistoryAlert?

    private var data: ILTrainingData? { store.data }
    private var session: ILWorkoutSession? {
        data?.workoutSessions.first(where: { $0.id == sessionID && $0.endTime != nil })
    }

    var body: some View {
        Group {
            if let session, let data {
                detail(session: session, data: data)
            } else if store.data == nil {
                ProgressView("Training wird geladen …")
            } else {
                ContentUnavailableView("Training nicht gefunden", systemImage: "questionmark.folder")
            }
        }
        .navigationTitle(session?.displayName(planName: planName) ?? String(localized: "Training"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                sessionActionsMenu
            }
        }
        .sheet(item: $editingSet) { set in
            IOSHistorySetEditor(
                set: set,
                addedLoad: data?.exercises.first(where: { $0.id == set.exerciseId })?.tracksAddedLoad == true,
                unitSystem: settings.state.unitSystem,
                isWorking: store.isBusy,
                intention: store.intention(forSetId: set.id),
                onSave: { updated, intention in saveSet(updated, intention: intention) },
                onCancel: { editingSet = nil }
            )
        }
        .sheet(isPresented: $showNotesEditor) {
            if let session {
                IOSSessionNotesEditor(
                    initialNotes: session.notes,
                    isWorking: store.isBusy,
                    onSave: { notes in saveNotes(notes, for: session) },
                    onCancel: { showNotesEditor = false }
                )
            }
        }
        .alert(item: $alert) { item in
            Alert(title: Text(item.title), message: Text(item.message), dismissButton: .default(Text("OK")))
        }
        .confirmationDialog(
            "Satz löschen?",
            isPresented: Binding(
                get: { pendingSetDelete != nil },
                set: { if !$0 { pendingSetDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Satz löschen", role: .destructive) {
                guard let set = pendingSetDelete else { return }
                pendingSetDelete = nil
                deleteSet(set)
            }
            Button("Abbrechen", role: .cancel) { pendingSetDelete = nil }
        } message: {
            Text("Der Satz wird dauerhaft aus diesem Training entfernt.")
        }
        .confirmationDialog(
            "Training löschen?",
            isPresented: $showSessionDelete,
            titleVisibility: .visible
        ) {
            Button("Training löschen", role: .destructive) {
                deleteSession()
            }
            Button("Abbrechen", role: .cancel) { showSessionDelete = false }
        } message: {
            Text("Alle Sätze, Notizen und Fortschrittsdaten dieses Trainings werden entfernt.")
        }
        .confirmationDialog(
            "Notiz löschen?",
            isPresented: $showNotesDelete,
            titleVisibility: .visible
        ) {
            Button("Notiz löschen", role: .destructive) {
                guard let session else { return }
                showNotesDelete = false
                saveNotes("", for: session)
            }
            Button("Abbrechen", role: .cancel) { showNotesDelete = false }
        } message: {
            Text("Die Notiz wird dauerhaft aus diesem Training entfernt.")
        }
    }

    private var planName: String? {
        guard let planID = session?.planId else { return nil }
        return data?.trainingPlans.first(where: { $0.id == planID })?.name
    }

    private var canDeleteSession: Bool { session?.endTime != nil }

    private var sessionActionsMenu: some View {
        Menu {
            Button(role: .destructive) {
                showSessionDelete = true
            } label: {
                Label("Training löschen", systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .disabled(!canDeleteSession)
    }

    @ViewBuilder
    private func detail(session: ILWorkoutSession, data: ILTrainingData) -> some View {
        let sessionSets = data.sets(for: session)
            .filter(\.iosVisibleInHistory)
            .sorted { lhs, rhs in
                if lhs.exerciseId != rhs.exerciseId { return lhs.exerciseId < rhs.exerciseId }
                if lhs.setNumber != rhs.setNumber { return lhs.setNumber < rhs.setNumber }
                return lhs.completedAt < rhs.completedAt
            }
        let grouped = Dictionary(grouping: sessionSets, by: \.exerciseId)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                IOSHistoryDetailHeader(
                    session: session,
                    planName: planName,
                    sets: sessionSets,
                    unitSystem: settings.state.unitSystem,
                    onEditNotes: { showNotesEditor = true },
                    onDeleteNotes: { showNotesDelete = true }
                )

                if grouped.isEmpty {
                    ContentUnavailableView("Keine Sätze", systemImage: "list.number", description: Text("Dieses Training enthält keine sichtbaren Sätze."))
                } else {
                    ForEach(grouped.keys.sorted(), id: \.self) { exerciseID in
                        if let exerciseSets = grouped[exerciseID] {
                            IOSHistoryExerciseSection(
                                exercise: data.exercises.first(where: { $0.id == exerciseID }),
                                sets: exerciseSets,
                                records: data.personalRecords.filter { $0.exerciseId == exerciseID },
                                unitSystem: settings.state.unitSystem,
                                onEdit: { editingSet = $0 },
                                onDelete: { pendingSetDelete = $0 }
                            )
                        }
                    }
                }
            }
            .padding(16)
        }
        .thrymScreenBackground(ember: Color(uiColor: .systemGroupedBackground))
    }

    private func saveSet(_ set: ILWorkoutSet, intention: ILSetIntention) {
        Task { @MainActor in
            let success = await store.saveSet(set, intention: intention)
            if success {
                editingSet = nil
            } else {
                alert = IOSHistoryAlert(title: String(localized: "Satz konnte nicht gespeichert werden"), message: store.errorMessage ?? String(localized: "Bitte prüfe die Werte."))
            }
        }
    }

    private func deleteSet(_ set: ILWorkoutSet) {
        Task { @MainActor in
            let success = await store.command("set.delete", fields: ["id": set.id])
            if !success {
                alert = IOSHistoryAlert(title: String(localized: "Satz konnte nicht gelöscht werden"), message: store.errorMessage ?? String(localized: "Bitte versuche es erneut."))
            }
        }
    }

    private func saveNotes(_ notes: String, for session: ILWorkoutSession) {
        let updated = ILWorkoutSession(
            id: session.id,
            startTime: session.startTime,
            endTime: session.endTime,
            durationSeconds: session.durationSeconds,
            name: session.name,
            notes: notes,
            planId: session.planId,
            metaPlanId: session.metaPlanId
        )
        Task { @MainActor in
            do {
                let success = await store.command("workout.update", fields: ["session": try store.encoded(updated)])
                if success {
                    showNotesEditor = false
                } else {
                    alert = IOSHistoryAlert(title: String(localized: "Notiz konnte nicht gespeichert werden"), message: store.errorMessage ?? String(localized: "Bitte versuche es erneut."))
                }
            } catch {
                alert = IOSHistoryAlert(title: String(localized: "Notiz konnte nicht gespeichert werden"), message: error.localizedDescription)
            }
        }
    }

    private func deleteSession() {
        showSessionDelete = false
        Task { @MainActor in
            guard let session, session.endTime != nil else { return }
            let success = await store.command("workout.delete", fields: ["id": session.id])
            if success {
                dismiss()
            } else {
                alert = IOSHistoryAlert(title: String(localized: "Training konnte nicht gelöscht werden"), message: store.errorMessage ?? String(localized: "Bitte versuche es erneut."))
            }
        }
    }
}

/// Compatibility name for route wiring that mirrors Android's detail route.
struct IOSWorkoutDetailScreen: View {
    let sessionID: Int64

    var body: some View { IOSHistoryDetailScreen(sessionID: sessionID) }
}

private struct IOSHistoryDetailHeader: View {
    let session: ILWorkoutSession
    let planName: String?
    let sets: [ILWorkoutSet]
    let unitSystem: String
    let onEditNotes: () -> Void
    let onDeleteNotes: () -> Void

    private var workSets: [ILWorkoutSet] { sets.filter(\.iosCountsAsWorkSet) }
    private var volumeKg: Double { workSets.reduce(0) { $0 + max(0, $1.weightKg) * Double(max(0, $1.reps)) } }

    var body: some View {
        ThrymCard(title: session.displayName(planName: planName), subtitle: IOSHistoryFormatting.date(session.startDate)) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 18) { metrics }
                VStack(alignment: .leading, spacing: 8) { metrics }
            }
            if !session.notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(session.notes).font(.geist(.body))
            }
            Button(action: onEditNotes) {
                Label("Notiz bearbeiten", systemImage: "square.and.pencil")
            }
            .buttonStyle(.bordered)
            .accessibilityHint("Öffnet die Notizbearbeitung")
            if !session.notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Button(role: .destructive, action: onDeleteNotes) {
                    Label("Notiz löschen", systemImage: "trash")
                }
                .buttonStyle(.borderless)
            }
        }
    }

    @ViewBuilder private var metrics: some View {
        Label(ilSetCountText(sets.count), systemImage: "list.number")
        Label(IOSWeightFormatter.format(volumeKg, unitSystem: unitSystem), systemImage: "scalemass")
        Label(IOSHistoryFormatting.duration(seconds: session.iosDurationSeconds), systemImage: "timer")
    }
}

private struct IOSHistoryExerciseSection: View {
    let exercise: ILExercise?
    let sets: [ILWorkoutSet]
    let records: [ILPersonalRecord]
    let unitSystem: String
    let onEdit: (ILWorkoutSet) -> Void
    let onDelete: (ILWorkoutSet) -> Void

    var body: some View {
        ThrymCard(title: exercise?.displayName ?? String(localized: "Übung \(sets.first?.exerciseId ?? 0)"), subtitle: exercise?.categoryDisplayName) {
            if !records.isEmpty {
                VStack(alignment: .leading, spacing: 3) {
                    Label("Persönliche Bestleistungen", systemImage: "rosette")
                        .font(.geist(.subheadline, weight: .semibold))
                    ForEach(records) { record in
                        Text("\(ilRecordTypeText(record.type)): \(ilRecordValueText(type: record.type, value: record.value, unitSystem: unitSystem))")
                            .font(.geist(.caption))
                            .thrymSecondaryText()
                    }
                }
            }

            ForEach(sets) { set in
                IOSHistorySetRow(set: set, unitSystem: unitSystem, addedLoad: exercise?.tracksAddedLoad == true, onEdit: { onEdit(set) }, onDelete: { onDelete(set) })
                if set.id != sets.last?.id { Divider() }
            }
        }
    }
}

private struct IOSHistorySetRow: View {
    let set: ILWorkoutSet
    let unitSystem: String
    let addedLoad: Bool
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text("Satz \(set.setNumber)")
                    .font(.geist(.subheadline, weight: .semibold))
                    .frame(width: 58, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(iosSetValueText(weightKg: set.weightKg, reps: set.reps, unitSystem: unitSystem, addedLoad: addedLoad))
                    .font(.geist(.body).monospacedDigit())
                HStack(spacing: 8) {
                    Text(set.iosSetTypeDisplayName)
                    if let rpe = set.rpe, rpe.isFinite {
                        Text("RPE \(IOSHistoryFormatting.number(rpe))")
                    }
                }
                .font(.geist(.caption))
                .thrymSecondaryText()
            }
            Spacer(minLength: 4)
            Menu {
                Button("Bearbeiten", systemImage: "pencil", action: onEdit)
                Button("Löschen", systemImage: "trash", role: .destructive, action: onDelete)
            } label: {
                Image(systemName: "ellipsis.circle")
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Aktionen für Satz \(set.setNumber)")
        }
        .padding(.vertical, 5)
    }
}

private struct IOSHistorySetEditor: View {
    let set: ILWorkoutSet
    /// Bodyweight exercise: the weight field holds the added load.
    let addedLoad: Bool
    let unitSystem: String
    let isWorking: Bool
    let onSave: (ILWorkoutSet, ILSetIntention) -> Void
    let onCancel: () -> Void

    @State private var weightText: String
    @State private var repsText: String
    @State private var rpeText: String
    @State private var intention: ILSetIntention
    @State private var errorMessage: String?

    init(set: ILWorkoutSet, addedLoad: Bool, unitSystem: String, isWorking: Bool, intention: ILSetIntention, onSave: @escaping (ILWorkoutSet, ILSetIntention) -> Void, onCancel: @escaping () -> Void) {
        self.set = set
        self.addedLoad = addedLoad
        self.unitSystem = unitSystem
        self.isWorking = isWorking
        self.onSave = onSave
        self.onCancel = onCancel
        _intention = State(initialValue: intention)
        _weightText = State(initialValue: Self.displayedWeight(set.weightKg, unitSystem: unitSystem))
        _repsText = State(initialValue: "\(set.reps)")
        _rpeText = State(initialValue: set.rpe.map { IOSNumber.format($0, fractionDigits: 1) } ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section("Satz \(set.setNumber)") {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(addedLoad
                                 ? String(localized: "Zusatz (\(IOSWeightFormatter.unitLabel(for: unitSystem)))")
                                 : String(localized: "Gewicht (\(IOSWeightFormatter.unitLabel(for: unitSystem)))"))
                                .font(.geist(.caption)).thrymSecondaryText()
                            TextField(addedLoad ? String(localized: "Zusatzgewicht") : String(localized: "Gewicht"), text: $weightText)
                                .keyboardType(.decimalPad)
                                .accessibilityLabel(addedLoad
                                    ? String(localized: "Zusatzgewicht in \(IOSWeightFormatter.unitLabel(for: unitSystem))")
                                    : String(localized: "Gewicht in \(IOSWeightFormatter.unitLabel(for: unitSystem))"))
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Wiederholungen").font(.geist(.caption)).thrymSecondaryText()
                            TextField("Wiederholungen", text: $repsText)
                                .keyboardType(.numberPad)
                                .accessibilityLabel("Wiederholungen")
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            Text("RPE (optional)").font(.geist(.caption)).thrymSecondaryText()
                            TextField("RPE", text: $rpeText)
                                .keyboardType(.decimalPad)
                                .accessibilityLabel("RPE, optional")
                        }
                        Picker("Absicht (optional)", selection: $intention) {
                            ForEach(ILSetIntention.allCases, id: \.self) { value in
                                Text(ilSetIntentionText(value)).tag(value)
                            }
                        }
                        if let errorMessage {
                            Text(errorMessage).foregroundStyle(.red).font(.geist(.footnote))
                        }
                    }
                }
                .thrymListRows()
            }
            .navigationTitle("Satz bearbeiten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen", action: onCancel)
                        .disabled(isWorking)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Speichern") { save() }
                        .disabled(isWorking)
                }
            }
        }
    }

    private func save() {
        guard let displayedWeight = IOSNumber.parse(weightText), displayedWeight >= 0 else {
            errorMessage = String(localized: "Bitte gib ein gültiges Gewicht ein.")
            return
        }
        guard let reps = Int(repsText), reps >= 0 else {
            errorMessage = String(localized: "Bitte gib eine gültige Wiederholungszahl ein.")
            return
        }
        let rpe: Double?
        if rpeText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            rpe = nil
        } else if let parsed = IOSNumber.parse(rpeText), parsed >= 0, parsed <= 10 {
            rpe = parsed
        } else {
            errorMessage = String(localized: "Die RPE muss zwischen 0 und 10 liegen.")
            return
        }
        var updated = set
        updated.weightKg = IOSWeightFormatter.kilograms(fromDisplayedValue: displayedWeight, unitSystem: unitSystem)
        updated.reps = reps
        updated.rpe = rpe
        onSave(updated, intention)
    }

    private static func displayedWeight(_ kilograms: Double, unitSystem: String) -> String {
        let isImperial = unitSystem.uppercased() == "IMPERIAL"
        let value = isImperial ? kilograms * IOSWeightFormatter.poundsPerKilogram : kilograms
        return IOSNumber.format(value, fractionDigits: 2)
    }
}

private struct IOSSessionNotesEditor: View {
    let initialNotes: String
    let isWorking: Bool
    let onSave: (String) -> Void
    let onCancel: () -> Void

    @State private var notes: String

    init(initialNotes: String, isWorking: Bool, onSave: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.initialNotes = initialNotes
        self.isWorking = isWorking
        self.onSave = onSave
        self.onCancel = onCancel
        _notes = State(initialValue: initialNotes)
    }

    var body: some View {
        NavigationStack {
            TextEditor(text: $notes)
                .padding(12)
                .navigationTitle("Notiz bearbeiten")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Abbrechen", action: onCancel)
                            .disabled(isWorking)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Speichern") { onSave(notes) }
                            .disabled(isWorking)
                    }
                }
        }
    }
}

private struct IOSHistoryAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

extension ILWorkoutSession {
    var iosDurationSeconds: Int64 {
        if durationSeconds > 0 { return durationSeconds }
        guard let endTime else { return 0 }
        return max(0, (endTime - startTime) / 1_000)
    }

    func displayName(planName: String?) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        if let planName, !planName.isEmpty { return planName }
        return String(localized: "Training vom \(IOSHistoryFormatting.date(startDate))")
    }

    func iosSearchText(in data: ILTrainingData) -> String {
        let exerciseText: [String] = data.sets(for: self).compactMap { set -> String? in
            guard let exercise = data.exercises.first(where: { $0.id == set.exerciseId }) else { return nil }
            // Displayed and stored name, so both languages find a built-in exercise.
            return [exercise.displayName, exercise.name, exercise.notes].joined(separator: " ")
        }
        let planName = planId.flatMap { id in data.trainingPlans.first(where: { $0.id == id })?.name }
        return [name, notes, planName ?? "", exerciseText.joined(separator: " ")].joined(separator: " ")
    }
}
