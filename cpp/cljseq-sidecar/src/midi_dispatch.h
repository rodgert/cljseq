// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#include <cstdint>

/// Open the MIDI output port at `port_index` (0-based).
/// Logs all available ports to stderr on startup.
/// Returns true on success, false if the port is unavailable.
bool midi_init(unsigned int port_index = 0);

/// Dispatch a NoteOn. channel is 1–16; note and velocity are 0–127.
void midi_note_on(uint8_t channel, uint8_t note, uint8_t velocity);

/// Dispatch a NoteOff. channel is 1–16; note is 0–127.
void midi_note_off(uint8_t channel, uint8_t note);

/// Dispatch a Control Change. channel is 1–16; cc and value are 0–127.
void midi_cc(uint8_t channel, uint8_t cc, uint8_t value);

/// Dispatch a pitch bend (0xEn). channel is 1–16; lsb and msb are the
/// standard MIDI 14-bit split: 14-bit value = (msb << 7) | lsb.
/// Center (no bend) = lsb=0, msb=64 (value=8192).
void midi_pitch_bend(uint8_t channel, uint8_t lsb, uint8_t msb);

/// Dispatch a channel pressure / aftertouch (0xDn). channel is 1–16;
/// pressure is 0–127.
void midi_channel_pressure(uint8_t channel, uint8_t pressure);

/// Send a single MIDI real-time byte (0xF8 Clock, 0xFA Start, 0xFC Stop, etc.).
/// Real-time messages may be sent between bytes of any other message per the
/// MIDI spec. Called from the MIDI clock thread; must be non-blocking.
void midi_send_realtime(uint8_t byte);

/// Send a raw SysEx message. `data` must include the leading 0xF0 and
/// trailing 0xF7 delimiters. `len` is the total byte count including both.
void midi_send_sysex(const uint8_t* data, std::size_t len);

/// Close the MIDI port. Called on clean shutdown.
void midi_shutdown();

/// Enumerate all available MIDI output and input ports, printing each to stdout.
/// Format: "output N: <name>" and "input N: <name>". One port per line.
/// Called when the binary is invoked with --list-ports; exits after printing.
void midi_list_ports();
