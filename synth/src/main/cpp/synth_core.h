// Platform-independent heart of the native player: schedules MIDI events with
// sample accuracy into a synthesizer while rendering audio, handles loops,
// pause and stop, and maps the frame being heard back to the song position.
//
// Threading: control methods are called from one control thread (the Android
// main thread via JNI); render() is called from the audio callback. They only
// communicate through lock-free queues and atomics.
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

#include "spsc_queue.h"

namespace grulla {

/** A MIDI channel message at an absolute frame of the song. */
struct Event {
    int64_t frame;
    uint8_t status;
    uint8_t data1;
    uint8_t data2;
};

struct Sequence {
    std::vector<Event> events;  // sorted by frame
    int64_t endFrame = 0;       // where the song ends (last event)
};

/** The synthesizer the core drives (FluidSynth in the app, a recorder in tests). */
class EventSink {
public:
    virtual ~EventSink() = default;
    /** Applies a channel message now. */
    virtual void send(const Event& event) = 0;
    /** Renders interleaved stereo float frames. */
    virtual void render(float* stereo, int32_t frames) = 0;
    /** Releases all notes (pause, loop wrap). */
    virtual void notesOff() = 0;
    /** Silences everything and resets programs and controllers (new song, stop). */
    virtual void reset() = 0;
};

class SynthCore {
public:
    SynthCore(EventSink* sink, int32_t sampleRate);
    ~SynthCore();

    SynthCore(const SynthCore&) = delete;
    SynthCore& operator=(const SynthCore&) = delete;

    // --- Control thread -------------------------------------------------------
    // Commands take effect at the start of the next render() call. They return
    // false only if the command queue is full (render() is not being called).

    /** Replaces the song; playback is stopped at frame 0. */
    bool setSequence(std::unique_ptr<Sequence> sequence);
    bool play();
    /** Pauses and moves to songFrame (normally the frame being heard). */
    bool pauseAt(int64_t songFrame);
    bool stop();
    /** Repeats [startFrame, endFrame) until cleared or a new sequence is set. */
    bool setLoop(int64_t startFrame, int64_t endFrame);
    bool clearLoop();

    /**
     * Song frame that the output presents at stream frame `streamFrame` (the
     * count of frames rendered since the start, as seen by the audio device).
     */
    int64_t songFrameAt(int64_t streamFrame) const;

    /** Total frames rendered so far. */
    int64_t framesRendered() const { return framesRendered_.load(std::memory_order_acquire); }

    /** True once after the song played to its end (not while looping). */
    bool consumeCompleted() { return completed_.exchange(false, std::memory_order_acq_rel); }

    bool isPlaying() const { return playing_.load(std::memory_order_acquire); }

    /** Frees sequences the audio thread has retired. */
    void collectGarbage();

    int32_t sampleRate() const { return sampleRate_; }

    // --- Audio thread -----------------------------------------------------------

    void render(float* stereo, int32_t frames);

private:
    struct Command {
        enum Type { kSetSequence, kPlay, kPause, kStop, kSetLoop, kClearLoop } type;
        Sequence* sequence;
        int64_t a;
        int64_t b;
    };

    /** Song state at a stream frame; written by the audio thread, read by songFrameAt(). */
    struct Mark {
        std::atomic<int64_t> stream{0};
        std::atomic<int64_t> song{0};
        std::atomic<int32_t> playing{0};
    };

    bool send(const Command& command);
    void apply(const Command& command);
    void mark(int64_t streamFrame);
    size_t firstEventAtOrAfter(int64_t frame) const;

    EventSink* const sink_;
    const int32_t sampleRate_;
    const int64_t tailFrames_;

    SpscQueue<Command, 64> commands_;
    SpscQueue<Sequence*, 64> retired_;
    std::vector<std::unique_ptr<Sequence>> owned_;  // control thread: sequences handed to the audio thread

    // Audio-thread state.
    Sequence* sequence_ = nullptr;
    size_t next_ = 0;
    int64_t song_ = 0;
    int64_t loopStart_ = -1;
    int64_t loopEnd_ = -1;

    std::atomic<bool> playing_{false};
    std::atomic<bool> completed_{false};
    std::atomic<int64_t> framesRendered_{0};

    static constexpr int kMarks = 256;
    Mark marks_[kMarks];
    std::atomic<uint64_t> markCount_{0};
};

}  // namespace grulla
