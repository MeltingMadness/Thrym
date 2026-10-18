import Foundation
import SwiftUI
import UIKit

/// The rest timer is presentation state, but it must survive a view being rebuilt or the app
/// moving through the background.  Epoch values are persisted instead of a ticking counter so
/// the displayed value always derives from the current wall clock and cannot drift.
struct IOSWorkoutRestTimer: Codable, Equatable, Identifiable {
    let sessionID: Int64
    let rowKey: String
    let startedAtEpochMillis: Int64
    let deadlineEpochMillis: Int64?
    let durationSeconds: Int

    var id: String { "\(sessionID):\(rowKey)" }

    var isCountdown: Bool { deadlineEpochMillis != nil }

    func remaining(at date: Date = Date()) -> Int {
        let now = Int64(date.timeIntervalSince1970 * 1_000)
        if let deadlineEpochMillis {
            return max(0, Int((deadlineEpochMillis - now) / 1_000))
        }
        return max(0, Int((now - startedAtEpochMillis) / 1_000))
    }

    var hasElapsed: Bool { hasElapsed(at: Date()) }

    func hasElapsed(at date: Date) -> Bool {
        guard let deadlineEpochMillis else { return false }
        return Int64(date.timeIntervalSince1970 * 1_000) >= deadlineEpochMillis
    }

    /// Countdown shifted by `deltaSeconds` ("−15 s" / "+30 s"). A shortened timer never
    /// ends before now, and at least one second after its start. Elapsed-time timers have
    /// no deadline and stay unchanged.
    func adjusted(by deltaSeconds: Int, now: Date = Date()) -> IOSWorkoutRestTimer {
        guard let deadlineEpochMillis else { return self }
        let nowMillis = Int64(now.timeIntervalSince1970 * 1_000)
        let earliest = max(nowMillis, startedAtEpochMillis + 1_000)
        let deadline = max(earliest, deadlineEpochMillis + Int64(deltaSeconds) * 1_000)
        return IOSWorkoutRestTimer(
            sessionID: sessionID,
            rowKey: rowKey,
            startedAtEpochMillis: startedAtEpochMillis,
            deadlineEpochMillis: deadline,
            durationSeconds: Int((deadline - startedAtEpochMillis + 999) / 1_000)
        )
    }

    static func make(
        sessionID: Int64,
        rowKey: String,
        durationSeconds: Int,
        now: Date = Date()
    ) -> IOSWorkoutRestTimer {
        let started = Int64(now.timeIntervalSince1970 * 1_000)
        let deadline = durationSeconds > 0
            ? started + Int64(durationSeconds) * 1_000
            : nil
        return IOSWorkoutRestTimer(
            sessionID: sessionID,
            rowKey: rowKey,
            startedAtEpochMillis: started,
            deadlineEpochMillis: deadline,
            durationSeconds: max(0, durationSeconds)
        )
    }
}

/// Rules of the Liquid Glass rest dock, which shows one row per running timer.
enum IOSWorkoutRestDock {
    /// Countdowns first, shortest remaining time on top; count-up timers follow
    /// in start order. Remaining time orders like the deadline, so the order
    /// only changes when a timer starts, moves or ends, not every second.
    static func ordered<Timers: Collection>(_ timers: Timers) -> [IOSWorkoutRestTimer]
    where Timers.Element == IOSWorkoutRestTimer {
        timers.sorted { lhs, rhs in
            switch (lhs.deadlineEpochMillis, rhs.deadlineEpochMillis) {
            case let (left?, right?) where left != right:
                return left < right
            case (.some, nil):
                return true
            case (nil, .some):
                return false
            default:
                return lhs.startedAtEpochMillis == rhs.startedAtEpochMillis
                    ? lhs.rowKey < rhs.rowKey
                    : lhs.startedAtEpochMillis < rhs.startedAtEpochMillis
            }
        }
    }

