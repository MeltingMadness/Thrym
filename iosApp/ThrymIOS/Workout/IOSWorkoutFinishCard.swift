import SwiftUI

/// End of the workout list once every planned set is logged: "Alle 9 geplanten Sätze
/// geschafft" with "Training beenden", tinted green. The button opens the finish sheet
/// like "Beenden", so a thumb that lands here right after the scroll cannot end the
/// workout on its own. Same card as Android's `WorkoutFinishCard`.
struct IOSWorkoutFinishCard: View {
    let plannedSetCount: Int
    let onFinish: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymAppearance) private var appearance

    var body: some View {
        let ink = ThrymSemanticInk.forDark(colorScheme == .dark)
        let liquidGlass = appearance == .liquidGlass
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.geist(.title3, weight: .semibold))
                    .foregroundStyle(ink.success)
                    .accessibilityHidden(true)
                Text("Alle \(plannedSetCount) geplanten Sätze geschafft")
                    .font(.geist(.headline, weight: .heavy))
            }
            if liquidGlass {
                Button("Training beenden", action: onFinish)
                    .buttonStyle(IOSWorkoutGlassPrimaryButtonStyle())
                    .accessibilityIdentifier("workout-finish-card-button")
            } else {
                Button(action: onFinish) {
                    Text("Training beenden")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 7)
                }
                .buttonStyle(.borderedProminent)
                .thrymButtonText()
                .accessibilityIdentifier("workout-finish-card-button")
            }
        }
        .padding(liquidGlass ? 18 : 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .modifier(IOSWorkoutTintedCardSurface(tint: ink.successFill, glassCornerRadius: 26))
        .accessibilityElement(children: .contain)
    }
}

/// Surface of a tinted workout card (finish card, record board): tinted glass in Liquid
/// Glass; in Ember the card surface with a faint tint and a tinted edge, like Android's
/// `ThrymSurfaceCard` with tone `COLORED`.
struct IOSWorkoutTintedCardSurface: ViewModifier {
    let tint: Color
    let glassCornerRadius: CGFloat

    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymAppearance) private var appearance

    func body(content: Content) -> some View {
        switch appearance {
        case .liquidGlass:
            content.liquidGlass(.tint, in: RoundedRectangle(cornerRadius: glassCornerRadius, style: .continuous), tint: tint)
        case .ember:
            let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
            content
                .background {
                    shape.fill(theme.palette(for: colorScheme).surface)
                        .overlay { shape.fill(tint.opacity(0.08)) }
                }
                .overlay { shape.strokeBorder(tint.opacity(0.18)) }
        }
    }
}
