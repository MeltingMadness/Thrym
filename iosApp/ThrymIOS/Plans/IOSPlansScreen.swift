import SwiftUI

/// Native plan and meta-plan entry point. All rows are rendered directly from
/// the shared Codable DTO graph and all mutations go through the shared store.
struct IOSPlansScreen: View {
    enum SectionMode: String, CaseIterable, Identifiable {
        case plans
        case metaPlans

        var id: String { rawValue }
        var title: String { self == .plans ? String(localized: "Pläne") : String(localized: "Meta-Pläne") }
        var systemImage: String { self == .plans ? "list.bullet.rectangle.portrait" : "rectangle.stack" }
    }

    @Environment(IOSTrainingStore.self) private var store
    @State private var mode: SectionMode = .plans
    @State private var editor: IOSPlansEditorPresentation?
    @State private var exerciseLibrary: IOSExerciseLibraryPresentation?
    @State private var pendingDelete: IOSPlansDeleteRequest?
    @State private var isStarting = false
    private let unitSystem: String

    init(unitSystem: String = "METRIC") {
        self.unitSystem = unitSystem
    }

    private var data: ILTrainingData? { store.data }

    var body: some View {
        NavigationStack {
            Group {
                if let error = store.errorMessage, data == nil {
                    ContentUnavailableView(
                        "Trainingsdaten nicht verfügbar",
                        systemImage: "externaldrive.badge.exclamationmark",
                        description: Text(error)
                    )
                } else {
                    planList
                }
            }
            .thrymScreenBackground()
            .navigationTitle("Pläne")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        editor = mode == .plans ? .plan(nil) : .metaPlan(nil)
                    } label: {
                        Label("Neu", systemImage: "plus")
                    }
                    .accessibilityHint(mode == .plans ? String(localized: "Neuen Trainingsplan anlegen") : String(localized: "Neuen Meta-Plan anlegen"))
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        exerciseLibrary = IOSExerciseLibraryPresentation()
                    } label: {
                        Label("Übungsbibliothek", systemImage: "figure.strengthtraining.traditional")
                    }
                    .accessibilityHint("Übungen suchen, bearbeiten und archivieren")
                }
            }
            .overlay(alignment: .bottom) {
                if isStarting {
                    ProgressView("Training wird gestartet …")
                        .padding(.horizontal, 18)
                        .padding(.vertical, 12)
                        .background(.regularMaterial, in: Capsule())
                        .padding(.bottom, 12)
                }
            }
        }
        .sheet(item: $editor) { destination in
            NavigationStack {
                switch destination {
                case .plan(let id):
                    IOSPlanEditorScreen(planID: id, unitSystem: unitSystem)
                case .metaPlan(let id):
                    IOSMetaPlanEditorScreen(metaPlanID: id)
                }
            }
        }
        .sheet(item: $exerciseLibrary) { _ in
            NavigationStack {
                IOSExerciseLibraryScreen()
            }
        }
        .confirmationDialog(
            pendingDelete.map { String(localized: "\($0.title) löschen?") } ?? String(localized: "Löschen?"),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Löschen", role: .destructive) {
                guard let request = pendingDelete else { return }
                pendingDelete = nil
                Task { await delete(request) }
            }
            Button("Abbrechen", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("Dieser Vorgang kann nicht rückgängig gemacht werden. Zugehörige Trainingsdaten bleiben erhalten.")
        }
    }

    @ViewBuilder
    private var planList: some View {
        List {
            Group {
            Section {
                IOSPlansModeSwitcher(
                    mode: $mode,
                    planCount: data?.trainingPlans.count ?? 0,
                    metaPlanCount: data?.metaTrainingPlans.count ?? 0
                )
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }

            if let error = store.errorMessage {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                        .font(.geist(.footnote))
                }
            }

            if mode == .plans {
                let plans = (data?.trainingPlans ?? []).sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
                if plans.isEmpty {
                    ContentUnavailableView("Noch keine Pläne", systemImage: "list.bullet.rectangle", description: Text("Lege einen Trainingsplan an, um Übungen und Ziele zu speichern."))
                        .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(plans) { plan in
                            IOSPlanListRow(
                                plan: plan,
                                exerciseCount: data?.planExercises.filter { $0.planId == plan.id }.count ?? 0,
                                onOpen: { editor = .plan(plan.id) },
                                onStart: { Task { await start(plan: plan) } },
                                onDelete: { pendingDelete = IOSPlansDeleteRequest(kind: .plan, itemID: plan.id, title: plan.name) }
                            )
                        }
                        .onDelete { offsets in
                            pendingDelete = offsets.compactMap { plans[safe: $0] }.first.map {
                                IOSPlansDeleteRequest(kind: .plan, itemID: $0.id, title: $0.name)
                            }
                        }
                    }
                }
            } else {
                let metaPlans = (data?.metaTrainingPlans ?? []).sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
                if metaPlans.isEmpty {
                    ContentUnavailableView("Noch keine Meta-Pläne", systemImage: "rectangle.stack", description: Text("Bündele mehrere Trainingspläne zu einer wiederholbaren Rotation."))
                        .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(metaPlans) { metaPlan in
                            IOSMetaPlanListRow(
                                plan: metaPlan,
                                itemCount: data?.metaPlanItems.filter { $0.metaPlanId == metaPlan.id }.count ?? 0,
                                onOpen: { editor = .metaPlan(metaPlan.id) },
                                onStart: { Task { await start(metaPlan: metaPlan) } },
                                onDelete: { pendingDelete = IOSPlansDeleteRequest(kind: .metaPlan, itemID: metaPlan.id, title: metaPlan.name) }
                            )
                        }
                        .onDelete { offsets in
                            pendingDelete = offsets.compactMap { metaPlans[safe: $0] }.first.map {
                                IOSPlansDeleteRequest(kind: .metaPlan, itemID: $0.id, title: $0.name)
                            }
                        }
                    }
                }
            }
            }
            .thrymListRows()
        }
        .listStyle(.insetGrouped)
        .refreshable { await Task.yield() }
    }

    private func start(plan: ILTrainingPlan) async {
        guard !isStarting else { return }
        isStarting = true
        defer { isStarting = false }
        _ = await store.startWorkout(name: plan.name, planId: plan.id)
    }

    private func start(metaPlan: ILMetaTrainingPlan) async {
        guard !isStarting else { return }
        isStarting = true
        defer { isStarting = false }
        _ = await store.startWorkout(name: metaPlan.name, metaPlanId: metaPlan.id)
    }

    private func delete(_ request: IOSPlansDeleteRequest) async {
        switch request.kind {
        case .plan:
            _ = await store.command("plan.delete", fields: ["id": request.itemID])
        case .metaPlan:
            _ = await store.command("metaplan.delete", fields: ["id": request.itemID])
        }
    }
}

