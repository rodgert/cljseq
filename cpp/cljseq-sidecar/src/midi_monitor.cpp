// SPDX-License-Identifier: LGPL-2.1-or-later
//
// midi_monitor — RtMidiIn wrapper for MIDI input monitoring
//
// Opens a MIDI input port and forwards every received message to a callback.
// The callback (supplied by IpcSession) frames the message as a MsgType::MidiIn
// (0x20) push frame and writes it to the connected JVM over the IPC socket.
//
// One port at a time.  Thread-safety: midi_monitor_start / midi_monitor_stop
// must be called from a single thread (the Asio io_context thread).  The
// RtMidi callback itself runs on RtMidi's internal thread and only reads
// g_callback and writes via async_write (which is safe by IpcSession design).

#include "midi_monitor.h"

#include <RtMidi.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <memory>

namespace cljseq {

namespace {

std::unique_ptr<RtMidiIn> g_midi_in;
MidiInCallback            g_callback;
std::atomic<bool>         g_open{false};

// Called on RtMidi's internal callback thread.
void rtmidi_callback(double /*delta_time*/,
                     std::vector<unsigned char>* msg,
                     void* /*userdata*/)
{
    if (!msg || msg->empty()) return;

    // Timestamp: wall-clock nanoseconds at receipt, matching system_clock used
    // by the JVM side (System/currentTimeMillis × 1 000 000).
    auto now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
                      std::chrono::system_clock::now().time_since_epoch())
                      .count();

    uint8_t status = (*msg)[0];
    uint8_t b1     = msg->size() > 1 ? (*msg)[1] : 0u;
    uint8_t b2     = msg->size() > 2 ? (*msg)[2] : 0u;

    if (g_callback)
        g_callback(static_cast<int64_t>(now_ns), status, b1, b2);
}

} // namespace

bool midi_monitor_start(unsigned int port_index, MidiInCallback cb)
{
    if (g_open.load()) {
        std::fprintf(stderr, "[midi_monitor] already open — call midi_monitor_stop() first\n");
        return false;
    }

    try {
        g_midi_in = std::make_unique<RtMidiIn>();
    } catch (RtMidiError& e) {
        std::fprintf(stderr, "[midi_monitor] RtMidiIn init failed: %s\n",
                     e.getMessage().c_str());
        return false;
    }

    unsigned int n = g_midi_in->getPortCount();
    if (port_index >= n) {
        std::fprintf(stderr,
                     "[midi_monitor] port %u out of range (%u input port(s) available)\n",
                     port_index, n);
        // Print available ports to help the user pick the right index.
        for (unsigned int i = 0; i < n; ++i)
            std::fprintf(stderr, "[midi_monitor]   [%u] %s\n",
                         i, g_midi_in->getPortName(i).c_str());
        g_midi_in.reset();
        return false;
    }

    std::fprintf(stderr, "[midi_monitor] opening input port %u: %s\n",
                 port_index, g_midi_in->getPortName(port_index).c_str());

    g_callback = std::move(cb);
    g_midi_in->setCallback(&rtmidi_callback, nullptr);

    // Forward all message types — caller decides what to do with timing/sysex.
    g_midi_in->ignoreTypes(false /*sysex*/, false /*timing*/, false /*active-sensing*/);

    try {
        g_midi_in->openPort(port_index, "cljseq-monitor");
    } catch (RtMidiError& e) {
        std::fprintf(stderr, "[midi_monitor] openPort failed: %s\n",
                     e.getMessage().c_str());
        g_midi_in.reset();
        g_callback = nullptr;
        return false;
    }

    g_open.store(true);
    return true;
}

void midi_monitor_stop()
{
    if (!g_open.exchange(false)) return; // already closed
    if (g_midi_in) {
        g_midi_in->cancelCallback();
        g_midi_in->closePort();
        g_midi_in.reset();
    }
    g_callback = nullptr;
    std::fprintf(stderr, "[midi_monitor] closed\n");
}

} // namespace cljseq
