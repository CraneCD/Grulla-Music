# Grulla Music

An Android sheet music reader for practising. You open a MusicXML score, play it back with
the notes highlighted, loop the hard bars, and choose which parts you hear. "Follow me"
listens to you play and moves on only when you play the right note; "Wait for me" plays the
other parts and waits for you. It covers the same ground as MuseScore's player, but uses no
MuseScore code (see [Licensing](#licensing)). In English and Spanish.

**Status:** milestones 1–9 are done (version 1.0.0). See [docs/ROADMAP.md](docs/ROADMAP.md).

## Features

- **Library.**
  - Recent scores with a page-1 thumbnail; search (case and accents ignored) and sort by
    recency, title, composer or date added.
  - **Setlists** for lessons and concerts, in your own order.
  - Each score remembers its own settings: zoom, layout, tempo, loop, mix, transposition,
    speed trainer, metronome and where you stopped.
  - Opens `.mxl`, `.musicxml` and `.xml` files through the system file picker or "Open with",
    or **imports a whole folder** (with subfolders). A public-domain sample is included.
- **Score view.**
  - Rendering by [Verovio](https://www.verovio.org) 6.3.0 (WebAssembly) in a WebView, in a
    **Web Worker**: layout never freezes the page, and the first page shows before the rest.
  - The score view stays alive while you visit the library or settings: going back is instant.
  - Continuous scroll, or page view; **two pages side by side** on wide screens in landscape.
  - Pinch zoom, or the zoom buttons.
  - **Page-turn pedals** (Bluetooth keyboards): Page Up/Down, and arrow keys unless switched
    off. Optional **early page turn** on the last bar of the page.
  - **Annotations:** pencil, fingerings and breath marks, anchored to the notation so they
    follow zoom and re-flow; eraser and undo.
- **Playback.**
  - **FluidSynth** with a SoundFont (FluidR3 Mono GM is the bundled default, or import any
    `.sf2`/`.sf3`). Android's built-in MIDI synthesizer is the fallback.
  - Play, pause and stop, with a tempo slider from 50 % to 150 %. Tap a measure to start there.
  - **Transpose** notation and sound by semitones; **concert pitch** for transposing instruments.
  - **Silent** scrolling: the score moves at the tempo without sound, for reading hands-free.
- **Practice tools.**
  - **A–B loop:** long-press the first bar, then the last. With FluidSynth the loop is gapless.
  - **Speed trainer:** the loop starts slower and speeds up a step every few passes.
  - **Parts:** mute or solo each part or staff, change a part's **instrument** (General MIDI)
    and **volume**.
  - **Metronome** that follows the time signature (pickups and compound meters included),
    and an optional **count-in**.
  - **Practice log:** time per score, loops played, Follow me accuracy, and the **trouble
    bars** with the most wrong notes, ready to loop.
- **Guidance.** The notes being played are highlighted and a cursor moves with the beat.
  The current system stays on screen, or pages turn automatically.
- **Follow me and Wait for me.** Choose your part, then:
  - **Follow me:** the microphone detects what you play and the score moves on when you play
    the expected note. Octave errors are tolerated and a missed note can be skipped.
  - **Wait for me:** the other parts play along and wait just before each of your notes.
  - **Chords:** optionally, a chord counts only when every note is heard, with three
    sensitivities.
- **Layouts.** Phones in portrait and landscape (a one-row dock), tablets (a centred dock),
  and foldables in tabletop posture (controls below the hinge).
- **Languages and accessibility.** English and Spanish (per-app language on Android 13+).
  TalkBack labels, headings and an adjustable position slider; text up to 200 % without
  clipping; 48 dp touch targets.
- **Find scores.** Search a catalogue of free scores (OpenScore Lieder and String Quartets,
  CC0; a public-domain selection of PDMX) by title, composer, instrument and difficulty, and
  download them into the library. The catalogue is built by `tools/catalog/` and hosted on
  this repository's `catalog` branch.
- **Private.** No account, no ads, no tracking. The internet is used only to download the
  score catalogue and the scores you choose. The microphone is only used during Follow me and
  Wait for me, and nothing is recorded
  ([privacy policy](docs/PRIVACY.md)).

### Design: "Crane & Ink"

The theme is named after the red-crowned crane (*grulla*): white paper, black ink and
one red crown.
- **Crown red means sound:** the play button, the notes being played, the cursor and
  the count-in.
- **Indigo means you:** the A–B loop, selected toggles, and the notes Follow me waits for.
- **Amber means attention:** wrong notes.
- **Type:** Fraunces (an engraved-style serif) for titles and note names; Inter for controls.
- **Night mode:** a dark page with light notation. It is a setting (Follow system /
  Always day / Always night) and also restyles the score page.
- **Code:** colors and type are in `ui/Theme.kt`, the score page's styles in
  `assets/web/score.css`.
- **Screenshots:** reference screenshots of every screen are in
  [app/screenshots](app/screenshots).

### Getting scores into MusicXML

| Source | Conversion |
|---|---|
| MuseScore (`.mscz`) | `mscore -o out.mxl in.mscz` |
| PDF or scan | [Audiveris](https://github.com/Audiveris/audiveris) (optical music recognition), then check the result in a notation editor |

## Architecture

```
app (Android, Compose)
├─ MainActivity: Library ⇄ Score ⇄ Settings, pedal keys, tabletop posture
├─ GrullaApp: app-wide services
│   ├─ AudioEngine: SwitchableMidiPlayer (FluidSynth or MediaPlayer), SoundFonts
│   ├─ LibraryRepository: library.json, thumbnails, annotations, setlists, folder import
│   ├─ PracticeRepository: practice.json
│   ├─ CatalogRepository: online score catalogue (index cache, downloads → download:<id>)
│   └─ AppearanceSettings, PlayerSettings, AppLanguage
├─ ScoreViewModel: open/save a score, web callbacks, transport, loop, mixer, transpose,
│                  annotations, speed trainer, Follow me / Wait for me, practice log
├─ score/ScoreView: ScoreWebViewHost (kept WebView) + GrullaHost bridge → assets/web/
│   score.js (layout, highlight, pages, loop) · verovio-worker.js · annotate.js
├─ listen/MicrophoneListener: AudioRecord → YIN → note events; FFT → chord check
└─ playback/MediaPlayerMidiPlayer, FrameClock (Choreographer)

synth (Android library, NDK)
├─ FluidSynthMidiPlayer (MidiPlayer) → JNI → libgrulla_synth.so
│   ├─ SynthCore: sample-accurate scheduler, gapless loops, presented-position history
│   ├─ FluidSink: FluidSynth synthesizer      ┐ prebuilt, unmodified FluidSynth 2.5.7
│   └─ Engine: AAudio low-latency stream      ┘ Android release (downloaded at build time)
└─ tests: host C++ tests (tools/test-synth-native.sh), JVM tests

core (pure Kotlin/JVM, unit tested)
├─ score/     MusicXmlLoader, PartListParser, RenderedScore
├─ timemap/   Timemap (Verovio renderToTimemap)
├─ midi/      MidiReader/Writer, TempoMap, PlaybackMidiBuilder (range, tempo, mix, clicks), GeneralMidi
├─ playback/  PlaybackController (incl. holds for Wait for me), PlaybackWindow, ActiveNoteIndex, Mixer
├─ library/   Library (search, sort, setlists, import), ScoreSettings
├─ catalog/   Catalog (the online catalogue's index and its search)
├─ practice/  SpeedTrainer, PracticeLog (time, loops, trouble bars)
└─ listen/    YinPitchDetector, NoteEventDetector, ChordDetector, FollowScore, FollowController
```

### One playback clock

There is exactly one clock: the position of the MIDI player.

1. **Prepare.** When you press play, or seek, change the tempo, the loop, the mix or the
   metronome while playing, `PlaybackMidiBuilder` derives the file to play from Verovio's MIDI:
   - cut to the start point (and the loop end), keeping instruments, controllers and the tempo there
   - tempo events scaled by the tempo factor
   - notes of muted tracks dropped
   - a metronome track and/or a count-in put on GM channel 10

   The prepared file always plays from 0.
2. **Map.** Its `PlaybackWindow` maps player time back to score time, the timemap's unit:
   `score = start + (player − countIn) × tempoFactor`.
3. **Tick.** On every display frame, `PlaybackController` reads the player position and
   maps it to score time. `ActiveNoteIndex` (a binary search) then gives the sounding notes.
4. **Show.** Only changes are sent to the WebView, as `grulla.highlight(ids, follow)`.

**With FluidSynth**, the native engine reports the position of the audio actually being
*heard*, from AAudio presentation timestamps. Events are applied at their exact sample
frame, and the loop wraps inside the audio callback. So highlighting is in sync with the
sound, and loops have no gap.

**With MediaPlayer**, the position updates once per audio buffer and is smoothed. Loops
restart at A, which leaves a short gap.

### Follow me

Tied notes are onsets in Verovio's timemap but are not re-struck in its MIDI. That's how
`FollowScore` can skip them, and how it knows which part each note belongs to.

1. **Steps.** `FollowScore` turns the timemap, each note's MIDI pitch (reported by
   `score.js`) and the MIDI into steps: the places where the followed parts strike notes.
2. **Pitch.** `MicrophoneListener` reads the microphone, using the unprocessed source when
   the device has one. Every ~12 ms, `YinPitchDetector` estimates the pitch.
3. **Notes.** `NoteEventDetector` turns stable pitches into notes. Repeated notes count
   through level-based attack detection.
4. **Follow.** `FollowController` moves to the next step when a note matches.
5. **Chords** (optional). For a chord step, `ChordDetector` checks an 8192-point FFT for a
   peak at each expected note's fundamental or octave, leaving out partials that another
   chord note explains (C4's third harmonic is G5), and compares it with the loudest peak.
   It looks once the window is past the attack, so the strike's noise does not count.

**Wait for me** runs the same steps while playing the other parts:
`PlaybackController.holdAt(t)` pauses playback 80 ms before the next step of your part (the
synth renders ahead of what is heard) and resumes when you play it. Notes heard long before
the hold are taken to be the accompaniment.

### JS ⇄ Kotlin bridge

| Direction | Call | Purpose |
|---|---|---|
| Kotlin → JS | `grulla.load(url, {scale, mode}, {transpose, concertPitch})` | fetch and render a score |
| | `grulla.setLayout({scale, mode})` | zoom, or switch page ⇄ continuous |
| | `grulla.setOptions({earlyTurn})` | turn pages on the last bar |
| | `grulla.highlight(ids, follow, kind)` | mark notes (`play` or `listen`), move the cursor, scroll or turn the page |
| | `grulla.turn(±1)` | page-turn pedal: next/previous page, spread or screenful |
| | `grulla.setLoop(firstId, lastId)` | shade the A–B loop (null clears it) |
| | `grulla.setAnnotations(json)`, `annotate(tool, text)`, `undoAnnotation()` | annotations |
| | `grulla.thumbnail(width)`, `setTheme(name)`, `clearHighlight()`, `showPage(n)`, `countIn(beat, total)` | |
| JS → Kotlin | `GrullaHost.onReady(version)` | Verovio initialised (in the worker) |
| | `GrullaHost.onFirstPage()` | page 1 is on screen |
| | `GrullaHost.onScoreLoaded(json)` | `{pageCount, timemap, midi (base64), source, pitches}` |
| | `GrullaHost.onMeasureTapped(id)`, `onMeasureLongPressed(id)` | seek / set the loop |
| | `GrullaHost.onAnnotationsChanged(json)` | save the marks |
| | `GrullaHost.onZoomChanged(scale)`, `onPageChanged(page, count, spread)`, `onThumbnail(png)`, `onError(msg)` | |

Annotations are stored as `{m, s, pts}`: the measure's index in the score, the staff in the
measure, and points relative to the measure (from its first note to its barline) and the
staff (in staff spaces). Verovio's ids change on every load, so they are not used.

## Building

Requirements:
- JDK 17 or newer
- Android SDK: platform `android-37.0`, build-tools `36.0.0`, NDK `29.0.14206865` and CMake `3.31.6`

Versions are in `gradle/libs.versions.toml`: Gradle 9.7.1 (wrapper), AGP 9.4.1 with
built-in Kotlin, Kotlin 2.4.20, and Compose BOM 2026.09.00.

The build downloads two things and checks each against a pinned SHA-256:
- the FluidSynth 2.5.7 Android release, from GitHub
- the FluidR3 Mono GM SoundFont, from MuseScore's repository at tag 2.3.2

```sh
./gradlew assembleDebug                 # → app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleRelease                 # → app/build/outputs/bundle/release/app-release.aab (Play)
./gradlew assembleRelease               # → app/build/outputs/apk/release/app-release.apk
./gradlew test                          # JVM unit tests (:core, :synth, :app screenshots)
./gradlew :app:verifyRoborazziDebug     # compare the screens with app/screenshots (record: recordRoborazziDebug)
./gradlew lintDebug
tools/test-synth-native.sh              # native core tests (ASan/UBSan), needs g++ (+ libfluidsynth-dev)
node tools/web-smoke-test.js [shots/]   # WebView page in headless Chromium, needs playwright
adb install app/build/outputs/apk/debug/app-debug.apk
```

**ABIs.** By default the native code is built for `arm64-v8a` and `armeabi-v7a` (phones).
For an x86_64 emulator or a Chromebook, add `-Pgrulla.abis=arm64-v8a,armeabi-v7a,x86_64`.
The native libraries are 16 KB page-aligned.

**SDK levels.** `compileSdk` and `targetSdk` are 37 (Android 17). Of Android 17's changes
for apps targeting it, none apply: playback stops when the app leaves the screen (no
background audio), native libraries are loaded read-only from the APK, and the orientation
is never locked.

**Release builds.**
- R8 shrinks and obfuscates the code (`app/proguard-rules.pro`; the JavaScript bridge and
  JNI methods are kept).
- The App Bundle splits native libraries by ABI, so Play delivers only arm64 or armv7 to
  each phone. Languages are not split, so the in-app language switch works offline.
- **Signing:** put the upload key in `keystore.properties` at the repository root
  (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), or set `GRULLA_KEYSTORE`,
  `GRULLA_KEYSTORE_PASSWORD`, `GRULLA_KEY_ALIAS` and `GRULLA_KEY_PASSWORD`. Both are ignored by
  git. Without one, release builds are signed with the debug key, for testing only. Create an
  upload key with
  `keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload`
  and keep it (and a backup) outside the repository.
- **Debug key:** `app/debug.keystore` is committed on purpose (standard password
  `android`), so debug builds from any machine install over each other.

**Screenshot tests** (Robolectric + Roborazzi) run on Java 21, which Robolectric needs
for Android 16. Gradle uses an installed JDK 21 as a toolchain; Android Studio ships one.

**Claude Code cloud sessions.** `.claude/hooks/session-start.sh` installs:
- JDK 17
- the Android SDK (platform, build-tools, NDK, CMake)
- `libfluidsynth-dev`

- JDK 21 (the screenshot tests' runtime)

The environment's network access must allow `dl.google.com` and `github.com`.

## Testing

- **`./gradlew test`** runs the JVM unit tests (over 120). They cover:
  - loading MusicXML/MXL files and reading their parts
  - the timemap
  - the MIDI codec and tempo map
  - preparing playback: start/end, tempo, mute/solo, volume and instrument, silence, metronome
    (including pickups and 6/8), count-in
  - the playback controller (in virtual time with fake players): loops, loop passes, holds
  - the library: search, sorting, setlists, import
  - the speed trainer and the practice log
  - pitch detection, note events, chord detection and "follow me"

  The fixtures in `core/src/test/resources/fixtures/` are real Verovio 6.3.0 output. The
  listening tests run on real piano recordings rendered with FluidSynth and the default
  SoundFont: a melody (`piano-melody-22050.wav`) and chords, complete and incomplete
  (`piano-chords-22050.wav`), with and without added noise.
- **Screenshot tests** (`app/src/test/.../ScreenshotTest.kt`) render every screen and
  sheet on the JVM: day and night, Spanish, text at 200 %, a phone in landscape, a tablet
  spread and a tabletop foldable. They are compared with the references in
  `app/screenshots/`.
- **`tools/test-synth-native.sh`** builds the native scheduler for the host, with
  AddressSanitizer and UBSan. It tests sample-accurate events, loop wrap, pause/resume, the
  position history and completion, and renders audio through the system's FluidSynth.
- **`node tools/web-smoke-test.js`** loads the real `assets/web` page and bundled Verovio
  in headless Chromium. It checks:
  - Verovio in a Web Worker, and the first page before the playback data
  - rendering, highlight and the cursor; tap and long press; loop shading
  - pitches in the payload; thumbnails; pinch zoom
  - page mode with page turns, pedal turns and early turns; a tablet two-page spread
  - annotations: drawing, fingerings, anchoring after a zoom, eraser and undo
  - the count-in, night theme, and errors

## Known limitations

- **Tested on devices** up to 0.3.0 (milestones 1–5). The features of 0.4–1.0 are covered by
  the tests above, but not yet by a device run.
- **Android 8.0:** FluidSynth needs Android 8.1 or newer (AAudio); 8.0 uses Android's built-in MIDI.
- **Follow me:**
  - Single notes are detected one at a time; chords are checked against what is expected,
    not transcribed. An octave doubling inside a chord cannot be told apart.
  - Very fast passages, or instruments with weak fundamentals, may be missed. Tap a measure
    to reposition.
- **Wait for me** works best with headphones, and with FluidSynth (MediaPlayer's latency is
  higher). It pauses the accompaniment while waiting, so held notes stop.
- **Ties:** a note tied from the previous one is highlighted at its own onset during playback.
- **Repeats** are expanded. Tapping a measure inside a repeat seeks to its first pass.
- **Instrument names** (General MIDI) are in English in both languages.
- **Per-app language** needs Android 13; older versions follow the phone's language.

## Licensing

- **Verovio** (`assets/verovio/`): LGPL-3.0-or-later. It is bundled unmodified as a
  separate, replaceable asset.
- **FluidSynth 2.5.7** and the libraries it needs:
  - FluidSynth, GLib and libsndfile are LGPL-2.1-or-later.
  - libogg, libvorbis, FLAC, Opus and PCRE are BSD-licensed; Oboe is Apache-2.0.
  - They are the official prebuilt shared libraries, unmodified and loaded as separate
    libraries.
- **FluidR3 Mono GM SoundFont:** MIT.
- **No MuseScore source code is used.** MuseScore is only suggested as an external converter.
  The SoundFont is downloaded from MuseScore's repository, but it is MIT-licensed data, not code.
- **No GPL code is used.** The pitch detector is our own implementation of the published
  YIN algorithm; TarsosDSP (GPL) is not used.
- **License texts:** they ship in the APK under `assets/licenses/` and `assets/verovio/`.
  See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **Sample score:** Beethoven's melody is in the public domain. The bundled piano setting is
  dedicated to the public domain (CC0 1.0).
- **Test fixture:** `chrono-trigger-04-peaceful-days.mxl` is a fan arrangement of copyrighted
  game music. It is used only as a test fixture and is not packaged in the app.
- **The app's own code** is under the [MIT License](LICENSE). This covers the code and
  assets written for this project, not the third-party components above (each keeps its own
  license) and not the test fixture `chrono-trigger-04-peaceful-days.mxl`.
