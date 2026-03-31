// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once

/// Accept one TCP connection on localhost:port and run the async IPC read loop.
/// Blocks until a Shutdown message is received or the connection is closed.
/// Received NoteOn/NoteOff/CC messages are forwarded to cljseq::scheduler_enqueue.
void ipc_serve(unsigned short port);
