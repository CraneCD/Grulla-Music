#include "engine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <thread>
#include <time.h>

#define LOG_TAG "GrullaSynth"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace grulla {

namespace {

int64_t nowNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

}  // namespace

Engine::~Engine() { closeStream(); }

std::string Engine::open() {
    const aaudio_result_t result = openStream();
    if (result != AAUDIO_OK) return std::string("Could not open audio output: ") + AAudio_convertResultToText(result);
    sink_ = std::make_unique<FluidSink>(sampleRate_);
    if (!sink_->ok()) return "Could not create the FluidSynth synthesizer";
    core_ = std::make_unique<SynthCore>(sink_.get(), sampleRate_);
    return "";
}

aaudio_result_t Engine::openStream() {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return result;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    // Keep the rate the synthesizer was created with; AAudio resamples if the device differs.
    if (sampleRate_ > 0) AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    if (__builtin_available(android 28, *)) {
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    }
    AAudioStreamBuilder_setDataCallback(builder, &Engine::onAudio, this);
    AAudioStreamBuilder_setErrorCallback(builder, &Engine::onError, this);
    result = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        stream_ = nullptr;
        return result;
    }
    sampleRate_ = AAudioStream_getSampleRate(stream_);
    // Two bursts of buffering: low latency with some protection against glitches.
    const int32_t burst = AAudioStream_getFramesPerBurst(stream_);
    if (burst > 0) AAudioStream_setBufferSizeInFrames(stream_, burst * 2);
    disconnected_.store(false);
    return AAUDIO_OK;
}

void Engine::closeStream() {
    if (stream_ == nullptr) return;
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
}

aaudio_data_callback_result_t Engine::onAudio(AAudioStream*, void* user, void* audio, int32_t frames) {
    auto* engine = static_cast<Engine*>(user);
    engine->core_->render(static_cast<float*>(audio), frames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void Engine::onError(AAudioStream*, void* user, aaudio_result_t error) {
    // Reopening is not allowed on the callback thread; the control thread does it (ensureStreamRunning).
    LOGW("Audio stream error: %s", AAudio_convertResultToText(error));
    static_cast<Engine*>(user)->disconnected_.store(true);
}

void Engine::ensureStreamRunning() {
    if (disconnected_.load() || stream_ == nullptr) {
        closeStream();
        streamOffset_ = core_->framesRendered();
        if (openStream() != AAUDIO_OK) return;
        if (running_) AAudioStream_requestStart(stream_);
    }
}

void Engine::retry(bool (SynthCore::*command)()) {
    // The queue only fills up if the audio thread is stalled; give it a moment.
    for (int i = 0; i < 100 && !((*core_).*command)(); ++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
}

void Engine::setSequence(std::unique_ptr<Sequence> sequence) {
    for (int i = 0; i < 100; ++i) {
        if (core_->setSequence(std::move(sequence))) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    pausedMs_ = 0;
}

void Engine::play() {
    retry(&SynthCore::play);
    ensureStreamRunning();
    if (!running_ && stream_ != nullptr) {
        AAudioStream_requestStart(stream_);
        running_ = true;
    }
}

void Engine::pause() {
    const double position = positionMs();
    core_->pauseAt(msToFrames(position));
    pausedMs_ = position;
    if (running_ && stream_ != nullptr) AAudioStream_requestStop(stream_);
    running_ = false;
}

void Engine::stop() {
    retry(&SynthCore::stop);
    pausedMs_ = 0;
    if (running_ && stream_ != nullptr) AAudioStream_requestStop(stream_);
    running_ = false;
}

void Engine::setLoop(double startMs, double endMs) { core_->setLoop(msToFrames(startMs), msToFrames(endMs)); }

int64_t Engine::presentedStreamFrame() {
    const int64_t written = core_->framesRendered();
    int64_t presented;
    int64_t framePosition = 0;
    int64_t timeNanos = 0;
    if (stream_ != nullptr &&
        AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &framePosition, &timeNanos) == AAUDIO_OK) {
        presented = streamOffset_ + framePosition + (nowNanos() - timeNanos) * sampleRate_ / 1000000000LL;
    } else {
        presented = written - (stream_ != nullptr ? AAudioStream_getBufferSizeInFrames(stream_) : 0);
    }
    // Never ahead of what was rendered, never further behind than the buffer can hold.
    const int64_t capacity = stream_ != nullptr ? AAudioStream_getBufferCapacityInFrames(stream_) : 0;
    return std::clamp<int64_t>(presented, std::max<int64_t>(0, written - capacity), written);
}

double Engine::positionMs() {
    ensureStreamRunning();
    core_->collectGarbage();
    if (!running_) return pausedMs_;
    return core_->songFrameAt(presentedStreamFrame()) * 1000.0 / sampleRate_;
}

}  // namespace grulla
