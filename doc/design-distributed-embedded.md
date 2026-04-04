# Distributed and Embedded cljseq — Design Notes

*Captured 2026-04-04. Status: exploratory design / pre-sprint.*

---

## Motivation

The standard cljseq deployment is a JVM process on a laptop, connected to synthesizers via
a USB-MIDI interface (e.g. iConnectivity Mio). This works well for a single-musician setup
at a desk, but two failure modes and one opportunity drove us to think further:

1. **Mio unresponsiveness.** iConnectivity Mio units (and similar USB-MIDI hubs) accumulate
   state and become unresponsive over days of continuous use, requiring a power cycle. This
   is a known failure mode in the hardware. Removing the Mio from the critical path for
   locally-attached devices is the cleanest fix.

2. **Multi-node coordination.** A live set with multiple musicians, each running their own
   sequencer, needs a shared clock. MIDI clock over cable is the legacy solution, but it
   introduces a master/slave topology and cable routing constraints. Ableton Link solves
   this over LAN without a master.

3. **Embedded appliances.** A Raspberry Pi (or similar SBC) running cljseq as headless
   firmware, with MIDI devices attached directly via USB, is a natural evolution of the
   §1.1 vision. The JVM runs fine on a Pi 4/5. The Pi connects to the LAN via Wi-Fi or
   Ethernet and participates in a Link session. Local MIDI devices are never routed over
   the network.

---

## 1. MIDI Tuning Standard (MTS) Support

### Background

Hydrasynth (and many other synthesizers) support importing microtonal tuning via the
**MIDI Tuning Standard** (MTS) Bulk Dump SysEx message (F0 7E 7F 08 01 ... F7). This
408-byte message retunes all 128 MIDI keys simultaneously — a much more efficient path
than per-note pitch bend, and the only path to polyphonic microtonality on hardware
without MPE.

### Proposed API

```clojure
;; cljseq.scala — extend with MTS generation

(defn scale->mts-bytes
  "Generate an MTS Bulk Dump SysEx byte array (408 bytes) for `ms`.

  Each MIDI note k is mapped to the nearest semitone in `ms` plus a fine-tune
  offset. If `kbm` is provided, the keyboard map controls which scale degrees
  each MIDI key plays (unmapped keys are left at 12-TET).

  The returned byte array is suitable for passing directly to sidecar/send-sysex!.

  Example:
    (def ms  (scala/load-scl \"31edo.scl\"))
    (def kbm (scala/load-kbm \"whitekeys.kbm\"))
    (sidecar/send-sysex! (scala/scale->mts-bytes ms kbm))"
  ([ms]       (scale->mts-bytes ms nil))
  ([ms kbm]   ...))

;; cljseq.sidecar — new MTS helper

(defn send-mts!
  "Retune the connected synth using MTS Bulk Dump SysEx.

  Generates the MTS byte array from `ms` (and optional `kbm`) and sends it
  as a single SysEx message.

  Example:
    (send-mts! (scala/load-scl \"31edo.scl\"))
    (send-mts! ms kbm)"
  ([ms]       (send-mts! ms nil))
  ([ms kbm]   (send-sysex! (scala/scale->mts-bytes ms kbm))))
```

### MTS Byte Format

Each of the 128 notes is encoded as three bytes:
- `coarse` (0–127): the nearest MIDI semitone
- `fine-msb` (0–127): upper 7 bits of the 14-bit fine-tune value
- `fine-lsb` (0–127): lower 7 bits

The fine-tune value represents ±100 cents in 16384 steps (14-bit). The math:

```
fine_14bit = round((bend_cents / 100.0) * 8192) + 8192
fine-msb   = (fine_14bit >> 7) & 0x7F
fine-lsb   = fine_14bit & 0x7F
```

This is a direct extension of the `midi->note` math already in `cljseq.scala`.

### Hydrasynth Integration

Add a `:mts/retune` node to `resources/devices/hydrasynth-explorer.edn` so the
device can be retuned via `ctrl/send!`:

```clojure
;; In hydrasynth-explorer.edn
{:path [:mts/retune]
 :type :trigger
 :on-set (fn [ms] (sidecar/send-mts! ms))}
```

---

## 2. Systemd Watchdog + Hardware Watchdog

### Problem

On an embedded Linux node (Pi or otherwise), cljseq should recover automatically
from:
- Software crashes / JVM hangs → **systemd watchdog** restarts the process
- Hardware lockups (kernel freeze, USB storm) → **hardware watchdog** reboots the board

