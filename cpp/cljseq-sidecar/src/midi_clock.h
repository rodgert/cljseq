// SPDX-License-Identifier: LGPL-2.1-or-later
//
// midi_clock.h — MIDI clock output derived from Ableton Link
//
// The clock thread emits 24 PPQN MIDI clock pulses (0xF8) with absolute
// timing derived from the Link timeline on every iteration — no accumulated
// drift regardless of how long the session runs.
//
// Transport transitions send MIDI Start (0xFA) and MIDI Stop (0xFC) on the
// same output port as note data.
//
// Lifecycle (called from main.cpp):
//   midi_clock_init(link)   — store Link reference; does not start thread
//   midi_clock_start()      — launch clock thread
//   midi_clock_stop()       — signal thread and join; safe to call if not started
//
// Transport control (called from ipc.cpp on LinkEnable / LinkDisable):
//   midi_clock_set_playing(true)  — thread begins emitting 0xFA + 0xF8 pulses
//   midi_clock_set_playing(false) — thread emits 0xFC and idles
#pragma once

#include <cljseq/link_bridge.h>

/// Inject the Link bridge reference. Must be called before midi_clock_start().
void midi_clock_init(cljseq::LinkBridge& link);

/// Start the clock thread. Idempotent — safe to call multiple times.
void midi_clock_start();

/// Signal the clock thread to exit and wait for it to join.
/// Sends 0xFC if currently playing. Safe to call before midi_clock_start().
void midi_clock_stop();

/// Set transport state. When transitioning false→true the thread sends 0xFA
/// and begins pulsing. When transitioning true→false it sends 0xFC and idles.
void midi_clock_set_playing(bool playing);
