import SwiftUI

/// Record board of the completion screen, the same in Ember and Liquid Glass and on Android
/// (`CompletionRecordsCard`): the number of records, one section per exercise in workout order
/// with label, value plus set context and the gain, and a footer for the exercises trained for
/// the first time. A workout without beaten records lists those first benchmarks instead.
struct IOSWorkoutRecordsCard: View {
    let summary: IOSWorkoutRecordSummary
    let unitSystem: String

    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymAppearance) private var appearance
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let ink = ThrymSemanticInk.forDark(colorScheme == .dark)
        let records = summary.exercisesWithRecords
        let firstTime = summary.firstTimeExercises
        VStack(alignment: .leading, spacing: 12) {
            if records.isEmpty {
                header(
                    symbol: "star.fill",
                    title: String(localized: "Erste Bestwerte"),
                    subtitle: String(localized: "Daran misst sich dein nächstes Training."),
                    ink: ink
                )
                ForEach(firstTime) { exercise in
                    Divider()
                    firstTimeRow(exercise)
                }
            } else {
                header(
                    symbol: "trophy.fill",
                    title: String(localized: "\(summary.recordCount) neue Rekorde"),
                    subtitle: String(localized: "in \(records.count) Übungen"),
                    ink: ink
                )
                if dynamicTypeSize.isAccessibilitySize {
                    // Large text: each row stacks label, value and gain, so the value can wrap
                    // and the gain keeps its full width.
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(records) { exercise in
                            Divider()
                            exerciseName(exercise)
                            ForEach(exercise.lines) { line in
                                stackedRecordRow(line, ink: ink)
                            }
                        }
                    }
                } else {
                    // One grid for the whole card keeps label, value and gain aligned across exercises.
                    Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 8) {
                        ForEach(records) { exercise in
                            Divider()
                                .gridCellUnsizedAxes(.horizontal)
                            exerciseName(exercise)
                            ForEach(exercise.lines) { line in
                                recordRow(line, ink: ink)
                            }
                        }
                    }
                }
                if !firstTime.isEmpty {
                    Divider()
                    Label {
                        Text("Erste Bestwerte: \(firstTime.map(\.name).joined(separator: ", "))")
                    } icon: {
                        Image(systemName: "star.fill")
                    }
                    .font(.geist(.footnote))
                    .thrymSecondaryText()
                }
            }
        }
        .padding(appearance == .liquidGlass ? 18 : 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .modifier(IOSWorkoutTintedCardSurface(tint: ink.warningFill, glassCornerRadius: 30))
        .accessibilityElement(children: .contain)
    }

    private func header(symbol: String, title: String, subtitle: String, ink: ThrymSemanticInk) -> some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(ink.warning)
                .frame(width: 44, height: 44)
                .background(ink.warningFill.opacity(0.22), in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.geist(size: 22, weight: .heavy, relativeTo: .title2))
                    .monospacedDigit()
                Text(subtitle)
                    .font(.geist(.subheadline))
                    .thrymSecondaryText()
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    private func exerciseName(_ exercise: IOSWorkoutExerciseRecords) -> some View {
        Text(exercise.name)
            .font(.geist(.headline, weight: .bold))
            .lineLimit(2)
    }

    /// Label, value with its set and the gain. VoiceOver reads the row as one sentence from
    /// the value cell; label and chip are hidden, so a grid cell never stands alone.
    private func recordRow(_ line: IOSWorkoutRecordLine, ink: ThrymSemanticInk) -> some View {
        GridRow {
            recordLabel(line)
                .lineLimit(2)
                .accessibilityHidden(true)
            recordValue(line)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(iosRecordAccessibilityText(line, unitSystem: unitSystem))
            chip(line, ink: ink)
                .lineLimit(1)
                .gridColumnAlignment(.trailing)
                .accessibilityHidden(true)
        }
    }

    /// The same row for accessibility text sizes: the label on its own line, then the value,
    /// which may wrap, and the gain below it. Nothing is truncated.
    private func stackedRecordRow(_ line: IOSWorkoutRecordLine, ink: ThrymSemanticInk) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            recordLabel(line)
            recordValue(line)
            chip(line, ink: ink)
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(iosRecordAccessibilityText(line, unitSystem: unitSystem))
    }

    private func recordLabel(_ line: IOSWorkoutRecordLine) -> some View {
        Text(iosRecordLabel(line.type).uppercased())
            .font(.geist(.caption, weight: .bold))
            .tracking(0.8)
            .foregroundStyle(theme.palette(for: colorScheme).textSecondary)
    }

    /// "100 kg × 5", "12 bei 60 kg": the value in bold, its set in secondary text.
    @ViewBuilder
    private func recordValue(_ line: IOSWorkoutRecordLine) -> some View {
        let value = Text(iosRecordValueText(type: line.type, value: line.value, unitSystem: unitSystem))
            .font(.geist(.headline, weight: .heavy))
            .monospacedDigit()
        if let context = iosRecordContext(line, unitSystem: unitSystem) {
            let secondary = theme.palette(for: colorScheme).textSecondary
            Text("\(value) \(Text(context).font(.geist(.subheadline)).foregroundStyle(secondary))")
        } else {
            value
        }
    }

    /// Gain in green ("+2,5 kg", "+2" for reps), or "Erstmals" in gold for a type reached for
    /// the first time.
    private func chip(_ line: IOSWorkoutRecordLine, ink: ThrymSemanticInk) -> some View {
        let gain = line.previous.map { iosRecordDeltaText(type: line.type, delta: line.value - $0, unitSystem: unitSystem) }
        return Text(gain ?? String(localized: "Erstmals"))
            .font(.geist(.callout, weight: .bold))
            .monospacedDigit()
            .foregroundStyle(gain == nil ? ink.warning : ink.success)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(gain == nil ? ink.warningFill.opacity(0.16) : ink.successFill.opacity(0.14), in: Capsule())
    }

    /// First benchmark of an exercise trained for the first time: the name above its best set,
    /// "Bankdrücken" over "80 kg × 8 Wdh", so a long value never squeezes the name.
    private func firstTimeRow(_ exercise: IOSWorkoutExerciseRecords) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(exercise.name)
                .font(.geist(.body, weight: .semibold))
                .lineLimit(2)
            if let set = exercise.bestSet {
                Text(iosSetValueText(weightKg: set.weightKg, reps: set.reps, unitSystem: unitSystem))
                    .font(.geist(.headline, weight: .heavy))
                    .monospacedDigit()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// Live record notice during the workout, above the list and in the Liquid Glass pause:
/// "1 neuer Rekord · Bankdrücken" and "Gewicht 102,5 kg (+2,5 kg)". A tap hides it.
struct IOSWorkoutRecordBanner: View {
    let notice: IOSWorkoutRecordNotice
    /// 0 for the bar above the list; the pause shows the notice as a rounded card.
    var cornerRadius: CGFloat = 0
    /// Called on a tap and once the notice's time is up.
    let onDismiss: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        HStack(spacing: 12) {
            Image(systemName: "trophy.fill")
                .font(.geist(.title3, weight: .semibold))
                .foregroundStyle(ThrymSemanticInk.forDark(colorScheme == .dark).warning)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(notice.title)
                    .font(.geist(.subheadline, weight: .bold))
                Text(verbatim: notice.detail)
                    .font(.geist(.callout))
                    .monospacedDigit()
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: shape)
        .contentShape(shape)
        .onTapGesture(perform: onDismiss)
        .accessibilityElement(children: .combine)
        // The banner hides itself wherever it is shown: the pause cover suspends the workout
        // screen behind it, so a timer there would stop. Waiting only for the rest of the
        // notice's time keeps a banner that moves into or out of the cover on schedule.
        .task(id: notice.id) {
            let remaining = notice.remainingDuration(at: Date())
            if remaining > 0 {
                do { try await Task.sleep(for: .seconds(remaining)) } catch { return }
            }
            onDismiss()
        }
    }
}
