import Shared
import SwiftUI
import UIKit

/// Settings and rules of the companion (Odin or a valkyrie). The rules and the pixel art live in
/// the shared core (`CompanionRules`, `CompanionSprites`); iOS only stores the choice, draws the
/// frames and translates the lines.
enum IOSCompanion {
    /// `CompanionChoice` name: `AUTO`, `ODIN`, `VALKYRIE` or `OFF`. Device state like the
    /// settings, kept in `UserDefaults` and not part of the backup.
    static let choiceKey = "thrym.companion.choice"
    /// Local day (days since 1970-01-01) on which the user hid the companion, `-1` for none.
    static let hiddenDayKey = "thrym.companion.hiddenDay"

    static let choices = ["AUTO", "ODIN", "VALKYRIE", "OFF"]

    /// The companion the choice resolves to (`ODIN` or `VALKYRIE`), `nil` when it is off.
    /// "Automatic" picks Odin until the app knows the user's sex.
    static func characterName(choice: String) -> String? {
        let name = IosCompanion.shared.characterName(choiceName: choice, profileFemale: false)
        return name.isEmpty ? nil : name
    }

    /// The home screen appearance for `epochDay`, or `nil` when the companion stays away today.
    /// `hiddenDay` is the day the user hid it, `-1` for none.
    static func homeAppearance(
        choice: String,
        epochDay: Int64,
        sessions: [ILWorkoutSession],
        hiddenDay: Int64
    ) -> (character: String, line: String)? {
        guard let appearance = IosCompanion.shared.homeAppearance(
            choiceName: choice,
            profileFemale: false,
            epochDay: epochDay,
            daysSinceLastWorkout: daysSinceLastWorkout(sessions: sessions) ?? -1,
            hiddenEpochDay: hiddenDay
        ) else { return nil }
        return (appearance.characterName, appearance.lineName)
    }

