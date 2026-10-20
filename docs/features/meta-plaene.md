# Meta-Pläne und Trainingsstart

Ein **Meta-Plan** ist eine Rotation über mehrere Trainingspläne (Teilpläne), z. B. Push → Pull → Beine. Die App schlägt jeweils den nächsten Teilplan vor.

## Training starten

Im Dialog „Training starten“ (Home) stehen **Meta-Pläne oben**, darunter die normalen Pläne. Innerhalb der Gruppen bleibt die gewohnte Sortierung.

Für jeden Meta-Plan zeigt der Dialog den vorgeschlagenen Teilplan mit der Aktion **„Überspringen“**.

## Pläne-Tab

Trainingspläne und Meta-Pläne teilen sich den Tab „Pläne“. Ein breiter Umschalter unter dem Titel wechselt zwischen beiden („Pläne“ und „Meta-Pläne“, je mit Anzahl). „+“ legt je nach Auswahl einen Trainingsplan oder einen Meta-Plan an. Der Umschalter bleibt auch sichtbar, wenn eine Art noch leer ist; dann zeigt die Liste einen Hinweis.

- **Android:** Meta-Pläne erscheinen als violette Karten mit Teilplan-Anzahl, nächstem Teilplan und letztem Training. Ein Tipp oder der Stift öffnet den Editor, der Mülleimer fragt vor dem Löschen nach. Eine eigene Meta-Plan-Seite gibt es nicht mehr.
- **iOS:** Meta-Pläne erscheinen als Listenzeilen mit Teilplan-Anzahl. Ein Tipp öffnet den Editor; „Rotation starten“, „Bearbeiten“ und „Löschen“ liegen im Menü der Zeile, Löschen auch als Wischaktion.

## Rotation

- Pro Teilplan zählt das **jüngste Ereignis**: der Start des letzten abgeschlossenen Trainings oder das letzte Überspringen.
- Vorgeschlagen wird der Teilplan mit dem **ältesten** Ereignis. Ein Teilplan ohne Ereignis kommt vor allen bereits genutzten.
- Bei Gleichstand entscheidet die Reihenfolge im Meta-Plan.

## Überspringen

- Speichert ein Rotationsereignis (`meta_plan_skips`) und wechselt sofort zum nächsten Teilplan.
- Erzeugt **kein** Training. Statistik und Verlauf bleiben unverändert.
- Bei nur einem Teilplan ist die Aktion sichtbar, aber deaktiviert.
- Die Aktion ist gegen Doppeltippen geschützt. Eine Transaktion prüft, ob der Vorschlag noch aktuell ist. Ist er veraltet, wird nichts gespeichert und die Liste neu geladen.

## Gewichtshistorie nach Kontext

Einstellung **„Gewichte zwischen Einzel- und Meta-Plänen teilen“**, Standard: **aus**.

| Einstellung | Training gestartet … | Frühere Trainings, die als Historie dienen |
|---|---|---|
| Aus | als normaler Plan | gleicher Plan, ohne Meta-Plan |
| Aus | innerhalb eines Meta-Plans | gleicher Plan im **selben** Meta-Plan |
| An | beliebig | gleicher Plan, egal in welchem Kontext |

Dieselbe Historie liefert die Gewichtshinweise im aktiven Workout und den Erfolgsindikator.

## Erfolgsindikator im Workout

Eine geplante Übung bekommt einen positiven Hinweis, wenn im letzten relevanten Training der **letzte Nicht-Aufwärmsatz** das **aktuelle** Zielgewicht und die aktuellen Ziel-Wiederholungen erreicht oder überschritten hat. Freie Workouts und Übungen ohne vollständiges Ziel zeigen keinen Indikator.

## Backup

Überspringen-Ereignisse sind Teil des Backups. Ältere Backups ohne dieses Feld importieren mit einer leeren Liste.

## Code

- Rotation und Überspringen: `MetaTrainingPlanRepository` / `MetaTrainingPlanDao`
- Startdialog: `feature/dashboard/.../PlanSelectionSheet.kt`
- Pläne-Tab mit Umschalter: `feature/plans/.../TrainingPlanListScreen.kt`, `PlansGlass.kt` (`PlansModeSwitcher`), `MetaPlanListCard.kt`; iOS `iosApp/ThrymIOS/Plans/IOSPlansScreen.swift` (`IOSPlansModeSwitcher`)
- Editor: `feature/plans/.../MetaPlanEditor*`, iOS `IOSMetaPlanEditorScreen.swift`
- Tests (Emulator): `app/src/androidTest/.../MetaPlanSkipDaoTest.kt`, `WorkoutSetDaoContextScopeTest.kt`
