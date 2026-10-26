# Grulla Music

An Android sheet music reader: open a MusicXML score, read it on screen, play it
back, and follow along while the playing notes are highlighted. It covers the
same ground as MuseScore's player, but uses no MuseScore code (see [Licensing](#licensing)).

**Status: milestone 1 (prototype).** The roadmap for later milestones is in [docs/ROADMAP.md](docs/ROADMAP.md).

## Features (milestone 1)

- Open `.mxl` (compressed) and `.musicxml` / `.xml` files with the system file
  picker, or with "Open with" from a file manager. A public-domain sample
  (*Ode to Joy*) is bundled and opens on start.
- Rendering by [Verovio](https://www.verovio.org) 6.3.0 (WebAssembly) in a WebView.
  - Continuous vertical scroll, or page view that fits the screen.
  - Pinch zoom, or the zoom buttons, re-lay out the score at the new size.
- Playback of Verovio's MIDI export through Android's built-in synthesizer:
  - play / pause / stop
  - tempo slider from 50 % to 150 %
  - tap a measure to seek there
- Guidance while playing:
  - the sounding notes are highlighted and a cursor moves over the current beat
  - the current system is kept on screen, and in page view pages turn automatically ("Follow" toggle)
  - optional one-bar count-in (wood-block clicks, accented downbeat)

### Getting scores into MusicXML

| Source | Conversion |
|---|---|
| MuseScore (`.mscz`) | `mscore -o out.mxl in.mscz` |
| PDF or scan | [Audiveris](https://github.com/Audiveris/audiveris) (optical music recognition), then check the result in a notation editor |

## Architecture

```
┌──────────────────────── app (Android) ────────────────────────┐
│ MainActivity ─ ScoreScreen (Compose: top bar, transport bar)  │
│                    │                                          │
│               ScoreViewModel ──────────────┐                  │
│                │        ▲                  │ WebCommand       │
│  PlaybackListener│      │ ScoreWebCallbacks▼                  │
│                │        │            ScoreView (WebView)      │
│                │        └──────────── GrullaHost bridge  ◄─┐  │
│                ▼                                           │  │
│   PlaybackController ◄─ awaitFrameNanos (Choreographer)    │  │
│        │ MidiPlayer                         assets/web/score.js
│        ▼                                    assets/verovio/verovio-toolkit-wasm.js
│   MediaPlayerMidiPlayer (Sonivox)                             │
└───────────────────────────────────────────────────────────────┘
┌──────────────────────── core (pure Kotlin/JVM) ───────────────┐
│ score/    MusicXmlLoader (.mxl unzip, encodings), RenderedScore│
│ timemap/  Timemap (Verovio renderToTimemap)                   │
│ midi/     MidiReader/Writer, TempoMap, PlaybackMidiBuilder     │
│ playback/ PlaybackController, PlaybackWindow, ActiveNoteIndex,│
│           HighlightTracker, PositionSmoother, MidiPlayer       │
└───────────────────────────────────────────────────────────────┘
```

`:core` has no Android dependencies. All non-UI logic lives there and is unit
tested on the JVM. `:app` adds only the Android-specific parts: the WebView, MediaPlayer,
the Choreographer frame clock and the Compose UI.

### Loading a score

1. `ScoreFiles` reads the bytes, through the Storage Access Framework or from assets.
2. `MusicXmlLoader` unpacks `.mxl` archives using `META-INF/container.xml` and
   decodes the text (BOM, declared encoding). It always produces a UTF-8 MusicXML string.
3. The WebView fetches that document from
   `https://appassets.androidplatform.net/score/<n>.musicxml`, served from memory by
   `WebViewAssetLoader`. File access stays disabled and the app needs no INTERNET permission.
4. `score.js` renders it with Verovio and sends back `{pageCount, timemap, midi}`.
   `RenderedScore` parses that payload, and the result goes to the `PlaybackController`.

### One playback clock

There is exactly one clock: the position of the MIDI player.

1. **Prepare.** On *play* (or a seek or tempo change while playing),
   `PlaybackMidiBuilder` derives the file to play from Verovio's MIDI:
   - cut at the start point, keeping instruments, controllers and the tempo there
   - every tempo event scaled by the tempo factor
   - optionally, one bar of count-in clicks put in front on GM channel 10

   The prepared file always plays from 0.
2. **Map.** The prepared file comes with a `PlaybackWindow`, which maps player
   time back to score time, the timemap's unit:
   `score = start + (player − countIn) × tempoFactor`.
3. **Tick.** `PlaybackController` reads the player position on every display frame (Choreographer).
   - `PositionSmoother` smooths the coarse MediaPlayer position.
   - The position is mapped to score time.
   - `HighlightTracker` binary-searches `ActiveNoteIndex`, a precomputed list of
     segments with constant sets of sounding notes.
4. **Show.** Only when the segment changes does the ViewModel send
   `grulla.highlight(ids, follow)` to the WebView. During the count-in it sends `grulla.countIn(beat, total)`.

Because the click is part of the same MIDI file, the count-in lines up with the
music without any cross-player latency. Re-preparing on seek or tempo change
causes a short audible gap (~100 ms with MediaPlayer). A FluidSynth backend can
remove it (see the roadmap).

### JS ⇄ Kotlin bridge

| Direction | Call | Purpose |
|---|---|---|
| Kotlin → JS | `grulla.load(url, {scale, mode})` | fetch and render a score |
| | `grulla.setLayout({scale, mode})` | zoom / switch page ⇄ continuous |
| | `grulla.highlight(ids, follow)` | mark sounding notes, move cursor, scroll or turn page |
| | `grulla.clearHighlight()`, `grulla.showPage(n)`, `grulla.countIn(beat, total)` | |
| JS → Kotlin | `GrullaHost.onReady(version)` | Verovio initialised |
| | `GrullaHost.onScoreLoaded(json)` | `{pageCount, timemap, midi (base64), source}` |
| | `GrullaHost.onMeasureTapped(id)` | seek |
| | `GrullaHost.onZoomChanged(scale)`, `onPageChanged(page, count)`, `onError(msg)` | |

Kotlin → JS calls are typed (`WebCommand`) and queued in a channel. The WebView
evaluates them in order. JS → Kotlin calls arrive on the JavaBridge thread and are
posted to the main thread.

## Building

Requirements:
- JDK 17 or newer
- Android SDK with platform `android-37.0` and build-tools `36.0.0`

Gradle 9.7.1 comes in through the wrapper. Versions are in `gradle/libs.versions.toml`:
AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.20, Compose BOM 2026.09.00.

```sh
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
./gradlew test            # unit tests (all in :core)
./gradlew lintDebug       # Android lint
adb install app/build/outputs/apk/debug/app-debug.apk
```

`compileSdk` is 37 because Compose 1.12 (BOM 2026.09) requires it. `targetSdk`
stays at 36 until the app has been tested against Android 17 behaviour changes.
Lint reports this, and it is intentional.

**Claude Code cloud sessions:** `.claude/hooks/session-start.sh` installs JDK 17
and the Android SDK into `/opt/android-sdk`, then exports `JAVA_HOME` and `ANDROID_HOME`.
The environment's network access must allow `dl.google.com`, which serves both the SDK
and Google's Maven repository.

## Testing

- **`./gradlew test`** runs the `:core` unit tests:
  - MusicXML/MXL loading
  - timemap parsing
  - the MIDI codec and tempo map
  - tempo scaling, start offsets and count-in
  - the player-time ↔ score-time window
  - the current-note lookup and the position smoother
  - the playback controller, driven in virtual time with a fake player

  The fixtures in `core/src/test/resources/fixtures/` are real Verovio 6.3.0
  output (timemap + MIDI) for `chrono-trigger-04-peaceful-days.mxl`.
- **`node tools/web-smoke-test.js [screenshotDir]`** loads the real
  `assets/web` page and bundled Verovio in headless Chromium at phone size. It checks:
  - rendering, highlight and the cursor
  - tap-to-seek
  - a two-finger pinch zoom
  - page mode with automatic page turns
  - the count-in badge and error reporting

  It needs the `playwright` npm package.

To regenerate the test fixtures after a Verovio update, load the `.mxl` with
`loadZipDataBuffer`, then write out `renderToTimemap({includeMeasures: true,
includeRests: true})` and `renderToMIDI()`, using the toolkit from
`app/src/main/assets/verovio/`.

## Known limitations (milestone 1)

- **Sound and sync quality:** Android's built-in MIDI synthesizer (Sonivox) sounds basic.
  Its position updates once per audio buffer; the controller smooths this, but sync
  is only accurate to a few tens of milliseconds.
- **Ties:** a note tied from the previous one gets highlighted at its own onset,
  because the timemap lists it, even though the MIDI does not re-strike it.
- **Repeats:** Verovio expands them in both the timemap and the MIDI. Tapping a
  measure inside a repeat seeks to its first pass.
- **Large scores:** Verovio runs on the WebView's main thread, so loading or re-laying out
  one blocks the page briefly.
- **Not yet supported:** dynamics beyond Verovio's MIDI export, swing, or instrument choice.

## Licensing

- **Verovio** (`app/src/main/assets/verovio/`) is under the LGPL-3.0-or-later. It
  is bundled unmodified as a separate, replaceable asset, with its license texts and
  a pointer to the corresponding source. See that folder's `README.md` and
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **No MuseScore source code is used.** MuseScore is only suggested as an external
  tool for converting your own files.
- **Sample score:** Beethoven's melody is in the public domain. The bundled piano
  setting is dedicated to the public domain (CC0 1.0).
- **Test fixture:** `chrono-trigger-04-peaceful-days.mxl` is a fan arrangement of
  copyrighted game music. It is used only as a test fixture and is not packaged in the app.
- **The app's own code** does not have a license yet. Add a `LICENSE` file (for
  example Apache-2.0 or MIT) before distributing it.
