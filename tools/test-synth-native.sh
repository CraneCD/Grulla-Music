#!/bin/bash
# Builds and runs the host tests for the native synth core (synth/src/main/cpp)
# with AddressSanitizer and UBSan. If FluidSynth's development files are
# installed (apt install libfluidsynth-dev), it also renders the bundled
# SoundFont through FluidSink.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
CXX="${CXX:-g++}"

SOURCES=("$ROOT/synth/src/test/cpp/synth_core_test.cpp" "$ROOT/synth/src/main/cpp/synth_core.cpp")
FLAGS=(-std=c++17 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer
       -I"$ROOT/synth/src/main/cpp")
ARGS=()
if pkg-config --exists fluidsynth 2>/dev/null; then
    SOURCES+=("$ROOT/synth/src/main/cpp/fluid_sink.cpp")
    read -r -a FS_FLAGS <<< "$(pkg-config --cflags --libs fluidsynth)"
    FLAGS+=(-DWITH_FLUIDSYNTH "${FS_FLAGS[@]}")
    SOUNDFONT="$ROOT/app/build/generated/soundfont-assets/soundfonts/FluidR3Mono_GM.sf3"
    [ -f "$SOUNDFONT" ] || (cd "$ROOT" && ./gradlew -q :app:downloadSoundFont)
    ARGS+=("$SOUNDFONT")
else
    echo "FluidSynth development files not found: skipping the FluidSink test." >&2
fi

"$CXX" "${SOURCES[@]}" "${FLAGS[@]}" -o "$OUT/synth_core_test"
# One-time global allocations inside the system's glib / libinstpatch are not leaks of ours.
printf 'leak:libglib-2.0\nleak:libgobject-2.0\nleak:libinstpatch\nleak:libfluidsynth\n' > "$OUT/lsan.supp"
LSAN_OPTIONS="suppressions=$OUT/lsan.supp:print_suppressions=0" "$OUT/synth_core_test" "${ARGS[@]}"
