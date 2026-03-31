# SPDX-License-Identifier: LGPL-2.1-or-later
cmake_minimum_required(VERSION 3.20)

# ---------------------------------------------------------------------------
# cljseq-audio — CLAP / audio binary
#
# Hosts CLAP plugins; provides direct Eurorack CV via Expert Sleepers ES-9
# or compatible DC-coupled interface. Uses libcljseq-rt for scheduling.
#
# NOTE: If cljseq-audio is ever built with Ableton Link, the combined binary
# becomes GPL-2.0-or-later. See doc/licensing.md for rationale.
# ---------------------------------------------------------------------------

add_executable(cljseq-audio
  src/main.cpp
  src/clap_host.cpp
  src/audio_io.cpp
)

target_link_libraries(cljseq-audio
  PRIVATE cljseq-rt
)

set_target_properties(cljseq-audio PROPERTIES
  CXX_STANDARD 17
)
