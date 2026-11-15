import SwiftUI

enum IOSDashboardOpenItem: Equatable {
    case progression, deload, comeback
}

enum IOSDashboardLayout {
    static func openItems(pendingProgressions: Int, deloadSuggested: Bool, deloadActive: Bool,
                          activeDeloadMode: String, hasTrend: Bool, hasComeback: Bool) -> [IOSDashboardOpenItem] {
        var items: [IOSDashboardOpenItem] = []
        if pendingProgressions > 0 { items.append(.progression) }
        if hasTrend && (deloadSuggested || deloadActive || activeDeloadMode != "NONE") { items.append(.deload) }
        if hasComeback { items.append(.comeback) }
        return items
    }

    static func orderedMuscles(_ muscles: [ILMuscleGroupContext], expanded: Bool) -> [ILMuscleGroupContext] {
        // Only muscles with a reason to be on today's card: planned, sore, or trained this week.
        let relevant = muscles.filter { $0.plannedToday == true || $0.soreness != nil || $0.setsInWindow > 0 || $0.setsToday > 0 }
        let ordered = relevant.filter { $0.plannedToday == true } + relevant.filter { $0.plannedToday != true }
        return expanded ? ordered : Array(ordered.prefix(4))
    }
}

struct IOSDashboardSkipMenu: View {
    let isBusy: Bool
    let action: () -> Void
    var body: some View {
        Menu {
            Button(action: action) { Label("Teilplan überspringen", systemImage: "forward.end") }
        } label: {
            Image(systemName: "ellipsis").frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .accessibilityLabel("Mehr")
        .disabled(isBusy)
    }
}

struct IOSDashboardStartLinks: View {
    let showFree: Bool
    let isBusy: Bool
    let onChooseTraining: () -> Void
    let onStartFree: () -> Void
    var body: some View {
        // Without any plan the big button already starts the free workout; nothing to switch to.
        Group {
            if showFree {
                ViewThatFits(in: .horizontal) {
                    links(vertical: false)
                    links(vertical: true)
                }
            }
        }
        .font(.geist(.subheadline, weight: .semibold))
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
        .disabled(isBusy)
    }
    @ViewBuilder private func links(vertical: Bool) -> some View {
        let layout = vertical ? AnyLayout(VStackLayout(alignment: .leading, spacing: 0)) : AnyLayout(HStackLayout(spacing: 18))
        layout {
            Button("Anderer Plan", action: onChooseTraining).frame(minHeight: 44)
            if showFree { Button("Freies Training", action: onStartFree).frame(minHeight: 44) }
        }
    }
}

struct IOSDashboardOpenCard: View {
    let rows: [IOSDashboardOpenItem]
    let pendingProgressions: Int
    let trend: ILTrainingTrendAssessment?
    let activeMode: String
    let pauseDays: Int?
    let onProgression: () -> Void
    let onDeload: () -> Void
    let onComeback: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymTheme) private var theme

    var body: some View {
        ThrymCard(title: String(localized: "Offen")) {
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { index, item in
                    if index > 0 { Divider() }
                    switch item {
                    case .progression:
                        row(symbol: "arrow.up.right.circle.fill", title: String(localized: "Progression prüfen"),
                            detail: String(localized: "\(pendingProgressions) Vorschläge warten"), action: onProgression)
                    case .deload:
                        let active = activeMode != "NONE" || (trend?.deloadActive ?? false)
                        row(symbol: "exclamationmark.triangle.fill",
                            title: active ? String(localized: "Deload aktiv") : String(localized: "Deload vorgeschlagen"),
                            detail: active ? String(localized: "Anzeige angepasst") : String(localized: "Wiederholte Verschlechterung erkannt"),
                            warning: true, action: onDeload)
                    case .comeback:
                        if let pauseDays {
                            comebackRow(pauseDays)
                        }
                    }
                }
            }
        }
    }

    private func comebackRow(_ pauseDays: Int) -> some View {
        let title = String(localized: "Nach \(pauseDays) Tagen Pause")
        let detail = String(localized: "Nach einer Pause macht der Deload-Modus deine Trainings eine Weile leichter. Du kannst ihn jederzeit beenden.")
        return HStack(spacing: 12) {
            Image(systemName: "arrow.uturn.forward.circle.fill")
                .foregroundStyle(theme.palette(for: colorScheme).primary).frame(width: 24)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.geist(.body, weight: .semibold))
                Text(detail).font(.geist(.subheadline)).thrymSecondaryText().lineLimit(2)
            }
            Spacer(minLength: 4)
            Button("Leichter einsteigen", action: onComeback).buttonStyle(.bordered).controlSize(.small)
        }
        .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture(perform: onComeback)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(title), \(detail)"))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { onComeback() }
    }

    private func row(symbol: String, title: String, detail: String, warning: Bool = false,
                     action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol).foregroundStyle(warning ? theme.palette(for: colorScheme).warning : theme.palette(for: colorScheme).primary)
                    .frame(width: 24).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.geist(.body, weight: .semibold))
                    Text(detail).font(.geist(.subheadline)).thrymSecondaryText().lineLimit(2)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).thrymSecondaryText().accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
        .accessibilityElement(children: .combine)
    }
}

