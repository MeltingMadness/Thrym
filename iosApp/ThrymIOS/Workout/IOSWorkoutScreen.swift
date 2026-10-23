import Foundation
import Shared
import SwiftUI
import UIKit

struct IOSWorkoutExerciseRow: Identifiable, Equatable {
    let id: String
    let exercise: ILExercise
    let target: ILWorkoutPlanTarget?
    /// The target shown to the athlete after the persisted deload mode is applied. The raw
    /// snapshot in `target` remains the identity used when sets are saved.
    let displayTarget: ILProgressionTarget?
    let sets: [ILWorkoutSet]
    let previousWorkWeightKg: Double?
    let nextSetRecommendation: IOSWorkoutNextSetRecommendation?

    var deloadMode: String = "NONE"

    var targetID: Int64? { target?.id }

    var loggingSlots: [ILPlannedSet] {
        guard let target else { return [] }
        if !target.setTargets.isEmpty {
            var remainingWork = displayTarget?.sets ?? target.target.sets
            return target.setTargets.compactMap { slot in
                if deloadMode == "HALVE_SET_VOLUME", slot.kind != "WARMUP" {
                    guard remainingWork > 0 else { return nil }
                    remainingWork -= 1
                }
                let adjusted = DeloadTargetAdjustment.shared.adjustByName(sets: 1, reps: Int32(slot.reps), weightKg: slot.weightKg, modeName: deloadMode)
                return ILPlannedSet(kind: slot.kind, reps: slot.reps, weightKg: adjusted.weightKg)
            }
        }
        return Array(repeating: ILPlannedSet(reps: displayTarget?.reps ?? target.target.reps, weightKg: displayTarget?.weightKg ?? target.target.weightKg), count: min(100, max(0, displayTarget?.sets ?? target.target.sets)))
    }
    var matchedSlots: [Int?] {
        var used = Set<Int>()
        return loggingSlots.map { slot in
            guard let index = sets.indices.first(where: { index in
                guard !used.contains(index) else { return false }
                let kind = resolvedIOSWorkoutSetType(sets[index])
                switch slot.kind {
                case "NORMAL": return kind == .normal || kind == .failure
                case "BACKOFF": return kind == .backoff || kind == .normal // Legacy backoff records.
                default: return kind.rawValue == slot.kind
                }
            }) else { return nil }
            used.insert(index)
            return index
        }
    }
    var nextPlannedSet: ILPlannedSet? {
        guard let index = matchedSlots.firstIndex(where: { $0 == nil }) else { return nil }
        return loggingSlots[index]
    }
    var targetReps: Int {
        if target?.setTargets.isEmpty == false { return nextPlannedSet?.reps ?? displayTarget?.reps ?? 0 }
        return sets.last(where: { [IOSWorkoutSetType.normal, .failure].contains(resolvedIOSWorkoutSetType($0)) })?.reps ?? displayTarget?.reps ?? 0
    }

    var defaultWeightKg: Double {
        if target?.setTargets.isEmpty == false, let nextPlannedSet { return nextPlannedSet.weightKg }
        if let last = sets.last(where: { [IOSWorkoutSetType.normal, .failure].contains(resolvedIOSWorkoutSetType($0)) }) { return last.weightKg }
        guard let target else { return previousWorkWeightKg ?? 0 }
        let displayWeight = displayTarget?.weightKg ?? target.target.weightKg
        return displayWeight > 0 ? displayWeight : (previousWorkWeightKg ?? 0)
    }

    var nextSetNumber: Int {
        (sets.map(\.setNumber).max() ?? 0) + 1
    }

    var completedNormalSets: Int {
        sets.filter { [IOSWorkoutSetType.normal, .failure].contains(resolvedIOSWorkoutSetType($0)) }.count
    }

    var remainingPlannedSets: Int? {
        guard displayTarget != nil else { return nil }
        return matchedSlots.filter { $0 == nil }.count
    }

    var isComplete: Bool {
        guard let remainingPlannedSets else { return false }
        return remainingPlannedSets == 0
    }
}

extension Array where Element == IOSWorkoutExerciseRow {
    /// Planned sets of the workout, after the deload mode is applied.
    var plannedSetCount: Int { reduce(0) { $0 + $1.loggingSlots.count } }

    /// True once a workout with planned sets has none open; same rule as Android's
    /// `allPlannedSetsDone()`. A free workout never is. Ad-hoc rows, extra, drop and
    /// unplanned warm-up sets change nothing, a planned warm-up slot must be filled, and a
    /// target without slots does not count as done on its own.
    var allPlannedSetsDone: Bool {
        plannedSetCount > 0 && reduce(0) { $0 + ($1.remainingPlannedSets ?? 0) } == 0
    }
}

/// A render-only block of the workout list, built by `IOSWorkoutExerciseLayout`: a
/// superset is rendered only when at least two target snapshots that are adjacent in
/// the default order carry the same group id. Rows that carry an isolated or
/// interrupted group id stay ordinary cards. A block moves as a whole.
struct IOSWorkoutExerciseRenderGroup: Identifiable {
    let id: String
    let supersetGroupID: Int?
    let rows: [IOSWorkoutExerciseRow]
}

