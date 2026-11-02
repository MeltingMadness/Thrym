import Shared
import SwiftUI

/// The quiet record marker of a set and of its exercise: a small trophy with "PR", or with the
/// mark the set reached ("100 kg"), in the warning tone. It replaces the record banner of the
/// running workout. VoiceOver reads "neuer Rekord" or "100-kg-Marke, zum ersten Mal erreicht".
struct IOSWorkoutRecordMarker: View {
    let badge: SessionSetBadge

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let ink = ThrymSemanticInk.forDark(colorScheme == .dark)
        HStack(spacing: 3) {
            Image(systemName: "trophy.fill")
                .font(.system(size: 10, weight: .semibold))
            Text(badge.mark.map(IOSRecognition.markValue) ?? "PR")
                .font(.geist(.caption2, weight: .bold))
                .monospacedDigit()
        }
        .foregroundStyle(ink.warning)
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .background(ink.warningFill.opacity(0.18), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.accessibilityText(badge))
    }

    static func accessibilityText(_ badge: SessionSetBadge) -> String {
        badge.mark.map { String(localized: "\(IOSRecognition.markTitle($0)), zum ersten Mal erreicht") }
            ?? String(localized: "neuer Rekord")
    }
}
