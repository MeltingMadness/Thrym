# Wochenziel, Meilensteine und Marken

Anerkennung ohne Punkte, Abzeichen oder Ranglisten, auf Android und iOS gleich. Thrym benennt, was im Trainingsverlauf ohnehin steht: ob das Wochenziel erreicht ist, das wievielte Training gerade endete, ob jemand nach einer Pause zurück ist und welche runde Zahl eine Übung zum ersten Mal erreicht hat. Alles wird aus den gespeicherten Trainings und Sätzen abgeleitet. Room-Schema und Backup bleiben unverändert; neu sind nur zwei Einstellungen.

Die Regeln liegen in `:shared` (`shared/src/commonMain/kotlin/com/thrym/shared/recognition/`), getestet in `WeeklyGoalEngineTest`, `TrainingMilestonesTest` und `StrengthMarksTest`. Die Plattformen rechnen nur Datumswerte um und formatieren Texte.

## Wochenziel und Serie

- **Ziel:** abgeschlossene Trainings pro Woche, 2 bis 7, Standard 3. Die Woche beginnt mit dem eingestellten Wochenstart. Ein Training zählt am Tag seines Starts.
- **Gehaltene Woche:** Ziel erreicht, oder eine Deload-Woche (mindestens ein Training mit `isDeload`), oder eine Woche, die ein Puffer abdeckt. Eine Deload-Woche hält die Serie also auch mit weniger Trainings.
- **Puffer:** Je vier erreichte oder Deload-Wochen gibt es eine Woche Puffer, höchstens zwei auf Vorrat. Eine verpasste Woche verbraucht einen Puffer, statt die Serie zu beenden; Pufferwochen bringen selbst keinen neuen Puffer. Ohne Puffer endet die Serie.
- **Laufende Woche:** Sie zählt zur Serie erst, wenn das Ziel erreicht ist. Bis Sonntag kann sie die Serie also nicht brechen.
- **Zieländerung:** gilt ab der laufenden Woche; frühere Wochen behalten ihr Ziel. Gespeichert wird die Historie der Änderungen (`WeeklyGoalRules.withChange`, Text `"0:3,20745:4"` aus Startwoche als Epochentag und Ziel). Eine leere Historie heißt Ziel 3.
- **Anzeige:** Startseite, Wochenleiste (Liquid Glass) bzw. Karte „Trainingsrhythmus“ (Ember): „2 von 3“ statt der Trainingszahl, darunter ab zwei Wochen „7 Wochen in Folge · 1 Woche Puffer“, sonst nach erreichtem Ziel „Wochenziel erreicht“. Screenreader lesen „2 von 3 Trainings diese Woche, 7 Wochen in Folge“.
- **Einstellungen:** Abschnitt „Wochenziel“ mit „Wochenziel anzeigen“ (Standard an) und der Auswahl 2 bis 7. Ausgeschaltet verschwinden Ziel, Serie und der Fakt im Abschluss; die Historie bleibt.

## Abschluss: Trainingsnummer und Fakten

- Die Datumszeile nennt die Nummer des Trainings („Dienstag, 20.10. · Push · Training 48“), gezählt nach Startzeit über alle abgeschlossenen Trainings.
- Unter den Kennzahlen steht ein Block mit höchstens zwei Fakten, in dieser Reihenfolge:
  1. **Wochenziel erreicht** – nur in dem Training, das das Ziel der Woche gerade erreicht („3 von 3 · 8 Wochen in Folge“).
  2. **Jubiläum** – „Dein 100. Training / Seit dem 14. März 2025“ beim 10., 25. und 50. Training, dann alle 50 bis 300, alle 100 bis 1000, danach alle 250.
  3. **Comeback** – „Wieder dabei / Nach 26 Tagen Pause“, wenn das vorige Training mindestens 21 Tage zurückliegt.
- Keine Animation, kein Konfetti; jede Zeile ist für Screenreader ein Satz.

## Comeback auf der Startseite

