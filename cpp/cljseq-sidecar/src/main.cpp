// SPDX-License-Identifier: LGPL-2.1-or-later
//
// cljseq-sidecar — real-time MIDI sidecar process
//
// Usage: cljseq-sidecar --port <N>
//
// The JVM spawns this process and immediately connects a TCP socket to
// localhost:N. Received IPC frames are decoded by ipc.cpp and forwarded
// to the scheduler and (optionally) the Link engine. The scheduler fires
// MIDI events via midi_dispatch at their scheduled wall-clock times.
//
// When built with CLJSEQ_ENABLE_LINK=ON (GPL-2.0-or-later), a real LinkEngine
// is injected. Otherwise a NullLinkBridge (no-op) is used and the binary
// remains LGPL-2.1-or-later.
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

#include <cljseq/link_bridge.h>
#include <cljseq/scheduler.h>

#ifdef CLJSEQ_WITH_LINK
#  include <cljseq/link_engine.h>
#endif

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
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

    // 1. Select Link bridge based on build configuration.
    //    The sidecar's business logic is identical in both cases.
#ifdef CLJSEQ_WITH_LINK
    // initial_bpm=120 — overridden immediately by LinkEnable message from JVM.
    cljseq::LinkEngine link_engine(120.0);
    cljseq::LinkBridge& link = link_engine;
    std::fprintf(stderr, "[main] Ableton Link enabled (GPL-2.0-or-later build)\n");
#else
    cljseq::NullLinkBridge null_link;
    cljseq::LinkBridge& link = null_link;
#endif

    // 2. Open first available MIDI output port.
    if (!midi_init()) {
        std::fprintf(stderr, "[main] MIDI init failed — continuing without MIDI\n");
    }

    // 3. Wire scheduler callbacks to MIDI dispatch.
    cljseq::scheduler_init({
        .note_on  = midi_note_on,
        .note_off = midi_note_off,
        .cc       = midi_cc,
    });

    // 4. Start scheduler on its own thread.
    std::thread sched_thread(cljseq::scheduler_run);

    // 5. Accept the JVM connection and run the bidirectional IPC event loop.
    //    (blocks until Shutdown message received)
    ipc_serve(port, link);

    // 6. Signal scheduler to stop and wait for it to drain.
    cljseq::scheduler_stop();
    sched_thread.join();

    // 7. Close MIDI port.
    midi_shutdown();

    std::fprintf(stderr, "[main] clean exit\n");
    return 0;
}
