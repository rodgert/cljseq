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
/// `link` must outlive ipc_serve(). A NullLinkBridge may be passed safely.
void ipc_serve(unsigned short port, cljseq::LinkBridge& link);
