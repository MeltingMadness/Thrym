# Marke: Logo und App-Icon

Das Logo von Thrym heißt **„Gestufter Dorn“** (englisch „Stepped Thorn“). Es ist die Rune Thurisaz **ᚦ**: „thurs“ heißt Riese, und Þ ist der erste Buchstabe von Þrymr. Gebaut ist sie aus dem Ende einer beladenen Langhantel in Seitenansicht:

- **Stab:** eine hohe Bumper-Scheibe aus dunklem Eisen,
- **Dorn:** zwei kleinere Scheiben, die auf der Stahlhülse nach rechts abstufen,
- **Spitze:** die Hülsenspitze aus Stahl.

So liest sich das Zeichen als Rune des Riesen, als aufgelegtes Eisen und als Treppe aus Scheiben, ein Bild für Progression. Es steht auf dem Amber-Verlauf der App mit einer weichen Schmiedeglut.

Die SVG-Dateien in diesem Ordner sind die **einzige Quelle** für Logo und Icon. Die Android-Drawables, die iOS-PNGs und das Store-Icon werden daraus abgeleitet; `tools/render-png.swift` erzeugt alle PNGs reproduzierbar (siehe unten). Wer das Icon ändert, ändert zuerst diese Dateien und danach beide Plattformen und das Store-Icon im selben PR.

## Dateien

| Datei | Inhalt | Verwendet in |
|---|---|---|
| [`icon.svg`](icon.svg) | App-Icon, vollflächig 1024 × 1024, opak, mit eckigen Ecken (die Maske legt das System an). Hintergrund wie `background.svg`, darauf das Zeichen 1,383-mal so groß wie in `mark.svg` (Stab 650 von 1024). | iOS-App-Icon (Standard), Store-Icon `fastlane/…/icon.png` |
| [`background.svg`](background.svg) | Amber-Verlauf `#FF8A2A` → `#E85500` von oben nach unten, darüber die Schmiedeglut (radial, `#FFB15C` mit 55 % → 0 %, Mitte knapp über der Bildmitte). Opak. | Android-Hintergrundebene `ic_launcher_background.xml` |
| [`mark.svg`](mark.svg) | Das Zeichen allein, transparent: Scheiben in Eisen, Hülse in Stahl. 1024 Einheiten = 108 dp; das Zeichen liegt im sicheren Kreis von 66 dp (Radius 313 um die Mitte, äußerster Punkt bei 263) und ist optisch zentriert. | Android-Vordergrundebene `ic_launcher_foreground.xml`, auch Splash-Icon |
| [`monochrome.svg`](monochrome.svg) | Einfarbige Fassung für Themed Icons ab Android 13. Gleiche Außenkontur wie `mark.svg`, aber die Scheiben sind links gekürzt, damit die Lücken auch bei 48 px offen bleiben; die Hülse erscheint nur als Spitze. | `ic_launcher_monochrome.xml` |
| [`icon-dark.svg`](icon-dark.svg) | iOS-Erscheinungsbild „Dunkel“ (ab iOS 18): Amber-Scheiben und heller Froststahl auf `#1A100A` → `#0C0806` mit schwacher Glut. Gleiche Geometrie wie `icon.svg`. Für das iOS-PNG wird nur das Zeichen verwendet, auf transparentem Grund; den dunklen Hintergrund legt iOS selbst darunter. Der Hintergrund in der SVG zeigt, wie das ungefähr aussieht. | iOS-App-Icon (Dunkel) |
| [`icon-tinted.svg`](icon-tinted.svg) | iOS-Erscheinungsbild „Getönt“: Graustufen auf Schwarz, das System färbt nach Helligkeit ein. Gleiche Geometrie. | iOS-App-Icon (Getönt), als Graustufen-PNG |
| [`lockup-dark.svg`](lockup-dark.svg) | Zeichen mit Amber-Scheiben und Schriftzug „THRYM“ in **dunkler** Schrift (`#1A100A`), transparenter Grund, 659 × 218. Für **helle** Hintergründe. | Kopf der `README.md` (helles Design) |
| [`lockup-light.svg`](lockup-light.svg) | Dasselbe mit **heller** Schrift (`#FFF8F0`) und hellerem Stahl. Für **dunkle** Hintergründe. | Kopf der `README.md` (dunkles Design) |
| [`lockup.svg`](lockup.svg) | Universalfassung mit Amber-Schrift (`#E65A0A`) für Stellen, an denen der Hintergrund nicht bekannt ist. Auf Weiß nur etwa 3,6 : 1 (WCAG), deshalb nur groß einsetzen. | derzeit nirgends |
| [`icon-composer/`](icon-composer/) | Drei Ebenen für ein späteres iOS-26-Icon aus Icon Composer (`.icon`): `1-background.svg` (Amber), `2-sleeve.svg` (Stahlhülse), `3-plates.svg` (Eisenscheiben), jeweils in der Geometrie von `icon.svg`. | noch nicht eingebunden |
| [`tools/render-png.swift`](tools/render-png.swift) | Hilfsskript, das aus den SVGs die PNGs für iOS und das Store-Icon erzeugt (siehe unten). | – |

