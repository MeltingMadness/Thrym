import Shared
import SwiftUI

/// Native iOS dashboard driven by the shared, immutable analytics projection.
///
/// The screen owns navigation, the optional daily check-in, and actions only.
/// Training trend, daily form, muscle facts, week boundaries, volume thresholds,
/// and rotation choices all come from the shared core (`ILTrainingAnalytics` and
/// `ILReadinessAssessment`); no readiness number is recomputed in Swift.
struct IOSDashboardScreen: View {
    @Environment(IOSTrainingStore.self) private var store
    @EnvironmentObject private var settings: IOSSettingsViewModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.thrymAppearance) private var appearance
    @State private var showingProgressionReview = false
    @State private var showingStatistics = false
    @State private var showingCheckIn = false
    @State private var skipSuggestion: ILMetaRotationSuggestion?
    @State private var showingDeload = false
    /// Local calendar day the dashboard currently shows. Kept fresh by
    /// `watchLocalDayChange()` and the scene re-activation check, so "heute" can
    /// never silently mean yesterday.
    @State private var dayGuard = ILLocalDayGuard()
    /// Level on the hero's journey; nil while loading or when the game elements are switched off.
    @State private var level: LevelProgress?

    private var todayKey: String { dayGuard.currentDate }

    private var weekStartsSunday: Bool {
        settings.state.weekStart.uppercased() == "SUNDAY"
    }

    var body: some View {
        // Read here: a value read only inside the lazy stack would not redraw it.
        let level = level
        NavigationStack {
            ScrollView {
                if let analytics = store.analytics {
                    dashboardContent(analytics, level: level)
                } else {
                    unavailableContent
                }
            }
            .thrymScreenBackground(ember: Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Dashboard")
            .navigationBarTitleDisplayMode(.large)
            // Liquid Glass shows date, greeting and the statistics button in the content.
            .toolbar(appearance == .liquidGlass ? .hidden : .automatic, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showingStatistics = true
                    } label: {
                        Label("Statistiken", systemImage: "chart.xyaxis.line")
                    }
                    .accessibilityHint("Öffnet die vollständigen Trainingsstatistiken")
                }
            }
            .refreshable {
                store.refreshAnalytics(
                    timeZoneId: TimeZone.current.identifier,
                    weekStartsSunday: weekStartsSunday
                )
            }
        }
        .sheet(isPresented: $showingProgressionReview) {
            IOSProgressionReviewScreen()
        }
        .sheet(isPresented: $showingStatistics) {
            IOSStatisticsScreen()
        }
        .sheet(isPresented: $showingDeload) {
            NavigationStack {
                ScrollView {
                    if let trend = store.readiness?.trainingTrend {
                        IOSDashboardDeloadCard(
                            trend: trend,
                            activeMode: settings.state.deloadMode.uppercased(),
                            onActivate: settings.updateDeloadMode,
                            onDeactivate: { settings.updateDeloadMode("NONE") }
                        )
                        .padding()
                    }
                }
                .navigationTitle("Deload")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { showingDeload = false } } }
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showingCheckIn) {
            IOSDashboardCheckInSheet(localDate: todayKey)
        }
        .confirmationDialog(
            "Rotation anpassen",
            isPresented: Binding(
                get: { skipSuggestion != nil },
                set: { isPresented in
                    if !isPresented { skipSuggestion = nil }
                }
            )
        ) {
            if let suggestion = skipSuggestion {
                Button("\(suggestion.nextTrainingPlanName) überspringen", role: .destructive) {
                    Task { @MainActor in
                        _ = await store.command(
                            "metaplan.skip",
                            fields: [
                                "metaPlanId": suggestion.metaPlanId,
                                "expectedTrainingPlanId": suggestion.nextTrainingPlanId
                            ]
                        )
                        skipSuggestion = nil
                    }
                }
            }
            Button("Abbrechen", role: .cancel) { skipSuggestion = nil }
        } message: {
            if let suggestion = skipSuggestion {
                Text("Der nächste gültige Teilplan von \(suggestion.metaPlanName) wird übersprungen. Diese Entscheidung bleibt in der Rotation gespeichert.")
            }
        }
        .task(id: settings.state.weekStart) {
            store.refreshAnalytics(
                timeZoneId: TimeZone.current.identifier,
                weekStartsSunday: weekStartsSunday
            )
        }
        .task {
            await watchLocalDayChange()
        }
        .task(id: IOSGamificationInput(data: store.data, settings: settings.state)) {
            await updateLevel()
        }
        .onChange(of: scenePhase) { _, phase in
            // A suspended app wakes up on whatever day it is now; re-read the
            // calendar before the athlete can act on a stale "heute".
            guard phase == .active else { return }
            refreshLocalDayIfNeeded()
        }
    }

    private func updateLevel() async {
        let state = settings.state
        guard state.gamificationEnabled, let data = store.data else {
            level = nil
            return
        }
        let progress = await IOSGamification.evaluateInBackground(data: data, settings: state).progress
        guard !Task.isCancelled else { return }
        level = progress
    }

    @ViewBuilder
    private func dashboardContent(_ analytics: ILTrainingAnalytics, level: LevelProgress?) -> some View {
        let unitSystem = settings.state.unitSystem
        let pending = store.data?.progressionSuggestions.filter { $0.status.uppercased() == "PENDING" }.count ?? 0
        let activeMode = settings.state.deloadMode.uppercased()
        let sessions = store.data?.workoutSessions ?? []
        let pauseDays = IOSRecognition.pauseDays(sessions: sessions)
        let trend = store.readiness?.trainingTrend
        let rows = IOSDashboardLayout.openItems(
            pendingProgressions: pending,
            deloadSuggested: trend?.deloadSuggested ?? false,
            deloadActive: trend?.deloadActive ?? false,
            activeDeloadMode: activeMode,
            hasTrend: trend != nil,
            hasComeback: pauseDays != nil && activeMode == "NONE" && store.data?.activeSession == nil
        )

        LazyVStack(alignment: .leading, spacing: 16) {
            if appearance == .liquidGlass {
                IOSDashboardGlassHeader(greeting: greetingTitle, onOpenStatistics: { showingStatistics = true })
                IOSDashboardGlassCommandCenter(
                    activeSession: store.data?.activeSession, suggestion: analytics.metaRotationSuggestions.first,
                    fallbackPlan: store.data?.trainingPlans.first, data: store.data, unitSystem: unitSystem,
                    isBusy: store.isBusy, onResume: resumeWorkout, onStartFree: startFreeWorkout,
                    onStartSuggestion: startSuggestion, onRequestSkip: { skipSuggestion = $0 },
                    onStartPlan: startPlan, onChooseTraining: { store.selectedTab = 1 }
                )
            } else {
                greeting
                IOSDashboardCommandCenter(
                    activeSession: store.data?.activeSession, suggestion: analytics.metaRotationSuggestions.first,
                    fallbackPlan: store.data?.trainingPlans.first, isBusy: store.isBusy,
                    onResume: resumeWorkout, onStartFree: startFreeWorkout,
                    onStartSuggestion: startSuggestion, onRequestSkip: { skipSuggestion = $0 },
                    onStartPlan: startPlan, onChooseTraining: { store.selectedTab = 1 }
                )
            }

            if !rows.isEmpty {
                IOSDashboardOpenCard(
                    rows: rows, pendingProgressions: pending, trend: trend, activeMode: activeMode,
                    pauseDays: pauseDays, onProgression: { showingProgressionReview = true },
                    onDeload: { showingDeload = true },
                    onComeback: { settings.updateDeloadMode("HALVE_SET_VOLUME") }
                )
            }

            IOSDashboardGlassWeekStrip(
                days: IOSDashboardWeekDay.week(startingAt: analytics.currentWeekStart, sessions: sessions),
                workoutsThisWeek: analytics.workoutsThisWeek,
                volumeKg: analytics.weeklyVolume.first { $0.weekStart == analytics.currentWeekStart }?.volumeKg ?? 0,
                unitSystem: unitSystem,
                weeklyGoal: IOSRecognition.weeklyGoal(sessions: sessions, settings: settings.state)
            )
            IOSDashboardTodayCard(
                checkIn: store.readinessData.checkIn(for: todayKey),
                muscleGroups: store.readiness?.muscleGroups ?? [],
                onEdit: { showingCheckIn = true }
            )
            if let level {
                IOSLevelCard(progress: level) { store.showAchievements() }
            }
            IOSDashboardStatisticsLink { showingStatistics = true }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func resumeWorkout() {
        store.selectedTab = 1
    }

    private func startFreeWorkout() {
        Task { _ = await store.startWorkout() }
    }

    private func startSuggestion(_ suggestion: ILMetaRotationSuggestion) {
        Task {
            _ = await store.startWorkout(
                name: suggestion.nextTrainingPlanName,
                planId: suggestion.nextTrainingPlanId,
                metaPlanId: suggestion.metaPlanId
            )
        }
    }

    private func startPlan(_ plan: ILTrainingPlan) {
        Task {
            _ = await store.startWorkout(name: plan.name, planId: plan.id)
        }
    }

    /// Keeps the local "today" fresh across midnight and time-zone changes, so the
    /// check-in is never written to yesterday's date. The loop is cancelled with the
    /// screen's `.task`, and re-reads the calendar day instead of assuming it advanced.
    private func watchLocalDayChange() async {
        while !Task.isCancelled {
            refreshLocalDayIfNeeded()
            try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
        }
    }

    /// Re-reads the local calendar day and, only when it actually changed, moves the
    /// whole screen onto the new day.
    ///
    /// A midnight crossing invalidates more than the date string: the shared
    /// readiness/trend projection still describes yesterday, and an open check-in
    /// sheet would keep its in-progress draft while receiving the new date, so saving
    /// it would file yesterday's answers under today. Dismissing the sheet discards
    /// that draft, and the projection is recomputed from the same durable snapshot.
    private func refreshLocalDayIfNeeded() {
        guard dayGuard.observe() else { return }
        if showingCheckIn {
            showingCheckIn = false
        }
        store.refreshAnalytics(
            timeZoneId: TimeZone.current.identifier,
            weekStartsSunday: weekStartsSunday
        )
    }

    private var greeting: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(greetingTitle)
                .font(.geist(.title2, weight: .bold))
        }
        .accessibilityElement(children: .combine)
    }

    private var greetingTitle: String {
        switch Calendar.current.component(.hour, from: Date()) {
        case 5..<12: return String(localized: "Guten Morgen")
        case 12..<18: return String(localized: "Guten Tag")
        default: return String(localized: "Guten Abend")
        }
    }

    private var unavailableContent: some View {
        ContentUnavailableView {
            Label("Auswertung nicht verfügbar", systemImage: "chart.bar.xaxis")
        } description: {
            Text(store.errorMessage ?? String(localized: "Die Trainingsdaten werden noch ausgewertet."))
        } actions: {
            ProgressView()
                .controlSize(.small)
        }
        .frame(maxWidth: .infinity, minHeight: 420)
        .padding(20)
    }
}

