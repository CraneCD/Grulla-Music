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
| M6 | Smoother on the music stand: the score view stays alive; Verovio in a Web Worker with the first page early; Bluetooth page-turn pedals and early page turns; two-page spreads on tablets, a one-row dock in landscape, tabletop posture; silent scrolling at the tempo |
| M7 | Practice coach: speed trainer; Wait for me (the accompaniment waits for you); chord checking in Follow me; practice log with trouble bars |
| M8 | Score tools: transpose and concert pitch; instrument and volume per part; annotations (pencil, fingerings, breath marks); library search, sorting, setlists and folder import |
| M9 | Release: MIT license for the app's code; English and Spanish (per-app language); TalkBack labels and headings, text up to 200 %, 48 dp targets; R8, signing config, App Bundle with ABI splits; targetSdk 37; Play listing and privacy policy (`docs/play-store`, `docs/PRIVACY.md`) |
| Find scores | Online catalogue of 13,458 free scores (OpenScore Lieder and String Quartets, CC0; public-domain PDMX selection), built by `tools/catalog`, hosted on the `catalog` branch; search, filters and download into the library |

M1–M5 and the design have been verified on a device; M6–M9 are covered by the unit,
screenshot, native and browser tests and wait for a device run.

## To publish on Google Play

1. Create an upload key and `keystore.properties` (README, "Release builds").
2. `./gradlew bundleRelease` and upload `app-release.aab` to a closed testing track.
3. Fill the listing from `docs/play-store/listing-en.md` / `listing-es.md`, with the
   screenshots in `app/screenshots/`, and publish `docs/PRIVACY.md` at a public URL.
4. Data safety form: no data collected (answers in the listing file).

## Next ideas

- **Device pass for 1.0:** Wait for me timing with FluidSynth on a few phones, chord
  sensitivity on real pianos and guitars, pedals from two brands, a foldable.
- **Wait for me without pauses:** hold inside the native engine at the exact sample, and
  sustain the accompaniment's notes while waiting.
- **Polyphonic Follow me for two hands:** follow both staves of a piano part at once,
  each at its own pace.
- **Recording:** record yourself over the accompaniment and listen back.
- **Backup:** library, setlists, annotations and practice log through Android backup, and
  an export file.
- **Native Verovio** (C++ via the NDK), if very large scores are still slow in the worker.
- **Instrument names in Spanish** (General MIDI list).