### Design

The cljseq MIDI clock thread already produces a regular heartbeat (24 PPQN from the
Link timeline). We tap this as the watchdog keepalive source, so **the heartbeat and
the watchdog are the same thing**: if the clock thread freezes, both consumers stop
getting fed.

#### Systemd watchdog

The C++ sidecar process is the natural watchdog owner (it stays alive as long as
the JVM is alive via stdin-EOF detection). Add to `cpp/src/sidecar.cpp`:

```cpp
// Called from the MIDI clock tick, every N ticks (e.g. every 24 = once per beat)
void watchdog_notify() {
#ifdef __linux__
    sd_notify(0, "WATCHDOG=1");
#endif
}
```

Conditional build:
```cmake
find_package(PkgConfig)
pkg_check_modules(SYSTEMD libsystemd)
if(SYSTEMD_FOUND)
    target_compile_definitions(cljseq_sidecar PRIVATE HAVE_SYSTEMD)
    target_link_libraries(cljseq_sidecar ${SYSTEMD_LIBRARIES})
endif()
```

Systemd unit (`cljseq.service`):
```ini
[Unit]
Description=cljseq sequencer node
After=network.target sound.target

[Service]
Type=simple
ExecStart=/usr/local/bin/cljseq-start
WatchdogSec=10s
Restart=on-failure
RestartSec=3s

[Install]
WantedBy=multi-user.target
```

The `WatchdogSec=10s` value is conservative — at 120 BPM the clock ticks 48 times per
second; even a single missed second means hundreds of missed keepalives.

#### Hardware watchdog

On Linux, open `/dev/watchdog` and write any byte on each MIDI clock tick. If the
kernel freezes (or the sidecar stops running), the watchdog timer fires and resets
the board.

```cpp
// In sidecar startup
#ifdef __linux__
int wdog_fd = open("/dev/watchdog", O_WRONLY);
// ...
// In tick:
if (wdog_fd >= 0) write(wdog_fd, "1", 1);
#endif
```

This requires the sidecar to run as a user with `/dev/watchdog` access (typically
`root` or the `video` group on Pi OS; configurable via udev rules).

The same heartbeat source feeds all three: MIDI clock output, `sd_notify`, and
`/dev/watchdog`. One tick path, three consumers.

---

## 3. Embedded Linux Deployment (Raspberry Pi)

### Hardware inventory (available test bed)

Several Pi 3B and Pi 4 units are available and otherwise idle. A Pi 4 with an
embedded USB hub (e.g. a Pi hat or compact powered USB hub) and MIDI devices
attached directly becomes a self-contained, LAN-connected MIDI node — essentially
a custom hardware appliance in a form factor smaller than most MIDI interfaces.

Pi 3B is sufficient for single-device MIDI routing and basic sequencing. Pi 4 is
preferred for full cljseq (JVM heap + real-time scheduling). Pi 5 is headroom if
needed.

### JVM considerations

- Pi 4/5: JVM 17+ (OpenJDK Temurin ARM64) runs well. Use `-server -XX:+UseG1GC`.
- Audio/MIDI latency: use the `hw:` ALSA device directly from the sidecar (not JACK).
  JACK adds latency and complexity that buys nothing when cljseq is the only audio
  process.
- USB-MIDI: devices attached to the Pi appear as standard ALSA MIDI ports. The
  sidecar's `list-midi-ports` already enumerates them.

### What the Pi removes

- The Mio from the critical path. MIDI devices attach to the Pi's USB directly.
- Network-routed MIDI. Local devices never go over the wire.
- The laptop as a single point of failure. Each Pi node is autonomous.

### Boot-to-performance

The Pi boots into a headless Clojure nREPL. A default init script loads a `session.clj`
from `~/.cljseq/` (or `/etc/cljseq/session.clj`) and starts the session. SSH or a
local touchscreen REPL provides interactive access.

---

## 4. mDNS Peer Discovery

### Overview

Each cljseq node advertises itself on the LAN via mDNS (Avahi on Linux, built-in on
macOS via `dns-sd`). Peers discover each other without a central registry.

### Service advertisement

Service type: `_cljseq._udp.local`

TXT record payload:
```
version=<semver>
node-id=<UUID>         ; stable across restarts, generated once and persisted
started=<unix-epoch>   ; changes on each restart — key staleness signal
capabilities=link,midi,osc
osc-port=<port>
ctrl-tree-path=<OSC path prefix for this node's ctrl tree>
```