struct IOSWorkoutScreen: View {
    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymAppearance) private var appearance
    @Environment(IOSTrainingStore.self) private var store
    @EnvironmentObject private var settings: IOSSettingsViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @SceneStorage("thrym.workout.completedSessionID") private var completedSessionID = 0
    @State private var isFinishing = false
    @State private var showingExercisePicker = false
    @State private var showingNotes = false
    @State private var showingFinishConfirmation = false
    @State private var showingCancelConfirmation = false
    @State private var editingContext: IOSWorkoutSetEditorContext?
    @State private var adHocExerciseIDs: [Int64] = []
    /// Row keys in display order, nil while the workout keeps its default order. Lives
    /// outside the training graph (`IOSWorkoutExerciseOrderPersistence`).
    @State private var exerciseOrder: [String]?
    /// Rest timers are keyed by the immutable exercise row identity, matching
    /// Android's `Map<WorkoutExerciseKey, RestTimerUi>` so supersets can count
    /// down independently.
    @State private var restTimers: [String: IOSWorkoutRestTimer] = [:]
    /// Liquid Glass pause screen: the row whose rest it shows, nil while closed.
    /// Only a timer start (`startRestTimer`) or a tap on a dock row sets it, so
    /// restoring timers after the view is rebuilt never reopens the pause.
    @State private var pauseRowKey: String?
    /// Height of the workout area, which caps the rest dock stack.
    @State private var viewportHeight: CGFloat = 0
    /// The head with the workout time has scrolled out of view; the time bubble shows instead.
    @State private var headTimeHidden = false
    private static let headID = "workout-head"
    @State private var localErrorMessage: String?
    @State private var deletedSet: ILWorkoutSet?
    @State private var deletedIntention: ILSetIntention = .unknown
    @State private var undoToken: UUID?
    @State private var isRestoringSet = false
    /// Counts the saves that completed the plan; each change scrolls to the finish card once.
    @State private var finishScrollRequest = 0
    /// The superset member to scroll to after a log; the token repeats a jump to the same card.
    @State private var supersetScrollRequest = IOSWorkoutCardScrollRequest(rowID: "", token: 0)
    /// Live record notice of the last log or edit; it hides after a few seconds.
    @State private var recordNotice: IOSWorkoutRecordNotice?

    private var data: ILTrainingData? { store.data }

    private var activeSession: ILWorkoutSession? { data?.activeSession }

    var body: some View {
        screenContent
            .safeAreaInset(edge: .top) {
                VStack(spacing: 0) {
                    if let recordNotice, recordNotice.sessionID == activeSession?.id {
                        recordBanner(recordNotice)
                    }
                    if let deletedSet, deletedSet.sessionId == activeSession?.id {
                        HStack {
                            Text("Satz \(deletedSet.setNumber) gelöscht")
                            Spacer()
                            Button("Rückgängig", action: undoDeleteSet)
                                .frame(minHeight: 44)
                                .disabled(store.isBusy || isRestoringSet)
                        }
                        .font(.geist(.callout))
                        .padding(.horizontal)
                        .background(.regularMaterial)
                        .accessibilityElement(children: .contain)
                    }
                }
            }
            .task(id: undoToken) {
                guard let token = undoToken else { return }
                do { try await Task.sleep(for: .seconds(8)) } catch { return }
                guard undoToken == token, !isRestoringSet else { return }
                deletedSet = nil
                undoToken = nil
            }
            .thrymScreenBackground(
                ember: theme.palette(for: colorScheme).background,
                emberNavigationBar: theme.palette(for: colorScheme).background
            )
            .navigationTitle(screenTitle)
            .navigationBarTitleDisplayMode(.inline)
            // A running Liquid Glass workout shows its own head instead of the bar.
            .toolbar(appearance == .liquidGlass && (activeSession != nil || completedSessionID != 0) ? .hidden : .automatic, for: .navigationBar)
            .sheet(isPresented: $showingExercisePicker) {
                exercisePickerSheet
            }
            .sheet(item: $editingContext) { context in
                setEditorSheet(context: context)
            }
            .sheet(isPresented: $showingNotes) {
                notesSheet
            }
            .alert(item: errorAlertBinding) { alert in
                Alert(
                    title: Text("Training"),
                    message: Text(alert.message),
                    dismissButton: .default(Text("OK"))
                )
            }
            .sheet(isPresented: $showingFinishConfirmation) {
                if let session = activeSession, let data {
                    IOSWorkoutFinishSheet(rows: exerciseLayout(session: session, data: data).rows, busy: isFinishing || store.isBusy, error: localErrorMessage,
                        onContinue: { showingFinishConfirmation = false }, onFinish: finishWorkout)
                        .interactiveDismissDisabled(isFinishing)
                }
            }
            .confirmationDialog(
                "Training verwerfen?",
                isPresented: $showingCancelConfirmation,
                titleVisibility: .visible
            ) {
                Button("Training verwerfen", role: .destructive) { cancelWorkout() }
                Button("Weiter trainieren", role: .cancel) { }
            } message: {
                Text("Die laufende Session und ihre Sätze werden verworfen.")
            }
            .task(id: activeSession?.id) {
                await restorePresentationState()
            }
            .onChange(of: settings.state.deloadMode) { _, _ in reconcileRestTimerAfterTargetChange() }
            .onChange(of: activeSession?.id) { _, _ in
                deletedSet = nil
                undoToken = nil
                recordNotice = nil
            }
    }

    /// Keeping the data branch separate from the sheet/lifecycle modifiers makes each
    /// expression small enough for Swift's result-builder type checker. This also keeps the
    /// active workout's identity stable when a sheet is presented.
    @ViewBuilder
    private var screenContent: some View {
        if let data {
            if data.activeSession == nil, let session = data.workoutSessions.first(where: { $0.id == Int64(completedSessionID) && $0.endTime != nil }) {
                IOSWorkoutCompletionScreen(session: session, rows: exerciseLayout(session: session, data: data).rows, unitSystem: settings.state.unitSystem,
                    onClose: closeCompletion)
            } else if let session = data.activeSession {
                activeWorkout(session: session, data: data)
            } else {
                IOSWorkoutStartView(
                    plans: data.trainingPlans.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending },
                    onStartFree: startFreeWorkout,
                    onStartPlan: startPlan
                )
            }
        } else {
            unavailableView
        }
    }

    private var exercisePickerSheet: some View {
        IOSWorkoutExercisePicker(
            exercises: data?.visibleExercises.sortedByDisplayName() ?? [],
            excludedIDs: Set(adHocExerciseIDs),
            onSelect: addAdHocExercise
        )
    }

    private func setEditorSheet(context: IOSWorkoutSetEditorContext) -> some View {
        IOSWorkoutSetEditor(context: context, onSave: saveSet)
    }

    @ViewBuilder
    private var notesSheet: some View {
        if let session = activeSession {
            IOSWorkoutNotesSheet(initialNotes: session.notes, onSave: saveNotes)
        }
    }

    private var errorAlertBinding: Binding<IOSWorkoutAlert?> {
        Binding(
            get: { localErrorMessage.map(IOSWorkoutAlert.init(message:)) },
            set: { _ in localErrorMessage = nil }
        )
    }

    private func restorePresentationState() async {
        guard let activeSession else {
            adHocExerciseIDs = []
            restTimers = [:]
            IOSWorkoutRestTimerPersistence.clear()
            // The completion screen keeps the order of the workout it shows.
            if completedSessionID != 0,
               let completed = data?.workoutSessions.first(where: { $0.id == Int64(completedSessionID) && $0.endTime != nil }) {
                exerciseOrder = IOSWorkoutExerciseOrderPersistence.load(for: completed)
            } else {
                exerciseOrder = nil
                IOSWorkoutExerciseOrderPersistence.clear()
            }
            return
        }

        // A new workout ends the completion screen of the previous one. Loading the new
        // workout's order removes the stored order of that screen, so it must not come back,
        // for example after the new workout is discarded.
        if completedSessionID != 0, completedSessionID != Int(activeSession.id) {
            completedSessionID = 0
        }
        loadAdHocExercises(for: activeSession.id)
        exerciseOrder = IOSWorkoutExerciseOrderPersistence.load(for: activeSession)
        if let data {
            restoreRestTimers(for: activeSession, data: data)
        } else {
            restTimers = [:]
            IOSWorkoutRestTimerPersistence.clear()
        }
    }

    private var unavailableView: some View {
        ContentUnavailableView {
            Label("Trainingsdaten nicht verfügbar", systemImage: "externaldrive.badge.questionmark")
        } description: {
            Text(store.errorMessage ?? String(localized: "Die lokale Trainingsdatei konnte nicht geladen werden."))
        }
        .padding()
    }

    private var screenTitle: String {
        guard let activeSession else { return String(localized: "Training") }
        return activeSession.name.isEmpty ? String(localized: "Training") : activeSession.name
    }

    @ViewBuilder
    private func activeWorkout(session: ILWorkoutSession, data: ILTrainingData) -> some View {
        let layout = exerciseLayout(session: session, data: data)
        let rows = layout.rows
        let liquidGlass = appearance == .liquidGlass
        let allPlannedSetsDone = rows.allPlannedSetsDone
        ScrollViewReader { proxy in
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                Group {
                    if liquidGlass {
                        glassWorkoutHeader(session: session, layout: layout, proxy: proxy)
                    } else {
                        workoutHeader(session: session)
                    }
                }
                .id(Self.headID)
                .modifier(IOSWorkoutHeadTimeVisibilityFallback(isHidden: $headTimeHidden))

                // Liquid Glass shows the timers in the dock and pause screen instead.
                if !liquidGlass {
                    restTimersView(layout: layout)
                }

                if let message = store.errorMessage ?? localErrorMessage {
                    Label(message, systemImage: "exclamationmark.triangle.fill")
                        .font(.geist(.footnote))
                        .foregroundStyle(.red)
                        .padding(.horizontal)
                }

                if rows.isEmpty {
                    ContentUnavailableView {
                        Label("Noch keine Übung", systemImage: "figure.strengthtraining.traditional")
                    } description: {
                        Text("Füge eine Übung hinzu, um den ersten Satz zu loggen.")
                    } actions: {
                        Button("Übung hinzufügen") { showingExercisePicker = true }
                            .buttonStyle(.borderedProminent).thrymButtonText()
                    }
                    .padding(.vertical, 30)
                } else {
                    ForEach(layout.groups) { group in
                        exerciseRenderGroup(group, layout: layout, sessionID: session.id)
                            .id(group.id)
                    }
                }

                Button {
                    showingExercisePicker = true
                } label: {
                    Label("Übung hinzufügen", systemImage: "plus.circle.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .padding(.horizontal)
                .padding(.bottom, allPlannedSetsDone ? 8 : 24)
                .accessibilityHint("Öffnet die Suche nach einer weiteren Übung")

                if allPlannedSetsDone {
                    IOSWorkoutFinishCard(plannedSetCount: rows.plannedSetCount) {
                        showingFinishConfirmation = true
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 24)
                    .id("workout-finish")
                }
            }
            .padding(.top, 8)
        }
        .scrollDismissesKeyboard(.interactively)
        .modifier(IOSWorkoutHeadTimeVisibility(isHidden: $headTimeHidden))
        .overlay(alignment: .top) {
            if headTimeHidden {
                IOSWorkoutElapsedBubble(startDate: session.startDate) {
                    withAnimation(reduceMotion || theme.reducedMotion ? nil : .snappy) {
                        proxy.scrollTo(Self.headID, anchor: .top)
                    }
                }
                .padding(.top, 6)
                .transition(
                    reduceMotion || theme.reducedMotion
                        ? .identity
                        : .opacity.combined(with: .scale(scale: 0.9, anchor: .top))
                )
            }
        }
        .animation(reduceMotion || theme.reducedMotion ? nil : .snappy, value: headTimeHidden)
        .safeAreaInset(edge: .bottom) {
            // Liquid Glass moves "Beenden" and "Verwerfen" into the head and shows
            // every running rest as a dock row instead.
            if liquidGlass {
                glassRestDock(rows: rows)
            } else {
            HStack(spacing: 12) {
                Button {
                    showingCancelConfirmation = true
                } label: {
                    Label("Verwerfen", systemImage: "trash")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(.red)

                let finishButton = Button {
                    showingFinishConfirmation = true
                } label: {
                    Label("Beenden", systemImage: "checkmark.circle.fill")
                        .frame(maxWidth: .infinity)
                }
                // While the finish card shows, it is the one prominent finish action.
                if allPlannedSetsDone {
                    finishButton.buttonStyle(.bordered)
                } else {
                    finishButton.buttonStyle(.borderedProminent).thrymButtonText()
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 10)
            .background(.thinMaterial)
            }
        }
        .onChange(of: supersetScrollRequest) { _, request in
            withAnimation(reduceMotion || theme.reducedMotion ? nil : .snappy) {
                proxy.scrollTo(IOSWorkoutCardScrollRequest.cardID(request.rowID), anchor: .top)
            }
        }
        .onChange(of: finishScrollRequest) { _, _ in
            // The card is the last item of the lazy stack: let it appear for one turn, then
            // scroll it above the dock or bar.
            Task { @MainActor in
                await Task.yield()
                withAnimation(reduceMotion || theme.reducedMotion ? nil : .snappy) {
                    proxy.scrollTo("workout-finish", anchor: .bottom)
                }
            }
        }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { viewportHeight = $0 }
        .fullScreenCover(item: pausePresentation) { presented in
            // While the cover slides away after its timer ended, it keeps the last value.
            let timer = restTimers[presented.id] ?? presented.timer
            let row = rows.first { $0.id == timer.rowKey }
            let planned = rows.reduce(0) { $0 + $1.loggingSlots.count }
            let open = rows.reduce(0) { $0 + ($1.remainingPlannedSets ?? 0) }
            let logged = rows.reduce(0) { $0 + $1.sets.count }
            IOSWorkoutGlassPauseScreen(
                timer: timer,
                exerciseName: row?.exercise.displayName ?? String(localized: "Übung"),
                nextSetNumber: row?.nextSetNumber ?? 1,
                progressText: planned > 0 ? String(localized: "\(planned - open) / \(planned) Sätze") : ilSetCountText(logged),
                onMinus: { adjustRestTimer(rowKey: timer.rowKey, by: -15) },
                onPlus: { adjustRestTimer(rowKey: timer.rowKey, by: 30) },
                onSkip: {
                    pauseRowKey = nil
                    dismissRestTimer(rowKey: timer.rowKey)
                },
                onClose: { pauseRowKey = nil }
            ) {
                // A record set before the rest opens the pause right away; show it there too,
                // below the top row, so "Zum Training" and the actions stay free.
                if let recordNotice, recordNotice.sessionID == activeSession?.id {
                    recordBanner(recordNotice, cornerRadius: 22)
                }
            }
            .background { restCompletionWatchers }
        }
        .onChange(of: Set(restTimers.keys)) { _, keys in
            // The shown rest ended: close instead of moving on to another exercise.
            if let pauseRowKey, !keys.contains(pauseRowKey) {
                self.pauseRowKey = nil
            }
        }
    }

    /// The rest dock: a row per countdown and one for the newest count-up timer
    /// (`IOSWorkoutRestDock.dockTimers`). The completion watchers sit here because
    /// the inset, unlike the lazy list, stays alive while the athlete scrolls.
    @ViewBuilder
    private func glassRestDock(rows: [IOSWorkoutExerciseRow]) -> some View {
        if !restTimers.isEmpty {
            IOSWorkoutGlassRestDockStack(
                entries: IOSWorkoutRestDock.dockTimers(restTimers.values).map { timer in
                    IOSWorkoutGlassRestDockStack.Entry(
                        timer: timer,
                        exerciseName: rows.first { $0.id == timer.rowKey }?.exercise.displayName ?? String(localized: "Pause")
                    )
                },
                maxHeight: IOSWorkoutGlassRestDockStack.maxHeight(viewportHeight: viewportHeight),
                onOpen: { pauseRowKey = $0.rowKey },
                onSkip: { dismissRestTimer(rowKey: $0.rowKey) }
            )
            .background { restCompletionWatchers }
        }
    }

    /// End-of-rest watchers for every timer. The pause screen repeats them so a rest
    /// also ends on time while the cover hides the dock; `completeRestTimer` keeps
    /// the second report from counting.
    private var restCompletionWatchers: some View {
        ForEach(orderedRestTimers) { timer in
            IOSWorkoutRestCompletionWatcher(timer: timer) { completeRestTimer(timer) }
        }
    }

    private var pausePresentation: Binding<IOSWorkoutPausePresentation?> {
        Binding(
            get: { pauseRowKey.flatMap { restTimers[$0] }.map(IOSWorkoutPausePresentation.init(timer:)) },
            set: { pauseRowKey = $0?.id }
        )
    }

    /// Liquid Glass head: position, time, progress and the exercise rail. A tap on
    /// the rail scrolls to that exercise.
    private func glassWorkoutHeader(
        session: ILWorkoutSession,
        layout: IOSWorkoutExerciseLayout,
        proxy: ScrollViewProxy
    ) -> some View {
        let rows = layout.rows
        let groups = layout.groups
        let currentIndex = groups.firstIndex { group in
            group.rows.contains { $0.target != nil && !$0.isComplete }
        }
        let name = session.name.isEmpty ? String(localized: "Freies Training") : session.name
        let planned = rows.reduce(0) { $0 + $1.loggingSlots.count }
        let open = rows.reduce(0) { $0 + ($1.remainingPlannedSets ?? 0) }
        let logged = rows.reduce(0) { $0 + $1.sets.count }
        let volume = rows.reduce(0.0) { total, row in
            total + row.sets.reduce(0.0) { $0 + $1.weightKg * Double($1.reps) }
        }
        let progress = planned > 0 ? String(localized: "\(planned - open) / \(planned) Sätze") : ilSetCountText(logged)
        return VStack(alignment: .leading, spacing: 12) {
            IOSWorkoutGlassHeader(
                label: currentIndex.map { String(localized: "\(name) · Übung \($0 + 1) von \(groups.count)") } ?? name,
                startDate: session.startDate,
                progressText: "\(progress) · \(ilVolumeText(volume.rounded(), unitSystem: settings.state.unitSystem))",
                hasNotes: !session.notes.isEmpty,
                onNotes: { showingNotes = true },
                onDiscard: { showingCancelConfirmation = true },
                onFinish: { showingFinishConfirmation = true }
            )
            if !groups.isEmpty {
                IOSWorkoutGlassRail(
                    items: groups.enumerated().map { index, group in
                        let done = group.rows.allSatisfy { $0.target != nil && $0.isComplete }
                        return IOSWorkoutRailItem(
                            id: group.id,
                            name: group.rows.map(\.exercise.displayName).joined(separator: " + "),
                            status: index == currentIndex ? .current : (done ? .done : .upcoming)
                        )
                    },
                    onSelect: { item in
                        withAnimation { proxy.scrollTo(item.id, anchor: .top) }
                    }
                )
            }
        }
        .padding(.top, 4)
    }

    @ViewBuilder
    private func restTimersView(layout: IOSWorkoutExerciseLayout) -> some View {
        if !restTimers.isEmpty {
            VStack(spacing: 8) {
                ForEach(orderedRestTimers) { timer in
                    restTimerView(timer, rows: layout.rows, groups: layout.groups)
                }
            }
            .padding(.horizontal)
        }
    }

    @ViewBuilder
    private func exerciseRenderGroup(
        _ group: IOSWorkoutExerciseRenderGroup,
        layout: IOSWorkoutExerciseLayout,
        sessionID: Int64
    ) -> some View {
        // Moving needs a neighbour; a workout with a single block shows no arrows.
        let movable = layout.groups.count > 1
        let canMoveUp = layout.canMove(groupID: group.id, direction: .up)
        let canMoveDown = layout.canMove(groupID: group.id, direction: .down)
        // Any member moves the whole block.
        let blockRowID = group.rows.first?.id ?? group.id
        VStack(alignment: .leading, spacing: 8) {
            if let groupID = group.supersetGroupID {
                IOSWorkoutSupersetHeader(
                    groupID: groupID,
                    exerciseCount: group.rows.count,
                    exerciseNames: group.rows.map { $0.exercise.displayName }.joined(separator: " • "),
                    canMoveUp: canMoveUp,
                    canMoveDown: canMoveDown,
                    onMoveUp: movable ? { moveExercise(rowID: blockRowID, direction: .up) } : nil,
                    onMoveDown: movable ? { moveExercise(rowID: blockRowID, direction: .down) } : nil
                )
                .padding(.horizontal)
            }

            ForEach(Array(group.rows.enumerated()), id: \.element.id) { index, row in
                // Superset members move with their header, so only single cards get arrows.
                let single = movable && group.supersetGroupID == nil
                IOSWorkoutExerciseCard(
                    row: row,
                    unitSystem: settings.state.unitSystem,
                    intensitySystem: settings.state.intensitySystem,
                    supersetTint: IOSWorkoutSupersetTint.color(
                        for: group.supersetGroupID,
                        index: index
                    ),
                    onAddSet: { presentNewSet(for: row, sessionID: sessionID) },
                    onApplyCoachWeight: { weightKg in
                        presentNewSet(
                            for: row,
                            sessionID: sessionID,
                            suggestedWeightKg: weightKg,
                            defaultSetType: .normal
                        )
                    },
                    onEditSet: { set in presentEditSet(set, row: row, sessionID: sessionID) },
                    onDeleteSet: deleteSet,
                    sessionID: sessionID,
                    onSaveSet: saveSet,
                    canMoveUp: canMoveUp,
                    canMoveDown: canMoveDown,
                    onMoveUp: single ? { moveExercise(rowID: row.id, direction: .up) } : nil,
                    onMoveDown: single ? { moveExercise(rowID: row.id, direction: .down) } : nil
                )
                .padding(.horizontal)
                .id(IOSWorkoutCardScrollRequest.cardID(row.id))
            }
        }
    }

    private func workoutHeader(session: ILWorkoutSession) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(session.name.isEmpty ? String(localized: "Freies Training") : session.name)
                        .font(.geist(.title2, weight: .bold))
                    Text(session.planId == nil ? String(localized: "Freie Session") : String(localized: "Plan-Session"))
                        .font(.geist(.subheadline))
                        .thrymSecondaryText()
                }
                Spacer()
                IOSWorkoutElapsedView(startDate: session.startDate)
            }

            HStack(spacing: 10) {
                Label("Seit \(session.startDate.formatted(date: .omitted, time: .shortened))", systemImage: "clock")
                    .font(.geist(.caption))
                    .thrymSecondaryText()

                Spacer()

                Button {
                    showingNotes = true
                } label: {
                    Label(session.notes.isEmpty ? String(localized: "Notiz") : String(localized: "Notiz bearbeiten"), systemImage: "note.text")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            .accessibilityElement(children: .contain)
        }
        .padding(.horizontal)
    }

    private func exerciseRows(session: ILWorkoutSession, data: ILTrainingData) -> [IOSWorkoutExerciseRow] {
        // A plan snapshot remains renderable even if its catalog exercise was archived after
        // the session started. The picker still uses `visibleExercises` above, but active rows
        // resolve against the complete catalog to preserve historical identity.
        let exerciseByID = Dictionary(uniqueKeysWithValues: data.exercises.map { ($0.id, $0) })
        let sessionSets = data.sets(for: session)
        let targets = data.workoutPlanTargets
            .filter { $0.sessionId == session.id }
            .sorted { lhs, rhs in
                lhs.orderIndex == rhs.orderIndex ? lhs.id < rhs.id : lhs.orderIndex < rhs.orderIndex
            }

        var rows: [IOSWorkoutExerciseRow] = []
        for target in targets {
            guard let exercise = exerciseByID[target.exerciseId] else { continue }
            let sets = sessionSets
                .filter { $0.planTargetSnapshotId == target.id }
                .sorted { lhs, rhs in lhs.setNumber == rhs.setNumber ? lhs.id < rhs.id : lhs.setNumber < rhs.setNumber }
            rows.append(IOSWorkoutExerciseRow(
                id: "target-\(target.id)",
                exercise: exercise,
                target: target,
                displayTarget: session.endTime == nil ? deloadAdjustedTarget(target.target) : target.target,
                sets: sets,
                previousWorkWeightKg: previousWorkWeight(exerciseID: exercise.id, currentSession: session, data: data),
                nextSetRecommendation: IOSWorkoutNextSetCoach.evaluate(
                    sets: sets,
                    target: target,
                    intensitySystem: settings.state.intensitySystem
                ),
                deloadMode: session.endTime == nil ? settings.state.deloadMode : "NONE"
            ))
        }

        for exerciseID in IOSWorkoutExerciseLayout.adHocExerciseIDs(added: adHocExerciseIDs, sets: sessionSets) {
            guard let exercise = exerciseByID[exerciseID] else { continue }
            let sets = sessionSets
                .filter { $0.planTargetSnapshotId == nil && $0.exerciseId == exerciseID }
                .sorted { lhs, rhs in lhs.setNumber == rhs.setNumber ? lhs.id < rhs.id : lhs.setNumber < rhs.setNumber }
            rows.append(IOSWorkoutExerciseRow(
                id: "adhoc-\(exerciseID)",
                exercise: exercise,
                target: nil,
                displayTarget: nil,
                sets: sets,
                previousWorkWeightKg: previousWorkWeight(exerciseID: exercise.id, currentSession: session, data: data),
                nextSetRecommendation: IOSWorkoutNextSetCoach.evaluate(
                    sets: sets,
                    target: nil,
                    intensitySystem: settings.state.intensitySystem
                ),
                deloadMode: session.endTime == nil ? settings.state.deloadMode : "NONE"
            ))
        }
        return rows
    }

    /// Rows and blocks in display order: the default order from `exerciseRows`, arranged
    /// by the stored order of this workout.
    private func exerciseLayout(session: ILWorkoutSession, data: ILTrainingData) -> IOSWorkoutExerciseLayout {
        IOSWorkoutExerciseLayout(
            rowsInDefaultOrder: exerciseRows(session: session, data: data),
            storedKeys: exerciseOrder ?? []
        )
    }

    /// Applies the shared display-only deload helper. The returned DTO is deliberately a fresh
    /// local value: `target` remains the immutable persisted snapshot used for set identity and
    /// all backend writes.
    private func deloadAdjustedTarget(_ target: ILProgressionTarget) -> ILProgressionTarget {
        let adjusted = DeloadTargetAdjustment.shared.adjustByName(
            sets: Int32(target.sets),
            reps: Int32(target.reps),
            weightKg: target.weightKg,
            modeName: settings.state.deloadMode
        )
        return ILProgressionTarget(
            sets: Int(adjusted.sets),
            reps: Int(adjusted.reps),
            weightKg: adjusted.weightKg
        )
    }

    private func previousWorkWeight(
        exerciseID: Int64,
        currentSession: ILWorkoutSession,
        data: ILTrainingData
    ) -> Double? {
        let candidates = data.workoutSessions
            .filter { session in
                guard session.id != currentSession.id, session.endTime != nil else { return false }
                if settings.state.shareWeightHistoryAcrossContexts {
                    // Android's SharedPlan scope keeps history within the selected plan;
                    // genuinely free sessions use the global scope.
                    return currentSession.planId == nil || session.planId == currentSession.planId
                }
                if let planID = currentSession.planId {
                    if let metaPlanID = currentSession.metaPlanId {
                        return session.planId == planID && session.metaPlanId == metaPlanID
                    }
                    return session.planId == planID && session.metaPlanId == nil
                }
                // Free sessions have no context and therefore use global history.
                return true
            }
            .sorted {
                $0.startTime == $1.startTime ? $0.id > $1.id : $0.startTime > $1.startTime
            }

        for session in candidates {
            let exerciseSets = data.sets(for: session)
                .filter { $0.exerciseId == exerciseID }
            // Android selects the most recent completed session that contains the
            // exercise. A warmup-only session therefore intentionally suppresses
            // an older work-set hint instead of leaking across sessions.
            guard !exerciseSets.isEmpty else { continue }
            return exerciseSets
                .filter { [IOSWorkoutSetType.normal, .failure].contains(resolvedIOSWorkoutSetType($0)) }
                .sorted {
                    $0.completedAt == $1.completedAt ? $0.id > $1.id : $0.completedAt > $1.completedAt
                }
                .first?.weightKg
        }
        return nil
    }

    private func startFreeWorkout(name: String) {
        Task {
            let success = await store.startWorkout(name: name.isEmpty ? String(localized: "Freies Training") : name)
            if !success {
                localErrorMessage = store.errorMessage ?? String(localized: "Training konnte nicht gestartet werden.")
            } else {
                store.errorMessage = nil
            }
        }
    }

    private func startPlan(_ plan: ILTrainingPlan) {
        Task {
            let success = await store.startWorkout(name: plan.name, planId: plan.id)
            if !success {
                localErrorMessage = store.errorMessage ?? String(localized: "Plan konnte nicht gestartet werden.")
            } else {
                store.errorMessage = nil
            }
        }
    }

    private func addAdHocExercise(_ exercise: ILExercise) {
        guard let session = activeSession, !adHocExerciseIDs.contains(exercise.id) else { return }
        adHocExerciseIDs.append(exercise.id)
        saveAdHocExercises(for: session.id)
        // Without a stored order the default order already lists ad-hoc exercises as added.
        if var order = exerciseOrder {
            order.append("adhoc-\(exercise.id)")
            exerciseOrder = order
            IOSWorkoutExerciseOrderPersistence.save(order, for: session)
        }
    }

    /// Moves the block that contains `rowID` (a single exercise or a whole superset) one
    /// place up or down. Only the display order changes: no store command, no
    /// `orderIndex` or superset id, so coach, plan write-back and readiness are untouched.
    private func moveExercise(rowID: String, direction: SessionOrderMove) {
        guard let session = activeSession, session.endTime == nil, let data else { return }
        guard let next = exerciseLayout(session: session, data: data).move(key: rowID, direction: direction) else { return }
        withAnimation(reduceMotion || theme.reducedMotion ? nil : .default) { exerciseOrder = next }
        IOSWorkoutExerciseOrderPersistence.save(next, for: session)
    }

    private func presentNewSet(
        for row: IOSWorkoutExerciseRow,
        sessionID: Int64,
        suggestedWeightKg: Double? = nil,
        defaultSetType: IOSWorkoutSetType? = nil
    ) {
        editingContext = IOSWorkoutSetEditorContext(
            id: "new-\(sessionID)-\(row.id)-\(UUID().uuidString)",
            sessionID: sessionID,
            row: row,
            existingSet: nil,
            defaultSetType: defaultSetType ?? (settings.state.defaultWarmupFlag ? .warmup : .normal),
            suggestedWeightKg: suggestedWeightKg
        )
    }

    private func presentEditSet(_ set: ILWorkoutSet, row: IOSWorkoutExerciseRow, sessionID: Int64) {
        editingContext = IOSWorkoutSetEditorContext(
            id: "edit-\(set.id)",
            sessionID: sessionID,
            row: row,
            existingSet: set,
            defaultSetType: resolvedIOSWorkoutSetType(set),
            intention: store.intention(forSetId: set.id)
        )
    }

    private func saveSet(_ set: ILWorkoutSet, intention: ILSetIntention) async -> Bool {
        // Only the athlete's own log or edit that completes the plan scrolls to the finish
        // card. Reopening a complete workout, an undo or a deload switch never does.
        let session = activeSession
        let wasDone = session.flatMap { session in
            data.map { exerciseRows(session: session, data: $0).allPlannedSetsDone }
        } ?? true
        // Records compare the exercise's sets before and after the save; warm-ups never count.
        let recordsBefore = resolvedIOSWorkoutSetType(set) == .warmup
            ? nil
            : data.map { IOSWorkoutRecords.recordInputs(exerciseID: set.exerciseId, data: $0) }
        let success = await store.saveSet(set, intention: intention)
        guard success else {
            localErrorMessage = store.errorMessage ?? String(localized: "Der Satz konnte nicht gespeichert werden.")
            return false
        }
        store.errorMessage = nil
        // In a superset a new work set moves on to the next member with open sets.
        var nextSupersetRowID: String?
        if let session, let data = store.data {
            if set.id == 0, resolvedIOSWorkoutSetType(set) != .warmup {
                nextSupersetRowID = exerciseLayout(session: session, data: data)
                    .nextSupersetRowID(after: iosWorkoutRestTimerRowKey(for: set))
            }
            var announcements: [String] = []
            if let recordsBefore,
               let notice = showRecordNotice(exerciseID: set.exerciseId, sessionID: session.id, before: recordsBefore, data: data) {
                announcements.append("\(notice.title). \(notice.detail)")
            }
            let rows = exerciseRows(session: session, data: data)
            if !wasDone, rows.allPlannedSetsDone {
                finishScrollRequest &+= 1
                // The finish card appears because of this save: say so once.
                announcements.append(String(localized: "Alle \(rows.plannedSetCount) geplanten Sätze geschafft"))
            }
            announce(announcements)
        }

        // Android keeps one timer per exercise key. A warmup or the final planned
        // work set removes only that row's timer; another superset member keeps
        // counting independently.
        if set.id == 0, let session = activeSession {
            let rowKey = iosWorkoutRestTimerRowKey(for: set)
            if resolvedIOSWorkoutSetType(set) == .warmup {
                dismissRestTimer(rowKey: rowKey)
            } else if set.planTargetSnapshotId != nil,
                      let data = store.data,
                      let row = exerciseRows(session: session, data: data).first(where: { $0.id == rowKey }) {
                if row.isComplete {
                    dismissRestTimer(rowKey: rowKey)
                } else {
                    // The next superset exercise comes first, so its pause stays in the dock.
                    startRestTimer(sessionID: session.id, rowKey: rowKey, mayOpenPause: nextSupersetRowID == nil)
                }
            } else if resolvedIOSWorkoutSetType(set) != .warmup {
                // Ad-hoc exercises have no immutable target to complete, so their
                // timer remains elapsed/countdown until the athlete dismisses it.
                // Android starts a timer for every non-warmup submission too.
                startRestTimer(sessionID: session.id, rowKey: rowKey)
            }
        }
        if let nextSupersetRowID {
            supersetScrollRequest = IOSWorkoutCardScrollRequest(
                rowID: nextSupersetRowID,
                token: supersetScrollRequest.token &+ 1
            )
        }
        return true
    }

    /// Live record notice after a log or an edit that beat an earlier best of another workout:
    /// the banner (also in the pause) and one success haptic; the caller announces it. The
    /// first workout of an exercise, ties and lowered edits stay quiet and return nil.
    private func showRecordNotice(
        exerciseID: Int64,
        sessionID: Int64,
        before: [RecordSetInput],
        data: ILTrainingData
    ) -> IOSWorkoutRecordNotice? {
        let exercise = data.exercises.first { $0.id == exerciseID }
        let after = IOSWorkoutRecords.recordInputs(exerciseID: exerciseID, data: data)
        let lines = IOSWorkoutRecords.liveImprovements(
            sessionID: sessionID,
            before: before,
            after: after,
            addedLoad: exercise?.tracksAddedLoad == true,
            data: data
        )
        // A mark always comes with a weight or reps record of the same set; it only names the title.
        let mark = lines.isEmpty ? nil : exercise.flatMap { exercise in
            IOSRecognition.liveMark(
                sessionID: sessionID,
                before: before,
                after: after,
                category: exercise.category,
                unitSystem: settings.state.unitSystem
            )
        }
        guard let notice = IOSWorkoutRecords.notice(
            sessionID: sessionID,
            exerciseName: exercise?.displayName ?? String(localized: "Übung"),
            lines: lines,
            unitSystem: settings.state.unitSystem,
            mark: mark
        ) else { return nil }
        withAnimation(noticeAnimation) { recordNotice = notice }
        let feedback = UINotificationFeedbackGenerator()
        feedback.prepare()
        feedback.notificationOccurred(.success)
        return notice
    }

    /// One VoiceOver announcement for everything a save brought. High priority: the save is
    /// often followed by a focus change (the set editor closes, the pause opens), which would
    /// cut a default announcement off.
    private func announce(_ parts: [String]) {
        guard !parts.isEmpty else { return }
        var announcement = AttributedString(parts.joined(separator: ". "))
        announcement.accessibilitySpeechAnnouncementPriority = .high
        AccessibilityNotification.Announcement(announcement).post()
    }

    private var noticeAnimation: Animation? {
        reduceMotion || theme.reducedMotion ? nil : .snappy
    }

    private func recordBanner(_ notice: IOSWorkoutRecordNotice, cornerRadius: CGFloat = 0) -> some View {
        IOSWorkoutRecordBanner(notice: notice, cornerRadius: cornerRadius) {
            dismissRecordNotice(id: notice.id)
        }
        .transition(reduceMotion || theme.reducedMotion ? .identity : .move(edge: .top).combined(with: .opacity))
    }

    /// Hides the notice on a tap or when its time is up, unless a newer one replaced it.
    private func dismissRecordNotice(id: UUID) {
        guard recordNotice?.id == id else { return }
        withAnimation(noticeAnimation) { recordNotice = nil }
    }

    private func deleteSet(_ set: ILWorkoutSet) {
        guard !store.isBusy, !isRestoringSet else { return }
        let intention = store.intention(forSetId: set.id)
        Task {
            let success = await store.command("set.delete", fields: ["id": set.id])
            if !success {
                localErrorMessage = store.errorMessage ?? String(localized: "Der Satz konnte nicht gelöscht werden.")
            } else if activeSession?.id == set.sessionId {
                store.errorMessage = nil
                // Undo has priority over a record notice, as on Android.
                recordNotice = nil
                deletedSet = set
                deletedIntention = intention
                undoToken = UUID()
            }
        }
    }

    private func undoDeleteSet() {
        guard var restored = deletedSet, restored.sessionId == activeSession?.id,
              !store.isBusy, !isRestoringSet else { return }
        let token = undoToken
        let intention = deletedIntention
        restored.id = 0
        isRestoringSet = true
        Task {
            defer { isRestoringSet = false }
            let success = await store.saveSet(restored, intention: intention)
            guard undoToken == token else { return }
            if success {
                deletedSet = nil
                undoToken = nil
            } else {
                localErrorMessage = store.errorMessage ?? String(localized: "Der Satz konnte nicht wiederhergestellt werden.")
                undoToken = UUID()
            }
        }
    }

    private func finishWorkout() {
        guard let session = activeSession, !isFinishing, !store.isBusy else { return }
        isFinishing = true
        // The order on screen, also without a move: the completion screen no longer knows the
        // order in which ad-hoc exercises were added.
        let order = data.map { exerciseLayout(session: session, data: $0).rows.map(\.id) } ?? exerciseOrder
        Task {
            defer { isFinishing = false }
            let success = await store.command("workout.finish", fields: ["sessionId": session.id])
            if success {
                completedSessionID = Int(session.id)
                // The completion screen lists the exercises in this order. The store
                // observer can publish the finished session first, and the restore that
                // follows would then drop the order before `completedSessionID` is set.
                exerciseOrder = order
                if let order {
                    IOSWorkoutExerciseOrderPersistence.save(order, for: session)
                }
                showingFinishConfirmation = false
                store.errorMessage = nil
                recordNotice = nil
                restTimers = [:]
                IOSWorkoutRestTimerPersistence.clear(sessionID: session.id)
                adHocExerciseIDs = []
                UserDefaults.standard.removeObject(forKey: adHocKey(for: session.id))
            } else {
                localErrorMessage = store.errorMessage ?? String(localized: "Training konnte nicht beendet werden.")
            }
        }
    }

    private func cancelWorkout() {
        guard let session = activeSession else { return }
        Task {
            let success = await store.command("workout.cancel", fields: ["sessionId": session.id])
            if success {
                store.errorMessage = nil
                recordNotice = nil
                restTimers = [:]
                IOSWorkoutRestTimerPersistence.clear(sessionID: session.id)
                exerciseOrder = nil
                IOSWorkoutExerciseOrderPersistence.clear()
                adHocExerciseIDs = []
                UserDefaults.standard.removeObject(forKey: adHocKey(for: session.id))
            } else {
                localErrorMessage = store.errorMessage ?? String(localized: "Training konnte nicht verworfen werden.")
            }
        }
    }

    private func closeCompletion() {
        completedSessionID = 0
        exerciseOrder = nil
        IOSWorkoutExerciseOrderPersistence.clear()
    }

    private func saveNotes(_ notes: String) {
        guard let session = activeSession else { return }
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
        Task {
            do {
                let success = await store.command("workout.update", fields: ["session": try store.encoded(updated)])
                if !success {
                    localErrorMessage = store.errorMessage ?? String(localized: "Notiz konnte nicht gespeichert werden.")
                } else {
                    store.errorMessage = nil
                }
            } catch {
                localErrorMessage = error.localizedDescription
            }
        }
    }

    private var orderedRestTimers: [IOSWorkoutRestTimer] {
        restTimers.values.sorted {
            $0.startedAtEpochMillis == $1.startedAtEpochMillis
                ? $0.rowKey < $1.rowKey
                : $0.startedAtEpochMillis < $1.startedAtEpochMillis
        }
    }

    @ViewBuilder
    private func restTimerView(
        _ timer: IOSWorkoutRestTimer,
        rows: [IOSWorkoutExerciseRow],
        groups: [IOSWorkoutExerciseRenderGroup]
    ) -> some View {
        let row = rows.first { $0.id == timer.rowKey }
        IOSWorkoutRestTimerView(
            timer: timer,
            title: row?.exercise.displayName,
            accentColor: restTimerTint(for: timer.rowKey, groups: groups),
            onDismiss: { dismissRestTimer(rowKey: timer.rowKey) },
            onComplete: { dismissRestTimer(rowKey: timer.rowKey) }
        )
    }

    private func restTimerTint(
        for rowKey: String,
        groups: [IOSWorkoutExerciseRenderGroup]
    ) -> Color? {
        guard let group = groups.first(where: { $0.rows.contains { $0.id == rowKey } }),
              let groupID = group.supersetGroupID,
              let index = group.rows.firstIndex(where: { $0.id == rowKey })
        else { return nil }
        return IOSWorkoutSupersetTint.color(for: groupID, index: index)
    }

    private func iosWorkoutRestTimerRowKey(for set: ILWorkoutSet) -> String {
        if let targetID = set.planTargetSnapshotId {
            return "target-\(targetID)"
        }
        return "adhoc-\(set.exerciseId)"
    }

    private func startRestTimer(sessionID: Int64, rowKey: String, mayOpenPause: Bool = true) {
        let timer = IOSWorkoutRestTimer.make(
            sessionID: sessionID,
            rowKey: rowKey,
            durationSeconds: max(
                0,
                settings.state.autoRestTimerEnabled ? settings.state.defaultRestTimeSeconds : 0
            )
        )
        let opensPause = mayOpenPause && appearance == .liquidGlass &&
            IOSWorkoutRestDock.opensPause(starting: timer, running: restTimers)
        restTimers[rowKey] = timer
        IOSWorkoutRestTimerPersistence.upsert(timer)
        if opensPause {
            pauseRowKey = rowKey
        }
    }

    /// End of a countdown: one success haptic as in Ember, then the timer goes.
    /// Ignores a report for a timer that was already ended, moved or restarted.
    private func completeRestTimer(_ timer: IOSWorkoutRestTimer) {
        guard restTimers[timer.rowKey] == timer else { return }
        let feedback = UINotificationFeedbackGenerator()
        feedback.prepare()
        feedback.notificationOccurred(.success)
        dismissRestTimer(rowKey: timer.rowKey)
    }

    /// Lengthens or shortens a running countdown (Liquid Glass pause: "−15 s" / "+30 s").
    private func adjustRestTimer(rowKey: String, by deltaSeconds: Int) {
        guard let timer = restTimers[rowKey] else { return }
        let adjusted = timer.adjusted(by: deltaSeconds)
        restTimers[rowKey] = adjusted
        IOSWorkoutRestTimerPersistence.upsert(adjusted)
    }

    private func dismissRestTimer(rowKey: String) {
        let sessionID = restTimers[rowKey]?.sessionID ?? activeSession?.id
        restTimers.removeValue(forKey: rowKey)
        if pauseRowKey == rowKey {
            pauseRowKey = nil
        }
        if let sessionID {
            IOSWorkoutRestTimerPersistence.remove(sessionID: sessionID, rowKey: rowKey)
        }
    }

    private func restoreRestTimers(for session: ILWorkoutSession, data: ILTrainingData) {
        let rows = exerciseRows(session: session, data: data)
        let persisted = IOSWorkoutRestTimerPersistence.load(sessionID: session.id)
        let validRowIDs = Set(rows.map(\.id))
        let validTimers = persisted.filter { rowKey, timer in
            guard timer.sessionID == session.id, validRowIDs.contains(rowKey) else { return false }
            guard let row = rows.first(where: { $0.id == rowKey }) else { return false }
            guard let remaining = row.remainingPlannedSets else {
                // Ad-hoc rows have no fixed final set and keep their timer until
                // the user dismisses it.
                return row.target == nil
            }
            return remaining > 0
        }
        restTimers = validTimers
        IOSWorkoutRestTimerPersistence.saveAll(Array(validTimers.values))
    }

    private func reconcileRestTimerAfterTargetChange() {
        guard let session = activeSession, let data else {
            restTimers = [:]
            IOSWorkoutRestTimerPersistence.clear()
            return
        }
        restoreRestTimers(for: session, data: data)
    }

    private func adHocKey(for sessionID: Int64) -> String {
        "thrym.workout.adhocExercises.\(sessionID)"
    }

    private func loadAdHocExercises(for sessionID: Int64) {
        let raw = UserDefaults.standard.string(forKey: adHocKey(for: sessionID)) ?? ""
        adHocExerciseIDs = raw
            .split(separator: ",")
            .compactMap { Int64($0) }
    }

    private func saveAdHocExercises(for sessionID: Int64) {
        UserDefaults.standard.set(
            adHocExerciseIDs.map(String.init).joined(separator: ","),
            forKey: adHocKey(for: sessionID)
        )
    }
}

