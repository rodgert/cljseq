// SPDX-License-Identifier: GPL-2.0-or-later
//
// link_engine.cpp — Ableton Link SDK wrapper.
//
// Thread model:
//   - Link's internal threads call tempo/peer/transport callbacks; these call
//     push_state() which holds cb_mutex_ briefly.
//   - set_bpm() is called from the IPC handler thread (app thread, non-RT).
//   - push_state() may also be called from the IPC handler thread after
//     set_bpm() — captureAppSessionState() is the correct call there.
//   - The sidecar's registered callback (state_cb_) writes a 0x80 frame to
//     the TCP socket via asio::async_write — non-blocking, safe from any thread.

#include <cljseq/link_engine.h>

#include <cstdio>

namespace cljseq {

LinkEngine::LinkEngine(double initial_bpm)
    : link_(initial_bpm)
{
    // Register Link callbacks. These fire on Link's internal threads.
    link_.setTempoCallback([this](double /*bpm*/) { push_state(); });
    link_.setNumPeersCallback([this](std::size_t /*peers*/) { push_state(); });
    link_.setStartStopCallback([this](bool /*playing*/) { push_state(); });
}

LinkEngine::~LinkEngine() {
    if (active_.load())
        link_.enable(false);
}

void LinkEngine::enable(double quantum) {
    quantum_ = quantum;
    link_.enable(true);
    active_.store(true);
    // Push initial state so JVM populates its :link-timeline immediately.
    push_state();
    std::fprintf(stderr, "[link] enabled (quantum=%.0f)\n", quantum_);
}

void LinkEngine::disable() {
    link_.enable(false);
    active_.store(false);
    // Push a final state with peers=0 so JVM knows Link is gone.
    push_state();
    std::fprintf(stderr, "[link] disabled\n");
}

bool LinkEngine::active() const {
    return active_.load();
}

void LinkEngine::set_bpm(double bpm) {
    // App-thread path: captureAppSessionState is the correct non-RT call.
    auto state = link_.captureAppSessionState();
    state.setTempo(bpm, link_.clock().micros());
    link_.commitAppSessionState(state);
    // push_state() will fire via the tempo callback registered in the ctor.
}

void LinkEngine::set_state_callback(LinkStateCallback cb) {
    std::lock_guard<std::mutex> lock(cb_mutex_);
    state_cb_ = std::move(cb);
}

void LinkEngine::push_state() {
    // captureAudioSessionState: RT-safe, correct even on callback threads.
    const auto state    = link_.captureAudioSessionState();
    const auto now_us   = link_.clock().micros();
    const double beat   = state.beatAtTime(now_us, quantum_);

    LinkState ls;
    ls.bpm         = state.tempo();
    ls.anchor_beat = beat;
    ls.anchor_us   = now_us.count();
    ls.peers       = static_cast<uint32_t>(link_.numPeers());
    ls.playing     = state.isPlaying();

    std::lock_guard<std::mutex> lock(cb_mutex_);
    if (state_cb_)
        state_cb_(ls);
}

} // namespace cljseq
