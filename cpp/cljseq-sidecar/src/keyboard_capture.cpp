// SPDX-License-Identifier: LGPL-2.1-or-later
//
// keyboard_capture.cpp — global keyboard event tap for cljseq.ivk
//
// macOS implementation: CGEventTap via ApplicationServices.
//   Requires Accessibility permission for the sidecar process (one-time grant).
//
// Linux / other: stub that returns false from kbd_capture_start().

#include "keyboard_capture.h"

#include <cstdio>

#ifdef __APPLE__

#include <ApplicationServices/ApplicationServices.h>
#include <thread>
#include <unordered_set>
#include <atomic>

namespace {

// ---------------------------------------------------------------------------
// State — all written once during kbd_capture_start(), read from callback
// ---------------------------------------------------------------------------

std::unordered_set<uint8_t>  g_capture_set;
cljseq::KbdCallback          g_callback;
CFMachPortRef                g_tap        = nullptr;
CFRunLoopSourceRef           g_source     = nullptr;
std::thread                  g_run_thread;
std::atomic<bool>            g_running{false};

// ---------------------------------------------------------------------------
// Modifier compression
//   bit 0 = shift, bit 1 = control, bit 2 = option/alt, bit 3 = command
// ---------------------------------------------------------------------------

uint8_t compress_flags(CGEventFlags f) noexcept {
    uint8_t m = 0;
    if (f & kCGEventFlagMaskShift)    m |= 0x01;
    if (f & kCGEventFlagMaskControl)  m |= 0x02;
    if (f & kCGEventFlagMaskAlternate) m |= 0x04;
    if (f & kCGEventFlagMaskCommand)  m |= 0x08;
    return m;
}

// ---------------------------------------------------------------------------
// CGEventTap callback
// ---------------------------------------------------------------------------

CGEventRef tap_callback(CGEventTapProxy /*proxy*/,
                        CGEventType    type,
                        CGEventRef     event,
                        void*          /*user_data*/) {
    if (!g_running.load(std::memory_order_relaxed))
        return event;

    // Re-enable the tap if it was disabled (can happen if the callback is slow)
    if (type == kCGEventTapDisabledByTimeout ||
        type == kCGEventTapDisabledByUserInput) {
        if (g_tap)
            CGEventTapEnable(g_tap, true);
        return event;
    }

    uint8_t evt_type;
    if      (type == kCGEventKeyDown)  evt_type = 0;
    else if (type == kCGEventKeyUp)    evt_type = 1;
    else                               return event; // not a key event

    auto keycode = static_cast<uint8_t>(
        CGEventGetIntegerValueField(event, kCGKeyboardEventKeycode));

    // Filter: skip keys not in the capture set (unless capture set is empty = all)
    if (!g_capture_set.empty() && g_capture_set.find(keycode) == g_capture_set.end())
        return event;

    // Suppress key repeat by checking kCGKeyboardEventAutorepeat
    int64_t is_repeat = CGEventGetIntegerValueField(event, kCGKeyboardEventAutorepeat);
    if (is_repeat)
        evt_type = 2; // key repeat

    uint8_t mods = compress_flags(CGEventGetFlags(event));

    g_callback(keycode, mods, evt_type);

    // Pass the event through — we are passive (passive tap won't block input)
    return event;
}

} // anonymous namespace

namespace cljseq {

bool kbd_capture_start(const std::vector<uint8_t>& capture_keycodes,
                       KbdCallback cb) {
    if (g_running.load())
        return true; // already running

    if (!cb) return false;

    // Check Accessibility permission
    if (!AXIsProcessTrusted()) {
        std::fprintf(stderr,
            "[kbd] CGEventTap requires Accessibility permission.\n"
            "[kbd] Open System Settings → Privacy & Security → Accessibility\n"
            "[kbd] and enable this application.\n");
        return false;
    }

    g_capture_set = std::unordered_set<uint8_t>(
        capture_keycodes.begin(), capture_keycodes.end());
    g_callback = std::move(cb);

    CGEventMask mask = CGEventMaskBit(kCGEventKeyDown)
                     | CGEventMaskBit(kCGEventKeyUp);

    g_tap = CGEventTapCreate(
        kCGSessionEventTap,         // tap at session level (global)
        kCGHeadInsertEventTap,      // insert at head
        kCGEventTapOptionListenOnly, // passive — does not consume events
        mask,
        tap_callback,
        nullptr);

    if (!g_tap) {
        std::fprintf(stderr, "[kbd] CGEventTapCreate failed — check Accessibility permission\n");
        return false;
    }

    g_source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, g_tap, 0);
    if (!g_source) {
        CFRelease(g_tap);
        g_tap = nullptr;
        std::fprintf(stderr, "[kbd] CFMachPortCreateRunLoopSource failed\n");
        return false;
    }

    g_running.store(true, std::memory_order_release);

    // Run the tap's CFRunLoop on a dedicated thread
    g_run_thread = std::thread([]() {
        CFRunLoopAddSource(CFRunLoopGetCurrent(), g_source, kCFRunLoopDefaultMode);
        CGEventTapEnable(g_tap, true);
        std::fprintf(stderr, "[kbd] keyboard capture active\n");
        CFRunLoopRun(); // blocks until CFRunLoopStop is called
        std::fprintf(stderr, "[kbd] keyboard capture stopped\n");
    });

    return true;
}

void kbd_capture_stop() {
    if (!g_running.exchange(false))
        return; // not running

    // Stop the run loop — this unblocks CFRunLoopRun() on the capture thread
    if (g_run_thread.joinable()) {
        // CFRunLoopStop must be called on the same thread's run loop.
        // We can't call it directly here; use a CFRunLoopTimer trick or
        // just stop the tap and let CFRunLoopRun exit on its own when
        // the source is removed.
        CGEventTapEnable(g_tap, false);

        // Remove the source so the run loop has nothing left to service.
        if (g_source)
            CFRunLoopRemoveSource(
                CFRunLoopGetMain(), // signal the tap thread's loop to drain
                g_source,
                kCFRunLoopDefaultMode);

        // Give the thread a moment to drain, then detach.
        g_run_thread.detach();
    }

    if (g_source) { CFRelease(g_source); g_source = nullptr; }
    if (g_tap)    { CFRelease(g_tap);    g_tap    = nullptr; }
    g_callback = nullptr;
    g_capture_set.clear();
}

} // namespace cljseq

#else // !__APPLE__

namespace cljseq {

bool kbd_capture_start(const std::vector<uint8_t>& /*keycodes*/,
                       KbdCallback /*cb*/) {
    std::fprintf(stderr,
        "[kbd] keyboard capture not implemented on this platform "
        "(macOS only in this build)\n");
    return false;
}

void kbd_capture_stop() {}

} // namespace cljseq

#endif // __APPLE__