Liegt das letzte abgeschlossene Training 21 Tage oder länger zurück, kein Deload-Modus ist aktiv und kein Training läuft, zeigt die Startseite „Nach 26 Tagen Pause“ mit dem Angebot „Leichter einsteigen“. Der Knopf schaltet den Deload-Modus „halbe Sätze“ ein (`HALVE_SET_VOLUME`); die Karte verschwindet damit. Mit dem nächsten Training ist die Pause vorbei.

## Marken

Runde Zahlen, die eine Übung zum ersten Mal erreicht, nur bei Langhantel- und Eigengewichtsübungen:

| Art | Kategorie | Leiter |
|---|---|---|
| Last | `LANGHANTEL` | kg: 30 bis 100 in 10er-Schritten, dann 120 bis 300 in 20er-Schritten. lb: 65, 95, 135, 185, 225, 275, 315, 365, 405, 455, 495, 545, 585, 635 |
| Zusatzlast | `EIGENGEWICHT` | kg: +10 bis +100 in 10er-Schritten. lb: +25 bis +225 in 25er-Schritten |
| Wiederholungen | `EIGENGEWICHT` | 5, 10, 15, 20, 25, 30, 40, 50 |

- Es zählen nur Arbeitssätze (keine Aufwärmsätze). lb-Marken gelten mit einer Toleranz von 0,2 lb, weil Gewichte in kg gespeichert sind.
- Eine Marke ist neu, wenn kein Satz aus einem anderen Training sie schon erreicht hat. Das allererste Training einer Übung setzt keine Marke, weil es nichts zum Vergleichen gibt.
- **Rekordtafel im Abschluss:** Je Übung steht vor den Rekorden eine Zeile „MARKE · 100 kg · Erstmals“. Marken zählen nicht zur Zahl der Rekorde im Kopf.
- **Im Training:** Der erste Satz, der eine neue Marke erreicht, trägt den Marker „100 kg“ statt „PR“, der Übungskopf ebenso. Die Ansage für Screenreader lautet „100-kg-Marke · Kniebeuge“.
- **Feier nach „Beenden“:** Eine neue Marke ist ein Höhepunkt („100-kg-Marke“ mit der Übung) und verdrängt die Rekorde ihrer Übung. Jubiläum, Comeback und Wochenziel sind ebenfalls Höhepunkte, mit denselben Texten wie im Faktenblock.
- **Übungsstatistik:** Karte „Marken · kg“ (bzw. „+kg“, „Wdh“) mit den letzten vier erreichten Marken samt Monat und Jahr und der nächsten Marke gedimmt. Die nächste Marke steht nur hier, nie im Training.

## Umsetzung

- **Shared:** `WeeklyGoalRules`, `WeeklyGoalEngine` (`WeeklyGoal.kt`), `TrainingMilestones` mit `CompletionFacts` (`TrainingMilestones.kt`), `StrengthMarks` (`StrengthMarks.kt`). Einstellungen über `SettingsPreferencesController.updateWeeklyGoalEnabled` und `updateWeeklyGoal`.
- **Android:** `AppPreferences.weeklyGoalEnabled` und `weeklyGoalHistory` im DataStore (`weekly_goal_enabled`, `weekly_goal_history`). Die Startseite liest Startzeiten und Deload-Markierung über `WorkoutRepository.getCompletedSessionStarts()` (eine schlanke Abfrage ohne Sätze). Texte in `RecognitionText.kt` und `recognition_strings.xml` (`core/designsystem`); Ansichten `WorkoutCompletionFacts.kt`, `WorkoutCompletionRecords.kt`, `ExerciseMarksCard.kt`, `DashboardGlass.kt`/`DashboardScreen.kt`.
- **iOS:** Einstellungen in `UserDefaults` (`settings.weeklyGoalEnabled`, `settings.weeklyGoalHistory`) über `IosSettingsFeature`. Brücke und Texte in `iosApp/ThrymIOS/Recognition/IOSRecognition.swift`, Ansichten in `IOSRecognitionViews.swift`; Tests in `RecognitionTests`.

## Offen

- Eine Bilanzkarte (Monat bzw. Jahr) und ein Schritt für das Wochenziel im Onboarding.
- Vergleich mit anderen (Kraftstandards); die Form ist noch nicht entschieden.
