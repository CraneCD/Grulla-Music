cmake_minimum_required(VERSION 3.22.1)
project(grulla_synth CXX)

# FLUIDSYNTH_DIR is the unpacked official FluidSynth Android release (include/ and
# lib/<abi>/), prepared by the :synth Gradle build. FluidSynth stays a separate
# shared library (LGPL-2.1+); this project only links to it.
if(NOT FLUIDSYNTH_DIR)
    message(FATAL_ERROR "FLUIDSYNTH_DIR is not set")
endif()

add_library(fluidsynth SHARED IMPORTED)
set_target_properties(fluidsynth PROPERTIES
    IMPORTED_LOCATION ${FLUIDSYNTH_DIR}/lib/${ANDROID_ABI}/libfluidsynth.so
    INTERFACE_INCLUDE_DIRECTORIES ${FLUIDSYNTH_DIR}/include)

add_library(grulla_synth SHARED
    synth_core.cpp
    fluid_sink.cpp
    engine.cpp
    jni_bridge.cpp)

target_compile_features(grulla_synth PRIVATE cxx_std_17)
target_compile_options(grulla_synth PRIVATE -Wall -Wextra -Werror -Werror=unguarded-availability)
# Newer AAudio calls (API 28+) are made only behind __builtin_available checks.
target_compile_definitions(grulla_synth PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_link_libraries(grulla_synth PRIVATE fluidsynth aaudio log)