struct IOSDashboardTodayCard: View {
    let checkIn: ILReadinessCheckIn?
    let muscleGroups: [ILMuscleGroupContext]
    let onEdit: () -> Void
    @State private var expanded = false

    var body: some View {
        let muscles = IOSDashboardLayout.orderedMuscles(muscleGroups, expanded: expanded)
        ThrymCard(title: String(localized: "Heute")) {
            VStack(alignment: .leading, spacing: 12) {
                Button(action: onEdit) {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(checkIn?.hasAnyAnswer == true ? String(localized: "Tagesform") : String(localized: "Check-in für heute"))
                                .font(.geist(.body, weight: .semibold))
                            Text(checkIn.map(answerText) ?? String(localized: "Freiwillig · 20 Sekunden"))
                                .font(.geist(.subheadline)).thrymSecondaryText().lineLimit(2)
                        }
                        Spacer(minLength: 4)
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold)).thrymSecondaryText().accessibilityHidden(true)
                    }
                    .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading).contentShape(Rectangle())
                }
                .buttonStyle(.plain).foregroundStyle(.primary)
                .accessibilityElement(children: .combine)
                if !muscles.isEmpty {
                    Divider()
                    Text(muscleGroups.contains { $0.plannedToday == true } ? String(localized: "Heute geplant") : String(localized: "Erfasste Muskeln"))
                        .font(.geist(.caption, weight: .semibold)).thrymSecondaryText()
                    ForEach(Array(muscles.enumerated()), id: \.element.muscleGroup) { index, muscle in
                        if index > 0, muscle.plannedToday != true, muscles[index - 1].plannedToday == true {
                            Text("Erfasste Muskeln").font(.geist(.caption, weight: .semibold)).thrymSecondaryText()
                        }
                        muscleRow(muscle)
                    }
                    let total = IOSDashboardLayout.orderedMuscles(muscleGroups, expanded: true).count
                    if total > 4 && !expanded {
                        Button("Alle \(total) anzeigen") { expanded = true }
                            .font(.geist(.subheadline, weight: .semibold)).frame(minHeight: 44)
                    }
                }
            }
        }
    }

    private func answerText(_ checkIn: ILReadinessCheckIn) -> String {
        guard checkIn.hasAnyAnswer else { return String(localized: "Freiwillig · 20 Sekunden") }
        var values: [String] = []
        if let v = checkIn.sleepQuality { values.append(String(localized: "Schlafqualität") + " " + ilCheckInScaleText(v, dimension: .sleepQuality)) }
        if let v = checkIn.energy { values.append(String(localized: "Energie") + " " + ilCheckInScaleText(v, dimension: .energy)) }
        if let v = checkIn.stress { values.append(String(localized: "Stress") + " " + ilCheckInScaleText(v, dimension: .stress)) }
        let soreness = checkIn.muscleSoreness.filter { (1...5).contains($0.value) }.sorted { $0.key < $1.key }
        if !soreness.isEmpty {
            values.append(String(localized: "Muskelkater") + ": " + soreness.map { "\(ilMuscleDisplayName($0.key)) \($0.value)/5" }.joined(separator: ", "))
        }
        return values.joined(separator: " · ")
    }

    private func muscleRow(_ muscle: ILMuscleGroupContext) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(ilMuscleDisplayName(muscle.muscleGroup)).font(.geist(.subheadline, weight: .semibold))
                if muscle.plannedToday == true { Text("geplant").font(.geist(.caption, weight: .semibold)).thrymAccentText() }
                Spacer(minLength: 4)
                if let soreness = muscle.soreness { Text("Muskelkater \(soreness)/5").font(.geist(.caption)).thrymSecondaryText() }
            }
            Text("\(ilPastDayText(muscle.lastTrainedEpochMillis)) · \(ilSetCountText(muscle.setsInWindow)) in den letzten 7 Tagen")
                .font(.geist(.caption)).thrymSecondaryText()
        }
        .accessibilityElement(children: .combine)
    }
}

struct IOSDashboardStatisticsLink: View {
    let action: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymTheme) private var theme
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: "chart.xyaxis.line").foregroundStyle(theme.palette(for: colorScheme).primary).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Statistiken ansehen").font(.geist(.body, weight: .semibold))
                    Text("Trend, Volumen und Rekorde").font(.geist(.subheadline)).thrymSecondaryText()
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).thrymSecondaryText().accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
        }
        .buttonStyle(.plain).foregroundStyle(.primary)
        .accessibilityElement(children: .combine)
    }
}
