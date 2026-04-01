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
#
# Before calling FetchContent_MakeAvailable we set three things:
#
# 1. LINK_BUILD_TESTS=OFF / LINK_BUILD_EXAMPLES=OFF — prevents Link from
#    pulling in Catch2 and building example programs we don't need.
#
# 2. LINK_ASIO_DIR — point Link at the Asio we already fetched in the root
#    CMakeLists.txt. Without this, Link will attempt to fetch its own copy
#    of Asio, causing a name collision with our existing `asio` interface
#    target and a redundant download.
#
# Tag note: Ableton Link uses tags of the form "Link-X.Y.Z". Check
# https://github.com/Ableton/link/releases for the current stable tag.
# Update deliberately and record the reason here.
# ---------------------------------------------------------------------------
set(LINK_BUILD_TESTS    OFF CACHE BOOL "Do not build Link unit tests"    FORCE)
set(LINK_BUILD_EXAMPLES OFF CACHE BOOL "Do not build Link example apps"  FORCE)

# asio_SOURCE_DIR is set by FetchContent_MakeAvailable(asio) in the root
# CMakeLists.txt. Link expects the path to contain asio.hpp directly under
# <dir>/asio/include/, which matches our layout.
if(DEFINED asio_SOURCE_DIR)
  set(LINK_ASIO_DIR "${asio_SOURCE_DIR}/asio/include" CACHE PATH
      "Asio include dir shared with cljseq root build" FORCE)
  message(STATUS "libcljseq-link: sharing Asio from ${LINK_ASIO_DIR}")
else()
  message(WARNING "libcljseq-link: asio_SOURCE_DIR not set — Link may fetch its own Asio")
endif()

FetchContent_Declare(ableton-link
  GIT_REPOSITORY https://github.com/Ableton/link.git
  GIT_TAG        Link-3.0.4        # last stable tag as of Sprint 9 (2026-04)
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
