import Shared
import SwiftUI

struct IOSWorkoutFinishSheet: View {
    let rows: [IOSWorkoutExerciseRow]
    let busy: Bool
    let error: String?
    let onContinue: () -> Void
    let onFinish: () -> Void
    private var remaining: Int { rows.compactMap(\.remainingPlannedSets).reduce(0, +) }
    private var planned: Int { rows.reduce(0) { $0 + $1.loggingSlots.count } }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Training beenden?").font(.geist(.title2, weight: .bold))
            Text(remaining > 0 ? String(localized: "Noch \(remaining) geplante Sätze offen. \(planned - remaining) von \(planned) absolviert.") : String(localized: "Deine bestätigten Sätze werden gespeichert."))
            ScrollView {
                VStack(spacing: 12) {
                    ForEach(rows.filter { ($0.remainingPlannedSets ?? 0) > 0 }) { row in
                        HStack { Text(row.exercise.displayName); Spacer(); Text("\(row.remainingPlannedSets ?? 0) offen").thrymSecondaryText() }
                    }
                }
            }.frame(maxHeight: 170)
            if let error { Text(error).font(.geist(.footnote)).foregroundStyle(.red) }
            Button(action: onContinue) { Text("Weitertrainieren").frame(maxWidth: .infinity).padding(.vertical, 7) }
                .buttonStyle(.borderedProminent).thrymButtonText().disabled(busy)
            Button(action: onFinish) { Text(busy ? String(localized: "Speichert …") : remaining > 0 ? String(localized: "Trotzdem beenden") : String(localized: "Training beenden")).frame(maxWidth: .infinity).padding(.vertical, 7) }
                .buttonStyle(.bordered).disabled(busy)
            Text("Nur absolvierte Sätze zählen zum Ergebnis.").font(.geist(.caption)).thrymSecondaryText()
        }.padding(24).presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
    }
}

struct IOSWorkoutCompletionScreen: View {
    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(IOSTrainingStore.self) private var store
    @Environment(\.thrymAppearance) private var appearance
    @EnvironmentObject private var settings: IOSSettingsViewModel
    let session: ILWorkoutSession
    let rows: [IOSWorkoutExerciseRow]
    let unitSystem: String
    let onClose: () -> Void
    @State private var showPlanChanges = false
    @State private var editPlan = false
    @State private var planApplied = false
    /// Records of this workout (`IOSWorkoutRecords.summary`), computed outside `body`.
    @State private var recordSummary: IOSWorkoutRecordSummary?
    /// Round marks of this workout per exercise and its facts (number, weekly goal, jubilee, comeback).
    @State private var marks: [Int64: [StrengthMark]] = [:]
    @State private var facts: CompletionFacts?
    /// The workout whose records already played the success haptic, so a rebuilt or
    /// restored screen does not celebrate twice.
    @SceneStorage("thrym.workout.recordsCelebratedSessionID") private var recordsCelebratedSessionID = 0
    private var sets: [ILWorkoutSet] { store.data?.workoutSets.filter { $0.sessionId == session.id && $0.reps > 0 } ?? [] }
    private var remaining: Int { rows.compactMap(\.remainingPlannedSets).reduce(0, +) }
    var body: some View {
        Group {
            if appearance == .liquidGlass {
                glassBody
            } else {
                emberBody
            }
        }
        // The order joins the key: a restored screen gets the stored order after its first frame.
        .task(id: "\(session.id)-\(session.endTime ?? 0)-\(sets.count)-\(rows.map(\.id).joined(separator: ","))") {
            updateRecords()
        }
    }

    private func updateRecords() {
        guard let data = store.data else {
            recordSummary = nil
            return
        }
        let summary = IOSWorkoutRecords.summary(session: session, rows: rows, data: data)
        recordSummary = summary
        marks = IOSRecognition.sessionMarks(
            sessionID: session.id,
            exercises: rows.map(\.exercise),
            data: data,
            unitSystem: unitSystem
        )
        facts = IOSRecognition.completionFacts(sessionID: session.id, sessions: data.workoutSessions, settings: settings.state)
        if summary.recordCount > 0, recordsCelebratedSessionID != Int(session.id) {
            recordsCelebratedSessionID = Int(session.id)
            let feedback = UINotificationFeedbackGenerator()
            feedback.prepare()
            feedback.notificationOccurred(.success)
        }
    }