    /// Whether `started` opens the full-screen pause by itself. Only a countdown
    /// does, and only when it begins a series (no other exercise has a countdown
    /// still running) or when it is the next set of an exercise that already has
    /// a timer. A second superset exercise therefore stays in the dock. Timers
    /// that end or are removed never call this, so they never open the pause.
    ///
    /// - Parameter running: the timers before `started` replaces its row's timer.
    static func opensPause(
        starting started: IOSWorkoutRestTimer,
        running: [String: IOSWorkoutRestTimer],
        now: Date = Date()
    ) -> Bool {
        guard started.isCountdown else { return false }
        if running[started.rowKey] != nil { return true }
        return !running.values.contains { timer in
            timer.rowKey != started.rowKey && timer.isCountdown && !timer.hasElapsed(at: now)
        }
    }
}

/// Named keys make the persisted timer inspectable and allow a later migration to remove or
/// replace this small presentation record without touching workout business data.
enum IOSWorkoutRestTimerPersistence {
    /// Current format: one JSON array per app, filtered by session and keyed by
    /// `rowKey` at the workout layer. This mirrors Android's per-exercise map.
    static let timersKey = "thrym.workout.restTimers"

    /// Legacy single-timer keys from the first iOS workout implementation. They
    /// stay readable for one migration and are removed after a successful read.
    static let sessionIDKey = "thrym.workout.restTimer.sessionID"
    static let rowKeyKey = "thrym.workout.restTimer.rowKey"
    static let startedAtEpochMillisKey = "thrym.workout.restTimer.startedAtEpochMillis"
    static let deadlineEpochMillisKey = "thrym.workout.restTimer.deadlineEpochMillis"
    static let durationSecondsKey = "thrym.workout.restTimer.durationSeconds"

    static func load(sessionID: Int64) -> [String: IOSWorkoutRestTimer] {
        let defaults = UserDefaults.standard
        if let data = defaults.data(forKey: timersKey),
           let decoded = try? JSONDecoder().decode([IOSWorkoutRestTimer].self, from: data) {
            let matching = decoded.filter { $0.sessionID == sessionID }
            return Dictionary(uniqueKeysWithValues: matching.map { ($0.rowKey, $0) })
        }

        // Migrate the old one-row representation exactly once. If it belongs to
        // another session it is stale and can be discarded without affecting the
        // current workout.
        guard let legacy = loadLegacy(sessionID: sessionID) else {
            // A legacy record for a different or already-finished session must
            // not spring back when that old id is encountered later.
            if defaults.object(forKey: sessionIDKey) != nil {
                clearLegacy()
            }
            return [:]
        }
        saveAll([legacy])
        clearLegacy()
        return [legacy.rowKey: legacy]
    }

    static func upsert(_ timer: IOSWorkoutRestTimer) {
        var timers = load(sessionID: timer.sessionID)
        timers[timer.rowKey] = timer
        saveAll(Array(timers.values))
    }

    static func remove(sessionID: Int64, rowKey: String) {
        var timers = load(sessionID: sessionID)
        timers.removeValue(forKey: rowKey)
        saveAll(Array(timers.values))
    }

    static func saveAll(_ timers: [IOSWorkoutRestTimer]) {
        let defaults = UserDefaults.standard
        let sortedTimers = timers.sorted {
            $0.sessionID == $1.sessionID
                ? ($0.rowKey == $1.rowKey
                    ? $0.startedAtEpochMillis < $1.startedAtEpochMillis
                    : $0.rowKey < $1.rowKey)
                : $0.sessionID < $1.sessionID
        }
        if let data = try? JSONEncoder().encode(sortedTimers) {
            defaults.set(data, forKey: timersKey)
        }
    }

    static func clear(sessionID: Int64) {
        let defaults = UserDefaults.standard
        if let data = defaults.data(forKey: timersKey),
           let decoded = try? JSONDecoder().decode([IOSWorkoutRestTimer].self, from: data) {
            saveAll(decoded.filter { $0.sessionID != sessionID })
        }
        if Int64(defaults.integer(forKey: sessionIDKey)) == sessionID {
            clearLegacy()
        }
    }

