// EventSink backed by a FluidSynth synthesizer.
#pragma once

#include <fluidsynth.h>

#include <string>

#include "synth_core.h"

namespace grulla {

class FluidSink : public EventSink {
public:
    explicit FluidSink(double sampleRate);
    ~FluidSink() override;

    FluidSink(const FluidSink&) = delete;
    FluidSink& operator=(const FluidSink&) = delete;

    bool ok() const { return synth_ != nullptr; }

    /** Loads a .sf2 / .sf3 file, replacing the current SoundFont. */
    bool loadSoundFont(const std::string& path);

    void setGain(float gain);

    void send(const Event& event) override;
    void render(float* stereo, int32_t frames) override;
    void notesOff() override;
    void reset() override;

private:
    fluid_settings_t* settings_ = nullptr;
    fluid_synth_t* synth_ = nullptr;
    int soundFontId_ = -1;
};

}  // namespace grulla
