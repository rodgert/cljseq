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
  src/keyboard_capture.cpp
  src/midi_clock.cpp
  src/midi_dispatch.cpp
  src/midi_monitor.cpp
  src/osc_dispatch.cpp
)

# ---------------------------------------------------------------------------
# SQLite3 — session log persistence (0x30/0x31/0x32 IPC messages)
#
# Try CMake's built-in finder first, then fall back to a manual search of
# common Homebrew and system paths on macOS.
# ---------------------------------------------------------------------------
find_package(SQLite3 QUIET)

if(NOT SQLite3_FOUND)
  # Homebrew on Apple Silicon (/opt/homebrew) and Intel (/usr/local/opt)
  find_path(SQLite3_INCLUDE_DIRS sqlite3.h
    PATHS /opt/homebrew/opt/sqlite/include
          /usr/local/opt/sqlite/include
          /usr/include
          /usr/local/include)
  find_library(SQLite3_LIBRARIES sqlite3
    PATHS /opt/homebrew/opt/sqlite/lib
          /usr/local/opt/sqlite/lib
          /usr/lib
          /usr/local/lib)
  if(SQLite3_INCLUDE_DIRS AND SQLite3_LIBRARIES)
    set(SQLite3_FOUND TRUE)
    if(NOT TARGET SQLite3::SQLite3)
      add_library(SQLite3::SQLite3 UNKNOWN IMPORTED)
      set_target_properties(SQLite3::SQLite3 PROPERTIES
        IMPORTED_LOCATION "${SQLite3_LIBRARIES}"
        INTERFACE_INCLUDE_DIRECTORIES "${SQLite3_INCLUDE_DIRS}")
    endif()
  endif()
endif()

if(SQLite3_FOUND)
  target_link_libraries(cljseq-sidecar PRIVATE SQLite3::SQLite3)
  target_compile_definitions(cljseq-sidecar PRIVATE CLJSEQ_WITH_SQLITE)
  message(STATUS "cljseq-sidecar: SQLite3 found — tx-log durability enabled")
else()
  message(STATUS "cljseq-sidecar: SQLite3 not found — tx-log durability disabled")
endif()

target_link_libraries(cljseq-sidecar
  PRIVATE cljseq-rt
          asio
          rtmidi
)

# CGEventTap requires ApplicationServices.framework on macOS.
if(APPLE)
  target_link_libraries(cljseq-sidecar
    PRIVATE "-framework ApplicationServices"
  )
endif()

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
