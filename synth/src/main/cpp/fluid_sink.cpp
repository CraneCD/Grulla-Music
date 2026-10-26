#include "fluid_sink.h"

namespace grulla {

FluidSink::FluidSink(double sampleRate) {
    settings_ = new_fluid_settings();
    if (settings_ == nullptr) return;
    fluid_settings_setnum(settings_, "synth.sample-rate", sampleRate);
    fluid_settings_setnum(settings_, "synth.gain", 0.7);
    fluid_settings_setint(settings_, "synth.cpu-cores", 1);
    fluid_settings_setint(settings_, "synth.polyphony", 256);
    synth_ = new_fluid_synth(settings_);
}

FluidSink::~FluidSink() {
    if (synth_ != nullptr) delete_fluid_synth(synth_);
    if (settings_ != nullptr) delete_fluid_settings(settings_);
}

bool FluidSink::loadSoundFont(const std::string& path) {
    if (synth_ == nullptr) return false;
    const int id = fluid_synth_sfload(synth_, path.c_str(), 1 /* reset presets */);
    if (id == FLUID_FAILED) return false;
    if (soundFontId_ >= 0) fluid_synth_sfunload(synth_, soundFontId_, 1);
    soundFontId_ = id;
    return true;
}

void FluidSink::setGain(float gain) {
    if (synth_ != nullptr) fluid_synth_set_gain(synth_, gain);
}

void FluidSink::send(const Event& e) {
    const int channel = e.status & 0x0F;
    switch (e.status & 0xF0) {
        case 0x80:
            fluid_synth_noteoff(synth_, channel, e.data1);
            break;
        case 0x90:
            if (e.data2 == 0) {
                fluid_synth_noteoff(synth_, channel, e.data1);
            } else {
                fluid_synth_noteon(synth_, channel, e.data1, e.data2);
            }
            break;
        case 0xA0:
            fluid_synth_key_pressure(synth_, channel, e.data1, e.data2);
            break;
        case 0xB0:
            fluid_synth_cc(synth_, channel, e.data1, e.data2);
            break;
        case 0xC0:
            fluid_synth_program_change(synth_, channel, e.data1);
            break;
        case 0xD0:
            fluid_synth_channel_pressure(synth_, channel, e.data1);
            break;
        case 0xE0:
            fluid_synth_pitch_bend(synth_, channel, (e.data2 << 7) | e.data1);
            break;
        default:
            break;
    }
}

void FluidSink::render(float* stereo, int32_t frames) {
    // Interleaved: left at even indices, right at odd ones.
    fluid_synth_write_float(synth_, frames, stereo, 0, 2, stereo, 1, 2);
}

void FluidSink::notesOff() { fluid_synth_all_notes_off(synth_, -1); }

void FluidSink::reset() {
    fluid_synth_all_sounds_off(synth_, -1);
    fluid_synth_system_reset(synth_);
}

}  // namespace grulla
