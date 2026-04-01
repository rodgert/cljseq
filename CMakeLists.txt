# SPDX-License-Identifier: LGPL-2.1-or-later
cmake_minimum_required(VERSION 3.20)

project(cljseq
  VERSION 0.1.0
  DESCRIPTION "cljseq — music-theory-aware Clojure sequencer runtime"
  LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Dependency policy (Q51)
#
# - No Boost. C++17 STL is sufficient for all use cases.
# - Standalone Asio (BSL-1.0, header-only) for async I/O / OSC server.
#   Fetched via FetchContent at a pinned tag — no system package assumption.
# - Ableton Link via FetchContent, behind CLJSEQ_ENABLE_LINK opt-in flag.
# - CLAP headers via git submodule (header-only, no build step).
# - General rule: FetchContent + pinned tag for anything with a build step;
#   no find_package for libraries that diverge across distro packaging.
# ---------------------------------------------------------------------------

include(FetchContent)

# Standalone Asio — async I/O for OSC server (Q12, Q51)
# BSL-1.0; header-only; same API as Boost.Asio without the Boost dependency.
# Pin: asio-1-30-2 — update deliberately and document the reason.
FetchContent_Declare(asio
  GIT_REPOSITORY https://github.com/chriskohlhoff/asio.git
  GIT_TAG        asio-1-30-2
  GIT_SHALLOW    TRUE)
FetchContent_MakeAvailable(asio)
add_library(asio INTERFACE)
target_include_directories(asio INTERFACE ${asio_SOURCE_DIR}/asio/include)
target_compile_definitions(asio INTERFACE ASIO_STANDALONE)

# RtMidi — cross-platform MIDI I/O (MIT)
# Pin: 6.0.0 — stable release; matches Phase 1 handoff spec (Q51).
FetchContent_Declare(rtmidi
  GIT_REPOSITORY https://github.com/thestk/rtmidi.git
  GIT_TAG        6.0.0
  GIT_SHALLOW    TRUE)
set(RTMIDI_BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(rtmidi)

# ---------------------------------------------------------------------------
# Build options
# ---------------------------------------------------------------------------

option(CLJSEQ_ENABLE_LINK "Build with Ableton Link support (GPL-2.0-or-later)" OFF)
option(CLJSEQ_BUILD_SIDECAR "Build cljseq-sidecar MIDI/CV binary" ON)
option(CLJSEQ_BUILD_AUDIO "Build cljseq-audio CLAP/audio binary" ON)
option(CLJSEQ_BUILD_TESTS "Build C++ unit tests" OFF)

if(CLJSEQ_ENABLE_LINK)
  message(STATUS "Ableton Link support enabled — combined binary is GPL-2.0-or-later")
  # libcljseq-link is GPL-2.0-or-later; must be added AFTER libcljseq-rt
  # so that its FetchContent for the Link SDK runs after our own dependencies.
endif()

# ---------------------------------------------------------------------------
# Subdirectories
# ---------------------------------------------------------------------------

add_subdirectory(cpp/libcljseq-rt)

if(CLJSEQ_ENABLE_LINK)
  add_subdirectory(cpp/libcljseq-link)
endif()

if(CLJSEQ_BUILD_SIDECAR)
  add_subdirectory(cpp/cljseq-sidecar)
endif()

if(CLJSEQ_BUILD_AUDIO)
  add_subdirectory(cpp/cljseq-audio)
endif()