The `node-id` UUID is generated once at first boot and stored at
`~/.cljseq/node-id` (or `/var/lib/cljseq/node-id` for system-wide installs).
It persists across restarts, letting peers distinguish "same node restarted"
from "new node appeared."

### Stale node detection

mDNS provides two signals for staleness:

1. **Goodbye packet**: sent by the node on clean shutdown. Peers receive this and
   immediately mark the node's subtree as stale.
2. **TTL expiry**: if a node crashes or is network-partitioned, its mDNS records stop
   being refreshed. Standard mDNS TTL is 75% of the published TTL (typically 4500
   seconds for service records, but we publish with a shorter TTL of ~60s so stale
   nodes are detected within a minute).

When a peer is detected as stale:
- Its subtree in our ctrl tree is tombstoned (marked `:status :stale`)
- Subscriptions to its nodes are suspended
- If the peer reappears with a different `started` epoch, we treat it as a fresh
  registration and clear the tombstone

If the peer reappears with the **same** `node-id` and `started` epoch, it was likely
just a network hiccup — we resume without clearing state.

### Clojure API (sketch)

```clojure
;; cljseq.discovery (new namespace)

(defn start-discovery!
  "Advertise this node via mDNS and begin listening for peers.

  Options:
    :osc-port      — UDP port for OSC control (default 57120)
    :capabilities  — set of capability keywords (default #{:link :midi :osc})

  Returns the discovery state atom."
  [& {:keys [osc-port capabilities]
      :or   {osc-port 57120 capabilities #{:link :midi :osc}}}]
  ...)

(defn peer-nodes
  "Return a map of {node-id peer-state} for all discovered peers."
  []
  ...)

(defn on-peer-change!
  "Register a callback (fn [node-id event]) for peer join/leave/stale events.
  Events: :joined :left :stale :resumed"
  [f]
  ...)
```

---

## 5. Distributed Device Tree

### Model

Each cljseq node owns a subtree of the global ctrl tree rooted at its `node-id`:

```
/cljseq/<node-id>/midi/...      ; MIDI ports on this node
/cljseq/<node-id>/device/...    ; Named devices (Hydrasynth, etc.)
/cljseq/<node-id>/loop/...      ; Running live loops
/cljseq/<node-id>/status        ; {:bpm ... :beat ... :started ...}
```

A peer's subtree is populated by reading from its OSC control endpoint. Changes to
our own subtree are broadcast to subscribed peers via OSC.

### Two-plane protocol: OSC (control) + EDN (data)

Because this is a cljseq-to-cljseq protocol — both ends are JVM/Clojure processes —
we are not constrained to OSC's type system for everything. We define a clean split:

| Plane        | Protocol     | Use cases                                              |
|--------------|--------------|--------------------------------------------------------|
| Control      | OSC (UDP)    | Real-time: note events, CC values, beat ticks, status  |
| Data         | EDN over TCP | Bulk: ctrl-tree snapshots, device maps, loop defs, config |

**Why EDN for the data plane:**
- Both peers are JVM processes; `pr-str` / `edn/read-string` is zero-cost
- EDN naturally represents the ctrl tree (nested maps, keywords, vectors)
- No schema definition, no code generation, no serialization library to maintain
- Human-readable — a peer's tree snapshot can be inspected directly at the REPL
- Extensible via tagged literals when needed (e.g. `#cljseq/trajectory {...}`)

**OSC blob for small structured payloads:**
Where the data is small and fits naturally in an OSC message, it can be carried as
an OSC blob argument (`b` typetag): a length-prefixed byte array. The blob content
is EDN UTF-8 bytes. This avoids a second connection for small queries.

```
→ OSC /cljseq/query "tree" ""                    ; request ctrl-tree snapshot
← OSC /cljseq/reply "tree" <blob: EDN bytes>     ; response as OSC blob
```

**Separate TCP port for large payloads:**
Device maps, full ctrl-tree dumps, and session state can be hundreds of kilobytes.
For these, we use a simple framed TCP protocol on a separate port (default: `osc-port + 1`):

```
[4-byte big-endian length][EDN UTF-8 payload]
```

The frame header is enough — no envelope, no versioning needed at this stage. The
`started` epoch from mDNS tells the receiver whether to trust its cache or request
a fresh dump.

**Clojure API sketch:**