private enum IOSPlansEditorPresentation: Identifiable {
    case plan(Int64?)
    case metaPlan(Int64?)

    var id: String {
        switch self {
        case .plan(let id): "plan-\(id.map(String.init) ?? "new")"
        case .metaPlan(let id): "meta-\(id.map(String.init) ?? "new")"
        }
    }
}

private struct IOSExerciseLibraryPresentation: Identifiable {
    let id = UUID()
}

private struct IOSPlansDeleteRequest: Identifiable {
    enum Kind { case plan, metaPlan }
    let kind: Kind
    let itemID: Int64
    let title: String
    var id: String { "\(kind)-\(itemID)" }
}

/// Switches between plans and meta plans. A full-width capsule below the title
/// with the count per kind; the selected segment is a bright pill in Liquid Glass
/// (white on dark, near black on light glass) and the accent color in Ember.
private struct IOSPlansModeSwitcher: View {
    @Binding var mode: IOSPlansScreen.SectionMode
    let planCount: Int
    let metaPlanCount: Int

    @Environment(\.thrymAppearance) private var appearance
    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var selection

    private static let glassInk = Color(red: 11 / 255, green: 13 / 255, blue: 18 / 255)

    var body: some View {
        let palette = theme.palette(for: colorScheme)
        HStack(spacing: 4) {
            ForEach(IOSPlansScreen.SectionMode.allCases) { section in
                segment(section, count: section == .plans ? planCount : metaPlanCount, palette: palette)
            }
        }
        .padding(4)
        .background { container(palette: palette) }
        .sensoryFeedback(.selection, trigger: mode)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Planbereich")
    }

