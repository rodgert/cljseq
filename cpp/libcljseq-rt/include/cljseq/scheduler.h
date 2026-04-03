// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once

#include <cstdint>
#include <functional>

namespace cljseq {

// ---------------------------------------------------------------------------
// Wire message types — must match cljseq.sidecar Clojure enum
// ---------------------------------------------------------------------------

enum class MsgType : uint8_t {
    NoteOn      = 0x01,
    NoteOff     = 0x02,
    CC          = 0x03,
    PitchBend   = 0x04,  ///< per-note MPE pitch bend; payload bytes: lsb, msb (14-bit)
    ChanPressure = 0x05, ///< per-channel pressure (MPE aftertouch); payload byte: pressure
    Ping        = 0xF0,
    Shutdown    = 0xFF,
};

// ---------------------------------------------------------------------------
// ScheduledEvent — one entry in the timed-dispatch priority queue
//
// Field reuse by type:
//   NoteOn/NoteOff  — note_or_cc = note;     velocity_or_value = velocity / 0
//   CC              — note_or_cc = cc number; velocity_or_value = value
//   PitchBend       — note_or_cc = lsb;       velocity_or_value = msb   (14-bit = msb<<7|lsb)
//   ChanPressure    — note_or_cc = 0 (unused); velocity_or_value = pressure
// ---------------------------------------------------------------------------

struct ScheduledEvent {
    int64_t  time_ns;           ///< epoch-nanoseconds (system_clock epoch)
    MsgType  type;
    uint8_t  channel;           ///< MIDI channel 1–16
    uint8_t  note_or_cc;        ///< note, CC number, or pitch-bend LSB
    uint8_t  velocity_or_value; ///< velocity, CC value, pitch-bend MSB, or pressure
};

// ---------------------------------------------------------------------------
// DispatchCallbacks — provided by the sidecar; called by the scheduler
// ---------------------------------------------------------------------------

struct DispatchCallbacks {
    std::function<void(uint8_t channel, uint8_t note, uint8_t velocity)> note_on;
    std::function<void(uint8_t channel, uint8_t note)>                   note_off;
    std::function<void(uint8_t channel, uint8_t cc,   uint8_t value)>    cc;
    /// Pitch bend: lsb = low 7 bits, msb = high 7 bits; 14-bit value = (msb<<7)|lsb
    std::function<void(uint8_t channel, uint8_t lsb, uint8_t msb)>       pitch_bend;
    /// Channel pressure (aftertouch): pressure 0–127
    std::function<void(uint8_t channel, uint8_t pressure)>               chan_pressure;
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
