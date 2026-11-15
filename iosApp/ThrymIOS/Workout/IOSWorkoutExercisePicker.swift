import SwiftUI

struct IOSWorkoutExercisePicker: View {
    @Environment(\.dismiss) private var dismiss
    let exercises: [ILExercise]
    let excludedIDs: Set<Int64>
    /// Exercises of the latest workouts, newest first; shown on top while nothing is filtered.
    var recentIDs: [Int64] = []
    let onSelect: (ILExercise) -> Void
    @State private var searchText = ""
    @State private var group: String?
    @State private var selected: [Int64] = []
    private var filtered: [ILExercise] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        return exercises.filter {
            (group == nil || $0.primaryMuscleGroupDisplayName == group) &&
            (query.isEmpty || $0.nameMatches(query) || $0.categoryDisplayName.localizedCaseInsensitiveContains(query))
        }
    }
    private var recent: [ILExercise] {
        guard group == nil, searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        return recentIDs.compactMap { id in exercises.first { $0.id == id } }
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 8) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        filter(String(localized: "Alle"), value: nil)
                        ForEach(Array(Set(exercises.map(\.primaryMuscleGroupDisplayName))).sorted(), id: \.self) { name in filter(name, value: name) }
                    }.padding(.horizontal)
                }
                HStack {
                    Text("\(selected.count) ausgewählt").thrymAccentText().bold()
                    Spacer()
                    Button("Auswahl leeren") { selected = [] }
                }.font(.geist(.subheadline)).padding(.horizontal)
                List {
                    if !recent.isEmpty {
                        Section("Zuletzt trainiert") { ForEach(recent) { row($0) } }
                    }
                    Section(recent.isEmpty ? "" : String(localized: "Alle Übungen")) { ForEach(filtered) { row($0) } }
                }.listStyle(.insetGrouped)
            }
            .searchable(text: $searchText, prompt: "Übung suchen")
            .thrymScreenBackground()
            .navigationTitle("Übungen wählen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            // Only with a selection: a disabled "0 Übungen hinzufügen" did nothing and showed
            // as an unreadable dark bar above the search field.
            .safeAreaInset(edge: .bottom) {
                if !selected.isEmpty {
                    Button {
                        selected.compactMap { id in exercises.first { $0.id == id } }.forEach(onSelect)
                        dismiss()
                    } label: { Text("\(selected.count) Übungen hinzufügen").frame(maxWidth: .infinity).padding(.vertical, 6) }
                    .buttonStyle(.borderedProminent).thrymButtonText()
                    .padding(.horizontal).padding(.vertical, 8)
                }
            }
        }
    }
    private func row(_ exercise: ILExercise) -> some View {
        Button {
            if selected.contains(exercise.id) { selected.removeAll { $0 == exercise.id } }
            else { selected.append(exercise.id) }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(exercise.displayName).font(.geist(.headline)).foregroundStyle(.primary)
                    Text("\(exercise.primaryMuscleGroupDisplayName) · \(exercise.categoryDisplayName)").font(.geist(.caption)).thrymSecondaryText()
                }
                Spacer()
                Image(systemName: selected.contains(exercise.id) || excludedIDs.contains(exercise.id) ? "checkmark.square.fill" : "square")
                    .font(.title2).foregroundStyle(.tint)
            }.frame(minHeight: 48).contentShape(Rectangle())
        }
        // Plain, so the name keeps the text color instead of the button tint.
        .buttonStyle(.plain)
        .disabled(excludedIDs.contains(exercise.id))
        .listRowBackground(selected.contains(exercise.id) ? Color.accentColor.opacity(0.12) : Color(uiColor: .secondarySystemGroupedBackground))
        .accessibilityValue(selected.contains(exercise.id) ? String(localized: "Ausgewählt") : excludedIDs.contains(exercise.id) ? String(localized: "Bereits hinzugefügt") : String(localized: "Nicht ausgewählt"))
        .thrymListRows()
    }
    private func filter(_ name: String, value: String?) -> some View {
        Button(name) { group = value }
            .buttonStyle(.bordered)
            .tint(group == value ? .accentColor : .secondary)
            .thrymAccentText()
    }
}
