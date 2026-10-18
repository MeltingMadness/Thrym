import Charts
import SwiftUI

enum IOSExerciseMetric: String, CaseIterable, Identifiable {
    case e1rm
    case weight
    case reps
    case volume

    var id: String { rawValue }

    var title: String {
        switch self {
        case .e1rm: return String(localized: "e1RM")
        case .weight: return String(localized: "Gewicht")
        case .reps: return String(localized: "Wiederholungen")
        case .volume: return String(localized: "Volumen")
        }
    }

    var shortTitle: String {
        switch self {
        case .e1rm: return String(localized: "e1RM")
        case .weight: return String(localized: "Gewicht")
        case .reps: return String(localized: "Wdh.")
        case .volume: return String(localized: "Volumen")
        }
    }
}

private enum IOSRecordFilter: String, CaseIterable, Identifiable {
    case all
    case maxWeight = "MAX_WEIGHT"
    case maxReps = "MAX_REPS"
    case maxE1rm = "MAX_E1RM"
    case maxVolume = "MAX_VOLUME"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return String(localized: "Alle Rekorde")
        default: return ilRecordTypeText(rawValue)
        }
    }
}

/// Exercise detail backed by the shared per-session analytics series.
struct IOSExerciseStatisticsScreen: View {
    let exerciseId: Int64

    @Environment(IOSTrainingStore.self) private var store
    @EnvironmentObject private var settings: IOSSettingsViewModel
    @Environment(\.thrymAppearance) private var appearance
    @Environment(\.dismiss) private var dismiss
    @State private var selectedMetric: IOSExerciseMetric = .e1rm
    @State private var recordFilter: IOSRecordFilter = .all

    private var weekStartsSunday: Bool {
        settings.state.weekStart.uppercased() == "SUNDAY"
    }

    private var analyticsExercise: ILExerciseAnalytics? {
        store.analytics?.exerciseStatistics.first(where: { $0.exerciseId == exerciseId })
    }

    private var exercise: ILExercise? {
        store.data?.exercises.first(where: { $0.id == exerciseId })
    }

    private var exerciseName: String {
        exercise?.displayName ?? analyticsExercise?.exerciseName ?? String(localized: "Übung \(exerciseId)")
    }

    var body: some View {
        ScrollView {
            if let analyticsExercise, appearance == .liquidGlass {
                glassContent(analyticsExercise)
            } else if let analyticsExercise {
                detailContent(analyticsExercise)
            } else {
                ContentUnavailableView {
                    Label("Keine Übungsstatistik", systemImage: "chart.xyaxis.line")
                } description: {
                    Text("Für diese Übung gibt es noch keine abgeschlossenen Arbeitssätze.")
                }
                .frame(maxWidth: .infinity, minHeight: 420)
                .padding(20)
            }
        }
        .thrymScreenBackground(ember: Color(uiColor: .systemGroupedBackground))
        .navigationTitle(exerciseName)
        .navigationBarTitleDisplayMode(.inline)
        // Liquid Glass shows a round back button and the name in the content.
        .toolbar(appearance == .liquidGlass && analyticsExercise != nil ? .hidden : .automatic, for: .navigationBar)
        .task(id: "\(settings.state.weekStart)-\(settings.state.unitSystem)") {
            store.refreshAnalytics(
                timeZoneId: TimeZone.current.identifier,
                weekStartsSunday: weekStartsSunday
            )
        }
    }

