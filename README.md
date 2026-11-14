# Dueo — Phase 1 visual redesign

Native Android application in the existing `com.example.dueo` project. The dashboard follows the supplied fintech reference: compact brand header, centered net balance, three action cards, horizontal frequent-person cards, connected people section, and floating four-destination navigation.

## Implemented

- Original supplied Dueo SVG paths as light, dark, monochrome, and adaptive launcher vectors; original PNG on the branded Settings screen.
- Home, searchable People, Activity, Settings, and person-ledger navigation.
- Net dues is receivables minus payables **for display only**. Separate obligations remain visible and are never automatically settled.
- Lend / Add / Borrow actions. Add exposes all four transaction types, including repayments.
- Frequent cards use actual stored people and rank by transaction count; a second obligation is displayed explicitly when both exist.
- Immediate System / Light / Dark selection, persisted with Preferences DataStore.
- Shared theme, typography, dimensions, icons, press animations, and light/dark Compose previews.
- Integer-paise arithmetic, explicit Indian digit grouping, and repayment validation.
- Versioned atomic JSON persistence. Legacy transactions migrate into saved people with stable IDs; the original v1 ledger remains untouched. No sample records are inserted into the application.

## Source map

- `ui/HomeScreen.kt`: dashboard, balance, frequent cards, person rows, previews.
- `ui/Components.kt`: brand header, vector line icons, action cards, avatars, floating navigation.
- `ui/DueoApp.kt`: app state, navigation, theme choices, People/Activity/Settings/person screens.
- `ui/TransactionComponents.kt`: existing transaction form and history rows.
- `ui/theme/`: palettes, typography, shapes, dimensions.
- `data/ThemePreferences.kt`: DataStore theme preferences.
- `data/Ledger.kt`: existing ledger storage/calculations and Indian currency formatting.
- `res/drawable/`, `res/mipmap-anydpi/`, `branding/`: faithful logo variants and source assets.
- `androidTest/`: dashboard action/theme/empty-state checks and persistent theme/navigation checks.

