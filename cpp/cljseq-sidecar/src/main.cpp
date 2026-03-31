// SPDX-License-Identifier: LGPL-2.1-or-later
//
// cljseq-sidecar — real-time MIDI sidecar process
//
// Usage: cljseq-sidecar --port <N>
//
// The JVM spawns this process and immediately connects a TCP socket to
// localhost:N. Received IPC frames are decoded by ipc.cpp and forwarded
// to the scheduler. The scheduler fires MIDI events via midi_dispatch
// at their scheduled wall-clock times.
//
// Shutdown sequence:
//   1. JVM sends Shutdown frame
//   2. ipc_serve() returns (Asio event loop stopped)
//   3. scheduler_stop() signals the scheduler thread
//   4. Scheduler thread joins
//   5. midi_shutdown() closes the MIDI port
//   6. Process exits with code 0

#include "ipc.h"
#include "midi_dispatch.h"

#include <cljseq/scheduler.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>

static unsigned short parse_port(int argc, char* argv[]) {
    for (int i = 1; i + 1 < argc; ++i) {
        if (std::strcmp(argv[i], "--port") == 0) {
            int p = std::atoi(argv[i + 1]);
            if (p > 0 && p < 65536)
                return static_cast<unsigned short>(p);
        }
    }
    std::fprintf(stderr, "usage: cljseq-sidecar --port <1-65535>\n");
    std::exit(1);
}

int main(int argc, char* argv[]) {
    unsigned short port = parse_port(argc, argv);

    // 1. Open first available MIDI output port
    if (!midi_init()) {
        std::fprintf(stderr, "[main] MIDI init failed — continuing without MIDI\n");
        // Not fatal: sidecar may be tested without a MIDI device
    }

    // 2. Wire scheduler callbacks to MIDI dispatch
    cljseq::scheduler_init({
        .note_on  = midi_note_on,
        .note_off = midi_note_off,
        .cc       = midi_cc,
    });

    // 3. Start scheduler on its own thread
    std::thread sched_thread(cljseq::scheduler_run);

    // 4. Accept the JVM connection and run the IPC event loop
    //    (blocks until Shutdown message received)
    ipc_serve(port);

    // 5. Signal scheduler to stop and wait for it to drain
    cljseq::scheduler_stop();
    sched_thread.join();

    // 6. Close MIDI port
    midi_shutdown();

    std::fprintf(stderr, "[main] clean exit\n");
    return 0;
}
