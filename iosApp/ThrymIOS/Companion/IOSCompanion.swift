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
        default:
            return ""
        }
    }

    /// Bitmap of one frame, built once per character and pose.
    @MainActor
    static func image(character: String, pose: String) -> CGImage? {
        let key = "\(character).\(pose)"
        if let cached = imageCache[key] { return cached }
        let frame = IosCompanion.shared.frame(characterName: character, poseName: pose)
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

/// The companion as 16-bit pixel art. It breathes and blinks; `cheering` shows the happy pose
/// with a little hop. With reduced motion it stands still and only changes its pose.
struct IOSCompanionSprite: View {
    let character: String
    var size: CGFloat = 88
    var cheering: Bool = false

    @Environment(\.thrymTheme) private var theme
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @State private var blinking = false
    @State private var bob: CGFloat = 0
    @State private var hop: CGFloat = 0

    private var reduceMotion: Bool { systemReduceMotion || theme.reducedMotion }

    var body: some View {
        let pose = cheering ? "CHEER" : (blinking ? "BLINK" : "IDLE")
        // Whole screen points per sprite pixel, with room for the hop above.
        let pixel = max(1, (size / 35).rounded(.down))
        Group {
            if let image = IOSCompanion.image(character: character, pose: pose) {
                Image(decorative: image, scale: 1)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: pixel * 32, height: pixel * 32)
                    .offset(y: -(bob + hop) * pixel)
            }
        }
        .frame(width: size, height: size, alignment: .bottom)
        .task(id: "\(reduceMotion)-\(cheering)") {
            blinking = false
            bob = 0
            guard !reduceMotion, !cheering else { return }
            var elapsed: UInt64 = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 520_000_000)
                elapsed += 520
                bob = bob == 0 ? 1 : 0
                if elapsed >= 3_600 {
                    elapsed = 0
                    blinking = true
                    try? await Task.sleep(nanoseconds: 140_000_000)
                    blinking = false
                }
            }
        }
        .task(id: "hop-\(reduceMotion)-\(cheering)") {
            hop = 0
            guard !reduceMotion, cheering else { return }
            for height in [1, 2, 3, 2, 1, 0] as [CGFloat] {
                hop = height
                try? await Task.sleep(nanoseconds: 90_000_000)
            }
        }
    }
}

/// The companion with a speech bubble. A tap on the companion makes it cheer. `onHide` adds a
/// button that hides it; without it the card has no close button (onboarding).
struct IOSCompanionCard: View {
    let character: String
    let line: String
    var spriteSize: CGFloat = 88
    var cheering: Bool = false
    var onHide: (() -> Void)?

    @State private var tapCheer = false

    var body: some View {
        let text = IOSCompanion.lineText(line, character: character)
        HStack(alignment: .bottom, spacing: 8) {
            IOSCompanionSprite(character: character, size: spriteSize, cheering: cheering || tapCheer)
                .contentShape(Rectangle())
                .onTapGesture {
                    tapCheer = true
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 1_200_000_000)
                        tapCheer = false
                    }
                }
                .accessibilityHidden(true)

            ThrymCard(tone: .elevated) {
                HStack(alignment: .top, spacing: 8) {
                    Text(text)
                        .font(.geist(.subheadline))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
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
            }
            .padding(.bottom, 12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
