# Third-party notices

The full list of bundled components and their license texts ships in the app at
`app/src/main/assets/licenses/README.md`, with Verovio's texts in `app/src/main/assets/verovio/`.

## Bundled in the APK

### Verovio 6.3.0

- File: `app/src/main/assets/verovio/verovio-toolkit-wasm.js`, an unmodified copy of
  `dist/verovio-toolkit-wasm.js` from the npm package `verovio@6.3.0`.
- License: GNU Lesser General Public License v3.0 or later. The texts are
  `app/src/main/assets/verovio/COPYING.LESSER` and `COPYING`.
- Authors: Laurent Pugin and others (per the npm package metadata); see the Verovio
  repository for the full list.
- Source: https://github.com/rism-digital/verovio (tag `version-6.3.0`)

Verovio is used as a separate library. It is loaded at run time from its own file and
called only through its public JavaScript API, so it can be replaced with another build.

### FluidSynth 2.5.7 and its dependencies

These are the unmodified shared libraries of the official FluidSynth Android release:
https://github.com/FluidSynth/fluidsynth/releases/tag/v2.5.7
(`fluidsynth-v2.5.7-android24.zip`, SHA-256
`ff3e4a524932c40072ca93f6d3b7c92f93da3cfc6affba65c6fbe75d40068dc8`). They are downloaded
by the `:synth` build and loaded as separate libraries. The app's own native code
(`libgrulla_synth.so`) only links to them.

| Library | License |
|---|---|
| FluidSynth | LGPL-2.1-or-later |
| GLib (libglib-2.0, libgthread-2.0) | LGPL-2.1-or-later |
| libsndfile | LGPL-2.1-or-later |
| libogg, libvorbis, libvorbisenc | BSD-3-Clause (Xiph.Org) |
| libFLAC | BSD-3-Clause (Xiph.Org) |
| Opus | BSD-3-Clause |
| PCRE 8.x | BSD-3-Clause |
| Oboe | Apache-2.0 |
| LLVM libc++ (`libc++_shared.so`, from the NDK) | Apache-2.0 WITH LLVM-exception |

### FluidR3 Mono GM SoundFont

- File: `assets/soundfonts/FluidR3Mono_GM.sf3`, downloaded by the `:app` build from
  https://github.com/musescore/MuseScore/blob/2.3.2/share/sound/FluidR3Mono_GM.sf3
  (SHA-256 `cfcd66d89e8386823400eca64934b14fbea7bf48ba1f00d21189af1262794ec2`).
- License: MIT. Copyright Frank Wen (Fluid R3), Michael Cowgill (mono version), and the
  contributors credited in `assets/licenses/FluidR3Mono-License.md`.

### Fonts

- Fraunces (© 2018 The Fraunces Project Authors) and Inter (© 2020 The Inter Project
  Authors), variable TTFs from the
  Google Fonts repository (https://github.com/google/fonts, `ofl/fraunces`, `ofl/inter`),
  in `app/src/main/assets/fonts/`, under the SIL Open Font License 1.1
  (`assets/licenses/Fraunces-OFL.txt`, `Inter-OFL.txt`).

### Icons

The icons in `app/src/main/kotlin/com/grulla/music/ui/Icons.kt` and the Grulla mark
were drawn for this project.

## Build and runtime dependencies (not vendored)

These are fetched by Gradle; see `gradle/libs.versions.toml` for the versions.

| Dependency | License |
|---|---|
| AndroidX (Compose, Activity, Lifecycle, Core, WebKit) | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| JUnit 4 (tests only) | EPL-1.0 |

## Content

- `app/src/main/assets/samples/ode-to-joy.musicxml`: the melody (Beethoven) is in the
  public domain. The piano setting was written for this project and is dedicated to the
  public domain under CC0 1.0.
- `core/src/test/resources/fixtures/piano-melody-22050.wav` (tests only): the same melody
  rendered with FluidSynth and the FluidR3 Mono SoundFont (MIT).