```clojure
;; Sending a ctrl-tree snapshot to a peer (data plane)
(discovery/send-tree! peer-node-id (ctrl/snapshot))

;; Requesting a device map from a peer
(discovery/request! peer-node-id :device-map "hydrasynth-explorer")
;; => {:async true :on-result (fn [edn-map] ...)}

;; OSC control plane — unchanged, used for real-time values
(osc/send! peer-osc-addr "/cljseq/beat" 42.0)
```

### OSC query convention (control plane)

For real-time queries that fit in OSC, we follow the `liblo`/TouchOSC convention:

```
→ /cljseq/<node-id>/status                        ; request status
← /cljseq/<node-id>/status <bpm> <beat> <started> ; response
→ /cljseq/<node-id>/sub <path>                    ; subscribe to path changes
← /cljseq/<node-id>/val <path> <value>            ; pushed update
```

The `started` epoch from mDNS lets a re-subscribing peer know whether to trust its
cached subtree or request a full EDN re-sync over TCP.

---

## 6. Why Not MIDI for Peer Data Exchange

The EDN data plane does something MIDI fundamentally cannot: it pushes **semantic
device descriptions** — parameter names, ranges, NRPN tables, type hierarchies,
and full sequencer vocabulary — between peers as structured data.

MIDI's data model is a 7-bit command stream. Even complex SysEx is essentially an
opaque byte blob with device-specific semantics that must be known in advance by
both ends. There is no mechanism in MIDI 1.0 for a device to describe itself to a
peer; you either hard-code the device knowledge or ship a separate spec document.

This matters not just for hardware devices (Hydrasynth EDN maps), but for **virtual
devices defined in cljseq's own sequencer vocabulary** — a live loop, a conductor
pattern, a fractal context, a flux sequence. These are rich structures that can be
serialized as EDN, pushed to a peer, and reconstructed there as live Clojure values.
A peer could receive a running loop definition and hot-swap it into its own session.
That capability is not expressible in any version of MIDI.

**MIDI 2.0 and Property Exchange:**
MIDI 2.0 introduces Property Exchange (PE), which does provide a mechanism for
devices to publish capability and configuration data (parameter names, ranges, etc.)
over a JSON-based protocol layered on MIDI-CI SysEx. This is conceptually closer to
what we're describing, but:

- PE is vendor-defined at the property level; there is no universal schema
- It requires MIDI-CI negotiation before use and is bounded by SysEx bandwidth
- Hardware support is sparse as of 2026; tooling is immature
- It targets interoperability between *any* MIDI 2.0 devices, so it necessarily
  operates at a lowest-common-denominator level

cljseq's EDN plane is opinionated and cljseq-native — a deliberate trade-off. We
get full expressiveness (arbitrary Clojure data, tagged literals, lazy sequences)
at the cost of requiring a cljseq peer on both ends. MIDI 2.0 PE is the right path
for interop with third-party hardware when PE support matures; EDN is the right path
for cljseq-to-cljseq capability exchange.

**Research note (future sprint):** Track MIDI 2.0 PE adoption and evaluate whether
cljseq devices should publish a PE property set for third-party interop alongside
the native EDN interface. Estimated horizon: when major hardware vendors ship PE
support and there is a testable device on the bench.

## 7. WiFi, Link, and OSC Bundle Scheduling

### Why WiFi breaks MIDI clock but not Link

MIDI clock is a pulse protocol: receivers infer tempo from the *interval between pulses*.
WiFi's CSMA/CA contention, power-saving buffer flushes, and retransmission variability
produce inter-packet jitter of ±5–20ms. At 24 PPQN / 120 BPM (pulse interval = 20.8ms),
this jitter is larger than the interval itself — the receiver cannot recover a stable tempo.

Link sidesteps this entirely. It does not transmit timing pulses. Instead:
- Each peer maintains a local high-resolution clock
- Link's consensus protocol synchronizes the *phase relationship* between clocks, using
  timestamped UDP messages whose arrival time is measured, not depended upon
- Between consensus messages, the local clock provides beat position independently
- WiFi jitter affects consensus frequency, not beat-position accuracy

A Link sync message can arrive with 50ms jitter and it doesn't matter. The local clock
fills in between messages with sub-millisecond accuracy. This is why Link works over WiFi
while MIDI clock fundamentally cannot.

### OSC bundle timetags: distributed `sleep!`

The OSC specification defines a bundle format with a 64-bit NTP timetag:

```
#bundle <timetag> <message> [<message> ...]
```

The timetag specifies the absolute wall-clock time at which all messages in the bundle
should be executed. The receiver holds the bundle and fires it at the right moment —
network latency is fully absorbed as long as the bundle arrives before its timetag.

cljseq already has the bridge: `clock/beat->epoch-ms` converts any beat position to an
absolute wall-clock millisecond on the Link timeline. The mapping is the same on all Link
peers. Converting to NTP (seconds + fractional seconds since 1900-01-01) is arithmetic.

```clojure
;; Node A: schedule a remote play! for beat 42.0
(discovery/send-at! peer-node-id
                    :beat       42.0    ; → absolute epoch-ms via Link
                    :headroom-ms 100    ; send early enough to always arrive in time
                    (osc/msg "/cljseq/play" {:pitch/midi 60 :dur/beats 1/4}))

;; Node B: hold bundle until timetag, then execute
;; → fires at exactly beat 42.0 on the shared Link timeline
```

This is a **distributed `sleep!`**: the cljseq virtual-time model extended across the
network. Both nodes reference the same Link timeline; no further negotiation is required.

### Scheduling headroom as a design parameter

The headroom (how far ahead bundles are sent) is a tunable musical parameter:

| Headroom | @ 120 BPM | Character |
|----------|-----------|-----------|
| 50ms     | 0.1 beats | Tight, requires reliable LAN |
| 100ms    | 0.2 beats | Comfortable for home WiFi |
| 500ms    | 1 beat    | "Plan one bar ahead" — natural sequencer lookahead |
| 1000ms   | 2 beats   | Generous; enables complex remote processing |

A natural operating mode: **plan one bar ahead**. At the start of bar N, compute all
remote events for bar N+1 and send them as bundles with timetags one bar in the future.
This is how hardware sequencers buffer their lookahead; we're doing it over WiFi.

### When timetags are overkill

Not all OSC traffic needs scheduling discipline:
- **Parameter automation** (filter sweeps, trajectory sampling via `ctrl/send!`): fire-and-
  forget UDP. A ±5ms jitter on a filter cutoff is inaudible.
- **Ctrl-tree sync** (EDN data plane): happens at join/restart, latency irrelevant.
- **Beat-critical events** (note-on, beat-locked transitions, cue! signals): these benefit
  from timetag scheduling.

The three-mode protocol:
1. OSC UDP fire-and-forget — realtime parameter control
2. EDN over TCP — bulk data (device maps, tree snapshots)
3. OSC bundle with timetag — beat-critical remote event scheduling

### Sonic Pi interop

Sonic Pi 4.x has Ableton Link support and a logical-time model (virtual beat counter +
drift-free `sleep`) isomorphic to cljseq's. However, Sonic Pi's OSC runtime (JRuby /
`osc-ruby`) strips bundle timetags before surfacing messages to user code. Sonic Pi cannot
consume cljseq's timetag bundles directly.

For cljseq ↔ Sonic Pi interop:
- Both join the same Link session — beat alignment is automatic
- cljseq sends plain OSC messages (not bundles); Sonic Pi receives them and routes into
  its own `sync`/`cue` mechanism for musical scheduling
- The timetag discipline is a cljseq-to-cljseq protocol; Sonic Pi sees ordinary OSC

This is the general boundary: timetag scheduling is most powerful as a native cljseq peer
protocol. Third-party tools that understand Link but not timetags get Link synchronization;
tools that understand neither get approximate synchronization.

### Clock accuracy across nodes

Link re-syncs peers every few seconds. Between syncs, each peer's local clock drifts
independently. For a 100ms scheduling window, inter-sync drift is sub-millisecond on
modern hardware — negligible. The guarantee is "within Link consensus accuracy" (~1ms),
not sample-accurate across nodes.

For applications requiring sub-millisecond distributed timing (sample-accurate multi-node
MIDI start, distributed click track at pro-audio precision), PTP (IEEE 1588 Precision
Time Protocol) over Ethernet would be required — a different hardware requirement and
a future research item if the use case arises.

### Novel use cases enabled

1. **Choreographed multi-device WiFi ensembles**: Pi nodes owning different MIDI devices
   execute conductor-planned sequences over WiFi with musical precision.

2. **Speculative/generative distribution**: a master node runs complex harmonic or
   stochastic logic, distributes pre-computed event streams as bundles to instrument
   nodes. Instrument nodes are lightweight; intelligence is centralized.

