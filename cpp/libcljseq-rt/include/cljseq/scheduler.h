// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once

#include <cstdint>
#include <functional>

namespace cljseq {

// ---------------------------------------------------------------------------
// Wire message types — must match cljseq.sidecar Clojure enum
// ---------------------------------------------------------------------------

enum class MsgType : uint8_t {
    NoteOn   = 0x01,
    NoteOff  = 0x02,
    CC       = 0x03,
    Ping     = 0xF0,
    Shutdown = 0xFF,
};

// ---------------------------------------------------------------------------
// ScheduledEvent — one entry in the timed-dispatch priority queue
// ---------------------------------------------------------------------------

struct ScheduledEvent {
    int64_t  time_ns;           ///< epoch-nanoseconds (system_clock epoch)
    MsgType  type;
    uint8_t  channel;           ///< MIDI channel 1–16
    uint8_t  note_or_cc;        ///< note number or CC number
    uint8_t  velocity_or_value; ///< velocity (NoteOn) or value (CC); 0 for NoteOff
};

// ---------------------------------------------------------------------------
// DispatchCallbacks — provided by the sidecar; called by the scheduler
// ---------------------------------------------------------------------------

struct DispatchCallbacks {
    std::function<void(uint8_t channel, uint8_t note, uint8_t velocity)> note_on;
    std::function<void(uint8_t channel, uint8_t note)>                   note_off;
    std::function<void(uint8_t channel, uint8_t cc,   uint8_t value)>    cc;
};

// ---------------------------------------------------------------------------
// Scheduler API
// ---------------------------------------------------------------------------

/// Initialise the scheduler with dispatch callbacks.
/// Must be called before scheduler_enqueue or scheduler_run.
void scheduler_init(DispatchCallbacks cb);

/// Thread-safe enqueue. Called from the IPC receive thread.
void scheduler_enqueue(ScheduledEvent ev);

/// Scheduler loop — blocks until scheduler_stop() is called.
/// Run on a dedicated thread.
void scheduler_run();

/// Signal the scheduler loop to exit. Thread-safe.
void scheduler_stop();

} // namespace cljseq