    @ViewBuilder
    private func detailContent(_ analyticsExercise: ILExerciseAnalytics) -> some View {
        let sessions = analyticsExercise.sessions
        let unitSystem = settings.state.unitSystem

        LazyVStack(alignment: .leading, spacing: 16) {
            exerciseSummary

            if sessions.count >= 2, analyticsExercise.lastWorkoutComparison != nil {
                IOSExerciseE1rmProgressionCard(
                    firstE1rmKg: analyticsExercise.firstE1rmKg,
                    latestE1rmKg: analyticsExercise.latestE1rmKg,
                    absoluteChangeKg: analyticsExercise.e1rmDeltaKg,
                    relativeChangePercent: analyticsExercise.e1rmDeltaPercent,
                    unitSystem: unitSystem
                )
            }

            ThrymCard(title: String(localized: "Entwicklung"), subtitle: String(localized: "Einheit pro Einheit · \(selectedMetric.title)")) {
                Picker("Metrik", selection: $selectedMetric) {
                    ForEach(IOSExerciseMetric.allCases) { metric in
                        Text(metric.shortTitle).tag(metric)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityLabel("Metrik für den Verlauf")

                if sessions.count < 2 {
                    Text("Mindestens zwei abgeschlossene Einheiten sind für einen Verlauf nötig.")
                        .font(.geist(.body))
                        .thrymSecondaryText()
                        .padding(.vertical, 12)
                } else {
                    Chart {
                        ForEach(sessions) { session in
                            if let date = ilAnalyticsDate(session.date) {
                                LineMark(
                                    x: .value("Datum", date),
                                    y: .value(selectedMetric.title, metricValue(session))
                                )
                                .foregroundStyle(.orange)
                                .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))

                                PointMark(
                                    x: .value("Datum", date),
                                    y: .value(selectedMetric.title, metricValue(session))
                                )
                                .foregroundStyle(.orange)
                                .symbolSize(28)
                            }
                        }
                    }
                    .chartYScale(domain: .automatic(includesZero: selectedMetric == .reps || selectedMetric == .volume))
                    .chartXAxis {
                        AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                            AxisGridLine()
                            AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                        }
                    }
                    .chartYAxis {
                        AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) { value in
                            AxisGridLine()
                            AxisValueLabel {
                                if let number = value.as(Double.self) {
                                    Text(metricAxisValue(number, unitSystem: unitSystem))
                                }
                            }
                        }
                    }
                    .frame(minHeight: 230)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Verlauf \(selectedMetric.title)")
                    .accessibilityValue(
                        Text(sessions.map { "\(ilAnalyticsDateText($0.date, dateStyle: .short) ?? $0.date): \(metricText($0, unitSystem: unitSystem))" }.joined(separator: ", "))
                    )
                }
            }

            if let comparison = analyticsExercise.lastWorkoutComparison {
                IOSExerciseLastWorkoutComparisonCard(
                    comparison: comparison,
                    metric: selectedMetric,
                    unitSystem: unitSystem
                )
            }

            IOSExerciseRecordsCard(
                records: filteredRecords,
                unitSystem: unitSystem,
                filter: $recordFilter
            )

            IOSExerciseSessionHistoryCard(
                sessions: sessions,
                selectedMetric: selectedMetric,
                unitSystem: unitSystem
            )

