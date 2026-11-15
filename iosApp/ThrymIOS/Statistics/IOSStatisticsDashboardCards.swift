import SwiftUI

/// The training trend card in statistics. The ring keeps the existing graphical circle, but the
/// value is the shared `trainingIndex`, which the core leaves `nil` below its
/// evidence minimum. A missing index therefore renders as "not enough data"
/// instead of an invented 100 %.
struct IOSStatisticsTrainingTrendCard: View {
    let trend: ILTrainingTrendAssessment?

    @Environment(IOSTrainingStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymTheme) private var theme
    /// Collapsed, the card stays scannable (ring, status, evidence). Every reason,
    /// exercise and data-quality note the core computed stays reachable behind this
    /// disclosure, so a "further reasons" hint is never a dead end.
    @State private var showsDetails = false

    var body: some View {
        ThrymCard(title: String(localized: "Trainingstrend")) {
            if let trend {
                layout {
                    ThrymMetricRing(
                        title: String(localized: "Trainingstrend"),
                        value: trend.trainingIndex.map(String.init),
                        progress: trend.trainingIndex.map { Double($0) / 100.0 },
                        unit: trend.trainingIndex == nil ? nil : "%",
                        detail: nil,
                        accent: accent(trend),
                        unknownLabel: String(localized: "Nicht genug Daten")
                    )

                    VStack(alignment: .leading, spacing: 7) {
                        statusLine(trend)
                        evidenceLine(trend)
                        detailsDisclosure(trend)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                Text("Der Trainingstrend konnte nicht gelesen werden.")
                    .font(.geist(.body))
                    .thrymSecondaryText()
            }
        }
    }

    private var layout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 18))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 18))
    }

    /// The one short line kept outside the disclosure: the conclusion the core reached,
    /// plus the actionable deload state. The long explanation stays in the disclosure.
    @ViewBuilder
    private func statusLine(_ trend: ILTrainingTrendAssessment) -> some View {
        Text(ilTrendStatusText(trend.status))
            .font(.geist(.subheadline, weight: .semibold))
            .fixedSize(horizontal: false, vertical: true)

        if trend.deloadSuggested {
            Label("Deload vorgeschlagen", systemImage: "exclamationmark.triangle.fill")
                .font(.geist(.subheadline, weight: .semibold))
                .foregroundStyle(theme.palette(for: colorScheme).warning)
        }
        if trend.deloadActive {
            Label("Deload aktiv", systemImage: "checkmark.circle.fill")
                .font(.geist(.subheadline, weight: .semibold))
                .foregroundStyle(theme.palette(for: colorScheme).success)
        }
    }

    /// The single evidence line kept outside the disclosure: how many exercises the core
    /// could actually evaluate, and how many comparable units carry that statement.
    private func evidenceLine(_ trend: ILTrainingTrendAssessment) -> some View {
        Text("\(trend.exercisesWithSufficientHistory) von \(trend.analyzedExerciseCount) Übungen auswertbar · \(trend.dataQuality.comparableUnitCount) vergleichbare Einheiten")
            .font(.geist(.subheadline))
            .thrymSecondaryText()
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Everything the core computed beyond the compact state. Nothing is dropped: the
    /// long explanation, the confidence, the strongest decline, every reason, every
    /// exercise and every data-quality note stay one tap away.
    @ViewBuilder
    private func detailsDisclosure(_ trend: ILTrainingTrendAssessment) -> some View {
        if hasDetails(trend) {
            DisclosureGroup(isExpanded: $showsDetails) {
                VStack(alignment: .leading, spacing: 10) {
                    if trend.status == .insufficientData {
                        Text("Es fehlen vergleichbare Einheiten für einen belastbaren Trend.")
                            .font(.geist(.caption))
                            .thrymSecondaryText()
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Text("Evidenz: \(ilConfidenceText(trend.confidence))")
                        .font(.geist(.caption))
                        .thrymSecondaryText()

                    if trend.repeatedDeclineExerciseCount > 0 {
                        Text("\(trend.repeatedDeclineExerciseCount) Übungen mit wiederholtem Rückgang")
                            .font(.geist(.caption))
                            .thrymSecondaryText()
                    }

                    if let strongest = trend.strongestDecline, let change = strongest.changePercent {
                        Text("Stärkster Rückgang: \(exerciseName(strongest)) (\(changeText(change)) über \(strongest.comparableUnitCount) Einheiten)")
                            .font(.geist(.caption))
                            .thrymSecondaryText()
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    allReasons(trend)
                    allExercises(trend)
                    dataQualityNotes(trend)
                }
                .padding(.top, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
            } label: {
                Text("Details")
                    .font(.geist(.subheadline, weight: .semibold))
            }
        }
    }

    /// The disclosure is only rendered when it actually has something to reveal.
    private func hasDetails(_ trend: ILTrainingTrendAssessment) -> Bool {
        trend.status == .insufficientData
            || !trend.reasons.isEmpty
            || !trend.exercises.isEmpty
            || !trend.dataQuality.notes.isEmpty
            || trend.repeatedDeclineExerciseCount > 0
            || trend.strongestDecline != nil
    }

    private func reasonLine(_ text: String) -> some View {
        Text("· \(text)")
            .font(.geist(.caption))
            .thrymSecondaryText()
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private func allReasons(_ trend: ILTrainingTrendAssessment) -> some View {
        let texts = ilReasonTexts(trend.reasons)
        if !texts.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Alle Gründe")
                    .font(.geist(.caption, weight: .semibold))
                ForEach(texts, id: \.self) { reasonLine($0) }
            }
        }
    }

    private func exerciseName(_ exercise: ILExerciseTrend) -> String {
        ilExerciseDisplayName(id: exercise.exerciseId, storedName: exercise.exerciseName, in: store.data) ?? exercise.exerciseName
    }

    @ViewBuilder
    private func allExercises(_ trend: ILTrainingTrendAssessment) -> some View {
        if !trend.exercises.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("Übungen (\(trend.exercises.count))")
                    .font(.geist(.caption, weight: .semibold))
                ForEach(trend.exercises, id: \.exerciseId) { exercise in
                    VStack(alignment: .leading, spacing: 1) {
                        Text("\(exerciseName(exercise)) · \(ilExerciseTrendStatusText(exercise.status))")
                            .font(.geist(.caption))
                        Text("\(changeText(exercise.changePercent)) über \(exercise.comparableUnitCount) Einheiten · \(ilConfidenceText(exercise.confidence))")
                            .font(.geist(.caption))
                            .thrymSecondaryText()
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func dataQualityNotes(_ trend: ILTrainingTrendAssessment) -> some View {
        if !trend.dataQuality.notes.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Datenqualität")
                    .font(.geist(.caption, weight: .semibold))
                ForEach(Array(trend.dataQuality.notes.enumerated()), id: \.offset) { _, note in
                    Text("· \(ilDataQualityNoteText(note))")
                        .font(.geist(.caption))
                        .thrymSecondaryText()
                }
            }
        }
    }

    private func accent(_ trend: ILTrainingTrendAssessment) -> ThrymTheme.Accent {
        switch trend.status {
        case .multipleExerciseDecline: return .warning
        case .singleExerciseDecline: return .secondary
        case .noNotableStrain: return .success
        case .insufficientData, .unknown: return .secondary
        }
    }

    private func changeText(_ value: Double?) -> String {
        guard let value, value.isFinite else { return String(localized: "keine Vergleichszahl") }
        return ilPercentChangeText(value)
    }
}

struct IOSStatisticsVolumeExplanation: View {
    var body: some View {
        Text("MEV ist das minimale wirksame Volumen, MAV der typische Zielbereich und MRV die obere Regenerationsgrenze. Der Trend stammt aus den gültigen Arbeitssätzen dieser Woche.")
            .font(.geist(.caption))
            .thrymSecondaryText()
            .padding(.horizontal, 4)
    }
}

struct IOSStatisticsRecentRecords: View {
    let records: [ILTrainingAnalyticsRecord]
    let unitSystem: String

    @Environment(IOSTrainingStore.self) private var store
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymTheme) private var theme

    var body: some View {
        ThrymCard(title: String(localized: "Neue Rekorde"), subtitle: records.isEmpty ? nil : String(localized: "Zuletzt erfasst")) {
            if records.isEmpty {
                Text("Noch keine persönlichen Rekorde gespeichert.")
                    .font(.geist(.body))
                    .thrymSecondaryText()
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(records) { record in
                            VStack(alignment: .leading, spacing: 7) {
                                Text(ilExerciseDisplayName(id: record.exerciseId, storedName: record.exerciseName, in: store.data) ?? String(localized: "Übung \(record.exerciseId)"))
                                    .font(.geist(.headline))
                                Text(ilRecordTypeText(record.type))
                                    .font(.geist(.caption))
                                    .thrymSecondaryText()
                                Text(ilRecordValueText(type: record.type, value: record.value, unitSystem: unitSystem))
                                    .font(.geist(.title3, weight: .bold))
                                    .foregroundStyle(theme.palette(for: colorScheme).primary)
                                Text(ilAnalyticsDateText(record.achievedAt) ?? record.achievedAt)
                                    .font(.geist(.caption))
                                    .thrymSecondaryText()
                            }
                            .frame(width: 150, alignment: .leading)
                            .padding(12)
                            .background(theme.palette(for: colorScheme).primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }
}
