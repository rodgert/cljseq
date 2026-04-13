// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once

#include <cstdint>
#include <functional>
#include <vector>

namespace cljseq {

/// Callback invoked on each keyboard event.
///
/// keycode    — macOS virtual keycode (kVK_* constants)
/// modifiers  — compressed modifier byte:
///                bit 0 = shift, bit 1 = control, bit 2 = option/alt, bit 3 = command
/// event_type — 0=keydown, 1=keyup, 2=keyrepeat
using KbdCallback = std::function<void(uint8_t keycode,
                                       uint8_t modifiers,
                                       uint8_t event_type)>;

/// Start global keyboard capture.
///
/// Only keys whose keycode appears in `capture_keycodes` are forwarded to `cb`.
/// An empty `capture_keycodes` vector captures all keys.
///
/// On macOS uses CGEventTap — requires Accessibility permission for the process.
/// Returns true on success, false if the event tap could not be created.
///
/// On non-macOS platforms this is a no-op stub that always returns false.
bool kbd_capture_start(const std::vector<uint8_t>& capture_keycodes,
                       KbdCallback cb);

/// Stop keyboard capture and release the CGEventTap.
/// Safe to call even if capture is not active (no-op).
void kbd_capture_stop();

} // namespace cljseq
