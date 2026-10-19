<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/lockup-light.svg">
    <img src="docs/brand/lockup-dark.svg" alt="Thrym" width="300">
  </picture>
</h1>

Thrym is a strength training log for Android and iPhone that works completely offline. You plan your training, log sets quickly at the gym, and a progression coach suggests your next targets. You decide whether to accept them. Your data stays on your device: there is no account, no server, no ads and no tracking. The app is available in English and German. Both apps are native: Android uses Kotlin and Jetpack Compose, iOS uses SwiftUI, and the training logic shared between them lives in a common Kotlin Multiplatform core.

Thrym was previously called **IronLog**. See [Migrating from IronLog](#migrating-from-ironlog).

| | |
|---|---|
| **Android** | Android 8.0 or later (API 26), target API 35 |
| **iOS** | iPhone with iOS 17 or later |
| **Languages** | English (default) and German. The app follows the device language. On Android 13 or later you can also set the language for Thrym alone in the system settings. |
| **Status** | In active development. There are no prebuilt downloads yet, so for now you build the app from source. Distribution through F-Droid, GitHub Releases and the App Store is planned. |
| **License** | [Apache License 2.0](LICENSE) for the source code. The name, logo and icon are not covered, see [License](#license). |

## Features

| Area | What the app does |
|---|---|
| **First start** | A short introduction that also asks for the weight unit (kg/lb); skipped automatically if training data already exists |
| **Companion** | Odin or a valkyrie in 16-bit pixel art guides you through the introduction and drops by on the home screen now and then with a line that fits your history; it never scolds you. Choose or switch it off in the settings |
| **Home** | Start a workout (from a plan, a meta-plan or as a free session), daily readiness check-in and training readiness, deload hint and deload mode (half the sets or −15 % weight), weekly volume per muscle group (MEV/MAV), 8-week volume trend, notice of pending progression suggestions |
| **Active workout** | Direct logging with prefilled values (weight, reps), expandable extra fields (RPE/RIR, set type, set intention, plate calculator), set types normal/warm-up/drop/failure, rest timer, supersets, moving exercises and supersets up or down during the session, multi-select when adding exercises, live detection of personal records with the gain over your previous best, a finish button at the end of the list once every planned set is logged, finishing early with open sets, and a summary with a records board |
| **Plans** | Training plans with targets and optional individual set targets per exercise, meta-plans (a rotation through several plans, including "skip"), a progression scheme per plan exercise |
| **Progression coach** | After the workout: suggestions for new targets, which are applied to the plan only after you confirm them |
| **History** | All workouts (paged) with search and filters by time period and plan, a detail view to correct or delete single sets and add a workout note, swipe to delete |
| **Exercises** | Exercise library with search and muscle filter, training figures per exercise (sessions, last trained), custom exercises, archiving, statistics per exercise (records, estimated 1RM, progress chart) |
| **Settings** | Units (kg/lb), first day of the week, appearance (Liquid Glass or Ember style, 7 color schemes, light/dark/system, Dynamic Color), reduced motion, companion, RPE/RIR/off, rest timer, plates and bar weight, workout reminders, backup export/import with an optional reminder, internal safety backup, incident report |

The table describes the Android app, which is the functional reference. The iOS app covers the same areas. Its current state and what has been verified on iOS are tracked in [`docs/ios-feature-parity.md`](docs/ios-feature-parity.md) (German). Some features depend on the platform, for example Dynamic Color, which needs Android 12 or later.

## Privacy

- **Data stays on the device.** Workouts and plans are stored locally: on Android in a Room/SQLite database, on iOS in the app's Application Support folder. Settings are kept on the device as well. Thrym has no server and no cloud sync, and it includes no analytics, advertising or crash-reporting SDKs.
- **No internet permission on Android.** The app does not request `android.permission.INTERNET`. The merged manifest does contain `ACCESS_NETWORK_STATE`, which WorkManager (used for reminders) adds. That permission only lets the app check whether a connection exists. It cannot send or receive any data.
- **No automatic Android cloud backup.** Android's system backup and device-to-device transfer are turned off for Thrym's data. On iOS, the app's data is included in your regular device backup (iCloud or computer) if you have one turned on, like any other app's data.
- **Backups are files you control.** A backup is a local JSON file. It is only created when you export one, and you choose where it goes (the file picker or share sheet). It contains your full training history unencrypted, so keep it somewhere safe. Before every import, the app creates an internal safety backup on the device.
- **Incident reports are opt-in.** An incident report is a diagnostics file with the app version, device model, OS version, the current screen and the error details. It is only created when you ask for one, and it only leaves the device if you share it yourself.

## Migrating from IronLog

Thrym has new app IDs (Android `com.thrym.app`, iOS `com.thrym.ios`). It therefore installs as a **new, separate app** and starts with no data, and IronLog is not updated in place. To bring your data over:

1. In IronLog, open **Settings** and **export a backup**. Save the file somewhere you can find it again.
2. Install Thrym, open **Settings → Import backup** and select the file. The import **replaces all workout data** in Thrym, so do it before you log new workouts.
3. Set your preferences again. App settings are **not part of the backup**, for example units, first day of the week, appearance, RPE/RIR, rest timer, plates and bar weight, reminders and the backup reminder.
4. Once you have checked that everything is there, you can uninstall IronLog. The backup file holds your complete training history: keep it in a safe place or delete it.

Every IronLog backup (backup format version 1, schema version up to 15) can be imported into Thrym, on both Android and iOS. A backup that is invalid or comes from a newer app version is rejected with a message, and your data is not changed.

## Building from source

**Android:** you need JDK 17 and an Android SDK with platforms 35 and 37.0 and build-tools 36.0.0. Point Gradle to the SDK with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=...`.

```bash
./gradlew assembleDebug                      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                       # build and install on a connected device
./gradlew test :shared:testAndroidHostTest   # unit tests (`test` alone skips the shared KMP tests)
./gradlew lintDebug                          # lint
```

**iOS:** you need a Mac with Xcode 26, Python 3 and JDK 17. A build phase in the Xcode project runs Gradle to build the shared Kotlin framework.

```bash
python3 iosApp/generate_project.py           # generate iosApp/ThrymIOS.xcodeproj
xcodebuild test -project iosApp/ThrymIOS.xcodeproj -scheme ThrymIOS \
  -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO
```

Simulator builds work without signing. To run Thrym on your own iPhone, set your Apple team and a bundle ID of your own in a local, untracked config file. [`CONTRIBUTING.md`](CONTRIBUTING.md) describes this, along with prerequisites, tests and CI checks, in detail.

**Display name:** the visible app name is defined in one place only: `appDisplayName` in [`branding.properties`](branding.properties). Android reads it at build time. On iOS, run `python3 iosApp/generate_project.py` after changing it.

## Project layout

```
app/                 Android entry point: MainActivity, dependency injection (Koin), navigation
core/
  model/             Domain models and repository interfaces
  common/            Logic without UI: formatting, AppLogger, progression engine, deload, plates
  database/          Room entities, DAOs, migrations, exported schemas (core/database/schemas/)
  designsystem/      Theme, design tokens, shared Compose components, string resources, fonts
data/                Repository implementations, settings (DataStore), backup, reminders, incident reports
feature/             Android screens: dashboard, workout, plans, progression, history,
                     exercises, statistics, settings, onboarding
shared/              Kotlin Multiplatform core for Android and iOS: backup format and validator,
                     progression rules, analytics, readiness, plates, and the training store used by iOS
iosApp/              Native SwiftUI app (ThrymIOS), its tests (ThrymIOSTests) and the project generator
```

The Android data layer uses Room. The iOS app stores the shared training graph as a JSON file and uses the same rules from `:shared`. [`docs/architektur.md`](docs/architektur.md) has more on this.

## Documentation

The internal design and engineering documents in `docs/` are written in **German**.

| File | Contents |
|---|---|
| [`docs/architektur.md`](docs/architektur.md) | Modules, layers, database schema, backup format, invariants |
| [`docs/design-system.md`](docs/design-system.md) | Current design (Liquid Glass, with Ember as an option): color schemes, typography, components |
| [`docs/brand/README.md`](docs/brand/README.md) | Logo and app icon: source files, where they are used, brand rules |
| [`docs/features/progressions-coach.md`](docs/features/progressions-coach.md) | Rules and flow of the progression coach |
| [`docs/features/meta-plaene.md`](docs/features/meta-plaene.md) | Meta-plan rotation, skipping, weight history |
| [`docs/features/onboarding.md`](docs/features/onboarding.md) | First-start introduction: steps, when it appears, implementation |
| [`docs/features/begleiter.md`](docs/features/begleiter.md) | Companion (Odin or a valkyrie): when it appears, pixel art, implementation |
| [`docs/readiness-data-integration.md`](docs/readiness-data-integration.md) | Daily check-in and set intention: data model and integration |
| [`docs/ios-feature-parity.md`](docs/ios-feature-parity.md) | Feature status and acceptance of the iOS app |
| [`iosApp/README.md`](iosApp/README.md) | Generating the iOS project, native checks |
| [`docs/quality-gates.md`](docs/quality-gates.md) | Pull request gates and service levels |
| [`docs/release-envelope.md`](docs/release-envelope.md) | Release chain, signing, distribution |
| [`docs/logging-guideline.md`](docs/logging-guideline.md) | Logging rules |
| [`CHANGELOG.md`](CHANGELOG.md) | Changes per version |

`docs/validation/`, `docs/design/` and `docs/research/` contain dated, historical records from the IronLog era: test reports, design comparisons and background research. They still use the old name and are kept as they were written. They do not describe the current state.

## Contributing

Bug reports, translations and pull requests are welcome. Please read [`CONTRIBUTING.md`](CONTRIBUTING.md) first, and follow the [Code of Conduct](CODE_OF_CONDUCT.md). Please report security issues privately as described in [`SECURITY.md`](SECURITY.md), not in a public issue. If you work with an AI coding agent, [`AGENTS.md`](AGENTS.md) has notes for it.

## License

The source code of Thrym is licensed under the [Apache License, Version 2.0](LICENSE). Attribution notices are in [`NOTICE`](NOTICE). The bundled Geist font is licensed under the [SIL Open Font License 1.1](docs/licenses/Geist-OFL.txt).

**Name and icon.** The Apache License covers the code, not the brand. As section 6 of the license says, it grants no permission to use the name "Thrym". The Thrym logo and app icon, which are the SVG sources in [`docs/brand/`](docs/brand/), the launcher icon files in `app/src/main/res/` and `iosApp/ThrymIOS/Assets.xcassets/AppIcon.appiconset/` and the store icon `fastlane/metadata/android/en-US/images/icon.png` (all listed in [`NOTICE`](NOTICE)), are not licensed for use by forks either. They are in this repository so that the official app can be built from it. If you publish a fork, give it its own name, its own icon and logo, its own Android `applicationId` and its own iOS bundle ID. Saying that your fork is based on Thrym is fine.
