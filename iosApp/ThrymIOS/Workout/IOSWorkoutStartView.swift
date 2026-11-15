import SwiftUI

/// The idle training tab: the saved plans first, a free workout as the second way in.
struct IOSWorkoutStartView: View {
    let plans: [ILTrainingPlan]
    let onStartFree: (String) -> Void
    let onStartPlan: (ILTrainingPlan) -> Void
    let onCreatePlan: () -> Void

    @State private var freeWorkoutName = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                ThrymCard(title: String(localized: "Trainingspläne"), subtitle: plans.isEmpty ? String(localized: "Noch kein Plan angelegt.") : nil) {
                    if plans.isEmpty {
                        Button(action: onCreatePlan) {
                            Label("Plan anlegen", systemImage: "plus")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityHint("Wechselt zu den Plänen")
                    } else {
                        ForEach(plans) { plan in
                            Button {
                                onStartPlan(plan)
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "play.fill")
                                        .foregroundStyle(.tint)
                                    Text(plan.name)
                                        .font(.geist(.body, weight: .semibold))
                                        .foregroundStyle(.primary)
                                    Spacer()
                                }
                                .frame(minHeight: 44)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("Plan \(plan.name) starten"))
                        }
                    }
                }

                ThrymCard(title: String(localized: "Freies Training"), subtitle: String(localized: "Übungen fügst du während des Trainings hinzu.")) {
                    TextField("Name (optional)", text: $freeWorkoutName)
                        .textInputAutocapitalization(.sentences)
                        .accessibilityLabel("Name des freien Trainings")

                    let start = Button {
                        onStartFree(freeWorkoutName.trimmingCharacters(in: .whitespacesAndNewlines))
                        freeWorkoutName = ""
                    } label: {
                        Label("Freies Training starten", systemImage: "play.fill")
                            .frame(maxWidth: .infinity)
                    }
                    // Without a plan the free workout is the only way to start, so it leads.
                    if plans.isEmpty {
                        start.buttonStyle(.borderedProminent).thrymButtonText()
                    } else {
                        start.buttonStyle(.bordered)
                    }
                }
            }
            .padding()
        }
    }
}
