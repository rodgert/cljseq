// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once

#include <cljseq/link_bridge.h>

/// Accept one TCP connection on localhost:port and run the async IPC read/write loop.
///
/// Blocks until a Shutdown message is received or the connection is closed.
///
/// Received NoteOn/NoteOff/CC messages are forwarded to cljseq::scheduler_enqueue.
/// Received LinkEnable/LinkDisable/LinkSetBpm messages are forwarded to `link`.
///
/// When `link` fires its state callback, a 0x80 LinkState frame is written back
/// to the connected JVM over the same TCP socket (bidirectional IPC).
///
/// When `midi_in_port >= 0`, an RtMidiIn port is opened on that index and every
/// received MIDI message is pushed to the JVM as a 0x20 MidiIn frame:
///   payload (12 bytes): [int64 time_ns][uint8 status][uint8 b1][uint8 b2][uint8 reserved]
///
/// When `enable_kbd` is true, a CGEventTap (macOS) is installed to capture global
/// keyboard events.  Each key event is pushed to the JVM as a 0x21 KbdEvent frame:
///   payload (3 bytes): [uint8 keycode][uint8 modifiers][uint8 event_type]
///   event_type: 0=keydown, 1=keyup, 2=keyrepeat
///   modifiers:  bit 0=shift, bit 1=ctrl, bit 2=option/alt, bit 3=command
/// Requires one-time macOS Accessibility permission for the sidecar process.
///
/// `link` must outlive ipc_serve(). A NullLinkBridge may be passed safely.
void ipc_serve(unsigned short port, cljseq::LinkBridge& link,
               int midi_in_port = -1, bool enable_kbd = false);
