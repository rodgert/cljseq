# cljseq — Testing Backlog

Items that need real-world verification before they can be considered fully
validated. Unlike CHANGELOG prerequisites (which gate a specific release),
these persist until someone has verified them in the right context.

Tags:
- `[hardware]` — needs a physical device in hand
- `[visual]`   — needs eyes on a running system
- `[integration]` — needs the full stack (SC, MIDI routing, audio output)

---

## Open

### Web control surface — build and visual check `[visual]`

`feat/ui-control-surface` branch; Tiers 1–3 implemented (beat pulse,
level-meter sliders, XY pad).

**Verify:**
1. `npx shadow-cljs release app` produces `resources/public/js/main.js`
2. Start server: `(server/start-server! :port 7177)` in REPL
3. Open `http://localhost:7177/` and confirm:
   - Beat dot pulses in time with BPM; downbeat brighter than beats 2–4
   - Float/int slider tracks fill proportionally (level-meter visual)
   - XY pad: select two float paths, drag updates both via WebSocket
   - Grouped ctrl tree collapses/expands; loop monitor shows running loops

---

### Hydrasynth phrases — 64 patterns `[hardware]`

All 64 built-in Hydrasynth phrases are transcribed and loaded
(`resources/arpeggios/built-in.edn`), but flagged `:verified? false`.

**Verify** on a physical Hydrasynth (Explorer, Desktop, Deluxe, or Keyboard 61):
- Play each phrase via `(arp/play-phrase! :phrase-N ...)` and confirm it matches
  the corresponding pattern on the synth
- Flip `:verified? true` in the EDN for each confirmed phrase

---

### ctrl→MIDI CC live verification `[hardware][integration]`

`ctrl/bind!` with `{:type :midi-cc}` is implemented and tested in unit tests.
Not confirmed live with a physical synth receiving CC messages.

**Verify:**
- Bind a ctrl path to a MIDI CC on a connected device
- `ctrl/set!` the path from the REPL and confirm the synth parameter moves
- Confirm bidirectional if hardware sends CC (e.g. knob turn updates ctrl tree)

---

### s42 mix levels and ostinati balance `[hardware][integration]`

s42 patch (`:s42`) and the ostinati session were last tuned by ear during a
live session. Levels noted as approximately correct but not locked down.

**Verify** with SC running and audio output connected:
- Drone voice, VCO voice, papa voice levels relative to each other
- Ostinati mix against s42 voices
- Confirm stereo reverb workaround still holds (SC 3.14 integer-arg bug)

---

### SC stereo reverb `[hardware][integration]`

Stereo reverb workaround for the SC 3.14 integer-argument bug is in place.
Not re-verified since the fix was applied.

**Verify** with a headless SC setup:
- `:s42` patch plays with audible stereo reverb
- No SC error on the `/cmd` bus during startup
- `sc-restart!` cleanly re-registers the patch after an SC crash

---

### Arturia KeyStep device map `[hardware]`

KeyStep (original) device map is marked `hardware-unverified` in the CHANGELOG.
Single-track, touch strip velocity, no MPE.

**Verify:**
- Note-on/off on correct channel
- Touch strip maps to the expected CC
- `defpatch!` / `play!` round-trip without errors

---

## Closed

*(move items here with a date when verified)*
