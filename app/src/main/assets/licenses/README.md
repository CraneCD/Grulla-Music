# Licenses of bundled third-party components

| Component | Where | License | Text |
|---|---|---|---|
| Verovio 6.3.0 | `assets/verovio/` | LGPL-3.0-or-later | `verovio/COPYING.LESSER`, `verovio/COPYING` |
| FluidSynth 2.5.7 (official Android build) | `lib/<abi>/libfluidsynth.so` | LGPL-2.1-or-later | `LGPL-2.1.txt` |
| GLib (libglib-2.0, libgthread-2.0) | `lib/<abi>/` | LGPL-2.1-or-later | `LGPL-2.1.txt` |
| libsndfile | `lib/<abi>/libsndfile.so` | LGPL-2.1-or-later | `LGPL-2.1.txt` |
| libogg | `lib/<abi>/libogg.so` | BSD-3-Clause | `ogg-COPYING.txt` |
| libvorbis, libvorbisenc | `lib/<abi>/` | BSD-3-Clause | `vorbis-COPYING.txt` |
| libFLAC | `lib/<abi>/libFLAC.so` | BSD-3-Clause (Xiph) | `flac-COPYING.Xiph.txt` |
| Opus | `lib/<abi>/libopus.so` | BSD-3-Clause | `opus-COPYING.txt` |
| PCRE 8.x | `lib/<abi>/libpcre.so` | BSD-3-Clause | `pcre-LICENCE.txt` |
| Oboe | `lib/<abi>/liboboe.so` | Apache-2.0 | `oboe-LICENSE.txt` |
| FluidR3 Mono GM SoundFont | `assets/soundfonts/FluidR3Mono_GM.sf3` (downloaded at build time) | MIT | `FluidR3Mono-License.md` |

The native libraries are the unmodified binaries of the FluidSynth project's
Android release (https://github.com/FluidSynth/fluidsynth/releases/tag/v2.5.7),
loaded as separate shared libraries; their sources are available from the
respective projects. They can be replaced with other builds of the same
libraries.