    @ViewBuilder
    private var recordsCard: some View {
        if let recordSummary, !recordSummary.exercises.isEmpty {
            IOSWorkoutRecordsCard(summary: recordSummary, unitSystem: unitSystem, marks: marks)
        }
    }

    @ViewBuilder
    private var factsBlock: some View {
        if let facts {
            IOSCompletionFactsBlock(facts: facts)
        }
    }

    /// Liquid Glass: "Geschafft.", three lenses, the record board on tinted glass,
    /// the logged sets and "Fertig" as the bright main action.
    private var glassBody: some View {
        let volume = sets.reduce(0) { $0 + $1.weightKg * Double($1.reps) }
        let volumeText = ilVolumeText(volume.rounded(), unitSystem: unitSystem)
        let trained = rows.filter { row in row.sets.contains { $0.reps > 0 } }
        return ScrollView {
            VStack(spacing: 20) {
                VStack(spacing: 6) {
                    Text([
                        session.startDate.formatted(.dateTime.weekday(.wide).day().month(.twoDigits)),
                        session.name.isEmpty ? nil : session.name,
                        facts.map { String(localized: "Training \(Int($0.trainingNumber))") }
                    ]
                        .compactMap { $0 }.joined(separator: " · ").uppercased())
                        .font(.geist(.caption, weight: .bold))
                        .tracking(0.8)
                        .thrymSecondaryText()
                        .multilineTextAlignment(.center)
                    Text(remaining > 0 ? String(localized: "Teiltraining gespeichert") : String(localized: "Geschafft."))
                        .font(.geist(size: remaining > 0 ? 34 : 54, weight: .bold))
                        .tracking(-1.5)
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    if remaining > 0 {
                        Text("Vorzeitig beendet · \(sets.count) bestätigte Sätze")
                            .thrymSecondaryText()
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 20)

                HStack(spacing: 8) {
                    lens(session.durationSeconds < 60 ? "< 1" : "\(session.durationSeconds / 60)", unit: String(localized: "min"), label: String(localized: "Dauer"))
                    lens(String(volumeText.split(separator: " ").dropLast().joined(separator: " ")),
                         unit: String(volumeText.split(separator: " ").last ?? ""), label: String(localized: "Volumen"))
                    lens("\(trained.count)", unit: trained.count == 1 ? String(localized: "Übung (Anzahl)", comment: "Unit under the exercise count, like min and kg") : String(localized: "Übungen (Anzahl)", comment: "Unit under the exercise count, like min and kg"), label: String(localized: "Übungen"))
                }

                factsBlock
                recordsCard

                ForEach(trained) { row in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(row.exercise.displayName).font(.geist(.headline, weight: .heavy))
                        ForEach(row.sets.filter { $0.reps > 0 }) { set in
                            Text("Satz \(set.setNumber) · \(iosSetValueText(weightKg: set.weightKg, reps: set.reps, unitSystem: unitSystem, addedLoad: row.exercise.tracksAddedLoad))")
                                .font(.geist(.subheadline))
                                .monospacedDigit()
                                .thrymSecondaryText()
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .liquidGlass(in: RoundedRectangle(cornerRadius: 26, style: .continuous))
                }

                if session.planId != nil {
                    Button(planApplied ? String(localized: "Satzwerte übernommen") : String(localized: "Planänderungen prüfen")) { showPlanChanges = true }
                        .buttonStyle(.bordered).disabled(planApplied)
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 10) {
                NavigationLink { IOSWorkoutDetailScreen(sessionID: session.id) } label: {
                    Text("Trainingsdetails öffnen")
                        .font(.geist(.body, weight: .heavy))
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .liquidGlass(in: Capsule())
                }
                .buttonStyle(.plain)
                Button("Fertig", action: onClose)
                    .buttonStyle(IOSWorkoutGlassPrimaryButtonStyle())
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
        }
        .sheet(isPresented: $showPlanChanges) {
            IOSWorkoutPlanChangesSheet(sessionID: session.id, rows: rows, unitSystem: unitSystem,
                onEditor: { showPlanChanges = false; editPlan = true }, onApplied: { planApplied = true })
        }
        .navigationDestination(isPresented: $editPlan) {
            IOSPlanEditorScreen(planID: session.planId, unitSystem: unitSystem)
        }
    }

    private func lens(_ value: String, unit: String, label: String) -> some View {
        VStack(spacing: 8) {
            // A clear square sets the lens size to the full column width.
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    VStack(spacing: 0) {
                        Text(value)
                            .font(.geist(size: value.count > 5 ? 22 : 28, weight: .bold))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                        Text(unit)
                            .font(.geist(.caption, weight: .bold))
                            .thrymSecondaryText()
                            .lineLimit(1)
                    }
                    .padding(8)
                }
                .liquidGlass(in: Circle())
            Text(label.uppercased())
                .font(.geist(.caption, weight: .bold))
                .tracking(0.8)
                .thrymSecondaryText()
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(value) \(unit)")
    }

    private var emberBody: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Label(remaining > 0 ? String(localized: "Vorzeitig beendet · \(sets.count) bestätigte Sätze") : String(localized: "Training gespeichert"), systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green).padding().frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.green.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
                Text(remaining > 0 ? String(localized: "Teiltraining gespeichert") : String(localized: "Training geschafft")).font(.geist(.title, weight: .bold))
                Text([session.name.isEmpty ? nil : session.name, facts.map { String(localized: "Training \(Int($0.trainingNumber))") }]
                    .compactMap { $0 }.joined(separator: " · "))
                    .thrymSecondaryText()
                HStack(spacing: 12) {
                    metric(String(localized: "Dauer"), String(localized: "\(session.durationSeconds / 60) min"))
                    metric(String(localized: "Sätze"), "\(sets.count)")
                }
                metric(String(localized: "Volumen"), iosWorkoutDisplayWeight(kilograms: sets.reduce(0) { $0 + $1.weightKg * Double($1.reps) }, unitSystem: unitSystem))
                factsBlock
                recordsCard
                ForEach(rows.filter { !$0.sets.isEmpty }) { row in
                    VStack(alignment: .leading, spacing: 10) {
                        Text(row.exercise.displayName).font(.geist(.headline))
                        ForEach(row.sets.filter { $0.reps > 0 }) { set in
                            Text("Satz \(set.setNumber) · \(iosSetValueText(weightKg: set.weightKg, reps: set.reps, unitSystem: unitSystem, addedLoad: row.exercise.tracksAddedLoad))").font(.geist(.subheadline)).monospacedDigit()
                        }
                    }.padding().frame(maxWidth: .infinity, alignment: .leading)
                        .background(theme.palette(for: colorScheme).surface, in: RoundedRectangle(cornerRadius: 14))
                }
                if session.planId != nil {
                    Button(planApplied ? String(localized: "Satzwerte übernommen") : String(localized: "Planänderungen prüfen")) { showPlanChanges = true }
                        .buttonStyle(.bordered).disabled(planApplied)
                }
            }.padding(20)
        }
        .navigationTitle("Training gespeichert")
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig", action: onClose) } }
        .safeAreaInset(edge: .bottom) {
            NavigationLink { IOSWorkoutDetailScreen(sessionID: session.id) } label: {
                Text("Trainingsdetails öffnen").frame(maxWidth: .infinity).padding(.vertical, 7)
            }.buttonStyle(.borderedProminent).thrymButtonText().padding().background(.bar)
        }
        .sheet(isPresented: $showPlanChanges) {
            IOSWorkoutPlanChangesSheet(sessionID: session.id, rows: rows, unitSystem: unitSystem,
                onEditor: { showPlanChanges = false; editPlan = true }, onApplied: { planApplied = true })
        }
        .navigationDestination(isPresented: $editPlan) {
            IOSPlanEditorScreen(planID: session.planId, unitSystem: unitSystem)
        }
    }
    private func metric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.geist(.caption)).thrymSecondaryText()
            Text(value).font(.geist(.title2, weight: .bold).monospacedDigit())
        }.padding().frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.palette(for: colorScheme).surface, in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct IOSWorkoutPlanChangesSheet: View {
    @Environment(IOSTrainingStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let sessionID: Int64
    let rows: [IOSWorkoutExerciseRow]
    let unitSystem: String
    let onEditor: () -> Void
    let onApplied: () -> Void
    @State private var choice = 0
    @State private var busy = false
    @State private var error: String?
    private let titles = [String(localized: "Nur dieses Training"), String(localized: "Satzwerte übernehmen"), String(localized: "Plan im Editor anpassen")]
    private let subtitles = [String(localized: "Plan unverändert lassen"), String(localized: "Nur ausgeführte Sätze aktualisieren · individuelle Vorgaben, manuelle Progression"), String(localized: "Übungen und Vorgaben selbst bearbeiten")]
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Wähle bewusst").font(.geist(.title, weight: .bold))
                    Text("Das Training ist gespeichert. Was soll für das nächste Training gelten?").thrymSecondaryText()
                    ForEach(rows.filter { $0.target != nil }) { row in
                        let target = row.target!
                        let slots = target.setTargets.isEmpty ? Array(repeating: ILPlannedSet(reps: target.target.reps, weightKg: target.target.weightKg), count: min(100, max(0, target.target.sets))) : target.setTargets
                        ForEach(Array(row.matchedSlots.enumerated()), id: \.offset) { index, match in
                            if let match, slots.indices.contains(index), row.sets.indices.contains(match) {
                                let set = row.sets[match]
                                if set.reps != slots[index].reps || set.weightKg != slots[index].weightKg {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text("\(row.exercise.displayName) · Satz \(index + 1)").font(.geist(.headline))
                                        // A grid keeps the values aligned whatever the label lengths.
                                        Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                                            GridRow {
                                                Text("Plan")
                                                Text("\(iosWorkoutDisplayWeight(kilograms: slots[index].weightKg, unitSystem: unitSystem)) × \(slots[index].reps)")
                                            }
                                            GridRow {
                                                Text("Heute").thrymAccentText()
                                                Text("\(iosWorkoutDisplayWeight(kilograms: set.weightKg, unitSystem: unitSystem)) × \(set.reps)").thrymAccentText()
                                            }
                                        }
                                        .monospacedDigit()
                                    }.padding().frame(maxWidth: .infinity, alignment: .leading)
                                        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
                                }
                            }
                        }
                    }
                    ForEach(0..<3, id: \.self) { index in
                        Button { choice = index } label: {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: choice == index ? "largecircle.fill.circle" : "circle").font(.title2)
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(titles[index]).font(.geist(.headline)).foregroundStyle(.primary)
                                    Text(subtitles[index]).font(.geist(.caption)).thrymSecondaryText()
                                }
                                Spacer(minLength: 0)
                            }.padding().frame(maxWidth: .infinity, alignment: .leading)
                                .background(choice == index ? Color.accentColor.opacity(0.12) : Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
                                .overlay { RoundedRectangle(cornerRadius: 14).stroke(choice == index ? Color.accentColor : .secondary.opacity(0.3)) }
                        }.buttonStyle(.plain).disabled(busy).accessibilityValue(choice == index ? String(localized: "Ausgewählt") : String(localized: "Nicht ausgewählt"))
                    }
                    Text("Offene Übungen und Sätze bleiben im Plan.").font(.geist(.caption))
                    if let error { Text(error).foregroundStyle(.red) }
                }.padding(20)
            }
            .navigationTitle("Planänderungen").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Später") { dismiss() }.disabled(busy) } }
            .safeAreaInset(edge: .bottom) {
                Button {
                    switch choice {
                    case 0: dismiss()
                    case 2: dismiss(); onEditor()
                    default: Task {
                        busy = true
                        let success = await store.command("plan.applyPerformedSets", fields: ["sessionId": sessionID])
                        busy = false
                        if success { onApplied(); dismiss() } else { error = store.errorMessage ?? String(localized: "Plan konnte nicht geändert werden.") }
                    }
                    }
                } label: {
                    Text(busy ? String(localized: "Speichert …") : [String(localized: "Plan unverändert lassen"), String(localized: "Satzwerte übernehmen"), String(localized: "Planeditor öffnen")][choice]).frame(maxWidth: .infinity).padding(.vertical, 7)
                }.buttonStyle(.borderedProminent).thrymButtonText().disabled(busy).padding().background(.bar)
            }
            .interactiveDismissDisabled(busy)
        }
    }
}
