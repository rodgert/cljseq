// SPDX-License-Identifier: LGPL-2.1-or-later
//
// link_bridge.h — Abstract interface between the sidecar and the Link engine.
//
// The sidecar always programs against this interface. When built without Link
// (CLJSEQ_ENABLE_LINK=OFF), main.cpp injects a NullLinkBridge. When built with
// Link (CLJSEQ_ENABLE_LINK=ON), it injects a LinkEngine from libcljseq-link.
//
// The GPL boundary is therefore entirely within libcljseq-link. The sidecar
// source files remain LGPL-2.1-or-later regardless of build configuration;
// only the resulting binary is GPL-2.0-or-later when linked with cljseq-link.
//
// LinkState — pushed to JVM via IPC 0x80 whenever tempo, peer count, or
// transport state changes. Layout matches the wire format documented in
// open-design-questions.md §IPC.
#pragma once

#include <cstdint>
#include <functional>

namespace cljseq {

// ---------------------------------------------------------------------------
// LinkState — pushed to the JVM on any change
// ---------------------------------------------------------------------------

struct LinkState {
    double   bpm;          ///< current session BPM
    double   anchor_beat;  ///< beat number at anchor_us
    int64_t  anchor_us;    ///< Link host time (µs) at anchor
    uint32_t peers;        ///< number of connected Link peers
    bool     playing;      ///< transport state (start/stop sync)
};

// ---------------------------------------------------------------------------
// AudioClockSnapshot — lightweight snapshot for MIDI clock generation
//
// Returned by LinkBridge::capture_audio_clock(). The MIDI clock thread calls
// this on every iteration to compute the absolute time of the next 24 PPQN
// pulse without accumulating drift.
//
// Given a snapshot, the time until the next pulse N is:
//   next_beat  = N / 24.0
//   delta_us   = (next_beat - beat_now) * 60_000_000 / bpm
//   wakeup     = steady_clock::now() + microseconds(delta_us)
// ---------------------------------------------------------------------------

struct AudioClockSnapshot {
    double  bpm;         ///< current session tempo
    double  beat_now;    ///< beat position at moment of capture
    bool    playing;     ///< Link transport state (true = playing)
};

// Callback type registered by the sidecar IPC layer.
// Called from a Link listener thread — must be thread-safe, non-blocking.
using LinkStateCallback = std::function<void(const LinkState&)>;

// ---------------------------------------------------------------------------
// LinkBridge — pure interface
// ---------------------------------------------------------------------------

class LinkBridge {
public:
    virtual ~LinkBridge() = default;

    /// Join a Link session. quantum is the phrase length in beats (default 4).
    /// Triggers an immediate LinkState push via the registered callback.
    virtual void enable(double quantum = 4.0) = 0;

    /// Leave the Link session. Triggers a final LinkState push with peers=0.
    virtual void disable() = 0;

    /// Returns true if the Link session is currently active.
    virtual bool active() const = 0;

    /// Propose a tempo change to the Link session.
    /// No-op when inactive.
    virtual void set_bpm(double bpm) = 0;

    /// Register the callback that receives LinkState pushes.
    /// Must be called before enable().
    virtual void set_state_callback(LinkStateCallback cb) = 0;

    /// Capture a lightweight snapshot of the current Link session state for
    /// use by the MIDI clock thread. Thread-safe; may be called from any thread.
    /// Returns a snapshot with bpm=120, beat_now=0, playing=false when inactive.
    virtual AudioClockSnapshot capture_audio_clock() const = 0;
};

// ---------------------------------------------------------------------------
// NullLinkBridge — no-op stub used when CLJSEQ_ENABLE_LINK=OFF
// ---------------------------------------------------------------------------

class NullLinkBridge final : public LinkBridge {
public:
    void enable(double /*quantum*/) override {}
    void disable() override {}
    bool active() const override { return false; }
    void set_bpm(double /*bpm*/) override {}
    void set_state_callback(LinkStateCallback /*cb*/) override {}
    AudioClockSnapshot capture_audio_clock() const override {
        return {120.0, 0.0, false};
    }
};

} // namespace cljseq