    static func clear() {
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: timersKey)
        clearLegacy()
    }

    private static func loadLegacy(sessionID: Int64) -> IOSWorkoutRestTimer? {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: sessionIDKey) != nil,
              Int64(defaults.integer(forKey: sessionIDKey)) == sessionID,
              let rowKey = defaults.string(forKey: rowKeyKey),
              defaults.object(forKey: startedAtEpochMillisKey) != nil,
              defaults.object(forKey: durationSecondsKey) != nil
        else { return nil }

        let deadline: Int64?
        if defaults.object(forKey: deadlineEpochMillisKey) == nil {
            deadline = nil
        } else {
            deadline = Int64(defaults.integer(forKey: deadlineEpochMillisKey))
        }

        return IOSWorkoutRestTimer(
            sessionID: sessionID,
            rowKey: rowKey,
            startedAtEpochMillis: Int64(defaults.integer(forKey: startedAtEpochMillisKey)),
            deadlineEpochMillis: deadline,
            durationSeconds: defaults.integer(forKey: durationSecondsKey)
        )
    }

    private static func clearLegacy() {
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: sessionIDKey)
        defaults.removeObject(forKey: rowKeyKey)
        defaults.removeObject(forKey: startedAtEpochMillisKey)
        defaults.removeObject(forKey: deadlineEpochMillisKey)
        defaults.removeObject(forKey: durationSecondsKey)
    }
}

struct IOSWorkoutRestTimerView: View {
    let timer: IOSWorkoutRestTimer
    let title: String?
    let accentColor: Color?
    let onDismiss: () -> Void
    let onComplete: () -> Void

    @State private var didComplete = false

    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let palette = theme.palette(for: colorScheme)
        let timerColor = accentColor ?? (timer.isCountdown ? palette.warning : palette.information)

        TimelineView(.periodic(from: Date(), by: 1)) { context in
            let seconds = timer.remaining(at: context.date)
            HStack(spacing: 12) {
                Image(systemName: timer.isCountdown ? "timer" : "stopwatch")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(timerColor)

                VStack(alignment: .leading, spacing: 2) {
                    if let title, !title.isEmpty {
                        Text(title)
                            .font(.geist(.subheadline, weight: .semibold))
                    } else {
                        Text(timer.isCountdown ? String(localized: "Pause") : String(localized: "Pause läuft"))
                            .font(.geist(.subheadline, weight: .semibold))
                    }
                    Text(timer.isCountdown ? String(localized: "Noch \(formatDuration(seconds))") : formatDuration(seconds))
                        .font(.geist(.headline).monospacedDigit())
                }

                Spacer(minLength: 8)

                // Own key: "Beenden" alone is the finish-workout action.
                Button("Pausentimer beenden", action: onDismiss)
                    .buttonStyle(.bordered)
                    .tint(palette.textSecondary)
                    .accessibilityLabel("Pausentimer für \(title ?? String(localized: "Übung")) beenden")
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surfaceElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(timerColor.opacity(0.38), lineWidth: 1)
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(title ?? String(localized: "Pausentimer"))
            .accessibilityValue(Text(timer.isCountdown ? String(localized: "Noch \(formatDuration(seconds))") : formatDuration(seconds)))
        }
        // The row key keeps SwiftUI's card identity stable, while a fresh start
        // epoch restarts the completion task when the athlete logs the next set.
        .task(id: "\(timer.id):\(timer.startedAtEpochMillis)")
        {
            await waitForCompletionIfNeeded()
        }
        .onChange(of: timer.startedAtEpochMillis) { _, _ in
            didComplete = false
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            completeIfElapsed()
        }
    }

    private func waitForCompletionIfNeeded() async {
        guard timer.isCountdown, let deadline = timer.deadlineEpochMillis else { return }

        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        let delayMillis = max(0, deadline - now)
        if delayMillis > 0 {
            do {
                try await Task.sleep(nanoseconds: UInt64(delayMillis) * 1_000_000)
            } catch {
                return
            }
        }
        guard !Task.isCancelled, !didComplete else { return }
        completeIfElapsed()
    }

    private func completeIfElapsed() {
        guard timer.isCountdown, timer.hasElapsed, !didComplete else { return }
        didComplete = true

        // Match Android's completion feedback while keeping the timer dismissal in the
        // workout screen, where the persisted timer state is cleared atomically.
        let feedback = UINotificationFeedbackGenerator()
        feedback.prepare()
        feedback.notificationOccurred(.success)
        onComplete()
    }

    private func formatDuration(_ totalSeconds: Int) -> String {
        let minutes = max(0, totalSeconds) / 60
        let seconds = max(0, totalSeconds) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }
}
