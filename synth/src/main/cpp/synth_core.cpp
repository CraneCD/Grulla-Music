#include "synth_core.h"

#include <algorithm>

namespace grulla {

SynthCore::SynthCore(EventSink* sink, int32_t sampleRate)
    : sink_(sink), sampleRate_(sampleRate), tailFrames_(sampleRate / 4) {
    mark(0);
}

SynthCore::~SynthCore() = default;  // owned_ frees every sequence

bool SynthCore::send(const Command& command) {
    collectGarbage();
    return commands_.push(command);
}

bool SynthCore::setSequence(std::unique_ptr<Sequence> sequence) {
    Sequence* raw = sequence.get();
    owned_.push_back(std::move(sequence));
    if (send({Command::kSetSequence, raw, 0, 0})) return true;
    owned_.pop_back();
    return false;
}

bool SynthCore::play() { return send({Command::kPlay, nullptr, 0, 0}); }

bool SynthCore::pauseAt(int64_t songFrame) { return send({Command::kPause, nullptr, std::max<int64_t>(0, songFrame), 0}); }

bool SynthCore::stop() { return send({Command::kStop, nullptr, 0, 0}); }

bool SynthCore::setLoop(int64_t startFrame, int64_t endFrame) {
    if (startFrame < 0 || endFrame <= startFrame) return clearLoop();
    return send({Command::kSetLoop, nullptr, startFrame, endFrame});
}

bool SynthCore::clearLoop() { return send({Command::kClearLoop, nullptr, 0, 0}); }

void SynthCore::collectGarbage() {
    Sequence* retired = nullptr;
    while (retired_.pop(retired)) {
        owned_.erase(std::remove_if(owned_.begin(), owned_.end(),
                                    [retired](const std::unique_ptr<Sequence>& s) { return s.get() == retired; }),
                     owned_.end());
    }
}

size_t SynthCore::firstEventAtOrAfter(int64_t frame) const {
    if (sequence_ == nullptr) return 0;
    const auto& events = sequence_->events;
    return std::lower_bound(events.begin(), events.end(), frame,
                            [](const Event& e, int64_t f) { return e.frame < f; }) -
           events.begin();
}

void SynthCore::apply(const Command& command) {
    const int64_t now = framesRendered_.load(std::memory_order_relaxed);
    switch (command.type) {
        case Command::kSetSequence:
            if (sequence_ != nullptr && !retired_.push(sequence_)) {
                // The control thread has not collected for a while; keep it (it is still owned).
            }
            sequence_ = command.sequence;
            next_ = 0;
            song_ = 0;
            loopStart_ = loopEnd_ = -1;
            playing_.store(false, std::memory_order_release);
            completed_.store(false, std::memory_order_release);
            sink_->reset();
            break;
        case Command::kPlay:
            if (sequence_ == nullptr) break;
            completed_.store(false, std::memory_order_release);
            playing_.store(true, std::memory_order_release);
            break;
        case Command::kPause:
            playing_.store(false, std::memory_order_release);
            song_ = command.a;
            next_ = firstEventAtOrAfter(song_);
            sink_->notesOff();
            break;
        case Command::kStop:
            playing_.store(false, std::memory_order_release);
            song_ = 0;
            next_ = 0;
            sink_->reset();
            break;
        case Command::kSetLoop:
            loopStart_ = command.a;
            loopEnd_ = command.b;
            if (song_ >= loopEnd_) {
                song_ = loopStart_;
                next_ = firstEventAtOrAfter(song_);
                sink_->notesOff();
            }
            break;
        case Command::kClearLoop:
            loopStart_ = loopEnd_ = -1;
            break;
    }
    mark(now);
}

void SynthCore::mark(int64_t streamFrame) {
    const uint64_t index = markCount_.load(std::memory_order_relaxed);
    Mark& m = marks_[index % kMarks];
    m.stream.store(streamFrame, std::memory_order_relaxed);
    m.song.store(song_, std::memory_order_relaxed);
    m.playing.store(playing_.load(std::memory_order_relaxed) ? 1 : 0, std::memory_order_relaxed);
    markCount_.store(index + 1, std::memory_order_release);
}

int64_t SynthCore::songFrameAt(int64_t streamFrame) const {
    const uint64_t count = markCount_.load(std::memory_order_acquire);
    if (count == 0) return 0;
    const uint64_t oldest = count > static_cast<uint64_t>(kMarks) ? count - kMarks + 1 : 0;
    for (uint64_t i = count; i-- > oldest;) {
        const Mark& m = marks_[i % kMarks];
        const int64_t stream = m.stream.load(std::memory_order_relaxed);
        if (stream <= streamFrame || i == oldest) {
            const int64_t song = m.song.load(std::memory_order_relaxed);
            const bool playing = m.playing.load(std::memory_order_relaxed) != 0;
            return std::max<int64_t>(0, playing ? song + std::max<int64_t>(0, streamFrame - stream) : song);
        }
    }
    return 0;
}

void SynthCore::render(float* stereo, int32_t frames) {
    Command command{};
    while (commands_.pop(command)) apply(command);

    const int64_t streamStart = framesRendered_.load(std::memory_order_relaxed);
    int32_t done = 0;
    while (done < frames) {
        const int32_t remaining = frames - done;
        if (!playing_.load(std::memory_order_relaxed) || sequence_ == nullptr) {
            sink_->render(stereo + 2 * done, remaining);
            done = frames;
            break;
        }
        const auto& events = sequence_->events;
        // Everything due now is applied before rendering the next samples.
        while (next_ < events.size() && events[next_].frame <= song_) sink_->send(events[next_++]);

        const bool looping = loopEnd_ > loopStart_ && loopStart_ >= 0;
        int64_t boundary = song_ + remaining;
        if (next_ < events.size()) boundary = std::min(boundary, events[next_].frame);
        if (looping) boundary = std::min(boundary, loopEnd_);
        const int32_t length = static_cast<int32_t>(std::max<int64_t>(0, boundary - song_));
        if (length > 0) {
            sink_->render(stereo + 2 * done, length);
            done += length;
            song_ += length;
        }

        if (looping && song_ >= loopEnd_) {
            sink_->notesOff();
            song_ = loopStart_;
            next_ = firstEventAtOrAfter(song_);
            mark(streamStart + done);
        } else if (!looping && next_ >= events.size() && song_ >= sequence_->endFrame + tailFrames_) {
            playing_.store(false, std::memory_order_release);
            completed_.store(true, std::memory_order_release);
            mark(streamStart + done);
        } else if (length == 0 && !(next_ < events.size() && events[next_].frame <= song_)) {
            // Defensive: no progress possible (should not happen); emit silence for the rest.
            sink_->render(stereo + 2 * done, frames - done);
            done = frames;
        }
    }
    framesRendered_.store(streamStart + frames, std::memory_order_release);
}

}  // namespace grulla