/// A scroll to one exercise card; a new token repeats a jump to the same card.
struct IOSWorkoutCardScrollRequest: Equatable {
    let rowID: String
    let token: Int

    static func cardID(_ rowID: String) -> String { "card-\(rowID)" }
}

private struct IOSWorkoutAlert: Identifiable {
    let id = UUID()
    let message: String
}

/// The rest shown on the full-screen pause. Its identity is the exercise row, so
/// "−15 s" / "+30 s" update the open cover instead of presenting a new one.
private struct IOSWorkoutPausePresentation: Identifiable {
    let timer: IOSWorkoutRestTimer
    var id: String { timer.rowKey }
}

private struct IOSWorkoutElapsedView: View {
    let startDate: Date

    var body: some View {
        TimelineView(.periodic(from: Date(), by: 1)) { context in
            VStack(alignment: .trailing, spacing: 2) {
                Text(iosWorkoutFormatDuration(max(0, Int(context.date.timeIntervalSince(startDate)))))
                    .font(.geist(.title3, weight: .semibold).monospacedDigit())
                Text("Training-Zeit")
                    .font(.geist(.caption))
                    .thrymSecondaryText()
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Training-Zeit")
        }
    }
}

/// How far the list scrolls before the head's time line (label and large clock,
/// below 8 pt list padding) has left the visible area.
private let iosWorkoutHeadTimeDepth: CGFloat = 56

/// Reports whether the head's time line has scrolled out of view, from the list's
/// scroll offset. Only the flip is reported, not every scroll frame.
private struct IOSWorkoutHeadTimeVisibility: ViewModifier {
    @Binding var isHidden: Bool

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollGeometryChange(for: Bool.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top > iosWorkoutHeadTimeDepth
            } action: { _, hidden in
                isHidden = hidden
            }
        } else {
            content
        }
    }
}

