// Host tests for SynthCore (and, with -DWITH_FLUIDSYNTH, FluidSink).
// Run with tools/test-synth-native.sh.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include "synth_core.h"
#ifdef WITH_FLUIDSYNTH
#include "fluid_sink.h"
#endif

using namespace grulla;

static int failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                      \
        }                                                                    \
    } while (0)

#define CHECK_EQ(a, b)                                                                       \
    do {                                                                                     \
        const long long va = static_cast<long long>(a), vb = static_cast<long long>(b);      \
        if (va != vb) {                                                                      \
            std::fprintf(stderr, "%s:%d: %s == %lld, expected %s == %lld\n", __FILE__, __LINE__, #a, va, #b, vb); \
            ++failures;                                                                      \
        }                                                                                    \
    } while (0)

/** Records the stream frame at which each event is sent and counts resets. */
class RecordingSink : public EventSink {
public:
    struct Sent {
        int64_t streamFrame;
        Event event;
    };
    std::vector<Sent> sent;
    int notesOffCount = 0;
    int resetCount = 0;
    int64_t streamFrame = 0;

    void send(const Event& e) override { sent.push_back({streamFrame, e}); }
    void render(float* stereo, int32_t frames) override {
        for (int i = 0; i < frames * 2; ++i) stereo[i] = 0.f;
        streamFrame += frames;
    }
    void notesOff() override { ++notesOffCount; }
    void reset() override { ++resetCount; }
};

static std::unique_ptr<Sequence> song(std::vector<int64_t> noteFrames, int64_t end) {
    auto s = std::make_unique<Sequence>();
    s->events.push_back({0, 0xC0, 0, 0});  // program change at the start
    for (int64_t f : noteFrames) {
        s->events.push_back({f, 0x90, 60, 100});
        s->events.push_back({f + 100, 0x80, 60, 0});
    }
    s->endFrame = end;
    return s;
}

static void renderFrames(SynthCore& core, int64_t frames, int32_t block = 192) {
    std::vector<float> buffer(block * 2);
    while (frames > 0) {
        const int32_t n = static_cast<int32_t>(std::min<int64_t>(frames, block));
        core.render(buffer.data(), n);
        frames -= n;
    }
}

static std::vector<int64_t> noteOnFrames(const RecordingSink& sink) {
    std::vector<int64_t> frames;
    for (const auto& s : sink.sent)
        if ((s.event.status & 0xF0) == 0x90) frames.push_back(s.streamFrame);
    return frames;
}

static void eventsAreSampleAccurate() {
    RecordingSink sink;
    SynthCore core(&sink, 48000);
    core.setSequence(song({1000, 1500, 4801}, 6000));
    core.play();
    renderFrames(core, 3000);
    // Events land on their exact frame even though blocks are 192 frames long.
    const auto ons = noteOnFrames(sink);
    CHECK_EQ(ons.size(), 2u);
    CHECK_EQ(ons[0], 1000);
    CHECK_EQ(ons[1], 1500);
    CHECK_EQ(sink.sent.front().streamFrame, 0);  // program change first
    renderFrames(core, 3000);
    CHECK_EQ(noteOnFrames(sink).back(), 4801);
    CHECK_EQ(sink.resetCount, 1);  // from setSequence
}

static void positionFollowsTheStream() {
    RecordingSink sink;
    SynthCore core(&sink, 48000);
    renderFrames(core, 960);  // stream already running before the song starts
    core.setSequence(song({0}, 48000));
    core.play();
    renderFrames(core, 4800);
    // The song started at stream frame 960.
    CHECK_EQ(core.songFrameAt(960), 0);
    CHECK_EQ(core.songFrameAt(960 + 2400), 2400);
    CHECK_EQ(core.songFrameAt(500), 0);  // before the start: still at 0
}

static void loopWrapsWithoutGaps() {
    RecordingSink sink;
    SynthCore core(&sink, 48000);
    core.setSequence(song({100, 1100, 2100}, 3000));
    core.setLoop(1000, 2000);
    core.play();
    // Starts at 0, not inside the loop: plays 0..2000, then 1000..2000 forever.
    renderFrames(core, 2000 + 1000 + 1000, 128);
    const auto ons = noteOnFrames(sink);
    CHECK_EQ(ons.size(), 4u);  // 100, 1100, then 1100 on each repeat
    if (ons.size() == 4) {
        CHECK_EQ(ons[0], 100);
        CHECK_EQ(ons[1], 1100);
        CHECK_EQ(ons[2], 2100);  // stream frame: 2000 (wrap) + 100
        CHECK_EQ(ons[3], 3100);
    }
    CHECK(sink.notesOffCount >= 2);
    // Position wraps: stream frame 2500 is song frame 1500.
    CHECK_EQ(core.songFrameAt(2500), 1500);
    CHECK(!core.consumeCompleted());
}

static void pauseResumesWhereHeard() {
    RecordingSink sink;
    SynthCore core(&sink, 48000);
    core.setSequence(song({1000, 2000}, 3000));
    core.play();
    renderFrames(core, 2400);  // rendered (and sent) both notes
    // Only frame 1800 has been heard: pause there, so the note at 2000 is played again.
    core.pauseAt(1800);
    renderFrames(core, 480);
    CHECK(!core.isPlaying());
    CHECK_EQ(core.songFrameAt(2880), 1800);
    core.play();
    renderFrames(core, 480);
    const auto ons = noteOnFrames(sink);
    CHECK_EQ(ons.size(), 3u);
    if (ons.size() == 3) CHECK_EQ(ons[2], 2880 + 200);
}

static void completesAfterTheEnd() {
    RecordingSink sink;
    SynthCore core(&sink, 48000);
    core.setSequence(song({0}, 1000));
    core.play();
    renderFrames(core, 1000 + 48000 / 4 - 10);
    CHECK(!core.consumeCompleted());
    renderFrames(core, 200);
    CHECK(core.consumeCompleted());
    CHECK(!core.consumeCompleted());  // reported once
    CHECK(!core.isPlaying());
}

static void newSequenceRetiresTheOldOne() {
    RecordingSink sink;
    SynthCore core(&sink, 48000);
    for (int i = 0; i < 300; ++i) {
        core.setSequence(song({10}, 100));
        core.play();
        renderFrames(core, 64);
    }
    core.collectGarbage();
    // Each new sequence restarts at frame 0, so every run plays its note at frame 10.
    CHECK_EQ(noteOnFrames(sink).size(), 300u);
    CHECK_EQ(sink.resetCount, 300);
}

#ifdef WITH_FLUIDSYNTH
static void fluidSynthRendersSound(const char* soundFont) {
    FluidSink sink(48000);
    CHECK(sink.ok());
    CHECK(sink.loadSoundFont(soundFont));
    SynthCore core(&sink, 48000);
    auto s = std::make_unique<Sequence>();
    s->events = {{0, 0xC0, 0, 0}, {0, 0x90, 60, 100}, {24000, 0x80, 60, 0}, {0, 0x99, 76, 110}};
    std::sort(s->events.begin(), s->events.end(), [](const Event& a, const Event& b) { return a.frame < b.frame; });
    s->endFrame = 24000;
    core.setSequence(std::move(s));
    core.play();
    std::vector<float> buffer(48000 * 2);
    core.render(buffer.data(), 48000);
    double energy = 0;
    for (float v : buffer) energy += v * v;
    const double rms = std::sqrt(energy / buffer.size());
    std::printf("FluidSynth render RMS: %.4f\n", rms);
    CHECK(rms > 0.01);
    // Write a WAV for listening (16-bit stereo).
    if (const char* out = std::getenv("SYNTH_TEST_WAV")) {
        FILE* f = std::fopen(out, "wb");
        if (f) {
            const uint32_t dataBytes = 48000 * 2 * 2;
            auto u32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
            auto u16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
            std::fwrite("RIFF", 1, 4, f); u32(36 + dataBytes); std::fwrite("WAVEfmt ", 1, 8, f);
            u32(16); u16(1); u16(2); u32(48000); u32(48000 * 4); u16(4); u16(16);
            std::fwrite("data", 1, 4, f); u32(dataBytes);
            for (float v : buffer) { int16_t s16 = static_cast<int16_t>(std::max(-1.f, std::min(1.f, v)) * 32767); std::fwrite(&s16, 2, 1, f); }
            std::fclose(f);
        }
    }
}
#endif

int main(int argc, char** argv) {
    eventsAreSampleAccurate();
    positionFollowsTheStream();
    loopWrapsWithoutGaps();
    pauseResumesWhereHeard();
    completesAfterTheEnd();
    newSequenceRetiresTheOldOne();
#ifdef WITH_FLUIDSYNTH
    if (argc > 1) fluidSynthRendersSound(argv[1]);
#endif
    (void)argc;
    (void)argv;
    if (failures == 0) std::printf("All synth core tests passed.\n");
    return failures == 0 ? 0 : 1;
}