3. **Distributed live loops**: `deflive-loop` extended with a `:remote peer-node-id` opt;
   `play!` bundles events for remote execution at the appropriate Link beat. Remote nodes
   become virtual MIDI ports with scheduling semantics.

4. **Reactive vs scheduled spectrum**: for any given event, the choice between fire-and-
   forget OSC and timetag bundle is a musical decision, not just a technical one. Low-
   frequency automation: fire-and-forget. Note events and cue! transitions: timetag.




### Link as the backbone

Ableton Link is peer-to-peer, discovery-automatic (same mDNS underpinning), and
handles tempo/beat/phase agreement without a master. All cljseq nodes join the same
Link session.

### MIDI clock as a leaf output

MIDI clock (24 PPQN) is produced **from** the Link timeline, not the other way around.
This is already implemented in Sprint 32 (MIDI clock output from Link anchor). Each
cljseq node produces MIDI clock independently for its locally attached hardware. There
is no MIDI clock routing between nodes.

### Consequence for the Mio

The Mio's role shrinks to "USB hub for legacy devices that don't have USB MIDI." A Pi
with USB ports handles this natively. If the Mio is eliminated entirely, clock sync
moves fully to Link and the network.

---

## 8. Beat Clock Synchronization (Summary)

See §7 for the full analysis. MIDI clock over WiFi is not viable. Link is the clock
backbone for all cljseq nodes. MIDI clock is a leaf output, produced per-node from
the local Link timeline for locally attached hardware — never routed between nodes.

## 9. Implementation Phasing

### Phase 1 (Clojure-only, no hardware required)
- [ ] `cljseq.scala/scale->mts-bytes` + `cljseq.sidecar/send-mts!`
- [ ] Hydrasynth device EDN: `:mts/retune` trigger node
- [ ] Tests: MTS byte encoding (known values from Scala reference)

### Phase 2 (Linux/Pi sidecar changes)
- [ ] C++ sidecar: `sd_notify` keepalive from MIDI clock thread
- [ ] C++ sidecar: `/dev/watchdog` keepalive
- [ ] CMakeLists: conditional `libsystemd` link
- [ ] `cljseq.service` systemd unit template

### Phase 3 (Distributed)
- [ ] `cljseq.discovery` namespace: mDNS advertisement + peer listener
  - macOS: `dns-sd` / `NSNetService` via JNI or subprocess
  - Linux: Avahi D-Bus or `avahi-publish` subprocess
- [ ] node-id persistence (`~/.cljseq/node-id`)
- [ ] Peer subtree mounting in ctrl tree
- [ ] `on-peer-change!` callback + stale tombstoning
- [ ] OSC `/tree` query + `/sub` subscription protocol
- [ ] OSC bundle timetag scheduling: `discovery/send-at!` using `clock/beat->epoch-ms`
      → NTP timetag; receiver holds bundle and fires at timetag wall-clock time
- [ ] Three-mode protocol documented: fire-and-forget / EDN-TCP / timetag bundle

### Phase 4 (Operational)
- [ ] Pi boot scripts / Ansible playbook for headless deployment
- [ ] `~/.cljseq/session.clj` auto-load on start
- [ ] SSH REPL + optional touchscreen UI

---

## 10. Open questions

1. **mDNS on the JVM**: Avahi D-Bus requires `dbus-java`; the `dns-sd` subprocess
   approach is simpler but less reliable. Consider `jmdns` (pure-Java mDNS) as a
   third option — it's battle-tested and has no native deps.

2. **EDN verbosity for large trees**: a full ctrl-tree snapshot with hundreds of device
   nodes will be non-trivial in EDN. Compression (gzip over TCP) is an option if
   latency matters; for initial sync on peer join, a few hundred ms is acceptable.

3. **Subscription protocol**: Push vs poll. Push (we notify on change) is lower latency;
   pull (peer polls `/status` periodically) is simpler to implement. Start with pull,
   move to push when needed.

4. **Security**: mDNS is LAN-only by default (link-local multicast doesn't cross routers).
   For a live performance LAN this is fine. For studio-wide deployment across subnets,
   we'd need mDNS proxy or a unicast fallback.

5. **JmDNS vs Avahi subprocess**: JmDNS is the path of least resistance for Phase 3.
   It's pure Java, works on macOS and Linux without native code, and handles both
   advertisement and browsing.
