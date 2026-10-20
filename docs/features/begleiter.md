# Begleiter (Odin und Walküre)

Ein Begleiter als bewegte 16-Bit-Pixelfigur, auf Android und iOS gleich. Er führt durch das Onboarding, taucht unregelmäßig auf der Startseite auf und freut sich nach dem Training, bei Rekorden mit einer Feier. Es gibt zwei Figuren, beide als ganze Figur von der Seite: **Odin** (Kapuzenmantel, Fellkragen, Augenklappe mit leuchtendem Auge, langer weißer Bart, Speer Gungnir) und eine **Walküre** (Flügelhelm, Zopf, Brustpanzer, roter Umhang, Speer).

## Wahl

In den Einstellungen unter „Begleiter“: **Automatisch** (Standard), **Odin**, **Walküre** oder **Aus**. „Automatisch“ wählt die Walküre, wenn das Profil „weiblich“ sagt, sonst Odin. Solange es das Profil noch nicht gibt, ist „Automatisch“ Odin. „Aus“ blendet den Begleiter überall aus; Onboarding und Startseite zeigen dann ihre normalen Inhalte.

## Wann er erscheint

- **Onboarding:** in allen drei Schritten mit eigener Sprechblase. Im ersten Schritt stellt er sich vor, im letzten jubelt er und sagt, dass man ihn in den Einstellungen tauschen oder ausschalten kann.
- **Startseite:** als Karte unter der Begrüßung, nie während eines laufenden Trainings. Was er sagt, hängt vom Verlauf ab:
  - noch kein abgeschlossenes Training: immer, mit einer Einladung zum ersten Training;
  - letztes Training 10 Tage oder länger her: immer, mit einem freundlichen „Schön, dich wiederzusehen“;
  - heute trainiert: an etwa jedem zweiten Trainingstag, mit Lob und einem Hinweis auf Erholung;
  - sonst: an etwa jedem vierten Tag, mit einem kurzen Tipp oder Spruch.
- Welche Tage das sind und welcher Spruch kommt, ergibt sich aus dem Datum. Das wirkt zufällig, bleibt aber den ganzen Tag gleich, sodass die Karte beim Neuladen nicht kommt und geht.
- Das X auf der Karte blendet ihn für den Rest des Tages aus.
- **Trainingsabschluss:** über der Rekordtafel. Mit mindestens einem neuen Rekord springt er hoch und feiert („Neue Bestleistung! Davon werden die Skalden singen.“), sonst jubelt er über das geschaffte Training. Welcher Spruch kommt, hängt am Training und bleibt beim erneuten Öffnen gleich.
- Er macht nie Vorwürfe: keine Sprüche über verpasste Trainings, keine Benachrichtigungen.

## Darstellung

- Die Pixel liegen einmal in `:shared` (`CompanionSprites`, `shared/src/commonMain/kotlin/com/thrym/shared/companion/`): 80 × 80 Pixel je Bild, Blickrichtung rechts, eine gemeinsame Palette, jedes Bild als lauflängenkodierte Zeichenkette. Die Datei ist erzeugt: Das Rig in `tools/companion-art/` (Python, `chars.py` für die Figuren, `poses.py` für die Posen) zeichnet jedes Bild, `python3 tools/companion-art/export_kt.py shared/src/commonMain/kotlin/com/thrym/shared/companion/CompanionSprites.kt` schreibt die Datei neu. `preview.py` und `gif.py` (brauchen Pillow) zeigen alle Bilder bzw. eine Szene. Figuren nur im Rig ändern, nie in der erzeugten Datei oder einer Plattformkopie.
- Animationen (`CompanionAnimation`): `IDLE` (4 Bilder, Atmen, Umhang weht), `WALK` (6 Bilder), `ATTACK` (5 Bilder, Speerstoß mit Funken), `CHEER` (3 Bilder, Speer und Faust hoch), `CELEBRATE` (6 Bilder, Sprung mit Funken und Staub bei der Landung). Die Dauer jedes Bilds steht in `CompanionSprites.durationsMs`.
- Die Figur lebt auf einer Bühne, einem Streifen unter dem Spruch. Was sie dort tut, entscheidet `CompanionDirector` zufällig: hin und her laufen (20 Sprite-Pixel pro Sekunde, nie zweimal hintereinander), stehen, mit dem Speer üben (immer zur Seite mit Platz) oder jubeln. Beim Umdrehen wird sie um ihre Mittellinie (`ANCHOR_X`) gespiegelt.
- Ein Auftritt kann mit einer Animation beginnen: Willkommen im Onboarding und die Vorschau in den Einstellungen mit Jubel, der letzte Onboarding-Schritt und Rekorde mit der Feier. Ein Tipp auf die Bühne lässt die Figur feiern.
- Bei reduzierter Bewegung (App-Einstellung, auf iOS auch die Systemeinstellung) steht die Figur still in der Mitte, im letzten Bild der Eingangsanimation oder im Stand.
- Pixel bleiben scharf: Ein Sprite-Pixel ist immer eine ganze Zahl von Bildschirmpixeln (Android) bzw. Gerätepixeln ohne Interpolation (iOS). Unter den Füßen liegt ein weicher Schatten.
- Screenreader lesen die Karte als „Odin sagt: …“. Die Bühne selbst ist für Screenreader ausgeblendet.

## Umsetzung

- **Regeln** in `:shared` (`CompanionRules`): Auflösung der Wahl, Moment aus dem Verlauf, Auftritt und Spruch je Tag, Spruch und Animation nach dem Training. Was die Figur auf der Bühne tut, entscheidet `CompanionDirector`. Sprüche sind Codes (`CompanionLine`), die Plattformen übersetzen sie. iOS greift über `IosCompanion` (`shared/src/iosMain`) mit Namen statt Kotlin-Enums darauf zu.
- **Android:** `CompanionStage` und `CompanionCard` in `core:designsystem` (`presentation/companion/`), Texte in `companion_strings.xml`. Gespeichert wird in einem eigenen DataStore `companion` (`CompanionRepository` in `core:model`, `CompanionRepositoryImpl` in `data`). Startseite: `DashboardCompanionViewModel`, Einstellungen: `CompanionSettingsContent` mit `CompanionSettingsViewModel`, Onboarding: `OnboardingViewModel.companion`, Trainingsabschluss: `CompletionCompanion` in `feature:workout`.
- **iOS:** `IOSCompanion`, `IOSCompanionStage` und `IOSCompanionCard` in `iosApp/ThrymIOS/Companion/`; der Abschluss steht in `IOSWorkoutCompletionScreen`. Gespeichert in `UserDefaults` unter `thrym.companion.choice` und `thrym.companion.hiddenDay`.
- Wahl und Ausblend-Tag sind Gerätezustand wie die Einstellungen und nicht im Backup.

## Tests

- `:shared`: `CompanionRulesTest`, `CompanionSpritesTest`, `CompanionDirectorTest`.
- iOS: `CompanionTests` (`ThrymIOSTests`).

## Offen

- „Automatisch“ an das Profil (Geschlecht) anbinden, sobald es auf `main` ist.
- Feier beim Erreichen einer Trophäe, sobald es Trophäen gibt (`CompanionCard` mit `entrance = CELEBRATE`).