            if !analyticsExercise.recentSets.isEmpty {
                IOSExerciseRecentSetsCard(
                    sets: analyticsExercise.recentSets,
                    unitSystem: unitSystem,
                    addedLoad: exercise?.tracksAddedLoad == true
                )
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    /// Liquid Glass: back button, large name, hero card with curve and metric
    /// switch, four record tiles; comparison, history and recent sets follow as glass cards.
    private func glassContent(_ analyticsExercise: ILExerciseAnalytics) -> some View {
        let unitSystem = settings.state.unitSystem
        return LazyVStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .bold))
                        .frame(width: 44, height: 44)
                        .liquidGlass(in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Zurück")
                if let exercise {
                    Text([exercise.primaryMuscleGroupDisplayName, exercise.categoryDisplayName, ilSessionCountText(analyticsExercise.sessions.count)]
                        .joined(separator: " · ").uppercased())
                        .font(.geist(.caption, weight: .bold))
                        .tracking(0.8)
                        .thrymSecondaryText()
                        .lineLimit(1)
                }
            }
            Text(exerciseName)
                .font(.geist(size: 40, weight: .bold))
                .tracking(-1.4)
                .accessibilityAddTraits(.isHeader)
            IOSExerciseGlassHero(sessions: analyticsExercise.sessions, metric: $selectedMetric, unitSystem: unitSystem)
            IOSExerciseGlassRecordTiles(
                records: store.data?.personalRecords.filter { $0.exerciseId == exerciseId } ?? [],
                unitSystem: unitSystem
            )
            if let comparison = analyticsExercise.lastWorkoutComparison {
                IOSExerciseLastWorkoutComparisonCard(comparison: comparison, metric: selectedMetric, unitSystem: unitSystem)
            }
            IOSExerciseSessionHistoryCard(sessions: analyticsExercise.sessions, selectedMetric: selectedMetric, unitSystem: unitSystem)
            if !analyticsExercise.recentSets.isEmpty {
                IOSExerciseRecentSetsCard(sets: analyticsExercise.recentSets, unitSystem: unitSystem, addedLoad: exercise?.tracksAddedLoad == true)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    private var exerciseSummary: some View {
        ThrymCard(tone: .elevated) {
            VStack(alignment: .leading, spacing: 6) {
                Text(exerciseName)
                    .font(.geist(.title2, weight: .bold))
                if let exercise {
                    Text([exercise.primaryMuscleGroupDisplayName, exercise.categoryDisplayName].joined(separator: " · "))
                        .font(.geist(.subheadline))
                        .thrymSecondaryText()
                }
                Text("\(analyticsExercise?.sessions.count ?? 0) abgeschlossene Einheiten")
                    .font(.geist(.caption))
                    .thrymSecondaryText()
            }
        }
    }

    private var filteredRecords: [ILPersonalRecord] {
        let records = store.data?.personalRecords
            .filter { $0.exerciseId == exerciseId }
            .filter { recordFilter == .all || $0.type.uppercased() == recordFilter.rawValue }
            .sorted { $0.achievedAt > $1.achievedAt } ?? []
        return records
    }

    private func metricValue(_ session: ILExerciseAnalyticsSession) -> Double {
        switch selectedMetric {
        case .e1rm: return session.maxE1rmKg
        case .weight: return session.maxWeightKg
        case .reps: return Double(session.maxReps)
        case .volume: return session.volumeKg
        }
    }

    private func metricText(_ session: ILExerciseAnalyticsSession, unitSystem: String) -> String {
        switch selectedMetric {
        case .e1rm: return ilWeightText(session.maxE1rmKg, unitSystem: unitSystem)
        case .weight: return ilWeightText(session.maxWeightKg, unitSystem: unitSystem)
        case .reps: return String(localized: "\(session.maxReps) Wdh.")
        case .volume: return ilVolumeText(session.volumeKg, unitSystem: unitSystem)
        }
    }

    private func metricAxisValue(_ value: Double, unitSystem: String) -> String {
        switch selectedMetric {
        case .reps: return value.formatted(.number.precision(.fractionLength(0...0)))
        case .e1rm, .weight, .volume: return ilWeightText(value, unitSystem: unitSystem)
        }
    }
}

private struct IOSExerciseRecordsCard: View {
    let records: [ILPersonalRecord]
    let unitSystem: String
    @Binding var filter: IOSRecordFilter

