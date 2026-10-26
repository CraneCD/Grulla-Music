# Roadmap

## Done

| Milestone | What |
|---|---|
| M1 | Verovio rendering in a WebView, MediaPlayer playback, one playback clock, highlighting, cursor, auto-scroll/page turn, count-in, tap-to-seek, tempo |
| M2 | A–B loop (long press two bars), mute/solo per part or staff, metronome (pickups, compound meters) |
| M3 | FluidSynth playback (prebuilt LGPL release, own AAudio engine): sample-accurate events, sync with the heard audio, gapless loops, SoundFont import; Android MIDI fallback |
| M4 | Library: recent scores, thumbnails, per-score settings, persistent file access |
| M5 | Follow me: microphone pitch detection (own YIN), moves on when you play the expected note; follows the parts audible in the mixer |

## Next

### Verify on devices
- Test the FluidSynth/AAudio path and the microphone path on a few phones:
  - several Android versions
  - Bluetooth output
  - 16 KB page-size devices
- Profile CPU use while listening.
- Calibrate the default listening thresholds with real instruments (piano, voice, violin, guitar).

### M6 (optional): native Verovio
- Build Verovio's C++ library with the NDK and render SVG off the main thread. This
  removes the WebView's single-threaded layout pauses on large scores, and the reload when
  you return to a score.
- Rendering options:
  - keep showing the SVG in the WebView, without the WASM step, or
  - draw it natively, e.g. with AndroidSVG (Apache-2.0)
- Verovio stays a separately built shared library, to keep the LGPL obligations simple.
- Worth doing only if profiling on real devices shows the WASM path is too slow. The bridge
  API (`load` / `highlight` / `setLayout`) was designed so the Kotlin side would barely change.

### Smaller improvements
- **Follow me:**
  - Polyphonic detection, so whole chords count rather than any one note.
  - A per-instrument sensitivity setting.
  - A "wait for me" mode that plays the other parts and waits at each step.
- **Loop:** an optional count-in before each repeat; gradual tempo increase per repeat.
- **Library:** search, sorting, folders, and importing a whole directory.
- **Performance:** keep the WebView alive when going back to the library.
- **targetSdk 37:** after testing against Android 17 behaviour changes.
- **Release builds:** R8/minify, an app bundle with per-ABI splits, and a signing config.