## Build and test

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
# Work around the installed JDK's long Windows temporary socket path:
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
# Use a test emulator for instrumented checks:
.\gradlew.bat :app:connectedDebugAndroidTest
```

Gradle, AGP, Kotlin, and Compose versions are unchanged. Added DataStore 1.2.1 for the requested theme persistence. Updated only the Android test libraries (Espresso 3.7.0 and AndroidX JUnit 1.3.0) after the original Espresso 3.5.1 failed to initialize input handling on the API 37 emulator.

## Next phases

Phase 2: shared-avatar transitions, saved-person selection, system contact/photo pickers, square cropping, custom decimal keypad, and review/confirmation are implemented.

Phase 3 remaining: Room + repository/ViewModel migration from `ledger-v2.json`, editable transaction/due dates, safe editing/deletion, and editing existing person profiles. People now have stable IDs; matching names do not combine ledgers.

Phase 4: balance/statement image sharing, FileProvider, backup/restore, optional biometric security, and further accessibility polish.

The transaction form now uses a three-step person → keypad → review flow. Export, backup, and security controls remain future work. Manrope is bundled locally in four static weights under SIL OFL 1.1; its license is included in both `branding/fonts/OFL.txt` and the APK resources. No font download is needed at runtime.

## Verification (13 November 2026)

- Debug APK assembled successfully.
- Five JVM unit tests passed (including arithmetic, repayment accounting, and Indian grouping).
- Four instrumented tests passed on the F55 API 37 emulator, including action callbacks, empty state, theme persistence, activity recreation, and navigation.
- Light and dark screens were visually inspected at the emulator's phone dimensions. Screenshots in `screenshots/` show isolated test data, not production records.
- `home-dark.png` is a direct ADB capture. Compose screenshot captures can miss animated child layers during transitions, so visual review used the full-screen capture as well.
- The debug app is installed on the emulator. Physical-device and enlarged-font checks remain advisable before release.


## Motion update

- Renamed the main summary to **Net dues**. It still represents receivables minus payables; both obligations remain separate.
- Staggered fade/slide/scale entrances, spring press feedback, expanding navigation destinations, directional page transitions, animated balance changes, palette crossfades, rotating theme icon, and animated list insertion/reordering.
- New searchable person selection, large animated amount keypad (integer-paise storage), review step, saving state, and drawn success tick.
- Animations are finite and interruptible, and Compose respects the system animator-duration scale. No perpetual decorative loops.
- `ui/Motion.kt` centralizes reusable motion; `ui/TransactionFlow.kt` contains the new flow.
- The originally linked Pinterest video was subsequently supplied as an MP4 and inspected directly. See the reference-video refinement below.

Motion-update verification: debug build succeeded, all 7 JVM tests passed, and all 6 emulator tests passed. The app was updated using `adb install -r`, retaining existing app data. Physical-device frame pacing has not been benchmarked.


## Saved people and square photos

1. Open **People → +**, or use **Add your first person** on Home.
2. Enter a required name, optional phone number, and optional photo. **Choose from contacts** uses Android's phone-contact picker; phone contacts are imported into a reviewable profile before saving.
3. Every selected photo opens a fixed **1:1 cropper**. Drag, pinch, or use the zoom slider; confirm with **Use this crop**. Cancel leaves the previous photo unchanged. Crops are 512×512 JPEGs; the preview grid is not included.
4. Save the person without needing a transaction. Then select that saved person in Lend/Borrow/Add, or open their ledger.

- No free-text person creation inside transaction entry; a valid saved person ID is required.
- Phone and photo are optional; blank/whitespace-only names cannot be saved.
- Same-name profiles have independent IDs and balances. Phone numbers help distinguish them in selection lists.
- Android system photo/contact pickers avoid broad storage/contacts permissions. Some providers may not grant contact-photo access; in that case details remain imported and the user can choose a gallery photo.
- Cropped photos are copied from staging cache into app-private files on successful profile save, and reload with the profile. Original external photos are unchanged.
- `data/People.kt`: profile model, atomic v2 state, v1 migration. `data/Photos.kt`: bounded decoding, orientation handling, crop geometry/export. `ui/PersonEditor.kt` and `ui/SquarePhotoCropper.kt`: profile and cropping interfaces.
- The UI and storage were tested on the emulator; contact-provider behavior on the physical Samsung device still needs a device check.

Person-profile verification: final debug build succeeded; all 9 JVM tests and all 12 emulator tests passed. Dashboard/navigation checks passed again after the final restoration guard. The crop screen was visually inspected. Updates used `adb install -r` to retain app data.


## Reference-video refinement — 14 November 2026

Inspected the supplied 15.12-second MP4 (720×1280, 60 fps), including its home/cards transition, recipient grid, rising amount sheet, large rounded numerals, and save confirmation. Extraction images are in `screenshots/reference/`.

- Bundled **Manrope** regular/medium/semibold/bold throughout all Material typography roles. This is a licensed visual approximation; the video does not identify its exact font. Font source: https://github.com/google/fonts/tree/main/ofl/manrope .
- Compact, left-aligned net-dues hero; dark upper section and rounded lower content panel. Smaller fractional amounts, lighter spacing, compact actions, and bright selected navigation capsule.
- Three-column person selection with photo avatars and a true shared-element avatar transition into the amount/review screens.
- One continuous transaction sheet between type selection and entry; the underlying dashboard scales back slightly. Successful save dismisses the sheet before showing confirmation.
- Refined finite 220–460 ms transitions with less overshoot, softer press feedback, and rounded keypad keys. No fake transfers, fake people, calculator operations, or bank-card functionality were introduced.
- Existing separate receivable/payable calculations, person-first persistence, cropper, and theme preferences remain.

The clip's frame rate is source metadata, not a claim that this app has been benchmarked at 60 fps on every device.

Verification: debug and instrumentation APK builds succeeded; 9 JVM tests and 13 emulator tests passed. Both dashboard tests passed again after correcting the light-theme hero text contrast. Recipient-grid, keypad, and dashboard screenshots were inspected. The updated app was installed with `adb install -r`, preserving existing data.