Die Namen der Lockups beziehen sich auf die **Schriftfarbe**, nicht auf den Hintergrund: `lockup-dark.svg` hat dunkle Schrift und gehört auf helle Flächen.

Der Schriftzug besteht aus Pfaden (geometrische lateinische Versalien in Anlehnung an Geist Bold), nicht aus einer Schrift. Er rendert deshalb überall gleich, auch auf GitHub.

### Technische Regeln für die SVGs

- Nur Pfade, Rechtecke und lineare oder radiale Verläufe mit `gradientUnits="userSpaceOnUse"`. Keine Texte, Konturen (`stroke`), Filter, Masken, Muster, Transformationen, Bitmaps oder externen Verweise. So lassen sie sich ohne Verlust in Android-VectorDrawables umwandeln, und GitHub zeigt sie in `<img>` an.
- `icon.svg`, `icon-dark.svg`, `icon-tinted.svg` und die Ebenen in `icon-composer/` teilen dieselben Koordinaten. `mark.svg` und `monochrome.svg` sind dasselbe Zeichen im Maßstab der Android-Vordergrundebene (Faktor 1 : 1,383). Eine Formänderung betrifft also alle diese Dateien.
- Die Scheiben haben gefaste Kanten, jede Ecke ist mit einem Kreisbogen (als kubische Kurve) gerundet. Der Dorn (mittlere Scheibe) ist halb so hoch wie der Stab, damit der Stab oben und unten deutlich übersteht und das Zeichen als Rune und nicht als Play-Symbol gelesen wird.

## Wo die Plattformdateien liegen

**Android** (adaptives Icon, minSdk 26):

| Datei | Quelle |
|---|---|
| `app/src/main/res/drawable/ic_launcher_background.xml` | `background.svg` |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | `mark.svg` |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | `monochrome.svg` |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` | binden die drei Ebenen ein |

Der Splash-Screen (`Theme.Thrym.Splash` in `app/src/main/res/values/themes.xml`) zeigt `ic_launcher_foreground` auf einem Amber-Kreis (`#FF6B00`) vor `#0C0806`. Er erbt dafür von `Theme.SplashScreen.IconBackground`, sonst bliebe der Kreis weg und das dunkle Zeichen wäre unsichtbar.

**iOS:** `iosApp/ThrymIOS/Assets.xcassets/AppIcon.appiconset/` enthält drei PNGs mit 1024 × 1024 px. `Contents.json` ordnet sie über `appearances` (`luminosity` `dark` bzw. `tinted`) den Erscheinungsbildern zu; Dunkel und Getönt gibt es ab iOS 18.

| Datei | Quelle | Format |
|---|---|---|
| `AppIcon.png` | `icon.svg` | sRGB, opak, ohne Alphakanal |
| `AppIcon-Dark.png` | Zeichen aus `icon-dark.svg` | sRGB mit Alphakanal, transparenter Grund |
| `AppIcon-Tinted.png` | `icon-tinted.svg` | Graustufen (Gray Gamma 2.2), opak, ohne Alphakanal |

**Store:** `fastlane/metadata/android/en-US/images/icon.png`, 512 × 512 aus `icon.svg`, ohne Alphakanal (F-Droid).

**README:** Der Kopf der `README.md` zeigt `lockup-dark.svg` und im dunklen GitHub-Design `lockup-light.svg` über ein `<picture>`-Element.

## Dateien neu erzeugen

### PNGs (iOS und Store)

Auf macOS rendert Quick Look (`qlmanage`) die SVGs. Sein PNG hat aber immer einen Alphakanal und legt transparente Flächen auf Weiß. App Store und F-Droid verlangen Icons ohne Alphakanal, das dunkle iOS-Icon braucht dagegen einen transparenten Grund. `tools/render-png.swift` erledigt beides:

- ohne Option: sRGB-PNG ohne Alphakanal (für vollflächige Icons),
- `--gray`: Graustufen-PNG ohne Alphakanal (getöntes iOS-Icon),
- `--transparent`: entfernt die `<rect>`-Hintergründe der SVG, rendert das Zeichen auf Schwarz und auf Weiß und gewinnt daraus den Alphakanal (dunkles iOS-Icon).

Aus dem Repository-Wurzelverzeichnis:

```sh
swift docs/brand/tools/render-png.swift docs/brand/icon.svg \
  iosApp/ThrymIOS/Assets.xcassets/AppIcon.appiconset/AppIcon.png 1024
swift docs/brand/tools/render-png.swift --transparent docs/brand/icon-dark.svg \
  iosApp/ThrymIOS/Assets.xcassets/AppIcon.appiconset/AppIcon-Dark.png 1024
swift docs/brand/tools/render-png.swift --gray docs/brand/icon-tinted.svg \
  iosApp/ThrymIOS/Assets.xcassets/AppIcon.appiconset/AppIcon-Tinted.png 1024
swift docs/brand/tools/render-png.swift docs/brand/icon.svg \
  fastlane/metadata/android/en-US/images/icon.png 512
```