    /// The local calendar day as a day number, stable for the whole day.
    static func epochDay(for date: Date = Date(), calendar: Calendar = .current) -> Int64 {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC") ?? .current
        let midnight = utc.date(from: components) ?? date
        return Int64((midnight.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    /// Calendar days since the start of the last finished workout, `nil` without one.
    static func daysSinceLastWorkout(
        sessions: [ILWorkoutSession],
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> Int64? {
        guard let last = sessions.filter({ $0.endTime != nil }).max(by: { $0.startTime < $1.startTime }) else {
            return nil
        }
        let start = Date(timeIntervalSince1970: TimeInterval(last.startTime) / 1000)
        let days = calendar.dateComponents(
            [.day],
            from: calendar.startOfDay(for: start),
            to: calendar.startOfDay(for: now)
        ).day ?? 0
        return Int64(max(days, 0))
    }

    static func displayName(_ character: String) -> String {
        character == "VALKYRIE" ? String(localized: "Walküre") : String(localized: "Odin")
    }

    /// Localized text of a `CompanionLine`; the onboarding welcome names the character.
    static func lineText(_ line: String, character: String) -> String {
        switch line {
        case "ONBOARDING_WELCOME":
            return character == "VALKYRIE"
                ? String(localized: "Heil dir! Ich bin deine Walküre und begleite dich durch die ersten Schritte.")
                : String(localized: "Heil dir! Ich bin Odin und begleite dich durch die ersten Schritte.")
        case "ONBOARDING_UNITS":
            return String(localized: "Kilo oder Pfund? Die Hantel ist mit beidem gleich schwer.")
        case "ONBOARDING_START":
            return String(localized: "Auf in die Halle! Ich schaue ab und zu vorbei. In den Einstellungen kannst du mich tauschen oder ausschalten.")
        case "FIRST_STEPS_WELCOME":
            return String(localized: "Willkommen in der Halle! Starte dein erstes Training, wann immer du bereit bist.")
        case "TRAINED_TODAY_REST":
            return String(localized: "Gut gekämpft heute. Auch Erholung gehört zum Training.")
        case "TRAINED_TODAY_SLEEP":
            return String(localized: "Das Tagwerk ist getan. Auch Thor braucht seinen Schlaf.")
        case "WELCOME_BACK_ONE_SET":
            return String(localized: "Schön, dich wiederzusehen! Jedes Comeback beginnt mit einem einzigen Satz.")
        case "EVERYDAY_YGGDRASIL":
            return String(localized: "Beständig schlägt schnell. Auch Yggdrasil wuchs Ring um Ring.")
        case "EVERYDAY_WARMUP":
            return String(localized: "Gutes Aufwärmen ist die halbe Schlacht.")
        case "EVERYDAY_TECHNIQUE":
            return String(localized: "Erst saubere Technik. Das schwere Gewicht kommt von allein.")
        case "EVERYDAY_RECOVERY":
            return String(localized: "Schlaf und gutes Essen machen auch stark.")
        case "EVERYDAY_SMALL_PLATES":
            return String(localized: "Kleine Scheiben zählen. Fortschritt ist Fortschritt.")
        case "WORKOUT_DONE_FOUGHT":
            return String(localized: "Training geschafft. Gut gekämpft!")
        case "WORKOUT_DONE_HALL":
            return String(localized: "Wieder ein Training in der Halle. Weiter so!")
        case "RECORD_SKALDS":
            return String(localized: "Neue Bestleistung! Davon werden die Skalden singen.")
        case "RECORD_SAGA":
            return String(localized: "Ein Rekord! Der kommt in die Saga.")
        default:
            return ""
        }
    }

    /// Line and animation for the workout summary: a celebration for records, a cheer otherwise.
    static func completion(sessionID: Int64, recordCount: Int) -> (animation: String, line: String) {
        let result = IosCompanion.shared.completion(sessionId: sessionID, recordCount: Int32(recordCount))
        return (result.animationName, result.lineName)
    }

    /// Frame durations of an animation in milliseconds.
    static func durations(_ animation: String) -> [UInt64] {
        IosCompanion.shared.durationsMs(animationName: animation).map { UInt64(max(0, $0.int64Value)) }
    }

    /// Bitmap of one frame, built once per character, animation and frame.
    @MainActor
    static func image(character: String, animation: String, index: Int) -> CGImage? {
        let key = "\(character).\(animation).\(index)"
        if let cached = imageCache[key] { return cached }
        let frame = IosCompanion.shared.frame(characterName: character, animationName: animation, index: Int32(index))
        let width = Int(frame.width)
        let height = Int(frame.height)
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        for index in 0..<(width * height) {
            let argb = UInt32(bitPattern: frame.argb.get(index: Int32(index)))
            let alpha = UInt8((argb >> 24) & 0xFF)
            guard alpha > 0 else { continue }
            bytes[index * 4] = UInt8((argb >> 16) & 0xFF)
            bytes[index * 4 + 1] = UInt8((argb >> 8) & 0xFF)
            bytes[index * 4 + 2] = UInt8(argb & 0xFF)
            bytes[index * 4 + 3] = alpha
        }
        let image = bytes.withUnsafeMutableBytes { buffer -> CGImage? in
            guard let context = CGContext(
                data: buffer.baseAddress,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return nil }
            return context.makeImage()
        }
        imageCache[key] = image
        return image
    }

    @MainActor private static var imageCache: [String: CGImage] = [:]
}

/// A strip in which the companion lives: it walks around, stands, practises spear thrusts and
/// cheers, as the shared `CompanionDirector` decides. One sprite pixel is always a whole number of
/// device pixels, so the art stays sharp. `entrance` plays once when the stage appears; a tap
/// makes the companion celebrate. With reduced motion it stands still in the middle.
struct IOSCompanionStage: View {
    let character: String
    var height: CGFloat = 112
    var entrance: String?

    @Environment(\.thrymTheme) private var theme
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @Environment(\.displayScale) private var displayScale
    @State private var stageWidth: CGFloat = 0
    @State private var x: CGFloat = .nan
    @State private var facingRight = true
    @State private var animation = "IDLE"
    @State private var frameIndex = 0
    @State private var celebrations = 0
    @State private var entrancePlayed = false

    private var reduceMotion: Bool { systemReduceMotion || theme.reducedMotion }
    private static let size = CGFloat(CompanionSprites.shared.SIZE)
    private static let anchor = CGFloat(CompanionSprites.shared.ANCHOR_X)
    private static let feetRow: CGFloat = 78

    /// Points per sprite pixel, rounded down to whole device pixels.
    private var pixel: CGFloat {
        let scale = max(displayScale, 1)
        return max(1, (height * scale / Self.size).rounded(.down)) / scale
    }

    private var spriteWidth: CGFloat { (stageWidth / pixel).rounded(.down) }

    var body: some View {
        let p = pixel
        ZStack(alignment: .topLeading) {
            if !x.isNaN, let image = IOSCompanion.image(character: character, animation: animation, index: frameIndex) {
                let anchorX = x.rounded(.down) * p
                let left = anchorX - Self.anchor * p
                let top = height - Self.size * p
                Ellipse()
                    .fill(Color.black.opacity(0.25))
                    .frame(width: 22 * p, height: 3 * p)
                    .offset(x: anchorX - 11 * p, y: top + (Self.feetRow - 1) * p)
                Image(decorative: image, scale: 1)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: Self.size * p, height: Self.size * p)
                    .scaleEffect(x: facingRight ? 1 : -1, y: 1, anchor: UnitPoint(x: (Self.anchor + 0.5) / Self.size, y: 0.5))
                    .offset(x: left, y: top)
            }
        }
        .frame(maxWidth: .infinity, minHeight: height, maxHeight: height, alignment: .topLeading)
        .background(GeometryReader { proxy in
            Color.clear
                .onAppear { stageWidth = proxy.size.width }
                .onChange(of: proxy.size.width) { _, width in stageWidth = width }
        })
        .clipped()
        .contentShape(Rectangle())
        .onTapGesture { celebrations += 1 }
        .accessibilityHidden(true)
        .task(id: "\(character)-\(reduceMotion)-\(celebrations)-\(stageWidth > 0)") {
            await run()
        }
    }

    @MainActor
    private func run() async {
        let width = spriteWidth
        guard width > 0 else { return }
        let edge = CGFloat(CompanionDirector.shared.EDGE)
        if x.isNaN {
            x = entrance != nil ? width / 2 : edge + CGFloat.random(in: 0...1) * max(0, width - 2 * edge)
        }
        x = min(max(x, 0), width)
        if reduceMotion {
            x = width / 2
            facingRight = true
            let still = celebrations > 0 ? "CELEBRATE" : (entrance ?? "IDLE")
            animation = still
            frameIndex = max(0, IOSCompanion.durations(still).count - 1)
            return
        }
        if celebrations > 0 {
            guard await play("CELEBRATE") else { return }
        } else if let entrance, !entrancePlayed {
            entrancePlayed = true
            guard await play(entrance, loops: entrance == "CHEER" ? 3 : 1) else { return }
        }
        var previous = animation
        while !Task.isCancelled {
            let action = IosCompanion.shared.nextAction(
                seed: Int32.random(in: Int32.min...Int32.max),
                x: Float(x),
                facingRight: facingRight,
                stageWidth: Float(width),
                previousName: previous
            )
            facingRight = action.facingRight
            switch action.animationName {
            case "WALK":
                animation = "WALK"
                let frameMs = IOSCompanion.durations("WALK").first ?? 110
                let step = CGFloat(CompanionDirector.shared.WALK_SPEED) * CGFloat(frameMs) / 1000
                let target = CGFloat(action.targetX)
                var index = 0
                while abs(target - x) > 0.01 {
                    frameIndex = index % 6
                    index += 1
                    guard await pause(frameMs) else { return }
                    x = target > x ? min(target, x + step) : max(target, x - step)
                }
            case "IDLE":
                animation = "IDLE"
                let durations = IOSCompanion.durations("IDLE")
                var left = UInt64(max(0, action.durationMs))
                var index = 0
                while left > 0 {
                    frameIndex = index % durations.count
                    guard await pause(durations[frameIndex]) else { return }
                    left = left > durations[frameIndex] ? left - durations[frameIndex] : 0
                    index += 1
                }
            case "CHEER":
                guard await play("CHEER", loops: 2) else { return }
            default:
                guard await play(action.animationName) else { return }
            }
            previous = action.animationName
        }
    }

    /// Plays `name` `loops` times; false when the stage went away meanwhile.
    @MainActor
    private func play(_ name: String, loops: Int = 1) async -> Bool {
        animation = name
        for _ in 0..<loops {
            for (index, millis) in IOSCompanion.durations(name).enumerated() {
                frameIndex = index
                guard await pause(millis) else { return false }
            }
        }
        return true
    }

    private func pause(_ millis: UInt64) async -> Bool {
        try? await Task.sleep(nanoseconds: millis * 1_000_000)
        return !Task.isCancelled
    }
}

/// The companion with what it says: name and line on top, below the stage on which it walks
/// around. `entrance` plays once when the card appears. `onHide` adds a button that hides it;
/// without it the card has no close button (onboarding).
struct IOSCompanionCard: View {
    let character: String
    let line: String
    var stageHeight: CGFloat = 112
    var entrance: String?
    var onHide: (() -> Void)?

    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let text = IOSCompanion.lineText(line, character: character)
        ThrymCard(tone: .elevated) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(IOSCompanion.displayName(character).uppercased())
                            .font(.geist(.caption, weight: .bold))
                            .tracking(1.5)
                            .foregroundStyle(theme.palette(for: colorScheme).accentText)
                        Text(text)
                            .font(.geist(.subheadline))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text("\(IOSCompanion.displayName(character)) sagt: \(text)"))
                    if let onHide {
                        Button(action: onHide) {
                            Image(systemName: "xmark")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .frame(minWidth: 28, minHeight: 28)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("Für heute ausblenden"))
                    }
                }
                IOSCompanionStage(character: character, height: stageHeight, entrance: entrance)
            }
        }
    }
}
