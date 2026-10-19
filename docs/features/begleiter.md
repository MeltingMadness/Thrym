# Begleiter (Odin und Walküre)

Ein kleiner Begleiter in 16-Bit-Pixeloptik, auf Android und iOS gleich. Er führt durch das Onboarding und taucht unregelmäßig auf der Startseite auf. Es gibt zwei Figuren: **Odin** (Flügelhelm, Augenklappe, Bart, Speer Gungnir) und eine **Walküre** (Flügelhelm, Zöpfe, Schild, Speer).

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
- Er macht nie Vorwürfe: keine Sprüche über verpasste Trainings, keine Benachrichtigungen.

## Darstellung

- Die Pixel liegen einmal in `:shared` (`CompanionSprites`, `shared/src/commonMain/kotlin/com/thrym/shared/companion/`): 32 × 32 Pixel je Bild, ein Zeichen je Pixel, eine Palette. Je Figur gibt es drei Bilder: `IDLE`, `BLINK`, `CHEER`. Änderungen an der Figur nur dort.
- Im Ruhezustand atmet er (ein Pixel auf und ab) und blinzelt alle paar Sekunden. Ein Tipp auf ihn lässt ihn jubeln und hüpfen. Bei reduzierter Bewegung (App-Einstellung, auf iOS auch die Systemeinstellung) steht er still und wechselt nur das Bild.
- Pixel bleiben scharf: Ein Pixel der Figur ist immer eine ganze Zahl von Bildschirmpixeln (Android) bzw. Punkten ohne Interpolation (iOS).
- Screenreader lesen die Sprechblase als „Odin sagt: …“. Die Figur selbst ist neben der Sprechblase ausgeblendet.

## Umsetzung

- **Regeln** in `:shared` (`CompanionRules`): Auflösung der Wahl, Moment aus dem Verlauf, Auftritt und Spruch je Tag. Sprüche sind Codes (`CompanionLine`), die Plattformen übersetzen sie. iOS greift über `IosCompanion` (`shared/src/iosMain`) mit Namen statt Kotlin-Enums darauf zu.
- **Android:** `CompanionSprite` und `CompanionCard` in `core:designsystem` (`presentation/companion/`), Texte in `companion_strings.xml`. Gespeichert wird in einem eigenen DataStore `companion` (`CompanionRepository` in `core:model`, `CompanionRepositoryImpl` in `data`). Startseite: `DashboardCompanionViewModel`, Einstellungen: `CompanionSettingsContent` mit `CompanionSettingsViewModel`, Onboarding: `OnboardingViewModel.companion`.
- **iOS:** `IOSCompanion`, `IOSCompanionSprite` und `IOSCompanionCard` in `iosApp/ThrymIOS/Companion/`. Gespeichert in `UserDefaults` unter `thrym.companion.choice` und `thrym.companion.hiddenDay`.
- Wahl und Ausblend-Tag sind Gerätezustand wie die Einstellungen und nicht im Backup.

## Tests

- `:shared`: `CompanionRulesTest`, `CompanionSpritesTest`.
- iOS: `CompanionTests` (`ThrymIOSTests`).

## Offen

- „Automatisch“ an das Profil (Geschlecht) anbinden, sobald es auf `main` ist.
- Auftritt beim Erreichen einer Trophäe, sobald es Trophäen gibt.