private struct IOSDashboardCommandCenter: View {
    let activeSession: ILWorkoutSession?
    let suggestion: ILMetaRotationSuggestion?
    let fallbackPlan: ILTrainingPlan?
    let isBusy: Bool
    let onResume: () -> Void
    let onStartFree: () -> Void
    let onStartSuggestion: (ILMetaRotationSuggestion) -> Void
    let onRequestSkip: (ILMetaRotationSuggestion) -> Void
    let onStartPlan: (ILTrainingPlan) -> Void
    let onChooseTraining: () -> Void

    var body: some View {
        ThrymCard(tone: .elevated) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Nächster Schritt").font(.geist(.headline, weight: .semibold))
                        Text(subtitle).font(.geist(.subheadline)).thrymSecondaryText()
                    }
                    Spacer(minLength: 4)
                    if activeSession == nil, let suggestion, suggestion.canSkip {
                        IOSDashboardSkipMenu(isBusy: isBusy) { onRequestSkip(suggestion) }
                    }
                }
                if let activeSession {
                    Button(action: onResume) {
                        Label(
                            activeSession.name.isEmpty ? String(localized: "Training fortsetzen") : activeSession.name,
                            systemImage: "play.fill"
                        )
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.borderedProminent).thrymButtonText()
                    .disabled(isBusy)
                } else if let suggestion {
                    Button {
                        onStartSuggestion(suggestion)
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Label("Training starten", systemImage: "arrow.triangle.2.circlepath")
                                .font(.geist(.headline))
                            Text("\(suggestion.metaPlanName) · \(suggestion.nextTrainingPlanName)")
                                .font(.geist(.subheadline))
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.borderedProminent).thrymButtonText()
                    .disabled(isBusy)
                } else if let fallbackPlan {
                    Button {
                        onStartPlan(fallbackPlan)
                    } label: {
                        Label("\(fallbackPlan.name) starten", systemImage: "play.fill")
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.borderedProminent).thrymButtonText()
                    .disabled(isBusy)
                } else {
                    Button(action: onStartFree) {
                        Label("Freies Training starten", systemImage: "play.fill")
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.borderedProminent).thrymButtonText()
                    .disabled(isBusy)
                }

                if activeSession == nil {
                    IOSDashboardStartLinks(showFree: suggestion != nil || fallbackPlan != nil,
                        isBusy: isBusy, onChooseTraining: onChooseTraining, onStartFree: onStartFree)
                }
            }
            .buttonBorderShape(.roundedRectangle(radius: 12))
        }
    }

    private var subtitle: String {
        if activeSession != nil { return String(localized: "Ein begonnenes Training wartet auf dich.") }
        if let suggestion { return String(localized: "Nächster Vorschlag aus \(suggestion.metaPlanName).") }
        if fallbackPlan != nil { return String(localized: "Dein erster gespeicherter Plan ist bereit.") }
        return String(localized: "Starte eine freie Einheit und halte sie sauber fest.")
    }
}

