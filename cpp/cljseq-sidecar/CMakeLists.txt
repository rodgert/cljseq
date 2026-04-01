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
          asio
          rtmidi
)

# When Link is enabled, link against cljseq-link (GPL-2.0-or-later).
# The resulting binary is GPL-2.0-or-later; sidecar source remains LGPL.
if(CLJSEQ_ENABLE_LINK)
  target_link_libraries(cljseq-sidecar PRIVATE cljseq-link)
endif()

target_include_directories(cljseq-sidecar
  PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src
)

set_target_properties(cljseq-sidecar PROPERTIES
  CXX_STANDARD 17
)
