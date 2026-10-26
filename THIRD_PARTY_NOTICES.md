# Third-party notices

## Bundled in the APK

### Verovio 6.3.0

- File: `app/src/main/assets/verovio/verovio-toolkit-wasm.js` (unmodified copy of
  `dist/verovio-toolkit-wasm.js` from the npm package `verovio@6.3.0`)
- License: GNU Lesser General Public License v3.0 or later. The texts are in
  `app/src/main/assets/verovio/COPYING.LESSER` and `COPYING`, and ship inside the APK.
- Authors: Laurent Pugin and others (per the npm package metadata); see the Verovio repository for the full list
- Source: https://github.com/rism-digital/verovio (tag `version-6.3.0`)

Verovio is used as a separate library. It is loaded at run time from its own file
and called only through its public JavaScript API, so users can replace it with
their own build.

### Material icon path data

The play, pause, stop, folder, zoom, layout and chevron icons in
`app/src/main/kotlin/com/grulla/music/ui/Icons.kt` use path data from Google's
Material Icons, under the Apache License 2.0.

## Build and runtime dependencies (not vendored)

These are fetched by Gradle; see `gradle/libs.versions.toml` for the versions.

| Dependency | License |
|---|---|
| AndroidX (Compose, Activity, Lifecycle, Core, WebKit) | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| JUnit 4 (tests only) | EPL-1.0 |

## Content

- `app/src/main/assets/samples/ode-to-joy.musicxml`: the melody (Beethoven) is in the
  public domain. The piano setting was written for this project and is dedicated
  to the public domain under CC0 1.0.
