// SPDX-License-Identifier: LGPL-2.1-or-later
//
// MIDI dispatch — RtMidi wrapper for cljseq-sidecar
//
// Phase 1: opens the first available MIDI output port. Port selection by name
// is deferred to Phase 2 (defdevice EDN maps, Q55).

#include "midi_dispatch.h"

#include <RtMidi.h>
#include <cstdio>
#include <memory>
#include <vector>

namespace {

std::unique_ptr<RtMidiOut> g_midi;

} // anonymous namespace

bool midi_init() {
    try {
        g_midi = std::make_unique<RtMidiOut>();
    } catch (const RtMidiError& e) {
        std::fprintf(stderr, "[midi] RtMidiOut init error: %s\n", e.what());
        return false;
    }

    unsigned int port_count = g_midi->getPortCount();
    std::fprintf(stderr, "[midi] available output ports (%u):\n", port_count);
    for (unsigned int i = 0; i < port_count; ++i) {
        std::fprintf(stderr, "  [%u] %s\n", i, g_midi->getPortName(i).c_str());
    }

    if (port_count == 0) {
        std::fprintf(stderr, "[midi] no output ports available — MIDI disabled\n");
        return false;
    }

    // Phase 1: open the first available port
    try {
        g_midi->openPort(0);
        std::fprintf(stderr, "[midi] opened port [0] %s\n",
                     g_midi->getPortName(0).c_str());
    } catch (const RtMidiError& e) {
        std::fprintf(stderr, "[midi] failed to open port 0: %s\n", e.what());
        return false;
    }

    return true;
}

void midi_note_on(uint8_t channel, uint8_t note, uint8_t velocity) {
    if (!g_midi || !g_midi->isPortOpen()) return;
    // MIDI channel is 1-indexed in cljseq; wire format is 0-indexed
    uint8_t ch = static_cast<uint8_t>((channel - 1) & 0x0F);
    std::vector<uint8_t> msg = { static_cast<uint8_t>(0x90 | ch), note, velocity };
    try {
        g_midi->sendMessage(&msg);
    } catch (const RtMidiError& e) {
        std::fprintf(stderr, "[midi] sendMessage error: %s\n", e.what());
    }
}

void midi_note_off(uint8_t channel, uint8_t note) {
    if (!g_midi || !g_midi->isPortOpen()) return;
    uint8_t ch = static_cast<uint8_t>((channel - 1) & 0x0F);
    std::vector<uint8_t> msg = { static_cast<uint8_t>(0x80 | ch), note, 0x00 };
    try {
        g_midi->sendMessage(&msg);
    } catch (const RtMidiError& e) {
        std::fprintf(stderr, "[midi] sendMessage error: %s\n", e.what());
    }
}

void midi_cc(uint8_t channel, uint8_t cc, uint8_t value) {
    if (!g_midi || !g_midi->isPortOpen()) return;
    uint8_t ch = static_cast<uint8_t>((channel - 1) & 0x0F);
    std::vector<uint8_t> msg = { static_cast<uint8_t>(0xB0 | ch), cc, value };
    try {
        g_midi->sendMessage(&msg);
    } catch (const RtMidiError& e) {
        std::fprintf(stderr, "[midi] sendMessage error: %s\n", e.what());
    }
}

void midi_shutdown() {
    if (g_midi && g_midi->isPortOpen()) {
        g_midi->closePort();
        std::fprintf(stderr, "[midi] port closed\n");
    }
    g_midi.reset();
}
