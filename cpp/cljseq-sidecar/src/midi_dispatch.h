// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#include <cstdint>

/// Open the first available MIDI output port.
/// Logs available ports to stderr on startup.
/// Returns true on success, false if no ports are available.
bool midi_init();

/// Dispatch a NoteOn. channel is 1–16; note and velocity are 0–127.
void midi_note_on(uint8_t channel, uint8_t note, uint8_t velocity);

/// Dispatch a NoteOff. channel is 1–16; note is 0–127.
void midi_note_off(uint8_t channel, uint8_t note);

/// Dispatch a Control Change. channel is 1–16; cc and value are 0–127.
void midi_cc(uint8_t channel, uint8_t cc, uint8_t value);

/// Close the MIDI port. Called on clean shutdown.
void midi_shutdown();
