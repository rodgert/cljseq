// SPDX-License-Identifier: LGPL-2.1-or-later
//
// midi_clock.cpp — 24 PPQN MIDI clock output derived from Ableton Link
//
// Threading model:
//   g_thread  — dedicated clock thread; sleeps between pulses
//   g_playing — atomic flag; set by midi_clock_set_playing() from the IPC thread
//   g_stop    — atomic flag; set by midi_clock_stop() from main thread
//
// Timing:
//   Each clock pulse time is computed from capture_audio_clock() on every
//   iteration — an absolute beat-anchored calculation with no error accumulation.
//   Sleep precision is limited by OS scheduling (~1–2 ms on macOS with normal
//   thread priority). Consider SCHED_FIFO / QoS if sub-ms jitter is needed.

#include "midi_clock.h"
#include "midi_dispatch.h"

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <thread>

namespace {

cljseq::LinkBridge*     g_link    = nullptr;
std::thread             g_thread;
std::atomic<bool>       g_stop    {false};
std::atomic<bool>       g_playing {false};

// ---------------------------------------------------------------------------
// Interruptible sleep: wakes early if g_stop or g_playing changes.
// Polls in 2 ms chunks; coarser than a busy-wait but well within the ~42 ms
// inter-pulse interval at 120 BPM (24 PPQN → 20.8 ms/pulse at 120 BPM).
// ---------------------------------------------------------------------------

static void sleep_until_interruptible(
    std::chrono::steady_clock::time_point target,
    bool expected_playing)
{
    using namespace std::chrono;
    static constexpr auto kChunk = milliseconds(2);

    while (true) {
        auto now = steady_clock::now();
        if (now >= target) return;
        if (g_stop.load(std::memory_order_relaxed)) return;
        if (g_playing.load(std::memory_order_relaxed) != expected_playing) return;

        auto remaining = target - now;
        std::this_thread::sleep_for(
            remaining < kChunk ? remaining : kChunk);
    }
}

// ---------------------------------------------------------------------------
// Clock thread
// ---------------------------------------------------------------------------

static void clock_thread_fn() {
    using namespace std::chrono;

    bool was_playing = false;

    while (!g_stop.load(std::memory_order_relaxed)) {
        bool should_play = g_playing.load(std::memory_order_relaxed);

        // --- Idle path: not playing ---
        if (!should_play) {
            if (was_playing) {
                midi_send_realtime(0xFC);  // MIDI Stop
                was_playing = false;
                std::fprintf(stderr, "[midi_clock] MIDI Stop sent\n");
            }
            std::this_thread::sleep_for(milliseconds(5));
            continue;
        }

        // --- Playing path ---
        auto snap = g_link->capture_audio_clock();

        if (!was_playing) {
            // Transition to playing.
            // Send MIDI Start (0xFA) before the first clock pulse.
            midi_send_realtime(0xFA);
            was_playing = true;
            std::fprintf(stderr,
                "[midi_clock] MIDI Start sent — bpm=%.2f beat=%.3f\n",
                snap.bpm, snap.beat_now);
        }

        // Compute time to next 24 PPQN pulse.
        //
        // snap.beat_now is the beat at the moment capture_audio_clock() was called.
        // We want the next pulse index strictly after beat_now.
        //
        // next_pulse_beat = ceil(beat_now * 24 + epsilon) / 24
        //
        // Using floor + 1 gives the next integer multiple of 1/24:
        //   next_pulse = floor(beat_now * 24) + 1
        //   next_beat  = next_pulse / 24.0
        //
        // The delta from now to that beat (in µs) is:
        //   delta_us = (next_beat - beat_now) * 60_000_000 / bpm
        //
        // We add this to steady_clock::now() for the wakeup target.
        // This correctly derives from the Link timeline anchor on every
        // iteration, so no error accumulates across tempo changes or
        // long sessions.

        const double beat_now  = snap.beat_now;
        const double bpm       = (snap.bpm > 1.0) ? snap.bpm : 120.0;
        const int64_t next_idx = static_cast<int64_t>(std::floor(beat_now * 24.0)) + 1;
        const double  next_beat  = static_cast<double>(next_idx) / 24.0;
        const double  delta_beats = next_beat - beat_now;
        const int64_t delta_us    =
            static_cast<int64_t>(delta_beats * 60'000'000.0 / bpm);

        const auto wakeup = steady_clock::now() + microseconds(delta_us);

        sleep_until_interruptible(wakeup, /*expected_playing=*/true);

        if (g_stop.load(std::memory_order_relaxed)) break;
        if (!g_playing.load(std::memory_order_relaxed)) continue;

        midi_send_realtime(0xF8);  // MIDI Clock
    }

    // Ensure Stop is sent on shutdown if we were playing.
    if (was_playing) {
        midi_send_realtime(0xFC);
        std::fprintf(stderr, "[midi_clock] MIDI Stop sent on shutdown\n");
    }

    std::fprintf(stderr, "[midi_clock] thread exited\n");
}

} // anonymous namespace

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

void midi_clock_init(cljseq::LinkBridge& link) {
    g_link = &link;
}

void midi_clock_start() {
    if (g_thread.joinable()) return;  // already running
    g_stop.store(false);
    g_thread = std::thread(clock_thread_fn);
    std::fprintf(stderr, "[midi_clock] clock thread started\n");
}

void midi_clock_stop() {
    g_stop.store(true);
    if (g_thread.joinable()) {
        g_thread.join();
    }
}

void midi_clock_set_playing(bool playing) {
    g_playing.store(playing, std::memory_order_relaxed);
    std::fprintf(stderr, "[midi_clock] set_playing(%s)\n",
                 playing ? "true" : "false");
}
