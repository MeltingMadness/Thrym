import SwiftUI

/// One segment of a `ThrymModeSwitcher`: the mode it selects, its name and symbol, and an
/// optional count shown as a small pill.
struct ThrymModeSegment<Mode: Hashable>: Identifiable {
    let mode: Mode
    let title: String
    let systemImage: String
    var count: Int?

    var id: Mode { mode }
}

/// Switches between the modes of a screen (plans and meta plans, workouts and achievements).
/// A full-width capsule below the title; the selected segment is a bright pill in Liquid Glass
/// (white on dark, near black on light glass) and the accent color in Ember.
struct ThrymModeSwitcher<Mode: Hashable>: View {
    @Binding var selection: Mode
    let segments: [ThrymModeSegment<Mode>]
    /// VoiceOver name of the whole switcher, e.g. "Planbereich".
    let accessibilityLabel: String

    @Environment(\.thrymAppearance) private var appearance
    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var selectionNamespace
    /// Counts the taps that changed the mode. The haptic follows it, not `selection`, so a mode
    /// set by the screen itself stays silent.
    @State private var taps = 0

    private static var glassInk: Color { Color(red: 11 / 255, green: 13 / 255, blue: 18 / 255) }

    var body: some View {
        let palette = theme.palette(for: colorScheme)
        HStack(spacing: 4) {
            ForEach(segments) { segment in
                segmentButton(segment, palette: palette)
            }
        }
        .padding(4)
        .background { container(palette: palette) }
        .sensoryFeedback(.selection, trigger: taps)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(accessibilityLabel)
    }

    private func segmentButton(_ segment: ThrymModeSegment<Mode>, palette: ThrymTheme.Palette) -> some View {
        let selected = segment.mode == selection
        let ink = selected ? selectedInk(palette: palette) : palette.textPrimary
        return Button {
            guard !selected else { return }
            taps += 1
            if reduceMotion {
                selection = segment.mode
            } else {
                withAnimation(.snappy(duration: 0.28)) { selection = segment.mode }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: segment.systemImage)
                    .font(.system(size: 14, weight: .semibold))
                Text(segment.title)
                    .font(.geist(.subheadline, weight: .bold))
                    .lineLimit(1)
                if let count = segment.count {
                    Text(count, format: .number)
                        .font(.geist(.caption, weight: .bold))
                        .monospacedDigit()
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(ink.opacity(selected ? 0.14 : 0.10), in: Capsule())
                }
            }
            .minimumScaleFactor(0.75)
            .foregroundStyle(ink)
            .frame(maxWidth: .infinity, minHeight: 44)
            .padding(.horizontal, 8)
            .background {
                if selected {
                    Capsule()
                        .fill(selectedFill(palette: palette))
                        .shadow(color: .black.opacity(appearance == .liquidGlass ? 0.28 : 0.12), radius: 6, y: 3)
                        .matchedGeometryEffect(id: "selection", in: selectionNamespace)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(segment.title)
        .accessibilityValue(segment.count.map { Text($0, format: .number) } ?? Text(verbatim: ""))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder
    private func container(palette: ThrymTheme.Palette) -> some View {
        switch appearance {
        case .liquidGlass:
            Capsule().fill(.clear).liquidGlass(.standard, in: Capsule())
        case .ember:
            Capsule()
                .fill(palette.surfaceMuted)
                .overlay { Capsule().strokeBorder(palette.separator) }
        }
    }

    private func selectedFill(palette: ThrymTheme.Palette) -> Color {
        switch appearance {
        case .liquidGlass: colorScheme == .dark ? .white : Self.glassInk
        case .ember: palette.primary
        }
    }

    private func selectedInk(palette: ThrymTheme.Palette) -> Color {
        switch appearance {
        case .liquidGlass: colorScheme == .dark ? Self.glassInk : .white
        case .ember: palette.buttonText
        }
    }
}
