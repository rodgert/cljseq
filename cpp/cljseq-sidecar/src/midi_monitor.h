// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once

#include <cstdint>
#include <functional>

namespace cljseq {

/// Callback invoked on each received MIDI message.
///
/// time_ns — system_clock epoch nanoseconds at time of receipt
/// status  — MIDI status byte (e.g. 0x90 = NoteOn ch1, 0xB0 = CC ch1)
/// b1, b2  — data bytes (b2 may be 0 for 2-byte messages such as Program Change)
using MidiInCallback = std::function<void(int64_t time_ns,
                                          uint8_t status,
                                          uint8_t b1,
                                          uint8_t b2)>;

/// Open MIDI input port `port_index` and invoke `cb` on each received message.
///
/// The callback is called from RtMidi's internal callback thread.  It must not
/// block; use a lock-free queue or async_write (as IpcSession::push_midi_in
/// does) to hand work off.
///
/// Returns true on success, false if the port index is out of range or a port
/// is already open.
bool midi_monitor_start(unsigned int port_index, MidiInCallback cb);

/// Close the MIDI input port opened by midi_monitor_start().
/// Safe to call even if no port is open (no-op).
void midi_monitor_stop();

} // namespace cljseq