/// The check-in form for one already-fixed local date.
///
/// Every dimension starts unanswered and stays unanswered until the athlete taps a
/// value. "Abbrechen" and the swipe-down gesture dismiss without writing, so a
/// skipped form never deletes answers that were already stored.
private struct IOSDashboardCheckInSheet: View {
    let localDate: String

    @Environment(IOSTrainingStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var sleepQuality: Int?
    @State private var energy: Int?
    @State private var stress: Int?
    @State private var soreness: [String: Int] = [:]
    @State private var recordedAtEpochMillis: Int64?
    @State private var didLoad = false
    @State private var isSaving = false
    @State private var saveFailed = false

    private var existingCheckIn: ILReadinessCheckIn? {
        store.readinessData.checkIn(for: localDate)
    }

    private var hasAnyAnswer: Bool {
        sleepQuality != nil || energy != nil || stress != nil || !soreness.isEmpty
    }

    /// Today's planned muscles first, so the form opens on what the athlete will train.
    private var orderedMuscleGroups: [String] {
        let planned = store.readiness?.muscleGroups.map(\.muscleGroup) ?? []
        return planned + ilReadinessMuscleGroups.filter { !planned.contains($0) }
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section {
                        ILCheckInScaleRow(title: String(localized: "Schlafqualität"), dimension: .sleepQuality, value: $sleepQuality)
                        ILCheckInScaleRow(title: String(localized: "Energie"), dimension: .energy, value: $energy)
                        ILCheckInScaleRow(title: String(localized: "Stress"), dimension: .stress, value: $stress)
                    } header: {
                        Text("Tagesform · \(ilCheckInDateText(localDate))")
                    } footer: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Alle Angaben sind freiwillig. Eine nicht beantwortete Zeile bleibt offen und wird nicht als Wert gespeichert.")
                            if let form = store.readiness?.dailyForm {
                                Text("Aussagekraft: \(ilConfidenceText(form.confidence)). Der Trainingstrend bleibt davon unabhängig.")
                            }
                        }
                    }