/// iOS 17 has no scroll geometry; there the head reports its own position in the
/// scroll view. The bubble may then appear a few points late.
private struct IOSWorkoutHeadTimeVisibilityFallback: ViewModifier {
    @Binding var isHidden: Bool

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content
        } else {
            content.onGeometryChange(for: Bool.self) { proxy in
                proxy.frame(in: .scrollView).minY + iosWorkoutHeadTimeDepth < 8
            } action: { hidden in
                isHidden = hidden
            }
        }
    }
}

/// Floating workout time while the head is scrolled away: a small glass capsule at
/// the top with the running clock. A tap scrolls back to the head.
private struct IOSWorkoutElapsedBubble: View {
    let startDate: Date
    let onTap: () -> Void

    @Environment(\.thrymAppearance) private var appearance
    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        TimelineView(.periodic(from: startDate, by: 1)) { context in
            let clock = iosWorkoutElapsedClock(Int(context.date.timeIntervalSince(startDate)))
            Button(action: onTap) {
                HStack(spacing: 6) {
                    Image(systemName: "stopwatch")
                        .font(.system(size: 13, weight: .bold))
                        .thrymAccentText()
                    Text(clock)
                        .font(.geist(size: 16, weight: .bold))
                        .monospacedDigit()
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 36)
                .background { surface }
                .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
                // 44 pt hit area around the slimmer capsule.
                .padding(.vertical, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Training-Zeit")
            .accessibilityValue(clock)
            .accessibilityHint("Zeigt den Trainingskopf")
        }
    }

    @ViewBuilder
    private var surface: some View {
        if appearance == .liquidGlass {
            Color.clear.liquidGlass(.strong, in: Capsule())
        } else {
            let palette = theme.palette(for: colorScheme)
            Capsule()
                .fill(palette.surfaceElevated)
                .overlay { Capsule().strokeBorder(palette.primary.opacity(0.35)) }
        }
    }
}

private struct IOSWorkoutNotesSheet: View {
    @Environment(\.dismiss) private var dismiss
    let initialNotes: String
    let onSave: (String) -> Void

    @State private var notes: String

    init(initialNotes: String, onSave: @escaping (String) -> Void) {
        self.initialNotes = initialNotes
        self.onSave = onSave
        _notes = State(initialValue: initialNotes)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("Notizen zum laufenden Training")
                    .font(.geist(.headline))
                TextEditor(text: $notes)
                    .frame(minHeight: 180)
                    .padding(8)
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(.quaternary)
                    }
                    .accessibilityLabel("Training-Notizen")
                Spacer()
            }
            .padding()
            .navigationTitle("Notizen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Speichern") {
                        onSave(notes.trimmingCharacters(in: .whitespacesAndNewlines))
                        dismiss()
                    }
                }
            }
        }
    }
}
