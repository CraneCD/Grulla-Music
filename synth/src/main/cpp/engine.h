// Android audio output for SynthCore: an AAudio low-latency float stream.
#pragma once

#include <aaudio/AAudio.h>

#include <atomic>
#include <memory>
#include <string>

#include "fluid_sink.h"
#include "synth_core.h"

namespace grulla {

class Engine {
public:
    Engine() = default;
    ~Engine();

    /** Opens the audio stream and creates the synthesizer. Returns an error message, empty on success. */
    std::string open();

    bool loadSoundFont(const std::string& path) { return sink_->loadSoundFont(path); }

    void setSequence(std::unique_ptr<Sequence> sequence);
    void play();
    void pause();
    void stop();
    void setLoop(double startMs, double endMs);

    /** The song position being heard right now, in ms. */
    double positionMs();

    bool isPlaying() const { return core_ && core_->isPlaying(); }
    bool consumeCompleted() { return core_ && core_->consumeCompleted(); }
    int32_t sampleRate() const { return sampleRate_; }
    int64_t msToFrames(double ms) const { return static_cast<int64_t>(ms * sampleRate_ / 1000.0 + 0.5); }

private:
    static aaudio_data_callback_result_t onAudio(AAudioStream* stream, void* user, void* audio, int32_t frames);
    static void onError(AAudioStream* stream, void* user, aaudio_result_t error);

    aaudio_result_t openStream();
    void closeStream();
    void ensureStreamRunning();
    int64_t presentedStreamFrame();
    void retry(bool (SynthCore::*command)());

    AAudioStream* stream_ = nullptr;
    int32_t sampleRate_ = 0;
    std::unique_ptr<FluidSink> sink_;
    std::unique_ptr<SynthCore> core_;

    /** Core frames rendered before the current stream was opened (after a device change). */
    int64_t streamOffset_ = 0;
    std::atomic<bool> disconnected_{false};
    bool running_ = false;
    double pausedMs_ = 0;
};

}  // namespace grulla
