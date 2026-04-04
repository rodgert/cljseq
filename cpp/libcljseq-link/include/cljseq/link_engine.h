// SPDX-License-Identifier: GPL-2.0-or-later
//
// link_engine.h — LinkBridge implementation backed by the Ableton Link SDK.
//
// Included only when CLJSEQ_ENABLE_LINK=ON. main.cpp injects a LinkEngine
// instance into the sidecar's IPC layer via the LinkBridge interface.
#pragma once

#include <cljseq/link_bridge.h>
#include <ableton/Link.hpp>

#include <atomic>
#include <mutex>

namespace cljseq {

class LinkEngine final : public LinkBridge {
public:
    /// Construct with the initial BPM. Link is not yet enabled; call enable().
    explicit LinkEngine(double initial_bpm);
    ~LinkEngine() override;

    void enable(double quantum = 4.0) override;
    void disable() override;
    bool active() const override;
    void set_bpm(double bpm) override;
    void set_state_callback(LinkStateCallback cb) override;
    AudioClockSnapshot capture_audio_clock() const override;

private:
    ableton::Link   link_;
    double          quantum_{4.0};
    std::atomic<bool> active_{false};

    mutable std::mutex  cb_mutex_;
    LinkStateCallback   state_cb_;

    /// Build a LinkState from the current session and push via state_cb_.
    void push_state();
};

} // namespace cljseq
