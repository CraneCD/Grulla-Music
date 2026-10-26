# Roadmap

Milestone 1 (done) is a prototype: Verovio in a WebView, MediaPlayer playback, a
single playback clock, highlighting, a count-in and tap-to-seek. Below is the
plan for the later milestones. None of it is built yet.

## M2: Practice tools

- **A–B loop.**
  - Selecting the loop: long-press a start measure and then an end measure in
    `score.js`. JS reports the ids, and Kotlin resolves them to times with
    `Timemap.measureStartMs`, plus the next measure's start for the end point.
  - Playback: `PlaybackMidiBuilder` gets an `endMs`, and drops events after it.
    `PlaybackController` restarts the run at A when the player completes, with or
    without a count-in.
  - With FluidSynth (M3) the loop can be gapless.
- **Mute / solo parts.**
  - Verovio writes one MIDI track per staff or part. `PlaybackMidiBuilder` gets a
    per-track volume, and muting means dropping that track's note-ons.
  - Part names come from the MusicXML part-list. A small parser in `:core`
    (`score/PartList.kt`) maps parts to MIDI tracks.
  - Settings are kept per score (see M4).
- **Metronome.** A generalisation of the count-in. `CountIn` becomes a
  `ClickTrack(countInBars, metronome: Boolean)`. The metronome emits a click on
  every beat across the whole file, following the time signatures (FF 58 events)
  and Verovio's measure boundaries. Accent pattern options include compound meters.

## M3: FluidSynth playback

- Add a `:synth` Android library module with the NDK.
  - FluidSynth (LGPL-2.1+) is built as a shared library, so it stays separate and
    replaceable. It outputs through Oboe (Apache-2.0).
- `FluidSynthMidiPlayer` implements the existing `MidiPlayer` interface:
  - `load()` hands it the prepared SMF bytes
  - the FluidSynth sequencer schedules them
  - `positionMs` is computed from the number of audio frames rendered, which gives
    sample-accurate sync with the highlight and no position smoothing
- SoundFont: let the user pick an `.sf2`/`.sf3` (MuseScore_General is MIT;
  FluidR3_GM is MIT). Also offer a small bundled or downloadable default.
- This removes the restart gap on tempo change and seek. Tempo becomes a live
  sequencer setting, and seek a sequencer jump.
- Keep `MediaPlayerMidiPlayer` as a fallback for devices without a SoundFont.

## M4: Library

- A library screen with recent files.
  - Store the SAF URIs with `takePersistableUriPermission`, together with the
    title and composer read from the MusicXML.
  - Optionally, an app folder.
- Per-score settings in Room or DataStore:
  - zoom and layout
  - tempo, count-in and loop range
  - mute/solo state
  - the last position
- Render thumbnails of page 1 with Verovio (SVG → bitmap) in the background.

## M5: "Follow me" (listen and advance)

- Microphone input (RECORD_AUDIO) with pitch detection using TarsosDSP (GPL-3.0).
  - **License caution:** TarsosDSP is GPL, which would make the whole app GPL. Prefer
    a permissive detector instead:
    - a small YIN/MPM implementation in `:core`, or
    - `aubio` (GPL as well), or
    - Oboe input with our own YIN.
  - Decide this before starting.
- The expected notes come from `ActiveNoteIndex`, as MIDI pitches per segment.
  They are obtained from the timemap ids through Verovio's
  `getMIDIValuesForElement`, or by matching MIDI note-ons to segments.
- A new `FollowController` advances the cursor segment by segment when the
  detected pitch matches the expected pitch (or one of the chord's notes). It
  reuses the highlight/scroll path. Tempo is then driven by the player rather
  than a clock.
- Monophonic first. Chords and polyphonic input are research items.

## M6 (optional): native Verovio

- Build Verovio's C++ library with the NDK and render SVG off the main thread.
  This removes the WebView's single-threaded layout pauses on large scores.
- Rendering options:
  - keep showing the SVG in the WebView, without the WASM step, or
  - draw it natively, e.g. with AndroidSVG (Apache-2.0)
- Verovio stays a separately built shared library, to keep the LGPL obligations simple.
- Do this only if profiling on real devices shows the WASM path is too slow.
  The bridge API (`load` / `highlight` / `setLayout`) was designed so the Kotlin
  side would barely change.