Bei unveränderten SVGs entstehen byteweise dieselben Dateien wie im Repository. Danach prüfen:

```sh
sips -g pixelWidth -g pixelHeight -g hasAlpha -g space <datei>.png
```

Erwartet: `hasAlpha: no` für `AppIcon.png`, `AppIcon-Tinted.png` (`space: Gray`) und das Store-Icon, `hasAlpha: yes` für `AppIcon-Dark.png`.

Für eine schnelle Vorschau reicht `qlmanage -t -s 1024 -o <ordner> <datei>.svg` (Ausgabe `<datei>.svg.png`, transparente Flächen erscheinen weiß).

### Android-Drawables

Die drei `ic_launcher_*.xml` entstehen mit dem SVG-Konverter von Android Studio (Resource Manager → „+“ → „Vector Asset“ → „Local file“), der intern `com.android.ide.common.vectordrawable.Svg2Vector` aus `com.android.tools:sdk-common` nutzt. Danach von Hand prüfen und nachziehen:

- Größe `108dp` × `108dp`, Viewport 1024 × 1024 (wie die SVGs).
- Verläufe stehen als `<aapt:attr name="android:fillColor"><gradient …>` in den Pfaden.
- Der Kommentar am Dateianfang nennt die Quelle in `docs/brand/`.
- Die monochrome Ebene hat eine einzige volle Farbe; das System färbt sie ein.

## Markenregeln

1. **Der Name steht in lateinischen Buchstaben.** Der Schriftzug ist „THRYM“ in Versalien, im Fließtext „Thrym“.
2. **Den Namen nie in Runen schreiben** und dem Zeichen keine weiteren Runen zur Seite stellen. Das Zeichen ist die einzige Rune.
3. **Keine Rune im Kreis.** Das Zeichen bekommt keinen gezeichneten Ring, Kreis oder Schild als Rahmen. Die Masken des Systems (Kreis, Squircle, der Amber-Kreis des Android-Splash) zählen nicht dazu.
4. **Keine Schwarz-Weiß-Rot-Paletten**, auch nicht als Akzent.
5. **Amber bleibt die Markenfarbe:** Verlauf `#FF8A2A` → `#E85500`, Scheiben in Eisen (`#2B1A10` → `#140B06`), Hülse in Stahl. Auf dunklem Grund werden die Scheiben Amber (wie in `icon-dark.svg` und den Lockups). Das Icon folgt nicht den Farbschemata der App.
6. **Die Form nicht verändern:** nicht drehen, spiegeln oder verzerren, keine Konturen, Schatten oder Effekte ergänzen, Abstände und Proportionen nicht ändern. Der Dorn zeigt nach rechts.
7. **Das Zeichen in Eisen (`mark.svg`) steht nur auf Amber.** Auf anderen Flächen das ganze App-Icon oder ein Lockup verwenden.
8. **Mindestgrößen:** Das App-Icon ist bis hinunter zu 29 px geprüft; bei 48 px liegt der Kontrast Eisen zu Amber bei 5,6–7,1 : 1. Ein Lockup ist mindestens 120 px breit (Versalhöhe dann etwa 18 px); kleiner nur das App-Icon ohne Schriftzug verwenden.
9. **Schutzzone:** Um ein Lockup bleiben mindestens 28 Einheiten (bezogen auf seine Höhe von 218) frei. Die Lockup-Dateien haben diesen Rand schon eingebaut, nichts direkt an ihre Kante setzen.

Die Embleme der Kapitel (`docs/features/level-und-xp.md`) sind Spielgrafik, kein Logo: Scheiben auf einer Hülse mit einem Motiv aus der Sage, ohne Runen. Ihre Ringe und Kreise rahmen deshalb kein Zeichen im Sinn von Regel 3. Sie sind KI-generiert und freigestellt; neue Motive tragen ebenfalls keine Runen.

Zu den Regeln 2 bis 4: Runen im Kreis und die Farben Schwarz-Weiß-Rot werden von rechtsextremen Gruppen verwendet. Thrym bezieht sich auf die nordische Sage und auf das Krafttraining. Diese Regeln halten Abstand zu jeder solchen Lesart.

## Lizenz

Der Code von Thrym steht unter der Apache-2.0-Lizenz. Der Name „Thrym“, das Logo und das App-Icon sind davon ausgenommen: Alle Rechte daran bleiben vorbehalten, und sie sind **nicht für Forks lizenziert**. Das gilt für die SVG-Dateien in diesem Ordner (einschließlich `icon-composer/`) und für alle daraus erzeugten Plattformdateien. Sie liegen nur im Repository, damit sich die offizielle App daraus bauen lässt. Die vollständige Liste steht in [`NOTICE`](../../NOTICE), die Begründung in der [`README.md`](../../README.md#license) im Abschnitt „License“. Ein veröffentlichter Fork braucht einen eigenen Namen, ein eigenes Icon und ein eigenes Logo in seiner README.

Ausgenommen ist das Hilfsskript `tools/render-png.swift`. Es ist Code und steht wie der übrige Code unter Apache-2.0.
