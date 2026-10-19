import SwiftUI

/// Pages of the first-start introduction, in order.
///
/// A later step (for example a profile for the weekly goal and the trophies) goes in here;
/// the view pages through whatever this list contains.
enum IOSOnboardingStep: Int, CaseIterable {
    case welcome
    case units
    case start
}

enum IOSOnboarding {
    /// Device state like the settings, kept in `UserDefaults` and not part of the backup.
    static let completedKey = "thrym.onboarding.completed"

    /// The introduction appears once on a fresh install. Someone who already has training data
    /// (an update from a build before the onboarding existed, or restored data) never sees it.
    static func shouldShow(completed: Bool, data: ILTrainingData) -> Bool {
        !completed && data.workoutSessions.isEmpty && data.trainingPlans.isEmpty
    }
}

/// Short introduction on the first start: what Thrym is, the weight unit, and how to begin
/// (fresh or with an IronLog backup). Every step can be skipped.
struct IOSOnboardingView: View {
    let unitSystem: String
    let onSelectUnitSystem: (String) -> Void
    let onFinish: () -> Void
    let onImportBackup: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymTheme) private var theme
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @State private var step: IOSOnboardingStep = .welcome

    private var steps: [IOSOnboardingStep] { IOSOnboardingStep.allCases }
    private var stepIndex: Int { steps.firstIndex(of: step) ?? 0 }
    private var reduceMotion: Bool { systemReduceMotion || theme.reducedMotion }

    var body: some View {
        let palette = theme.palette(for: colorScheme)

        VStack(spacing: 0) {
            header

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    switch step {
                    case .welcome:
                        welcomeStep(palette: palette)
                    case .units:
                        unitsStep(palette: palette)
                    case .start:
                        startStep(palette: palette)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 24)
                .id(step)
                .transition(.opacity)
            }
            .scrollBounceBehavior(.basedOnSize)

            stepIndicator(palette: palette)
                .padding(.vertical, 16)

            buttons
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
        .font(.geist(.body))
        .thrymScreenBackground(ember: palette.background)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.22), value: step)
    }

    // MARK: - Chrome

    private var header: some View {
        HStack {
            if stepIndex > 0 {
                Button {
                    step = steps[stepIndex - 1]
                } label: {
                    Image(systemName: "chevron.backward")
                        .font(.body.weight(.semibold))
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel("Zurück")
            }
            Spacer()
            if step != .start {
                Button("Überspringen") {
                    step = .start
                }
                .frame(minHeight: 44)
            }
        }
        .frame(minHeight: 44)
    }

    @ViewBuilder
    private var buttons: some View {
        if step == .start {
            VStack(spacing: 10) {
                Button(action: onFinish) {
                    Text("Los geht's")
                        .font(.geist(.headline))
                        .frame(maxWidth: .infinity, minHeight: 34)
                }
                .buttonStyle(.borderedProminent).thrymButtonText()

                Button(action: onImportBackup) {
                    Label("Backup importieren", systemImage: "arrow.down.doc")
                        .frame(maxWidth: .infinity, minHeight: 30)
                }
                .buttonStyle(.bordered)
            }
        } else {
            Button {
                step = steps[min(stepIndex + 1, steps.count - 1)]
            } label: {
                Text("Weiter")
                    .font(.geist(.headline))
                    .frame(maxWidth: .infinity, minHeight: 34)
            }
            .buttonStyle(.borderedProminent).thrymButtonText()
        }
    }

    private func stepIndicator(palette: ThrymTheme.Palette) -> some View {
        HStack(spacing: 8) {
            ForEach(steps.indices, id: \.self) { index in
                Capsule()
                    .fill(index == stepIndex ? palette.primary : palette.textSecondary.opacity(0.35))
                    .frame(width: index == stepIndex ? 24 : 8, height: 8)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Schritt \(stepIndex + 1) von \(steps.count)"))
    }

    // MARK: - Steps

    @ViewBuilder
    private func welcomeStep(palette: ThrymTheme.Palette) -> some View {
        stepIcon("dumbbell.fill", palette: palette)
        stepTitle(Text("Willkommen bei \(Self.appName)"))
        Text("Dein Log für Krafttraining.")
            .font(.geist(.body))
            .thrymSecondaryText()

        ThrymCard(tone: .elevated) {
            VStack(alignment: .leading, spacing: 16) {
                featureRow(
                    icon: "lock.fill",
                    title: Text("Deine Daten bleiben auf deinem Gerät"),
                    body: Text("Kein Konto, keine Cloud. Alles funktioniert offline."),
                    palette: palette
                )
                featureRow(
                    icon: "dumbbell.fill",
                    title: Text("Sätze in Sekunden loggen"),
                    body: Text("Gewicht, Wiederholungen und auf Wunsch RPE oder RIR."),
                    palette: palette
                )
                featureRow(
                    icon: "chart.line.uptrend.xyaxis",
                    title: Text("Fortschritt im Blick"),
                    body: Text("Rekorde, Statistiken und ein Vorschlag für dein nächstes Gewicht."),
                    palette: palette
                )
            }
        }
    }

    @ViewBuilder
    private func unitsStep(palette: ThrymTheme.Palette) -> some View {
        stepTitle(Text("In welcher Einheit trainierst du?"))
        VStack(spacing: 12) {
            unitOption(value: "METRIC", short: "kg", label: Text("Kilogramm"), palette: palette)
            unitOption(value: "IMPERIAL", short: "lb", label: Text("Pfund"), palette: palette)
        }
        Text("Du kannst das jederzeit in den Einstellungen ändern.")
            .font(.geist(.subheadline))
            .thrymSecondaryText()
    }

    @ViewBuilder
    private func startStep(palette: ThrymTheme.Palette) -> some View {
        stepIcon("play.fill", palette: palette)
        stepTitle(Text("Bereit"))
        Text("Starte ein freies Training oder lege zuerst einen Plan an.")
            .font(.geist(.body))
            .thrymSecondaryText()

        ThrymCard(
            title: String(localized: "Du kommst von IronLog?"),
            subtitle: String(localized: "Exportiere in IronLog ein Backup und importiere es hier. Deine Trainings, Pläne und Rekorde kommen mit."),
            tone: .muted
        ) {
            EmptyView()
        }
    }

    // MARK: - Pieces

    private func stepIcon(_ systemName: String, palette: ThrymTheme.Palette) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 32, weight: .semibold))
            .foregroundStyle(palette.primary)
            .frame(width: 72, height: 72)
            .background(Circle().fill(palette.primary.opacity(0.16)))
            .accessibilityHidden(true)
    }

    private func stepTitle(_ text: Text) -> some View {
        text
            .font(.geist(.title, weight: .bold))
            .accessibilityAddTraits(.isHeader)
    }

    private func featureRow(icon: String, title: Text, body: Text, palette: ThrymTheme.Palette) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.body.weight(.semibold))
                .foregroundStyle(palette.primary)
                .frame(width: 24)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                title
                    .font(.geist(.subheadline, weight: .semibold))
                body
                    .font(.geist(.subheadline))
                    .thrymSecondaryText()
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func unitOption(value: String, short: String, label: Text, palette: ThrymTheme.Palette) -> some View {
        let selected = unitSystem == value
        return Button {
            onSelectUnitSystem(value)
        } label: {
            HStack(spacing: 16) {
                Text(verbatim: short)
                    .font(.geist(.title2, weight: .bold))
                    .foregroundStyle(selected ? palette.accentText : palette.textPrimary)
                    .frame(width: 44, alignment: .leading)
                label
                    .font(.geist(.headline, weight: .medium))
                    .foregroundStyle(palette.textPrimary)
                Spacer(minLength: 0)
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(palette.primary)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(selected ? palette.primary.opacity(0.14) : palette.surfaceMuted.opacity(0.6))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(selected ? palette.primary.opacity(0.6) : palette.separator, lineWidth: selected ? 2 : 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private static var appName: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "Thrym"
    }
}
