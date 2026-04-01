# SPDX-License-Identifier: GPL-2.0-or-later
#
# libcljseq-link — Ableton Link engine for cljseq-sidecar
#
# IMPORTANT LICENSE NOTE:
#   This library is GPL-2.0-or-later because it links against the Ableton Link
#   SDK (GPL-2.0-or-later). Binaries that link against this library are subject
#   to GPL-2.0-or-later terms. The rest of the cljseq C++ code is LGPL-2.1-or-later.
#
# This CMakeLists.txt is only added to the build when CLJSEQ_ENABLE_LINK=ON.
# The Ableton Link SDK is fetched via FetchContent at a pinned tag.
cmake_minimum_required(VERSION 3.20)

# ---------------------------------------------------------------------------
# Fetch Ableton Link SDK
#
# Link ships its own CMake integration and is a well-behaved FetchContent
# target. Pin to a specific tag; update deliberately and document the reason.
# Tag 3.1.0 is the latest stable release as of Sprint 9 (2026-04).
# ---------------------------------------------------------------------------
FetchContent_Declare(ableton-link
  GIT_REPOSITORY https://github.com/Ableton/link.git
  GIT_TAG        Link-3.1.0
  GIT_SHALLOW    TRUE)
FetchContent_MakeAvailable(ableton-link)

# ---------------------------------------------------------------------------
# libcljseq-link target
# ---------------------------------------------------------------------------
add_library(cljseq-link STATIC
  src/link_engine.cpp
)

target_include_directories(cljseq-link
  PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include>
  PRIVATE
    src
)

target_link_libraries(cljseq-link
  PUBLIC  cljseq-rt     # inherits LinkBridge interface
  PRIVATE Ableton::Link
)

set_target_properties(cljseq-link PROPERTIES
  CXX_STANDARD 17
  CXX_STANDARD_REQUIRED ON
)
