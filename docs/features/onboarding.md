# Onboarding

Kurze Einführung beim ersten Start, auf Android und iOS gleich aufgebaut. Sie erklärt in drei Schritten, was Thrym ist, fragt die Gewichtseinheit ab und zeigt, wie man loslegt. Jeder Schritt lässt sich überspringen.

## Schritte

1. **Willkommen:** „Willkommen bei Thrym“ (Name aus `branding.properties`) mit drei Punkten: Daten bleiben auf dem Gerät (kein Konto, keine Cloud), Sätze schnell loggen, Fortschritt mit Rekorden, Statistik und Gewichtsvorschlag.
2. **Einheit:** Kilogramm oder Pfund als große Auswahlkarten. Die Wahl wird sofort über die normale Einstellung gespeichert (Android `AppPreferencesRepository.updateUnitSystem`, iOS `IOSSettingsViewModel.updateUnitSystem`) und lässt sich später in den Einstellungen ändern. Vorausgewählt ist der aktuelle Wert (Standard kg).
3. **Bereit:** „Los geht's“ führt zur Startseite. „Backup importieren“ beendet das Onboarding ebenfalls und öffnet die Einstellungen mit dem Dateidialog für den Backup-Import; Vorschau, Bestätigung und Sicherheitsbackup laufen wie beim Import aus den Einstellungen. Ein Hinweis erklärt den Umzug von IronLog.

Oben stehen „Zurück“ (ab Schritt 2) und „Überspringen“ (springt zu Schritt 3), unten eine Fortschrittsanzeige („Schritt 2 von 3“ für Screenreader) und „Weiter“. Bei reduzierter Bewegung wechseln die Schritte ohne Überblendung. Android: Die Zurück-Taste des Systems geht einen Schritt zurück, im ersten Schritt schließt sie die App.

## Wann es erscheint

- Nur, solange es nicht erledigt ist. „Los geht's“ und „Backup importieren“ markieren es als erledigt; wer die App vorher schließt, sieht es beim nächsten Start wieder.
- Wer schon Trainingsdaten hat (ein Training, auch ein laufendes, oder einen Plan), sieht es nie. Die App markiert es dann beim Start still als erledigt. Das betrifft Updates von Versionen ohne Onboarding.
- Der Status ist Gerätezustand wie die Einstellungen und nicht im Backup. Android speichert ihn in einem eigenen DataStore `onboarding` (`OnboardingRepository` in `core:model`, `OnboardingRepositoryImpl` in `data`), iOS in `UserDefaults` unter `thrym.onboarding.completed`.

## Umsetzung

- **Android:** Modul `feature/onboarding` mit `OnboardingScreen`, `OnboardingViewModel` und `OnboardingGate`. `MainActivity` fragt `OnboardingGate.shouldShow()` einmal ab, solange der Splash steht, und startet den Navigationsgraphen dann mit `Screen.Onboarding` statt `Screen.Dashboard`. Nach dem Abschluss wird das Onboarding aus dem Back-Stack entfernt. Der Backup-Import nutzt die Route `Screen.SettingsImport` (`settings/import`), die `SettingsScreen(startImport = true)` zeigt.
- **iOS:** `IOSOnboardingView` (`iosApp/ThrymIOS/Onboarding/`) als `fullScreenCover` über `RootView`. Die Entscheidung trifft `IOSOnboarding.shouldShow`, sobald der Trainingsgraph geladen ist. Für den Backup-Import wechselt `RootView` nach dem Schließen auf den Einstellungen-Tab und setzt `IOSSettingsViewModel.isImportingBackup`.
- Die Schritte stehen als Liste in `OnboardingStep` bzw. `IOSOnboardingStep`. Ein weiterer Schritt, etwa ein Profil (Geschlecht, Geburtsjahr) für die Trophäen und das Wochenziel, kommt dort vor `START` hinzu und nutzt die Einstellungen, die dafür angelegt werden, statt eigener Felder.

## Tests

- Android: `OnboardingGateTest` (`feature/onboarding`, Unit-Test) und `NavigationSmokeTest.onboarding_sets_unit_and_finishes_on_dashboard` (Instrumentation).
- iOS: `OnboardingTests` (`ThrymIOSTests`).
