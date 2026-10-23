import SwiftUI

struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var training = IOSTrainingStore()
    @StateObject private var settings = IOSSettingsViewModel()
    @AppStorage(IOSOnboarding.completedKey) private var onboardingCompleted = false
    @State private var isShowingOnboarding = false

    var body: some View {
        TabView(selection: $training.selectedTab) {
            trainingContent { IOSDashboardScreen() }
                .tabItem { Label("Start", systemImage: "house") }.tag(0)
            trainingContent { NavigationStack { IOSWorkoutScreen() } }
                .tabItem { Label("Training", systemImage: "dumbbell") }.tag(1)
            trainingContent { IOSHistoryScreen() }
                .tabItem { Label("Verlauf", systemImage: "clock.arrow.circlepath") }.tag(2)
            trainingContent { IOSPlansScreen(unitSystem: settings.state.unitSystem) }
                .tabItem { Label("Pläne", systemImage: "list.bullet.rectangle") }.tag(3)
            SettingsScreen()
                .tabItem { Label("Einstellungen", systemImage: "gearshape") }.tag(4)
        }
        .font(.geist(.body))
        .fullScreenCover(isPresented: $isShowingOnboarding) {
            IOSOnboardingView(
                unitSystem: settings.state.unitSystem,
                onSelectUnitSystem: { settings.updateUnitSystem($0) },
                onFinish: finishOnboarding
            )
            .environment(\.thrymAppearance, appearance)
            .thrymTheme(theme)
            .preferredColorScheme(preferredColorScheme)
        }
        .environment(training)
        .environmentObject(settings)
        .task {
            if training.data == nil { await training.reload() }
            if let data = training.data {
                if IOSOnboarding.shouldShow(completed: onboardingCompleted, data: data) {
                    isShowingOnboarding = true
                } else if !onboardingCompleted {
                    onboardingCompleted = true
                }
            }
            // Central deload wiring for every workout start: the settings mode is authoritative,
            // and the store sends an explicit `false` while no mode is active.
            training.deloadModeActive = settings.state.deloadMode != "NONE"
            if training.deloadModeActive {
                _ = await training.markActiveSessionDeload()
            }
            if training.data != nil {
                await training.command("progression.generate")
            }
        }
        // As on Android, the setting keeps the screen on in the whole app, also during the
        // full-screen pause and on other tabs.
        .onAppear { updateIdleTimer() }
        .onChange(of: settings.state.timerKeepScreenOn) { _, _ in updateIdleTimer() }
        .onChange(of: settings.state.deloadMode) { _, newValue in
            let active = newValue != "NONE"
            training.deloadModeActive = active
            // Switching the mode on must mark a session that is already running. Switching it off
            // must not rewrite history: the durable store only ever escalates to `true`.
            if active {
                Task { _ = await training.markActiveSessionDeload() }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            updateIdleTimer()
            if phase == .active {
                training.refreshAnalytics(
                    timeZoneId: TimeZone.current.identifier,
                    weekStartsSunday: settings.state.weekStart == "SUNDAY"
                )
            }
        }
        .environment(\.thrymAppearance, appearance)
        .thrymTheme(theme)
        .preferredColorScheme(preferredColorScheme)
        .safeAreaInset(edge: .top) {
            if let error = training.errorMessage, training.data != nil {
                HStack(alignment: .top, spacing: 12) {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .font(.geist(.callout))
                    Spacer(minLength: 0)
                    Button { training.errorMessage = nil } label: {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .accessibilityLabel("Fehlermeldung schließen")
                }
                .padding()
                .background(.regularMaterial)
            }
        }
    }

    private var appearance: ThrymAppearance {
        ThrymAppearance(rawPreference: settings.state.appearanceStyle)
    }

    private var theme: ThrymTheme {
        ThrymTheme(
            schemeName: settings.state.themeScheme,
            reducedMotion: settings.state.reducedMotion
        )
    }

    private var preferredColorScheme: ColorScheme? {
        settings.state.themeMode == "DARK" ? .dark : settings.state.themeMode == "LIGHT" ? .light : nil
    }

    private func updateIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = scenePhase == .active && settings.state.timerKeepScreenOn
    }

    private func finishOnboarding() {
        onboardingCompleted = true
        isShowingOnboarding = false
    }

    @ViewBuilder
    private func trainingContent<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        if training.data != nil {
            content()
        } else if training.isLoading || training.errorMessage == nil {
            ProgressView("Trainingsdaten werden geladen …")
        } else {
            ContentUnavailableView {
                Label("Trainingsdaten nicht verfügbar", systemImage: "externaldrive.badge.exclamationmark")
            } description: {
                Text(training.errorMessage ?? String(localized: "Die Trainingsdaten werden geladen."))
            } actions: {
                Button("Erneut versuchen") { Task { await training.reload() } }
                Button("Backup in Einstellungen wiederherstellen") { training.selectedTab = 4 }
            }
        }
    }
}