    var body: some View {
        ThrymCard(title: String(localized: "Persönliche Rekorde"), subtitle: records.isEmpty ? nil : String(localized: "Auswahl nach Rekordtyp")) {
            Picker("Rekordfilter", selection: $filter) {
                ForEach(IOSRecordFilter.allCases) { item in
                    Text(item.title).tag(item)
                }
            }
            .pickerStyle(.menu)

            if records.isEmpty {
                Text("Für diesen Filter gibt es noch keinen Rekord.")
                    .font(.geist(.body))
                    .thrymSecondaryText()
            } else {
                ForEach(records) { record in
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ilRecordTypeText(record.type))
                                .font(.geist(.body, weight: .semibold))
                            Text(record.achievedDate.formatted(date: .abbreviated, time: .omitted))
                                .font(.geist(.caption))
                                .thrymSecondaryText()
                        }
                        Spacer(minLength: 12)
                        Text(ilRecordValueText(type: record.type, value: record.value, unitSystem: unitSystem))
                            .font(.geist(.body, weight: .semibold))
                    }
                    .padding(.vertical, 3)
                }
            }
        }
    }
}

private struct IOSExerciseE1rmProgressionCard: View {
    let firstE1rmKg: Double
    let latestE1rmKg: Double
    let absoluteChangeKg: Double
    let relativeChangePercent: Double
    let unitSystem: String

    var body: some View {
        ThrymCard(title: String(localized: "1RM-Entwicklung"), subtitle: String(localized: "Geschätzt nach der Epley-Formel")) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    metric(title: String(localized: "Erstes 1RM"), value: ilWeightText(firstE1rmKg, unitSystem: unitSystem))
                    metric(title: String(localized: "Aktuelles 1RM"), value: ilWeightText(latestE1rmKg, unitSystem: unitSystem))
                }

                HStack(spacing: 12) {
                    metric(
                        title: String(localized: "Steigerung"),
                        value: ilWeightText(absoluteChangeKg, unitSystem: unitSystem, signed: true)
                    )
                    metric(title: String(localized: "Steigerung (%)"), value: percentText(relativeChangePercent))
                }
            }
        }
    }

    private func metric(title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.geist(.caption))
                .thrymSecondaryText()
            Text(value)
                .font(.geist(.body, weight: .semibold))
                .monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func percentText(_ value: Double) -> String {
        ilPercentChangeText(value, fractionDigits: 0...1)
    }
}

private struct IOSExerciseLastWorkoutComparisonCard: View {
    let comparison: ILExerciseWorkoutComparison
    let metric: IOSExerciseMetric
    let unitSystem: String

    private var deltaIsPositive: Bool {
        switch metric {
        case .e1rm: return comparison.e1rmDeltaKg >= 0
        case .weight: return comparison.weightDeltaKg >= 0
        case .reps: return comparison.repsDelta >= 0
        case .volume: return comparison.volumeDeltaKg >= 0
        }
    }

    var body: some View {
        ThrymCard(title: String(localized: "Vergleich zum vorherigen passenden Training"), tone: .muted) {
            VStack(alignment: .leading, spacing: 8) {
                comparisonRow(
                    label: String(localized: "Vorheriges"),
                    date: comparison.previous.completedAtEpochMillis,
                    value: metricText(comparison.previous)
                )
                comparisonRow(
                    label: String(localized: "Letztes"),
                    date: comparison.latest.completedAtEpochMillis,
                    value: metricText(comparison.latest),
                    emphasized: true
                )
                Text("Veränderung: \(metricDeltaText)")
                    .font(.geist(.subheadline, weight: .semibold))
                    .foregroundStyle(deltaIsPositive ? .green : .red)
            }
        }
    }

