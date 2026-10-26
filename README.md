# Grulla Music

An Android sheet music reader for practising. You open a MusicXML score, play it back with
the notes highlighted, loop the hard bars, and choose which parts you hear. "Follow me"
listens to you play and moves on only when you play the right note. It covers the same
ground as MuseScore's player, but uses no MuseScore code (see [Licensing](#licensing)).

**Status:** milestones 1–5 are done. What's next is in [docs/ROADMAP.md](docs/ROADMAP.md).

## Features

- **Library.**
  - Shows recent scores with a page-1 thumbnail, composer and when you last opened them.
  - Each score remembers its own settings: zoom, layout, tempo, loop, mix, metronome and
    where you stopped.
  - Opens `.mxl`, `.musicxml` and `.xml` files through the system file picker, or with
    "Open with" from a file manager. A public-domain sample (*Ode to Joy*) is included.
- **Score view.**
  - Rendering by [Verovio](https://www.verovio.org) 6.3.0 (WebAssembly) in a WebView.
  - Continuous scroll, or page view that fits the screen.
  - Pinch zoom, or the zoom buttons.
- **Playback.**
  - **FluidSynth** with a SoundFont (FluidR3 Mono GM is the bundled default, or import any
    `.sf2`/`.sf3`). Android's built-in MIDI synthesizer is the fallback.
  - Play, pause and stop, with a tempo slider from 50 % to 150 %.
  - Tap a measure to start from there.
- **Practice tools.**
  - **A–B loop:** long-press the first bar, then the last. With FluidSynth the loop is gapless.
  - **Parts:** mute or solo each part, or each staff of a piano part (one hand at a time).
  - **Metronome** that follows the time signature (pickups and compound meters included),
    and an optional **count-in**.
- **Guidance.** The notes being played are highlighted and a cursor moves with the beat.
  The current system stays on screen, or pages turn automatically ("Follow").
- **Follow me.**
  - Uses the microphone to detect the pitch you play, and moves on only when you play the
    expected note.
  - Any note of a chord counts, octave errors are tolerated, and a missed note can be skipped.
  - It follows the parts audible in the mixer: solo your part to follow just that part.

### Getting scores into MusicXML

| Source | Conversion |
|---|---|
| MuseScore (`.mscz`) | `mscore -o out.mxl in.mscz` |
| PDF or scan | [Audiveris](https://github.com/Audiveris/audiveris) (optical music recognition), then check the result in a notation editor |

## Architecture

```
app (Android, Compose)
├─ MainActivity: Library ⇄ Score ⇄ Settings
├─ GrullaApp: app-wide services
│   ├─ AudioEngine: SwitchableMidiPlayer (FluidSynth or MediaPlayer), SoundFonts
│   └─ LibraryRepository: library.json + thumbnails
├─ ScoreViewModel: open/save a score, web callbacks, transport, loop, mixer, listening
├─ score/ScoreView: WebView + GrullaHost bridge → assets/web/score.js → assets/verovio/
├─ listen/MicrophoneListener: AudioRecord → YIN → note events
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
├─ midi/      MidiReader/Writer, TempoMap, PlaybackMidiBuilder (range, tempo, mix, clicks)
├─ playback/  PlaybackController, PlaybackWindow, ActiveNoteIndex, Mixer, MidiPlayer
├─ library/   Library, ScoreSettings
└─ listen/    YinPitchDetector, NoteEventDetector, FollowScore, FollowController
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

### JS ⇄ Kotlin bridge

| Direction | Call | Purpose |
|---|---|---|
| Kotlin → JS | `grulla.load(url, {scale, mode})` | fetch and render a score |
| | `grulla.setLayout({scale, mode})` | zoom, or switch page ⇄ continuous |
| | `grulla.highlight(ids, follow)` | mark notes, move the cursor, scroll or turn the page |
| | `grulla.setLoop(firstId, lastId)` | shade the A–B loop (null clears it) |
| | `grulla.thumbnail(width)` | render the top of page 1 to PNG |
| | `grulla.clearHighlight()`, `grulla.showPage(n)`, `grulla.countIn(beat, total)` | |
| JS → Kotlin | `GrullaHost.onReady(version)` | Verovio initialised |
| | `GrullaHost.onScoreLoaded(json)` | `{pageCount, timemap, midi (base64), source, pitches}` |
| | `GrullaHost.onMeasureTapped(id)`, `onMeasureLongPressed(id)` | seek / set the loop |
| | `GrullaHost.onZoomChanged(scale)`, `onPageChanged(page, count)`, `onThumbnail(png)`, `onError(msg)` | |

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
./gradlew test                          # JVM unit tests (:core, :synth)
./gradlew lintDebug
tools/test-synth-native.sh              # native core tests (ASan/UBSan), needs g++ (+ libfluidsynth-dev)
node tools/web-smoke-test.js [shots/]   # WebView page in headless Chromium, needs playwright
adb install app/build/outputs/apk/debug/app-debug.apk
```

**ABIs.** By default the native code is built for `arm64-v8a` and `armeabi-v7a` (phones).
For an x86_64 emulator or a Chromebook, add `-Pgrulla.abis=arm64-v8a,armeabi-v7a,x86_64`.
The native libraries are 16 KB page-aligned.

**SDK levels.** `compileSdk` is 37, which Compose 1.12 requires. `targetSdk` stays at 36
until the app has been tested against Android 17 behaviour changes; lint reports this
intentionally.

**Claude Code cloud sessions.** `.claude/hooks/session-start.sh` installs:
- JDK 17
- the Android SDK (platform, build-tools, NDK, CMake)
- `libfluidsynth-dev`

The environment's network access must allow `dl.google.com` and `github.com`.

## Testing

- **`./gradlew test`** runs the JVM unit tests. They cover:
  - loading MusicXML/MXL files and reading their parts
  - the timemap
  - the MIDI codec and tempo map
  - preparing playback: start/end, tempo, mute/solo, metronome (including pickups and 6/8), count-in
  - the playback controller (driven in virtual time with fake players, with and without seamless looping)
  - the library model
  - pitch detection, note events, and "follow me"

  The fixtures in `core/src/test/resources/fixtures/` are real Verovio 6.3.0 output. One
  listening test runs on a real piano recording (`piano-melody-22050.wav`, rendered with
  FluidSynth and the default SoundFont), with and without added noise.
- **`tools/test-synth-native.sh`** builds the native scheduler for the host, with
  AddressSanitizer and UBSan. It tests sample-accurate events, loop wrap, pause/resume, the
  position history and completion, and renders audio through the system's FluidSynth.
- **`node tools/web-smoke-test.js`** loads the real `assets/web` page and bundled Verovio
  in headless Chromium at phone size. It checks:
  - rendering, highlight and the cursor
  - tap and long press
  - loop shading
  - pitches in the payload
  - thumbnails
  - pinch zoom
  - page mode with page turns
  - the count-in
  - errors

## Known limitations

- **Not yet tested on a device:** the native audio path (AAudio + FluidSynth) and the
  microphone path. They compile, pass lint, and their logic is tested on the host and the JVM.
- **Android 8.0:** FluidSynth needs Android 8.1 or newer (AAudio); 8.0 uses Android's built-in MIDI.
- **Follow me:**
  - It is monophonic: it hears one pitch at a time.
  - Any note of a chord advances it.
  - Very fast passages, or instruments with weak fundamentals, may be missed. Tap a measure
    to reposition.
- **Ties:** a note tied from the previous one is highlighted at its own onset during playback.
- **Repeats** are expanded. Tapping a measure inside a repeat seeks to its first pass.
- **Large scores** block the WebView briefly while Verovio lays them out.
- **Returning to a score** from the library reloads Verovio, which takes a second.

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
- **The app's own code** does not have a license yet. Add a `LICENSE` file before distributing it.
