# SPDX-License-Identifier: LGPL-2.1-or-later
cmake_minimum_required(VERSION 3.20)

# ---------------------------------------------------------------------------
# cljseq-sidecar — MIDI / CV sidecar binary
#
# Receives scheduling commands from the JVM via IPC (Q16: direct JVM-to-process
# IPC; routing key in cljseq.sidecar Clojure map).
# Dispatches: MIDI CC/note, OSC bundles, CV gate (via libcljseq-rt).
# ---------------------------------------------------------------------------

add_executable(cljseq-sidecar
  src/main.cpp
  src/ipc.cpp
  src/midi_dispatch.cpp
  src/osc_dispatch.cpp
)

target_link_libraries(cljseq-sidecar
  PRIVATE cljseq-rt
)

set_target_properties(cljseq-sidecar PROPERTIES
  CXX_STANDARD 17
)
