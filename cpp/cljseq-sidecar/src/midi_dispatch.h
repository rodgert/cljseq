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

/// Close the MIDI port. Called on clean shutdown.
void midi_shutdown();
