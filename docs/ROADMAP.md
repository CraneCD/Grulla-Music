# Roadmap

## Done

| Milestone | What |
|---|---|
| M1 | Verovio rendering in a WebView, playback, one playback clock, highlighting, cursor, auto-scroll/page turn, count-in, tap-to-seek, tempo |
| M2 | A–B loop, mute/solo per part or staff, metronome (pickups, compound meters) |
| M3 | FluidSynth playback with its own AAudio engine: sample-accurate events, sync with the heard audio, gapless loops, SoundFont import |
| M4 | Library: recent scores, thumbnails, per-score settings, persistent file access |
| M5 | Follow me: microphone pitch detection (own YIN); the score moves on when you play the expected note |
| Design | "Crane & Ink" theme, redesigned screens, night mode setting, app icon, screenshot tests |

All of the above has been verified on a device.

## Next

### M6: Smoother on the music stand
- **Instant return to a score:** keep the WebView alive when going back to the library,
  instead of reloading Verovio.
- **Large scores:** run Verovio in a Web Worker, so layout never freezes the page, and show
  the first page while the rest lays out.
- **Page-turn pedals:** Bluetooth page turners send PageUp/PageDown or arrow keys. Map them
  to the next/previous page or system, and add a "turn on the last bar" option.
- **Tablets and landscape:** a two-page spread in page view, a wider dock layout, and
  foldable postures.
- **Hands-free scrolling while practising without playback:** an optional slow,
  tempo-based scroll.

### M7: Practice coach
- **Speed trainer:** a loop starts at a lower tempo and goes up a step every N clean
  repeats, until the target tempo.
- **Wait for me:** the app plays the other parts and waits at each of your notes. It
  combines Follow me with playback, so the accompaniment follows you.
- **Chords in Follow me:** polyphonic detection, so a chord counts only when its notes are
  heard, with a sensitivity setting per instrument.
- **Practice log:** time spent per score, loops repeated, and Follow me accuracy per bar.
  A "trouble bars" view suggests where to loop.

### M8: Score tools
- **Transpose:** both the notation (Verovio's `transpose` option) and the playback, for
  transposing instruments or singing in another key.
- **Mixer:** an instrument (General MIDI program) and a volume per part, not only mute/solo.
- **Annotations:** pencil marks, fingerings and breath marks drawn over the score, saved
  per score.
- **Library organisation:** search, sorting, folders or setlists, and importing a whole
  folder.

### M9: Release
- **License:** choose a license for the app's own code (to decide).
- **Languages:** Spanish and English, with all strings in resources.
- **Accessibility:**
  - a TalkBack pass over all screens
  - font scaling up to 200%
  - large touch targets on the dock
- **Release build:**
  - R8/minify and a signing config
  - an Android App Bundle with per-ABI splits (a smaller download than the 42 MB debug APK)
  - targetSdk 37, after testing Android 17 behaviour changes
- **Play Store:** listing, screenshots (from `app/screenshots`), and a privacy note. The
  microphone is only used in Follow me, and nothing leaves the device.

### Later / optional
- **Native Verovio** (C++ via the NDK), only if M6's worker is still too slow on real
  scores.
- **Recording:** record yourself over the accompaniment and compare.
- **Backup:** sync the library and settings through Android backup.