                    Section {
                        ForEach(orderedMuscleGroups, id: \.self) { muscle in
                            ILCheckInScaleRow(
                                title: ilMuscleDisplayName(muscle),
                                dimension: .soreness,
                                value: sorenessBinding(for: muscle)
                            )
                        }
                    } header: {
                        Text("Muskelkater je Muskelgruppe")
                    } footer: {
                        Text("1 bedeutet kein bis sehr geringer, 5 sehr starker Muskelkater. Nicht gemeldete Muskeln bleiben offen.")
                    }

                    if saveFailed {
                        Section {
                            Text(store.errorMessage ?? String(localized: "Speichern fehlgeschlagen. Bitte erneut versuchen."))
                                .font(.geist(.footnote))
                                .foregroundStyle(.orange)
                        }
                    }

                    if existingCheckIn != nil {
                        Section {
                            Button("Check-in für heute löschen", role: .destructive) {
                                Task { await deleteCheckIn() }
                            }
                            .disabled(isSaving)
                        } footer: {
                            Text("Löschen entfernt die gespeicherten Antworten dieses Tages. Abbrechen lässt sie unverändert.")
                        }
                    }
                }
                .thrymListRows()
            }
            .navigationTitle("Check-in")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen") { dismiss() }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Speichern") {
                        Task { await save() }
                    }
                    .disabled(isSaving || !hasAnyAnswer)
                }
            }
            .overlay {
                if isSaving {
                    ProgressView()
                        .controlSize(.regular)
                }
            }
        }
        .task { loadExistingAnswers() }
    }

    private func sorenessBinding(for muscle: String) -> Binding<Int?> {
        Binding(
            get: { soreness[muscle] },
            set: { newValue in
                if let newValue, (1...5).contains(newValue) {
                    soreness[muscle] = newValue
                } else {
                    soreness[muscle] = nil
                }
            }
        )
    }

    /// Prefills only from stored answers. A day without a record keeps every field open.
    private func loadExistingAnswers() {
        guard !didLoad else { return }
        didLoad = true
        guard let existing = existingCheckIn else { return }
        sleepQuality = existing.sleepQuality
        energy = existing.energy
        stress = existing.stress
        soreness = existing.muscleSoreness.filter { (1...5).contains($0.value) }
        recordedAtEpochMillis = existing.recordedAtEpochMillis
    }

    private func save() async {
        guard hasAnyAnswer, !isSaving else { return }
        isSaving = true
        saveFailed = false
        defer { isSaving = false }

        let saved = await store.upsertCheckIn(
            localDate: localDate,
            sleepQuality: sleepQuality,
            energy: energy,
            stress: stress,
            muscleSoreness: soreness.filter { (1...5).contains($0.value) },
            recordedAtEpochMillis: recordedAtEpochMillis
        )
        if saved {
            dismiss()
        } else {
            saveFailed = true
        }
    }

    private func deleteCheckIn() async {
        guard !isSaving else { return }
        isSaving = true
        saveFailed = false
        defer { isSaving = false }

        if await store.deleteCheckIn(localDate: localDate) {
            dismiss()
        } else {
            saveFailed = true
        }
    }
}

/// One 1...5 self-assessment row. Tapping the active value clears the answer again,
/// so "open" stays reachable after a mis-tap.
private struct ILCheckInScaleRow: View {
    let title: String
    let dimension: ILDailyFormDimension
    @Binding var value: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(title)
                Spacer(minLength: 8)
                Text(value.map { ilCheckInScaleText($0, dimension: dimension) } ?? String(localized: "offen"))
                    .font(.geist(.caption))
                    .thrymSecondaryText()
            }

            HStack(spacing: 8) {
                ForEach(1...5, id: \.self) { step in
                    Button {
                        value = value == step ? nil : step
                    } label: {
                        Text("\(step)")
                            .font(.geist(.subheadline, weight: .semibold))
                            .frame(minWidth: 32, minHeight: 32)
                    }
                    .buttonStyle(.bordered)
                    .tint(value == step ? Color.accentColor : Color.secondary)
                    .thrymAccentText()
                    .accessibilityLabel("\(title): \(step) von 5")
                    .accessibilityAddTraits(value == step ? [.isSelected] : [])
                }
            }
        }
        .padding(.vertical, 2)
    }
}
