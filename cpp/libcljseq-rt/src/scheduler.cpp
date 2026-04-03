// SPDX-License-Identifier: LGPL-2.1-or-later
//
// cljseq-rt scheduler — timed MIDI event dispatch
//
// Holds a min-heap of ScheduledEvent sorted by time_ns (epoch-nanoseconds,
// system_clock epoch — same reference as JVM System/currentTimeMillis×1e6).
// The scheduler thread sleeps until the next event's deadline, fires it, then
// sleeps again. A condition_variable wakes the thread early when a new event
// is enqueued before the current sleep target, or when scheduler_stop() fires.

#include <cljseq/scheduler.h>

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <queue>
#include <vector>
#include <cstdio>

namespace cljseq {

// ---------------------------------------------------------------------------
// Internal state
// ---------------------------------------------------------------------------

namespace {

struct EventCmp {
    bool operator()(const ScheduledEvent& a, const ScheduledEvent& b) const noexcept {
        return a.time_ns > b.time_ns; // min-heap: smallest time_ns fires first
    }
};

using EventQueue = std::priority_queue<ScheduledEvent,
                                       std::vector<ScheduledEvent>,
                                       EventCmp>;

std::mutex              g_mtx;
std::condition_variable g_cv;
EventQueue              g_queue;
DispatchCallbacks       g_dispatch;
bool                    g_stop = false;

/// Epoch-nanoseconds from system_clock — same epoch as JVM currentTimeMillis×1e6.
int64_t now_ns() noexcept {
    using namespace std::chrono;
    return duration_cast<nanoseconds>(system_clock::now().time_since_epoch()).count();
}

} // anonymous namespace

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

void scheduler_init(DispatchCallbacks cb) {
    std::lock_guard<std::mutex> lk(g_mtx);
    g_dispatch = std::move(cb);
    g_stop     = false;
}

void scheduler_enqueue(ScheduledEvent ev) {
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_queue.push(ev);
    }
    g_cv.notify_one();
}

void scheduler_run() {
    using namespace std::chrono;

    std::unique_lock<std::mutex> lk(g_mtx);

    while (!g_stop) {
        if (g_queue.empty()) {
            // Nothing scheduled — wait until enqueue or stop
            g_cv.wait(lk, [] { return g_stop || !g_queue.empty(); });
            continue;
        }

        const ScheduledEvent& next = g_queue.top();
        // system_clock's native duration is microseconds on macOS/libc++;
        // use a nanosecond time_point to avoid precision truncation.
        using ns_tp = std::chrono::time_point<system_clock, nanoseconds>;
        auto deadline = ns_tp{nanoseconds{next.time_ns}};

        // Wait until deadline, a new earlier event arrives, or stop signal
        g_cv.wait_until(lk, deadline, [] {
            return g_stop ||
                   (!g_queue.empty() &&
                    g_queue.top().time_ns <= now_ns());
        });

        if (g_stop) break;

        // Fire all events whose deadline has passed
        while (!g_queue.empty() && g_queue.top().time_ns <= now_ns()) {
            ScheduledEvent ev = g_queue.top();
            g_queue.pop();

            // Unlock while dispatching to avoid holding the mutex during MIDI I/O
            lk.unlock();

            switch (ev.type) {
            case MsgType::NoteOn:
                if (g_dispatch.note_on)
                    g_dispatch.note_on(ev.channel, ev.note_or_cc, ev.velocity_or_value);
                break;
            case MsgType::NoteOff:
                if (g_dispatch.note_off)
                    g_dispatch.note_off(ev.channel, ev.note_or_cc);
                break;
            case MsgType::CC:
                if (g_dispatch.cc)
                    g_dispatch.cc(ev.channel, ev.note_or_cc, ev.velocity_or_value);
                break;
            case MsgType::PitchBend:
                // note_or_cc = lsb (low 7 bits), velocity_or_value = msb (high 7 bits)
                if (g_dispatch.pitch_bend)
                    g_dispatch.pitch_bend(ev.channel, ev.note_or_cc, ev.velocity_or_value);
                break;
            case MsgType::ChanPressure:
                // velocity_or_value = pressure 0–127
                if (g_dispatch.chan_pressure)
                    g_dispatch.chan_pressure(ev.channel, ev.velocity_or_value);
                break;
            default:
                std::fprintf(stderr, "[scheduler] unexpected event type 0x%02x\n",
                             static_cast<unsigned>(ev.type));
                break;
            }

            lk.lock();
        }
    }
}

void scheduler_stop() {
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_stop = true;
    }
    g_cv.notify_one();
}

} // namespace cljseq