    private func segment(_ section: IOSPlansScreen.SectionMode, count: Int, palette: ThrymTheme.Palette) -> some View {
        let selected = section == mode
        let ink = selected ? selectedInk(palette: palette) : palette.textPrimary
        return Button {
            guard !selected else { return }
            if reduceMotion {
                mode = section
            } else {
                withAnimation(.snappy(duration: 0.28)) { mode = section }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: section.systemImage)
                    .font(.system(size: 14, weight: .semibold))
                Text(section.title)
                    .font(.geist(.subheadline, weight: .bold))
                    .lineLimit(1)
                Text(count, format: .number)
                    .font(.geist(.caption, weight: .bold))
                    .monospacedDigit()
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(ink.opacity(selected ? 0.14 : 0.10), in: Capsule())
            }
            .minimumScaleFactor(0.75)
            .foregroundStyle(ink)
            .frame(maxWidth: .infinity, minHeight: 44)
            .padding(.horizontal, 8)
            .background {
                if selected {
                    Capsule()
                        .fill(selectedFill(palette: palette))
                        .shadow(color: .black.opacity(appearance == .liquidGlass ? 0.28 : 0.12), radius: 6, y: 3)
                        .matchedGeometryEffect(id: "selection", in: selection)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(section.title)
        .accessibilityValue(Text(count, format: .number))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder
    private func container(palette: ThrymTheme.Palette) -> some View {
        switch appearance {
        case .liquidGlass:
            Capsule().fill(.clear).liquidGlass(.standard, in: Capsule())
        case .ember:
            Capsule()
                .fill(palette.surfaceMuted)
                .overlay { Capsule().strokeBorder(palette.separator) }
        }
    }

    private func selectedFill(palette: ThrymTheme.Palette) -> Color {
        switch appearance {
        case .liquidGlass: colorScheme == .dark ? .white : Self.glassInk
        case .ember: palette.primary
        }
    }

    private func selectedInk(palette: ThrymTheme.Palette) -> Color {
        switch appearance {
        case .liquidGlass: colorScheme == .dark ? Self.glassInk : .white
        case .ember: palette.buttonText
        }
    }
}

private struct IOSPlanListRow: View {
    let plan: ILTrainingPlan
    let exerciseCount: Int
    let onOpen: () -> Void
    let onStart: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                Image(systemName: "list.bullet.rectangle.portrait")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .frame(width: 28)

                VStack(alignment: .leading, spacing: 4) {
                    Text(plan.name)
                        .font(.geist(.body, weight: .semibold))
                        .foregroundStyle(.primary)
                    Text(ilExerciseCountText(exerciseCount))
                        .font(.geist(.caption))
                        .thrymSecondaryText()
                }

                Spacer(minLength: 8)
                Menu {
                    Button("Training starten", systemImage: "play.fill", action: onStart)
                    Button("Bearbeiten", systemImage: "pencil", action: onOpen)
                    Button("Löschen", systemImage: "trash", role: .destructive, action: onDelete)
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                        .thrymSecondaryText()
                }
                .accessibilityLabel("Aktionen für \(plan.name)")
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive, action: onDelete) {
                Label("Löschen", systemImage: "trash")
            }
        }
    }
}

private struct IOSMetaPlanListRow: View {
    let plan: ILMetaTrainingPlan
    let itemCount: Int
    let onOpen: () -> Void
    let onStart: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                Image(systemName: "rectangle.stack")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .frame(width: 28)

                VStack(alignment: .leading, spacing: 4) {
                    Text(plan.name)
                        .font(.geist(.body, weight: .semibold))
                        .foregroundStyle(.primary)
                    Text("\(itemCount) Teilpläne")
                        .font(.geist(.caption))
                        .thrymSecondaryText()
                }

                Spacer(minLength: 8)
                Menu {
                    Button("Rotation starten", systemImage: "play.fill", action: onStart)
                    Button("Bearbeiten", systemImage: "pencil", action: onOpen)
                    Button("Löschen", systemImage: "trash", role: .destructive, action: onDelete)
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                        .thrymSecondaryText()
                }
                .accessibilityLabel("Aktionen für \(plan.name)")
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive, action: onDelete) {
                Label("Löschen", systemImage: "trash")
            }
        }
    }
}

private extension Collection {
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