    private func comparisonRow(
        label: String,
        date: Int64,
        value: String,
        emphasized: Bool = false
    ) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(label) · \(ilAnalyticsDateTimeText(epochMillis: date))")
                    .font(.geist(.caption))
                    .thrymSecondaryText()
                Text(value)
                    .font(.geist(.body, weight: emphasized ? .semibold : .regular))
            }
            Spacer(minLength: 8)
        }
    }

    private func metricText(_ session: ILExerciseAnalyticsSession) -> String {
        switch metric {
        case .e1rm: return ilWeightText(session.maxE1rmKg, unitSystem: unitSystem)
        case .weight: return ilWeightText(session.maxWeightKg, unitSystem: unitSystem)
        case .reps: return String(localized: "\(session.maxReps) Wdh.")
        case .volume: return ilVolumeText(session.volumeKg, unitSystem: unitSystem)
        }
    }

    private var metricDeltaText: String {
        switch metric {
        case .e1rm:
            return ilWeightText(comparison.e1rmDeltaKg, unitSystem: unitSystem, signed: true)
        case .weight:
            return ilWeightText(comparison.weightDeltaKg, unitSystem: unitSystem, signed: true)
        case .reps:
            return (comparison.repsDelta > 0 ? "+" : "") + String(localized: "\(comparison.repsDelta) Wdh.")
        case .volume:
            return ilVolumeText(comparison.volumeDeltaKg, unitSystem: unitSystem, signed: true)
        }
    }
}

private struct IOSExerciseSessionHistoryCard: View {
    let sessions: [ILExerciseAnalyticsSession]
    let selectedMetric: IOSExerciseMetric
    let unitSystem: String

    var body: some View {
        ThrymCard(title: String(localized: "Einheiten"), subtitle: String(localized: "Quellwerte pro abgeschlossener Einheit")) {
            if sessions.isEmpty {
                Text("Noch keine Einheiten verfügbar.")
                    .font(.geist(.body))
                    .thrymSecondaryText()
            } else {
                ForEach(sessions.reversed()) { session in
                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text(ilAnalyticsDateText(session.date) ?? session.date)
                                .font(.geist(.body, weight: .semibold))
                            Spacer()
                            Text(metricText(session))
                                .font(.geist(.body, weight: .semibold))
                        }
                        Text("\(ilWeightText(session.maxWeightKg, unitSystem: unitSystem)) · \(session.maxReps) Wdh. · \(ilWeightText(session.maxE1rmKg, unitSystem: unitSystem)) e1RM · \(ilVolumeText(session.volumeKg, unitSystem: unitSystem))")
                            .font(.geist(.caption))
                            .thrymSecondaryText()
                    }
                    .padding(.vertical, 5)
                    if session.id != sessions.first?.id {
                        Divider()
                    }
                }
            }
        }
    }

    private func metricText(_ session: ILExerciseAnalyticsSession) -> String {
        switch selectedMetric {
        case .e1rm: return ilWeightText(session.maxE1rmKg, unitSystem: unitSystem)
        case .weight: return ilWeightText(session.maxWeightKg, unitSystem: unitSystem)
        case .reps: return String(localized: "\(session.maxReps) Wdh.")
        case .volume: return ilVolumeText(session.volumeKg, unitSystem: unitSystem)
        }
    }
}

private struct IOSExerciseRecentSetsCard: View {
    let sets: [ILExerciseSetStatistics]
    let unitSystem: String
    var addedLoad = false

    var body: some View {
        ThrymCard(title: String(localized: "Letzte Sätze"), tone: .muted) {
            ForEach(sets.prefix(12)) { set in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(ilAnalyticsDateTimeText(epochMillis: set.completedAtEpochMillis))
                        .font(.geist(.caption))
                        .thrymSecondaryText()
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Text("\(setTypeText(set.setType)) · \(iosSetValueText(weightKg: set.weightKg, reps: set.reps, unitSystem: unitSystem, addedLoad: addedLoad))")
                        .font(.geist(.body, weight: .medium))
                        .multilineTextAlignment(.trailing)
                }
                .padding(.vertical, 3)
            }
        }
    }

    private func setTypeText(_ raw: String) -> String {
        switch raw.uppercased() {
        case "NORMAL": return String(localized: "Arbeitssatz")
        case "WARMUP": return String(localized: "Aufwärmen")
        case "DROP_SET": return String(localized: "Dropsatz")
        case "BACKOFF": return String(localized: "Backoff-Satz")
        case "FAILURE": return String(localized: "Failure-Satz")
        default: return raw
        }
    }
}
