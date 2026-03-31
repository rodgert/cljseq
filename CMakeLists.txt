# SPDX-License-Identifier: LGPL-2.1-or-later
cmake_minimum_required(VERSION 3.20)

project(cljseq
  VERSION 0.1.0
  DESCRIPTION "cljseq — music-theory-aware Clojure sequencer runtime"
  LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Build options
# ---------------------------------------------------------------------------

option(CLJSEQ_ENABLE_LINK "Build with Ableton Link support (GPL-2.0-or-later)" OFF)
option(CLJSEQ_BUILD_SIDECAR "Build cljseq-sidecar MIDI/CV binary" ON)
option(CLJSEQ_BUILD_AUDIO "Build cljseq-audio CLAP/audio binary" ON)
option(CLJSEQ_BUILD_TESTS "Build C++ unit tests" OFF)

if(CLJSEQ_ENABLE_LINK)
  message(STATUS "Ableton Link support enabled — combined binary is GPL-2.0-or-later")
  add_compile_definitions(CLJSEQ_WITH_LINK)
endif()

# ---------------------------------------------------------------------------
# Subdirectories
# ---------------------------------------------------------------------------

add_subdirectory(cpp/libcljseq-rt)

if(CLJSEQ_BUILD_SIDECAR)
  add_subdirectory(cpp/cljseq-sidecar)
endif()

if(CLJSEQ_BUILD_AUDIO)
  add_subdirectory(cpp/cljseq-audio)
endif()
