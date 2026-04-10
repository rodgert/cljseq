# cljseq User Manual

Version 0.5.0 · April 2026

---

## Contents

1. [Prerequisites](#1-prerequisites)
2. [Build](#2-build)
3. [First Notes in 5 Minutes](#3-first-notes-in-5-minutes)
4. [Core Concepts](#4-core-concepts)
5. [MIDI Hardware Setup](#5-midi-hardware-setup)
6. [Live Loops](#6-live-loops)
7. [Music Theory](#7-music-theory)
8. [Device Maps](#8-device-maps)
9. [MPE and Microtonal Tuning](#9-mpe-and-microtonal-tuning)
10. [Ableton Link](#10-ableton-link)
11. [FX Automation](#11-fx-automation)
12. [Control Tree](#12-control-tree)
13. [Generative Techniques](#13-generative-techniques)
14. [Studio Topology](#14-studio-topology)
15. [Temporal Buffer](#15-temporal-buffer)
16. [Threshold Extractor](#16-threshold-extractor)
17. [Ensemble Harmony](#17-ensemble-harmony)
18. [ITexture and Spectral Analysis](#18-itexture-and-spectral-analysis)
19. [Ensemble Improvisation Agent](#19-ensemble-improvisation-agent)
20. [Peer Discovery](#20-peer-discovery)
21. [Note Transformers](#21-note-transformers)
22. [Bach Corpus (Music21)](#22-bach-corpus-music21)
23. [SuperCollider Integration](#23-supercollider-integration)
24. [Spatial Field](#24-spatial-field)
25. [Reference: REPL Commands and Step Keys](#25-reference)

---

## 1. Prerequisites

| Requirement | Minimum |
|-------------|---------|
| Java | 21+ |
| [Leiningen](https://leiningen.org) | 2.10+ |
| CMake | 3.20+ |
| C++ compiler | clang 14 / GCC 12 / MSVC 2022 |
| MIDI interface or IAC Driver | macOS/Linux/Windows |

**macOS** (recommended): all C++ dependencies are auto-fetched by CMake via
FetchContent. No Homebrew packages needed beyond Java and Leiningen.

**Linux**: install ALSA headers and build tools for MIDI support.

Ubuntu / Ubuntu Studio (recommended Linux target):
```bash
sudo apt install libasound2-dev cmake build-essential
```

Fedora / RHEL / CentOS Stream:
```bash
sudo dnf install alsa-lib-devel cmake gcc-c++ make
```

**Windows**: use MSVC or clang-cl; MIDI uses WinMM (built-in).

---

## 2. Build

```bash
git clone https://github.com/rodgert/cljseq.git
cd cljseq

# Build the real-time MIDI sidecar
cmake -B build -DCMAKE_BUILD_TYPE=Release -DCLJSEQ_BUILD_AUDIO=OFF
cmake --build build --parallel

# Run the test suite (optional; requires no MIDI hardware)
lein test
```

The build produces `build/cpp/cljseq-sidecar/cljseq-sidecar` (or `.exe` on
Windows). The Clojure side finds it automatically.

### Build with Ableton Link

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release \
      -DCLJSEQ_BUILD_AUDIO=OFF \
      -DCLJSEQ_ENABLE_LINK=ON
cmake --build build --parallel
```

> **License**: Link changes the sidecar's license from LGPL-2.1 to GPL-2.0.
> The Clojure library (EPL-2.0) is unaffected. See [doc/licensing.md](licensing.md).

### Offline build

On airgapped machines, pre-fetch the C++ dependencies and set
`FETCHCONTENT_SOURCE_DIR_ASIO` and `FETCHCONTENT_SOURCE_DIR_RTMIDI` to the
local paths. See [BUILD.md](../BUILD.md) for details.

---

## 3. First Notes in 5 Minutes

```bash
lein repl
```

```clojure
;; Load the cljseq API (convenience REPL namespace — exports everything)
(require '[cljseq.user :refer :all])

;; Connect MIDI output (opens port 0 by default)
(start-sidecar!)
;; or: (start-sidecar! :midi-port "IAC")   ; by name substring
;; or: (start-sidecar! :midi-port 2)       ; by index

;; Start the clock
(start! :bpm 120)

;; Your first live loop — plays middle C every beat
(deflive-loop :pulse {}
  (play! :C4)
  (sleep! 1))

;; Change it live — takes effect on the next iteration, no restart needed
(deflive-loop :pulse {}
  (play! (rand-nth [:C4 :E4 :G4]))
  (sleep! 1/2))

;; Add a second loop — they run concurrently
(deflive-loop :bass {}
  (play! {:pitch/midi 36 :dur/beats 2})
  (sleep! 2))

;; Stop individual loops
(stop-loop! :pulse)

;; Stop everything
(stop!)
```

---

## 4. Core Concepts

### Virtual Time

cljseq runs on *virtual time*: a beat counter that advances strictly from the
clock. `sleep!` parks the loop thread until the target beat, using
`LockSupport/parkUntil` for sub-millisecond accuracy. The JVM GC cannot cause
timing drift.

```
Beat 0  ─── play! ─── sleep!(1) ─── Beat 1 ─── play! ─── sleep!(1) ─── Beat 2
```

`sleep!` accepts beats as integers, fractions (`1/4`), or decimals (`0.25`).
One beat equals one quarter note at the current BPM.

### Steps

`play!` sends a *step* — a map of musical attributes:

```clojure
{:pitch/midi    60        ; MIDI note number (C4)
 :dur/beats     1/4       ; duration: 1/4 beat = one sixteenth note
 :mod/velocity  100       ; 0–127
 :mod/channel   1}        ; MIDI channel 1–16
```

You can pass a pitch keyword (`play! :C4`) or a raw step map. Steps flow
through the DSL pipeline: apply harmony context → apply tuning → schedule
with the sidecar.

### The Sidecar

All MIDI output goes to the `cljseq-sidecar` process — a native C++ program
that runs a scheduler and delivers notes at precise wall-clock times, independent
of JVM GC. The Clojure REPL and sidecar communicate over a local TCP socket.

```
REPL  ──(TCP)──  cljseq-sidecar  ──(RtMidi)──  MIDI hardware
```

---

## 5. MIDI Hardware Setup

### Discover ports

```clojure
(list-midi-ports)
;; => {:output [{:index 0 :name "IAC Driver Bus 1"}
;;              {:index 1 :name "Hydrasynth"}]
;;     :input  [{:index 0 :name "IAC Driver Bus 1"}
;;              {:index 1 :name "LinnStrument"}]}
```

### Connect output + input monitoring

```clojure
;; Output only
(start-sidecar! :midi-port "Hydra")

;; Output + MIDI input monitor (for MPE controllers, MIDI learn, etc.)
(start-sidecar! :midi-port "Hydra" :midi-in-port "Linn")

;; Wait for a specific incoming message (e.g. any NoteOn)
(await-midi-message #(= 0x90 (bit-and (:status %) 0xF0)) 5000)
;; => {:time-ns 1712345678900 :status 0x91 :b1 60 :b2 80}
```

### IAC Driver (macOS virtual ports)

Open **Audio MIDI Setup → MIDI Studio → IAC Driver** and create a Bus.
Start cljseq targeting that bus, then route it to a software synth (Surge XT,
VCV Rack, Ableton Live) in your DAW or audio router.

---

## 6. Live Loops

### Anatomy of a live loop

```clojure
(deflive-loop :name
  {:bpm     120         ; optional: override global BPM for this loop
   :channel 2           ; default MIDI channel
   :tuning  my-tuning}  ; optional: microtonal tuning context
  ;; body — evaluated every iteration
  (play! :C4)
  (sleep! 1))
```

### Hot-swap

Re-evaluating a `deflive-loop` form with the same name swaps the body *on the
next iteration boundary* — the running loop is never stopped, so there is no
click or timing glitch. The old body finishes its current `sleep!`, then the
new body starts.

### Loop control

```clojure
(stop-loop! :name)   ; stop after current iteration completes
(set-bpm! 140)       ; change global BPM; all loops update on their next sleep
```

### Playing chords and voicings

```clojure
;; Play all notes of a chord simultaneously
(play-chord! (make-chord :C 4 :maj7))

;; Play a specific voicing (vector of MIDI note numbers, bass → soprano)
(play-voicing! [48 55 64 71])

;; Arpeggiate a chord
(arp! (make-chord :A 4 :minor) :up 1/4)
;; or from root + quality keywords:
(arp! :A :minor :up 1/4)
```

---

## 7. Music Theory

### Scales and chords

```clojure
;; Build scales and chords
(def c-major (make-scale :C 4 :major))
(def am7     (make-chord :A 4 :m7))

;; Identify what chord a set of MIDI notes forms
(identify-chord [60 64 67 71])
;; => {:root :C :root-pc 0 :quality :maj7 :inversion 0 :score 1.0}

;; Roman numeral analysis — returns a map, :roman is a keyword
(chord->roman c-major (make-chord :G 4 :dom7))
;; => {:root :G :root-pc 7 :quality :dom7 :inversion 0 :score 1.0
;;     :degree 4 :roman :V :in-key? true}

;; Suggest scales that fit a set of pitch classes
(suggest-scales [0 2 4 5 7 9 11])
;; => [{:root :C :mode :major :fitness 1.0} ...]
```

### Key detection and analysis

```clojure
;; Detect key from a pitch-class set (0=C, 2=D, 4=E, ...)
(detect-key [0 2 4 5 7 9 11])
;; => {:root :C :mode :major :score 0.95}

;; Analyze a progression — each result is a chord->roman map
(analyze-progression c-major
  [(make-chord :C 4 :major) (make-chord :F 4 :major)
   (make-chord :G 4 :dom7)  (make-chord :C 4 :major)])
;; => [{:root :C :quality :major :roman :I  :degree 0 :in-key? true ...}
;;     {:root :F :quality :major :roman :IV :degree 3 :in-key? true ...}
;;     {:root :G :quality :dom7  :roman :V  :degree 4 :in-key? true ...}
;;     {:root :C :quality :major :roman :I  :degree 0 :in-key? true ...}]
```

### Tension and progression suggestion

```clojure
;; Score the tension of an analyzed chord (0.0 = stable, 1.0 = maximum tension)
;; tension-score takes a chord->roman result map, not a Chord record
(tension-score (chord->roman c-major (make-chord :G 4 :dom7)))  ; => 0.9  (V7)
(tension-score (chord->roman c-major (make-chord :C 4 :major))) ; => 0.0  (I)
;; or pass a bare map with :degree and :quality:
(tension-score {:degree 4 :quality :dom7})   ; => 0.9  (V7)
(tension-score {:degree nil :quality :major}) ; => 0.85 (chromatic — degree unknown)

;; Generate a progression matching a tension arc (4 chords)
(suggest-progression c-major [0.0 0.3 0.7 0.0])
;; => [{:root :C :quality :major}   ; I  — stable
;;     {:root :A :quality :minor}   ; vi — mild tension
;;     {:root :G :quality :dom7}    ; V7 — high tension
;;     {:root :C :quality :major}]  ; I  — resolution

;; Include borrowed (mode-mixture) chords
(suggest-progression c-major [0.0 0.8 1.0 0.0] :include-borrowed? true)
```

### Harmony context

```clojure
;; All play! calls in the loop snap pitches to the active scale
(use-harmony! (make-scale :C 4 :dorian))

;; Override harmony for a single form's duration
(with-harmony (make-scale :F 4 :lydian)
  (play! :C4)
  (play! :E4))
```

---

## 8. Device Maps

Device maps are EDN files in `resources/devices/` that describe a hardware
unit's MIDI implementation. They enable semantic CC access, NRPN routing,
and device-specific helpers.

### Loading a device

```clojure
(require '[cljseq.device :as device])

(device/load-device-map "hydrasynth-explorer.edn")
```

### Sending to a device

`device-send!` takes a device id, a path vector (the parameter location in
the device map), and a value (integer 0–127 or a semantic keyword).

```clojure
;; Send by path and numeric value
(device/device-send! :asm/hydrasynth-explorer [:filter :cutoff] 80)

;; Send using a semantic label defined in the device map's :values list
(device/device-send! :korg/minilogue-xd [:vco1 :wave] :saw)

;; Send an NRPN via the device's registered path
(device/device-send! :asm/hydrasynth-explorer [:osc1 :semitone] 64)
```

### Included device maps (v0.1.0)

| File | Device | Type |
|------|--------|------|
| `arturia-keystep-pro.edn` | Arturia KeyStep Pro | Controller / Sequencer |
| `arturia-keystep.edn` | Arturia KeyStep (original) | Controller |
| `hydrasynth-explorer.edn` | ASM Hydrasynth Explorer | Synth / MPE |
| `hydrasynth-desktop.edn` | ASM Hydrasynth Desktop | Synth / MPE |
| `hydrasynth-deluxe.edn` | ASM Hydrasynth Deluxe | Synth / MPE |
| `hydrasynth-keyboard61.edn` | ASM Hydrasynth Keyboard 61 | Synth / MPE |
| `korg-minilogue-xd.edn` | Korg Minilogue XD | Synth |
| `roger-linn-linnstrument.edn` | LinnStrument 200 | Controller / MPE |
| `strymon-nightsky.edn` | Strymon NightSky | Reverb synth |
| `strymon-volante.edn` | Strymon Volante | Delay / echo |
| `boss-dd-500.edn` | Boss DD-500 | Digital delay |
| `boss-rv-500.edn` | Boss RV-500 | Reverb |
| `boss-md-500.edn` | Boss MD-500 | Modulation |
| `boss-sde-3000d.edn` | Boss SDE-3000D | Dual delay |

> **Note on CC numbers:** Boss 500-series and Strymon CC assignments in these
> maps are representative. Verify against the official MIDI implementation
> chart (in each manufacturer's Owner's Manual) before relying on specific CC
> numbers in production.

---

## 9. MPE and Microtonal Tuning

### MPE (MIDI Polyphonic Expression)

cljseq supports MPE for controllers and synths that use per-note expression.
The Hydrasynth and LinnStrument ship with full MPE device maps. MPE events
(per-note pitch bend, CC 74 slide, channel pressure) are routed via the
sidecar's pitch-bend and channel-pressure IPC frames.

```clojure
;; Send a per-note pitch bend (14-bit: 0=full down, 8192=centre, 16383=full up)
(send-pitch-bend! time-ns channel 10000)

;; Send channel pressure (aftertouch)
(send-channel-pressure! time-ns channel 80)
```

### Scala / KBM microtonal tuning

Load `.scl` (scale definition) and `.kbm` (keyboard mapping) files from the
Scala scale archive or your own tuning files:

```clojure
(require '[cljseq.scala :as scala])

(def ms  (scala/load-scl "31edo.scl"))
(def kbm (scala/load-kbm "31edo.kbm"))   ; optional

;; Apply tuning to a live loop via the :tuning option
(deflive-loop :tuned-melody {:tuning {:scale ms :kbm kbm}}
  (play! :C4)
  (play! :E4)
  (sleep! 1))
```

Within the loop, `play!` automatically converts MIDI note numbers to the
nearest scale degree plus a pitch-bend value. The sidecar delivers this as
a NoteOn followed immediately by a PitchBend on the same channel.

### MTS Bulk Dump (Hydrasynth + MicroFreak)

The Hydrasynth (all models) and Arturia MicroFreak accept MTS (MIDI Tuning
Standard) Bulk Dump to retune all 128 keys at once without per-note pitch bend:

```clojure
;; Retune the Hydrasynth to 31-EDO
(def ms (scala/load-scl "31edo.scl"))
(send-mts! ms)

;; With an explicit keyboard map
(def kbm (scala/load-kbm "whitekeys.kbm"))
(send-mts! ms kbm)
```

The 408-byte SysEx is generated by `scale->mts-bytes` and sent via the
sidecar's SysEx frame (type 0x06). The tuning persists in the synth until
the patch changes or the unit is power-cycled.

---

## 10. Ableton Link

Ableton Link synchronises tempo and beat phase across applications on the
same LAN (Ableton Live, Bitwig, Sonic Pi, VCV Rack, and any other
Link-enabled software).

```clojure
(require '[cljseq.link :as link])

;; Join the Link session (creates one if no peers are present)
(link/enable!)

;; Query the session
(link/bpm)    ; => 120.0  (session BPM from most recent peer push)
(link/peers)  ; => 2      (number of connected peers)

;; New loops wait for the next bar boundary before their first iteration
(deflive-loop :synced {}
  (play! :C4)
  (sleep! 1))

;; Propose a tempo change to all peers
(link/set-bpm! 130)

;; Leave the session
(link/disable!)
```

Link requires the sidecar to be built with `-DCLJSEQ_ENABLE_LINK=ON`.

---

## 11. FX Automation

cljseq treats MIDI-addressable FX units (delays, reverbs, modulators) as
first-class devices. Load their EDN maps and automate parameters with the
same tools used for synth parameters.

### Basic FX send

```clojure
;; Send a CC directly via device path
(device/device-send! :boss/dd-500 [:engine-a :feedback] 80)

;; Sweep feedback over 8 bars
(deflive-loop :delay-build {}
  (doseq [v (range 20 100 10)]
    (device/device-send! :boss/dd-500 [:engine-a :feedback] v)
    (sleep! 1))
  (stop-loop! :delay-build))
```

### Strymon NightSky texture arc

```clojure
;; Freeze the reverb — holds current texture as a sustained pad
(send-cc! time-ns 1 24 127)   ; CC 24 = 127: freeze on

;; Release
(send-cc! time-ns 1 24 0)     ; CC 24 = 0: freeze off
```

### Volante head matrix

```clojure
;; Bring in or mute the four tape echo heads independently
(defn set-heads! [h1 h2 h3 h4]
  (let [t (now)]
    (send-cc! t 1 20 h1)   ; head 1
    (send-cc! t 1 21 h2)   ; head 2
    (send-cc! t 1 22 h3)   ; head 3
    (send-cc! t 1 23 h4))) ; head 4

;; Tight single 16th echo only
(set-heads! 127 0 0 0)

;; Gradually add the long ambient echo over 4 bars
(deflive-loop :head-swell {}
  (doseq [v (range 0 80 20)]
    (send-cc! (now) 1 23 v)
    (sleep! 1))
  (stop-loop! :head-swell))
```

---

## 12. Control Tree

The control tree is a hierarchical parameter store with MIDI CC binding, undo,
and checkpoints. Bind a physical knob to a named path and read it from any loop.

```clojure
(require '[cljseq.ctrl :as ctrl])

;; Set a value
(ctrl/set! [:filter/cutoff] 64)

;; Bind a MIDI CC to a path
(ctrl/bind! [:filter/cutoff]
            {:type :midi-cc :channel 1 :cc-num 74})

;; Read in a loop (ctrl/get returns the current value)
(deflive-loop :filter-sweep {}
  (let [cutoff (ctrl/get [:filter/cutoff])]
    (device/device-send! :asm/hydrasynth-explorer [:filter :cutoff] cutoff))
  (sleep! 1/4))

;; Undo / checkpoint
(ctrl/checkpoint!)
(ctrl/set! [:filter/cutoff] 100)
(ctrl/undo!)   ; => restores 64
```

### HTTP control server

The ctrl tree is also exposed over HTTP so that peer nodes can poll it. Start
the server before calling `peer/mount-peer!` on a remote host.

```clojure
(require '[cljseq.server :as server])

;; Start on default port 7177
(server/start-server!)

;; Or choose a port
(server/start-server! :port 7278)

;; Check status
(server/server-running?)  ; => true
(server/server-port)      ; => 7278

;; Stop
(server/stop-server!)
```

The server exposes the following endpoints:

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/ping` | Health check — returns `{"ok": true}` |
| `GET`  | `/bpm` | Current BPM |
| `PUT`  | `/bpm` | Set BPM — body `{"bpm": 140}` |
| `GET`  | `/ctrl` | Full ctrl-tree dump (JSON array) |
| `GET`  | `/ctrl/<path>` | Read one node by slash-separated path |
| `PUT`  | `/ctrl/<path>` | Write one node — body `{"value": ...}` |

Peer nodes poll `/ctrl/ensemble/harmony-ctx` and `/ctrl/spectral/state`
automatically when mounted via `peer/mount-peer!`.

---

## 13. Generative Techniques

### Scale-weighted random pitch

```clojure
(require '[cljseq.random :as r])

;; Random pitch biased toward a scale's characteristic degrees
(r/weighted-scale (make-scale :C 4 :pentatonic-minor))
;; => a MIDI note number

;; Use in a loop
(deflive-loop :gen {}
  (play! (r/weighted-scale (make-scale :C 4 :pentatonic-minor)))
  (sleep! 1/4))
```

### Stochastic sequences

```clojure
(require '[cljseq.stochastic :as stoch])

;; Build a stochastic generator context for a scale
(def gen (stoch/make-stochastic-context
           {:scale (make-scale :C 4 :dorian) :density 0.7}))

;; Advance and play
(deflive-loop :stoch-melody {}
  (when-let [note (stoch/next-x! gen)]
    (play! note))
  (sleep! 1/4))

;; Euclidean rhythm pattern
(stoch/stochastic-rhythm {:pulses 5 :steps 16})
;; => [1 0 0 1 0 0 1 0 0 1 0 0 1 0 0 0]
```

### Fractal sequences

```clojure
(require '[cljseq.fractal :as fractal])

;; Build a fractal context: a C-major trunk with a transposed and reversed variant
(def fctx
  (atom (fractal/make-fractal-context
          {:trunk      [{:pitch/midi 60 :dur/beats 1/4}   ; C4
                        {:pitch/midi 64 :dur/beats 1/4}   ; E4
                        {:pitch/midi 67 :dur/beats 1/4}   ; G4
                        {:pitch/midi 71 :dur/beats 1/2}]  ; B4
           :transforms [:reverse :transpose]
           :root       60
           :transpose-semitones 7})))   ; up a fifth

;; Advance step-by-step in a live loop
(deflive-loop :fractal-mel {}
  (when-let [step (fractal/next-step! fctx)]
    (play! step))
  (sleep! 1/4))
```

### Conductor arcs

```clojure
;; Define a performance arc with named sections and built-in gestures
(defconductor! :set-1
  [{:name :intro     :bars 8  :gesture :groove-lock}
   {:name :buildup   :bars 16 :gesture :trance-buildup}
   {:name :drop      :bars 8  :gesture :drop}
   {:name :breakdown :bars 16 :gesture :breakdown}])

;; Fire it — plays through all sections in order
(fire! :set-1)

;; Jump to a section at the next bar boundary
(cue! :set-1 :drop)
```

---

## 14. Studio Topology

The studio topology file declares the logical layout of your MIDI studio --
which synthesizers, controllers, and FX units you have, and which substring
of the OS MIDI port name to use to find each one. This decouples cljseq
scripts from fragile port indices that change across reboots and USB reconnects.

### Creating your topology file

Copy the schema reference and fill in your hardware:

```bash
# Linux / macOS (XDG default)
cp doc/topology-example.edn ~/.config/cljseq/topology.edn
```

The path can also be set explicitly:

```bash
export CLJSEQ_TOPOLOGY=/path/to/my-studio.edn
```

### Loading and using the topology

```clojure
(require '[cljseq.topology :as topology])

;; Load from default path (~/.config/cljseq/topology.edn or CLJSEQ_TOPOLOGY)
(topology/load-topology!)

;; Or load from an explicit path
(topology/load-topology! "path/to/my-studio.edn")

;; Start the sidecar targeting a named device
(topology/start-sidecar! :poly-synth)

;; Output to one device, monitor MIDI input from another
(topology/start-sidecar! :poly-synth :input :keyboard)

;; Show what is loaded
(topology/print-topology)

;; Query individual devices
(topology/device-info :poly-synth)
;; => {:port-pattern "PolySynth" :midi/channel 1 :role :synth ...}

(topology/device-ids)
;; => [:bass-synth :cv-bridge :iac :keyboard :poly-synth :reverb]
```

### Topology file schema

See `doc/topology-example.edn` for a fully-annotated example. The essential
structure:

```edn
{:topology/id      :my-studio
 :topology/version "0.1.0"

 :devices
 {:poly-synth  {:port-pattern "PolySynth"
                :midi/channel  1
                :role          :synth}

  :keyboard    {:port-pattern "KeyboardCtrl Out"
                :in-pattern   "KeyboardCtrl In"  ; optional: separate input name
                :midi/channel  1
                :role          :controller}

  :reverb      {:port-pattern "ReverbFX"
                :midi/channel  1
                :role          :fx}}

 ;; Optional: document your interfaces and hosts (used in Layer 2+ routing)
 :interfaces  {:hub-1 {:manufacturer "Example" :model "USB Hub" ...}}
 :hosts       {:main {:role :cljseq-host :os :macos}}}
```

`port-pattern` is matched case-insensitively as a substring of the OS MIDI
port name -- the same semantics as `(start-sidecar! :midi-port "substring")`.
Run `(list-midi-ports)` to see what port names your system reports.

---

## 15. Temporal Buffer

The Temporal Buffer is a beat-timestamped event store that replays events
from a sliding window of recent history. It is inspired by loop-and-modify
hardware (Mimeophon, Morphagene, Make Noise Phonogene) but operates in the
MIDI note-event domain rather than audio.

### Zones

Eight overlapping zones define the depth of history available for playback:

| Zone | Depth | Character |
|------|-------|-----------|
| `:z0` | 0.5 beats | Micro-repeat / flutter |
| `:z1` | 1 beat | Sixteenth note echo |
| `:z2` | 2 beats | Eighth note repeat |
| `:z3` | 8 beats | Short phrase (default) |
| `:z4` | 16 beats | Bar loop |
| `:z5` | 32 beats | Double bar |
| `:z6` | 64 beats | Four bars |
| `:z7` | 128 beats | Long form / ambient |

### Quick start

```clojure
(require '[cljseq.temporal-buffer :as tbuf])

;; Create a buffer with default settings (zone :z3, 8 beats deep)
(tbuf/deftemporal-buffer :echo)

;; Send events into the buffer from a live loop
(deflive-loop :melody {}
  (tbuf/temporal-buffer-send! :echo {:pitch/midi 60 :dur/beats 1/4})
  (sleep! 1/4))

;; Stop the buffer
(tbuf/stop! :echo)
```

### Built-in presets

Presets configure zone, rate, color, feedback and halo together:

```clojure
;; Start from a named preset
(tbuf/deftemporal-buffer-preset :my-buf :cosmos)

;; Available presets:
;;   :flux       -- dry passthrough, no processing
;;   :tape-echo  -- warm repeat with gentle feedback
;;   :looper     -- tight clocked loop with high feedback
;;   :cosmos     -- drifting ambient with warm color
;;   :mimeophon  -- dual-cursor Mimeophon-style with skew
```

### Rate and Doppler

Rate multiplies the playback cycle. Values above 1.0 speed up repetition
and pitch up (like tape); values below 1.0 slow down and pitch down.

```clojure
;; Half speed (and an octave down via Doppler)
(tbuf/temporal-buffer-rate! :echo 0.5)

;; Double speed
(tbuf/temporal-buffer-rate! :echo 2.0)

;; Clocked mode: rate affects timing only, pitch is frozen
(tbuf/temporal-buffer-clocked! :echo true)

;; Control the pitch coupling strength (cents per rate unit, default 1200)
(tbuf/temporal-buffer-tape-scale! :echo 80.0)  ; subtle Doppler
```

### Color

Color applies a per-pass transform to velocity, duration and pitch drift:

```clojure
;; Set color by preset keyword
(tbuf/temporal-buffer-color! :echo :warm)

;; Available presets:
;;   :dark      -- quieter, longer, slightly flat
;;   :warm      -- gentle fade, slight stretch
;;   :neutral   -- unmodified (default)
;;   :tape      -- slight random pitch wobble
;;   :bright    -- shorter, slightly sharp
;;   :crisp     -- short and bright (delay-like)

;; Or supply a custom fn [ev] -> ev
(tbuf/temporal-buffer-color! :echo
  (fn [ev] (update ev :mod/velocity #(max 0 (- % 5)))))
```

### Feedback

Feedback re-injects events back into the buffer after each playback pass:

```clojure
;; Gentle echo tail
(tbuf/temporal-buffer-feedback! :echo {:amount 0.4 :max-generation 6
                                        :velocity-floor 8})
;; :amount          -- re-injection probability per event (0.0-1.0)
;; :max-generation  -- stop feeding back after N passes
;; :velocity-floor  -- drop events quieter than this (prevents infinite tail)
```

### Halo

Halo generates temporal neighbor copies of each emitted event, creating a
smear or reverb-wash effect:

```clojure
(tbuf/temporal-buffer-halo! :echo
  {:amount 0.2 :copies 3 :spread 0.08 :pitch-spread 5
   :feedback-threshold 0.7 :max-halo-depth 4})
;; :amount              -- probability of generating halo copies
;; :copies              -- neighbor copies per emitted event
;; :spread              -- +/- beat offset range for copies
;; :pitch-spread        -- +/- cents range for copy pitch
;; :feedback-threshold  -- above this, copies enter the feedback buffer
;; :max-halo-depth      -- generation limit (prevents runaway wash)
```

### Hold (loop slice)

In Hold mode, large zones (`:z4`-`:z7`) lock a fixed window of the buffer
rather than following the rolling cursor:

```clojure
;; Freeze current 16-beat window and loop it
(tbuf/temporal-buffer-hold! :echo true)

;; Choose which beat offset to anchor the slice at
(tbuf/temporal-buffer-slice-start! :echo 4.0)

;; Unfreeze
(tbuf/temporal-buffer-hold! :echo false)
```

### Other controls

```clojure
;; Flip playback direction (retrograde)
(tbuf/temporal-buffer-flip! :echo true)

;; Change the active zone live
(tbuf/temporal-buffer-zone! :echo :z5)

;; Query buffer state
(tbuf/temporal-buffer-info :echo)

;; List all active buffers
(tbuf/buffer-names)
```

### trajectory integration

`temporal-buffer-set!` is a trajectory-compatible setter that lets conductor
arcs and trajectory curves drive buffer parameters live:

```clojure
;; Drive rate from a trajectory curve over 16 bars
(tbuf/temporal-buffer-set! :echo :rate
  (trajectory :smooth-step {:bars 16 :range [0.5 2.0]}))
```

---

## 16. Threshold Extractor

The Threshold Extractor watches a continuously varying value and fires note
or CC events whenever the value crosses a boundary. Rhythm emerges from the
*motion* of the source curve rather than from a pattern or random process.

Inspired by the MakeNoise GTE (Gestural Time Extractor), but operating in the
note-event domain with scale awareness and harmony integration.

### Core concept

A threshold comb of N-1 evenly-spaced boundaries divides the `[0.0, 1.0]`
source range into N channels. Crossing a boundary fires an `:on-cross` event.
The crossing rate is the rhythmic density; the current channel is the pitch
or routing target.

```
Source:   0.0 ─────────────────────────────── 1.0
               |      |      |      |      |
Channels: ch1    ch2    ch3    ch4    ch5    ch6
```

### Quick start

```clojure
(require '[cljseq.extractor :as ext])

;; A phasor cycling every 4 beats crosses 7 thresholds per cycle
(def my-phasor (make-phasor {:cycle-beats 4}))

(ext/defthreshold-extractor :rhythm
  {:source-fn  (fn [beat] (my-phasor beat))
   :channels   8
   :on-cross   {:type :note :pitch 60 :velocity 80 :duration 0.1
                :midi-channel 1}})

;; Stop it
(ext/extractor-stop! :rhythm)
```

### Source function

Any function `f(beat) -> [0.0, 1.0]` can serve as a source:

```clojure
;; Trajectory curve as source
(ext/defthreshold-extractor :tension-rhythm
  {:source-fn  (fn [b] ((trajectory :smooth-step {:bars 32}) b))
   :channels   8
   :on-cross   {:type :note :pitch 60 :velocity 80 :duration 0.05}})

;; Sine LFO
(ext/defthreshold-extractor :lfo-rhythm
  {:source-fn  (fn [b] (/ (inc (Math/sin (* 2 Math/PI (/ b 4.0)))) 2.0))
   :channels   4
   :on-cross   {:type :cc :cc 48 :value 127 :midi-channel 1}})
```

### Space parameter

`space` controls what fraction of `[0.0, 1.0]` the comb spans.
`space-center` sets the midpoint (default 0.5).

```clojure
;; Tight window: hypersensitive to small gestures in the center
(ext/defthreshold-extractor :sensitive
  {:source-fn  my-source
   :channels   8
   :space      0.3   ; comb spans 30% of the range
   :space-center 0.5
   :on-cross   {:type :note :pitch 60}})

;; Adjust space live
(ext/extractor-set! :sensitive :space 0.6)
(ext/extractor-set! :sensitive :space-center 0.7)
```

### Direction filter

```clojure
;; Only fire on rising crossings (source moving up)
(ext/defthreshold-extractor :rising-only
  {:source-fn  my-source
   :channels   4
   :direction  :rising    ; :both (default), :rising, :falling
   :on-cross   {:type :note :pitch 60}})
```

### Hysteresis

Hysteresis prevents rapid re-triggering when the source hovers near a
threshold boundary. The dead-zone is `hysteresis` x the inter-threshold spacing.

```clojure
;; Default hysteresis=0.01 (1% of spacing) — usually fine
;; Increase for a jittery source
(ext/defthreshold-extractor :noisy
  {:source-fn  noisy-fn
   :channels   8
   :hysteresis 0.05   ; 5% dead-zone
   :on-cross   {:type :note :pitch 60}})
```

### Arbitrary event dispatch

`:type :fn` gives full control over the output:

```clojure
(ext/defthreshold-extractor :custom
  {:source-fn my-source
   :channels  8
   :on-cross  {:type :fn
               :f    (fn [ch prev-ch direction beat]
                       ;; ch          -- new channel (1..N)
                       ;; prev-ch     -- prior channel
                       ;; direction   -- :rising or :falling
                       ;; beat        -- beat of crossing
                       (device/device-send! :boss/dd-500
                                           [:engine-a :feedback]
                                           (* ch 16)))}})
```

### Live mutation

All parameters can be changed on a running extractor:

```clojure
;; Replace source function
(ext/extractor-set! :rhythm :source-fn new-source-fn)

;; Replace on-cross config
(ext/extractor-set! :rhythm :on-cross {:type :note :pitch 64})

;; Query current state
(ext/extractor-status :rhythm)
;; => {:source-fn #fn :channels 8 :thresholds [...] :running? true ...}

;; Stop all extractors (called automatically by stop!)
(ext/extractor-stop-all!)
```

---

## 17. Ensemble Harmony

`cljseq.ensemble` provides a shared harmonic context derived from what the
music is actually playing, rather than what the programmer has declared in
advance. A background *harmony ear* watches a named Temporal Buffer, analyzes
its note content on a phrase boundary, and publishes an `ImprovisationContext`
map to `*harmony-ctx*` so that all live loops can read it.

### ImprovisationContext

The context is a plain Clojure map, inspectable and composable at the REPL:

```clojure
{:harmony/key       (scale/scale :D 3 :dorian)  ; detected or pinned Scale
 :harmony/chord     {:root :A :quality :min7
                     :roman :V :tension 0.65}    ; chord->roman result
 :harmony/tension   0.65   ; float [0.0, 1.0] — harmonic tension level
 :harmony/mode-conf 0.82   ; key-detection confidence (Krumhansl-Schmuckler)
 :harmony/ks-score  0.91   ; raw K-S Pearson correlation
 :harmony/pcs       {2 4, 9 3, 0 2}  ; pitch-class distribution in window
 :ensemble/register :mid   ; :low | :mid | :high — tessiture of the window
 :ensemble/density  0.4}   ; note events per beat in the active zone window
```

When `*harmony-ctx*` holds an `ImprovisationContext`, all DSL functions that
previously required a plain Scale record (`root`, `fifth`, `scale-degree`,
`in-key?`) still work — they extract `:harmony/key` automatically. Existing
loops using `:harmony (scale/scale ...)` are unaffected.

### Quick start

```clojure
(require '[cljseq.ensemble :as ensemble]
         '[cljseq.temporal-buffer :refer [deftemporal-buffer
                                          temporal-buffer-send!]])

;; 1. A Temporal Buffer accumulates what you play
(deftemporal-buffer :main {:active-zone :z3})  ; 8-beat window

;; 2. Start the ear — opt-in; nothing runs automatically
(ensemble/start-harmony-ear! :main)

;; 3. Live loops now see an ImprovisationContext in *harmony-ctx*
(deflive-loop :melody {}
  (play! (scale-degree (rand-int 7)) 1/4)  ; scale-degree uses detected key
  (sleep! 1/4))
```

### Manual snapshot

`analyze-buffer` performs a single analysis without starting the background
loop. Useful for one-shot inspection or for driving your own scheduling logic:

```clojure
(ensemble/analyze-buffer :main)
;; => {:harmony/key #Scale{...} :harmony/tension 0.3 :ensemble/density 1.25 ...}

;; With a key hint — skip detection, derive chord/tension on top of the hint:
(ensemble/analyze-buffer :main :key-scale (scale/scale :D 3 :dorian))
```

Key detection requires at least 3 distinct pitch classes in the window. Below
that threshold `:harmony/key`, `:harmony/chord`, and `:harmony/tension` are
omitted; the rest of the map is still populated.

### Ear options

```clojure
;; Phrase-length cadence (default 4 beats):
(ensemble/start-harmony-ear! :main :cadence 8)

;; Pin a key — skip auto-detection, still derive chord and tension:
(ensemble/start-harmony-ear! :main
  :key-scale (scale/scale :D 3 :dorian))

;; Transform the context before publishing (ctx → ctx):
(ensemble/start-harmony-ear! :main
  :transform (fn [ctx]
    ;; Fall back to a known key when confidence is low
    (if (< (:harmony/mode-conf ctx 0.0) 0.6)
      (assoc ctx :harmony/key (scale/scale :G 4 :major))
      ctx)))

;; Side-effect hook — called after each publish:
(ensemble/start-harmony-ear! :main
  :on-ctx (fn [ctx]
    (println "Key:" (get-in ctx [:harmony/key :root])
             "Tension:" (:harmony/tension ctx))))

;; Compose transforms with comp:
(ensemble/start-harmony-ear! :main
  :transform (comp pin-key-when-unsure add-session-data))

;; Stop the ear:
(ensemble/stop-harmony-ear!)
```

The ear registers as the live loop `:harmony-ear` and participates fully in
the loop lifecycle — `stop-loop!`, hot-swap on re-eval, and Link phase sync
all apply. `start!` must have been called first.

### Reading context in loops

```clojure
(deflive-loop :context-aware {}
  (let [ctx  loop-ns/*harmony-ctx*   ; may be Scale or ImprovisationContext
        t    (:harmony/tension ctx 0.0)
        reg  (:ensemble/register ctx :mid)]
    ;; Play a longer note when tension is high
    (play! (root) (if (> t 0.7) 1/2 1/4))
    ;; Drop an octave if the ensemble is playing high
    (when (= reg :high)
      (play! (- (pitch/pitch->midi (root)) 12) 1/4)))
  (sleep! 1/2))
```

### Empty buffer behaviour

When the Temporal Buffer has no events in the active zone window (e.g. during
a rest phrase), the ear retains the previous context unchanged. This means the
key and tension are never forgotten mid-performance due to silence. On the very
first call before any events have arrived, `analyze-buffer` returns nil.

---

## 18. ITexture and Spectral Analysis

`cljseq.texture` provides `ITexture` — a unified protocol for any device or
subsystem whose state can be set, faded, frozen, and thawed. `cljseq.spectral`
implements `ITexture` as a Spectral Analysis Model (SAM) that continuously
derives harmonic-texture characteristics from a live Temporal Buffer.

### ITexture protocol

```clojure
(defprotocol ITexture
  (freeze!       [t]             "Arrest analysis/output; hold current state.")
  (thaw!         [t]             "Resume analysis/output.")
  (frozen?       [t]             "True when frozen.")
  (texture-state [t]             "Read the current params map.")
  (texture-set!  [t params]      "Merge params immediately.")
  (texture-fade! [t target beats] "Fade to target over beats."))
```

Any hardware effects unit can implement `ITexture` and register with the
texture registry, which then becomes the target of `texture-transition!`.

### Shadow state (hardware devices)

Hardware devices have no readback — their parameters are write-only. The shadow
state registry tracks the last-sent values so you can query them without
re-reading hardware:

```clojure
(require '[cljseq.texture :as tx])

;; Declare a shadow node for a hardware device
(tx/shadow-init! :nightsky {:reverb/size 0.5 :reverb/mix 0.7})

;; Update it as you send CCs
(tx/shadow-update! :nightsky {:reverb/size 0.8})

;; Read back the shadow
(tx/shadow-get :nightsky)   ; => {:reverb/size 0.8 :reverb/mix 0.7}
```

### Named texture registry

```clojure
;; Register any ITexture implementation
(tx/deftexture! :reverb my-reverb-device)
(tx/deftexture! :delay  my-delay-device)

;; Inspect
(tx/texture-names)           ; => #{:reverb :delay}
(tx/get-texture :reverb)     ; => the registered device
```

### texture-transition!

Drive multiple devices at once from a single ops map. The same ops map shape
is used by `apply-texture-routing!` in the ensemble improv agent:

```clojure
;; Immediate set (no :beats) or fade (with :beats)
(tx/texture-transition!
  {:reverb {:target {:reverb/size 0.9 :reverb/mix 0.8} :beats 8}
   :delay  {:target {:delay/feedback 0.4}}})
```

### Spectral Analysis Model

`SpectralState` adds three texture fields derived from pitch content:

| Field | Range | Meaning |
|-------|-------|---------|
| `:spectral/density` | 0–1 | Distinct pitch classes ÷ 12 |
| `:spectral/centroid` | 0–1 | Mean MIDI pitch ÷ 127 |
| `:spectral/blur` | 0–1 | 0 = live analysis, 1 = fully frozen |

```clojure
(require '[cljseq.spectral :as spectral])

;; Start the SAM loop watching a Temporal Buffer
(def sam (spectral/start-spectral! :main))

;; Read the current spectral state
(spectral/spectral-ctx sam)
;; => {:spectral/density 0.58 :spectral/centroid 0.52 :spectral/blur 0.0}

;; Freeze — holds current state; analysis stops; on-ctx callbacks still fire
(tx/freeze! sam)
;; => blur becomes 1.0

;; Thaw — resumes analysis; blur → 0.0 on next tick
(tx/thaw! sam)

;; Register as a named texture and fade blur
(tx/deftexture! :spectral sam)
(tx/texture-transition! {:spectral {:target {:spectral/blur 0.5} :beats 8}})

;; Stop the SAM loop
(spectral/stop-spectral! sam)
```

### Using spectral context in loops

`spectral-ctx` returns a map fragment compatible with the ImprovisationContext,
so it can be merged directly:

```clojure
(deflive-loop :texture-aware {}
  (let [spec (spectral/spectral-ctx sam)
        harm loop-ns/*harmony-ctx*
        ctx  (merge harm spec)]
    ;; Drive note density from spectral density
    (when (< (Math/random) (:spectral/density ctx 0.3))
      (play! (root) 1/4))
    ;; Fade the reverb freeze based on blur
    (tx/texture-transition!
      {:reverb {:target {:hold? (> (:spectral/blur ctx 0) 0.5)}}}))
  (sleep! 1/4))
```

---

## 19. Ensemble Improvisation Agent

`cljseq.ensemble-improv` combines the harmony ear, the spectral SAM, and the
texture routing system into a single generative loop — the *improv agent* —
that responds to the live musical context and drives both MIDI output and
effects-device state from the same snapshot.

### Quick start

```clojure
(require '[cljseq.ensemble-improv :as improv]
         '[cljseq.spectral        :as spectral])

;; Start the harmony ear first
(ensemble/start-harmony-ear! :main)

;; Optionally start the SAM for spectral context
(def sam (spectral/start-spectral! :main))

;; Start the improv agent
(improv/start-improv! :main :sam sam)

;; Stop
(improv/stop-improv!)
```

### Note generation strategy

Pitches are chosen from the scale in `:harmony/key`, shaped by context:

| `:harmony/tension` | Degree pool |
|--------------------|-------------|
| < 0.35 | Root triad only — `[0 2 4]` |
| < 0.65 | Diatonic pentatonic — `[0 1 2 3 4]` |
| ≥ 0.65 | Full scale — all degrees |

`:ensemble/register` (:low/:mid/:high) shifts the pool by ±1 octave.
`:ensemble/density` × profile `:gate` scales play probability; capped at 0.95
so silence always remains possible.

When `:harmony/key` is absent (insufficient pitch diversity in the buffer) the
agent rests silently.

### Profile options

```clojure
(improv/start-improv! :main
  :profile {:step-beats   1/4   ; cadence between note attempts
            :gate         0.65  ; base play probability [0,1]
            :dur-beats    1/4   ; note duration
            :velocity     80    ; base MIDI velocity
            :vel-variance 16    ; ± random velocity spread
            :channel      1})   ; MIDI output channel
```

### Texture routing

Map ImprovisationContext fields to ITexture devices. Each `:params-fn` is
called with the current context and returns a params map for the target device:

```clojure
(improv/start-improv! :main
  :sam sam
  :routing
  {:reverb {:params-fn (fn [ctx]
                         {:hold? (> (:harmony/tension ctx 0) 0.8)})
             :beats 4}
   :delay  {:params-fn (fn [ctx]
                         {:active-zone (if (< (:ensemble/density ctx 0) 0.5)
                                         :z3 :z4)})}})
```

### Hot update

Profile and routing changes take effect on the next tick without restarting
the loop:

```clojure
(improv/update-improv! {:profile {:gate 0.9 :vel-variance 30}})
(improv/update-improv! {:routing {:reverb {:params-fn f :beats 4}}})
```

### Conductor integration

`improv-gesture-fn` returns an `:on-start`-compatible no-arg function for use
in conductor sections. At the section boundary it merges a profile override,
optionally replaces routing, and fires an immediate texture transition:

```clojure
(defconductor! :my-arc
  [{:name :buildup :bars 32 :gesture :buildup
    :on-start (improv/improv-gesture-fn
                {:profile {:gate 0.95 :vel-variance 30}
                 :texture {:reverb {:target {:hold? false} :beats 8}}})}
   {:name :wind-down :bars 8 :gesture :wind-down
    :on-start (improv/improv-gesture-fn
                {:profile {:gate 0.4 :vel-variance 10}
                 :texture {:reverb {:target {:hold? true}}}})}])

(fire! :my-arc)
```

---

## 20. Peer Discovery

`cljseq.peer` enables a multi-host cljseq studio: each node broadcasts a UDP
beacon so peers can discover it, and `mount-peer!` starts a background poll
that mounts the peer's harmony and spectral context into the local ctrl tree.

### Node identity

```clojure
(require '[cljseq.peer :as peer])

;; Call before start-discovery!
(peer/set-node-profile!
  {:node-id    :mac-mini   ; keyword — unique per node
   :role       :main
   :http-port  7177        ; must match (server/start-server! :port 7177)
   :nrepl-port 7888})
```

### Discovery

```clojure
;; Start broadcasting + listening (multicast 239.255.43.99:7743, 5 s interval)
(peer/start-discovery!)

;; After a few seconds
(peer/peers)
;; => {:ubuntu {:node-id :ubuntu :host "192.168.1.10" :http-port 7177 ...}
;;     :rpi    {:node-id :rpi    :host "10.0.0.5"     :http-port 7177 ...}}

;; Stop
(peer/stop-discovery!)
```

Peers expire automatically after 15 s without a beacon.

### Mounting peer context

Once a peer is discovered and its HTTP server is reachable, `mount-peer!`
polls two ctrl-tree paths and stores the results locally:

```clojure
;; Prerequisites: server must be running on the peer
;; (server/start-server!) on the remote host

(peer/mount-peer! :ubuntu)

;; Polled every 2 s from the peer:
;;   GET /ctrl/ensemble/harmony-ctx → [:peers :ubuntu :ensemble :ctx]
;;   GET /ctrl/spectral/state       → [:peers :ubuntu :spectral :ctx]

;; Read the mounted harmony context
(peer/peer-harmony-ctx :ubuntu)
;; => {:harmony/root "D" :harmony/octave 3
;;     :harmony/intervals [2 1 2 2 2 1 2]
;;     :harmony/tension 0.65 :ensemble/density 0.4 ...}

;; Reconstruct a live Scale record from the serialized form
(peer/serial->scale (peer/peer-harmony-ctx :ubuntu))
;; => #Scale{:root D3 :intervals [2 1 2 2 2 1 2]}

;; Spectral state
(peer/peer-spectral-ctx :ubuntu)
;; => {:spectral/density 0.58 :spectral/centroid 0.52 :spectral/blur 0.0}

;; Stop polling
(peer/unmount-peer! :ubuntu)
```

### Using peer context in loops

```clojure
;; Follow the remote node's key in a local loop
(deflive-loop :follower {}
  (when-let [remote-ctx (peer/peer-harmony-ctx :ubuntu)]
    (let [scale (peer/serial->scale remote-ctx)]
      (binding [loop-ns/*harmony-ctx* {:harmony/key scale}]
        (play! (scale-degree (rand-int 7)) 1/4))))
  (sleep! 1/4))
```

### Serialization format

Scale and Pitch records cannot round-trip through JSON. The harmony ear
publishes a simplified primitive map at `[:ensemble :harmony-ctx]`:

```clojure
{:harmony/root      "D"           ; string step name
 :harmony/octave    3             ; integer
 :harmony/intervals [2 1 2 2 2 1 2]
 :harmony/tension   0.65
 :harmony/mode-conf 0.82
 :ensemble/density  0.4
 :ensemble/register "mid"}
```

`peer/serial->scale` reconstructs the Scale record from these fields.
`peer/ctx->serial` performs the reverse conversion (useful for testing).

---

## 21. Note Transformers

`cljseq.transform` provides composable per-event processing that sits between
note generation and `play!`. Each transformer receives a note event map and
returns zero, one, or many `{:event map :delay-beats number}` results. An
empty result silently drops the event; multiple results produce multiple sounds.

### Quick start

```clojure
(require '[cljseq.transform :as xf])

;; Single transformer
(def my-echo (xf/echo {:repeats 3 :decay 0.7 :delay-beats 1/4}))

(deflive-loop :melody {}
  (when-let [note (pick-note)]
    (xf/play-transformed! my-echo note))
  (sleep! 1))
```

### Composing transformers

`compose-xf` chains transformers left to right. The output events from each
feed into the next; delay-beats accumulate so an echo of a strum replays the
full strummed pattern N times:

```clojure
(def my-chain
  (xf/compose-xf
    (xf/velocity-curve {:breakpoints [[0 0] [64 90] [127 127]]})
    (xf/harmonize {:intervals [7 12]})
    (xf/echo {:repeats 2 :decay 0.6 :delay-beats 1/4})))

(deflive-loop :melody {}
  (xf/play-transformed! my-chain (pick-note))
  (sleep! 1))
```

### Built-in transformers

#### `velocity-curve` — piecewise linear velocity mapping

```clojure
;; Raise mid-range velocities, leave extremes
(xf/velocity-curve {:breakpoints [[0 0] [64 90] [127 127]]})
```

#### `quantize` — snap pitch to scale

```clojure
;; Snap to C major; drop notes more than 1 semitone away
(xf/quantize {:scale  (make-scale :C 4 :major)
              :forgiveness 1})
```

When `:forgiveness` is set and the pitch is farther than that many semitones
from any scale degree, the event is dropped (not clamped).

#### `harmonize` — add interval voices

```clojure
;; Perfect fifth + octave above; snaps to *harmony-ctx* scale by default
(xf/harmonize {:intervals [7 12]})

;; Explicit scale, raw chromatic (no snapping)
(xf/harmonize {:intervals [4 7]
               :key       (make-scale :D 3 :dorian)
               :snap?     false})

;; Harmony only, omit the original
(xf/harmonize {:intervals [7] :include-original? false})
```

When `:snap?` is true (default), harmony notes are snapped to the nearest
scale degree. The scale is read from `*harmony-ctx*` unless `:key` is
provided. `harmonize` is the primary *ensemble follower* mechanism.

#### `echo` — decay repeat chain

```clojure
;; 3 echoes, each 70% of the previous velocity, 1/4 beat apart
(xf/echo {:repeats 3 :decay 0.7 :delay-beats 1/4})

;; Ascending echo (pitch rises each repeat)
(xf/echo {:repeats 4 :decay 0.8 :delay-beats 1/8 :pitch-step 2})
```

#### `note-repeat` — rhythmic copies

```clojure
;; 4 evenly spaced copies within 1 beat
(xf/note-repeat {:n-copies 4 :window-beats 1})

;; Euclidean distribution — 3 pulses spread across a 2-beat window
(xf/note-repeat {:n-copies 3 :window-beats 2 :euclidean? true})
```

Euclidean mode uses the Bjorklund algorithm to distribute copies as evenly as
possible, producing the characteristic feel of Euclidean rhythms.

#### `strum` — spread chord tones

Input event should carry `:chord/notes` — a vector of MIDI values.

```clojure
;; Strum a chord vector upward, one note every 1/32 beat
(xf/play-transformed!
  (xf/strum {:direction :up :rate-beats 1/32})
  {:chord/notes [60 64 67 72] :dur/beats 1 :mod/velocity 90})

;; Downward strum
(xf/strum {:direction :down :rate-beats 1/32})

;; Random order
(xf/strum {:direction :random :rate-beats 1/16})
```

Strum integrates naturally with `compose-xf` — strum a chord, then echo the
full strummed pattern: `(compose-xf (strum ...) (echo ...))`.

#### `dribble` — bouncing-ball timing

Repeats the event with exponentially shrinking inter-onset gaps, like a ball
bouncing to rest:

```clojure
(xf/dribble {:bounces       5
             :initial-delay 1/2   ; gap before first bounce
             :restitution   0.65}) ; each gap is 65% of the previous
```

#### `latch` — toggle or velocity gate

```clojure
;; Every other note passes (toggle open/closed)
(xf/latch {:mode :toggle})

;; Only notes with velocity ≥ 80 pass
(xf/latch {:mode :velocity :threshold 80})
```

Toggle starts open by default; pass `:initial false` to start closed.

### ITransformer protocol

You can define custom transformers:

```clojure
(defrecord MyTransformer [amount]
  xf/ITransformer
  (transform [_ event]
    ;; Return a seq of {:event map :delay-beats num}
    [{:event (update event :mod/velocity + amount) :delay-beats 0}]))
```

---

## 22. Bach Corpus (Music21)


cljseq integrates with [Music21](https://web.mit.edu/music21/) for the Bach
chorale corpus. Requires Python 3.x with `music21` installed:

```bash
pip install music21
```

```clojure
(require '[cljseq.m21 :as m21])

;; List available chorales (BWV numbers)
(m21/list-chorales)   ; => [1 2 3 ... 371 ...]

;; Play BWV 371 — all voices on one channel (server starts automatically)
(m21/play-chorale! 371)

;; Play with separate MIDI channels per voice (SATB)
(m21/play-chorale-parts! 371)
(m21/play-chorale-parts! 371 {:dur-mult 0.8
                               :channels {:soprano 1 :alto 2 :tenor 3 :bass 4}})

;; Load the raw data for your own processing
(def chords (m21/load-chorale 371))
;; => [{:pitches [48 55 62 67] :dur/beats 1.0} ...]

;; Stop the Python server when done
(m21/stop-server!)
```

The first call starts a persistent Python server that stays alive for the JVM
session. Repeat calls are instant (in-memory cache). Results are also cached
to disk in `~/.local/share/cljseq/corpora/m21/`.

---

## 23. SuperCollider Integration

cljseq talks to SuperCollider on two ports:

- **sclang** (default 57120) — receives sclang code strings for SynthDef compilation
- **scsynth** (default 57110) — receives OSC node control messages

### Connecting

```clojure
(require '[cljseq.user :refer :all])
(session!)

(connect-sc!)                           ; localhost, defaults
(connect-sc! :host "192.168.1.5")       ; remote SC
(sc-connected?)                         ; => true
```

### SynthDef lifecycle

cljseq ships built-in synths (`:beep`, `:sine`, `:saw-pad`, `:blade`, `:prophet`,
`:supersaw`, `:dull-bell`, `:fm`, `:tb303`, `:perc`). Load them into a running SC
server with `send-synthdef!`:

```clojure
(send-synthdef! :blade)        ; compile + load one synth
(send-all-synthdefs!)          ; load every registered synth
(ensure-synthdef! :blade)      ; lazy — only sends if not already loaded this session
```

Inspect the generated sclang:

```clojure
(synthdef-str :prophet)
;=> "SynthDef(\\prophet, {\n  |freq=440.0, ...|\n  ...\n}).add;"
```

### Playing notes via SC

Once connected, `play!` routes any step map containing `:synth` to SC automatically:

```clojure
;; Route to SC — :synth key triggers the SC backend
(play! {:synth :blade :pitch/midi 60 :dur/beats 1/2})
(play! {:synth :sine  :pitch/midi 69 :pitch/midi 69 :dur/beats 1})

;; Inside a live loop
(deflive-loop :sc-bass {}
  (play! {:synth :tb303 :pitch/midi 36 :dur/beats 1/2})
  (sleep! 1))
```

Step maps without `:synth` continue to route through MIDI as before.

### Manual node control

```clojure
(sc-synth! :blade {:freq 440 :amp 0.6})   ; => node-id (e.g. 1001)
(set-param! 1001 :cutoff 60)              ; live update
(free-synth! 1001)                        ; gate=0 — envelope tail
(kill-synth! 1001)                        ; immediate removal
```

### Trajectory control

`apply-trajectory!` drives a live SC node parameter continuously over time using
cljseq's temporal vocabulary:

```clojure
;; 3-arity: drive node-id param with an ITemporalValue
(let [node (sc-synth! :blade {:freq 220 :amp 0.5})]
  (apply-trajectory! node :cutoff
    (trajectory :from 0.1 :to 0.9 :beats 16 :curve :s-curve :start (now))))

;; 2-arity: general form — any setter function
(apply-trajectory! #(println "val:" %)
  (trajectory :from 0.0 :to 1.0 :beats 8 :start (now)))
```

Both forms return a cancel function. Call it to stop the trajectory early.

### Defining custom synths

```clojure
(defsynth! :my-pad
  {:args  {:freq 440 :amp 0.5 :attack 0.5 :release 2.0}
   :graph [:out 0 [:pan2 [:* [:env-gen [:adsr :attack 0.1 0.8 :release] :gate :amp]
                              [:sin-osc :freq]] 0.0]]})

(send-synthdef! :my-pad)
(sc-synth! :my-pad {:freq 330 :amp 0.3})
```

The graph DSL supports: oscillators (`:sin-osc`, `:saw`, `:pulse`, `:var-saw`, ...),
filters (`:lpf`, `:hpf`, `:rlpf`, `:bpf`, `:moog-ff`), envelopes (`:adsr`, `:perc`,
`:asr`), effects (`:free-verb`, `:comb-l`, `:allpass-n`, `:delay-l`),
pan/mix (`:pan2`, `:mix`), and math operators (`:*`, `:+`, `:-`, `:/`).

---

## 24. Spatial Field

`cljseq.spatial-field` models bouncing particles inside an N-sided polygon. Wall
collisions generate MIDI events (`:generation` mode) or sweep modulation parameters
(`:modulation` mode). The geometry is continuous — non-integer N values like `4.7`
produce asymmetric rooms with irrational reflection angles, creating natural polyrhythm.

### Room geometry

```clojure
;; Integer N — regular polygon
(ngon-walls 6)          ; hexagon, 6 equal walls

;; Decimal N — asymmetric: ceil(N) walls, irregular angles
(ngon-walls 4.7)        ; 5 walls, polyrhythmic character

;; Custom wall behaviour per wall
(ngon-walls 4 :type :generate)                           ; all walls fire events
(ngon-walls 4 :wall-types [:reflect :generate :absorb :portal])
```

Each wall has `:a`, `:b` (endpoints), `:normal` (inward unit vector), and `:type`.

Wall types:
| Type | Behaviour |
|------|-----------|
| `:reflect` | Elastic bounce — `v' = v − 2(v·n)n` |
| `:generate` | Reflect and emit a MIDI event |
| `:absorb` | Kill particle velocity |
| `:portal` | Teleport particle to the opposite wall |

### Defining and running a field

```clojure
(defspatial-field! ::my-room
  {:mode      :generation   ; or :modulation
   :sides     5.0
   :ball-speed 1.5
   :particles  1
   :forces    {:gravity {:direction :down :strength 0.3}
               :damping 0.1}})

(start-field! ::my-room)
(field-state  ::my-room)   ; => :running
(stop-field!  ::my-room)
```

Configuration keys:
| Key | Default | Description |
|-----|---------|-------------|
| `:mode` | `:generation` | `:generation` or `:modulation` |
| `:sides` | `4.0` | N-gon sides (decimal allowed) |
| `:ball-speed` | `1.0` | Initial particle speed (room units/s) |
| `:particles` | `1` | Number of simultaneous particles |
| `:min-velocity` | `0.001` | Stop threshold for absorbed particles |
| `:forces` | `{}` | Map of `{:gravity {:direction kw :strength f} :damping f}` |

### Named presets

8 presets are registered at load time:

| Preset | Mode | Character |
|--------|------|-----------|
| `:ricochet` | `:generation` | Fast 6-sided room, frequent events |
| `:dribble` | `:generation` | Low gravity, floor-bounce feel |
| `:drift` | `:modulation` | Slow multi-particle sweep |
| `:orbit` | `:modulation` | Circular motion, low damping |
| `:pinball` | `:generation` | Chaotic 7-sided room with portals |
| `:gravity-well` | `:generation` | Strong downward pull, 4-sided room |
| `:portal-room` | `:generation` | Mixed reflect/portal boundary types |
| `:instrument-quad` | `:modulation` | Quad-gains spatial panning |

```clojure
(start-field! :ricochet)
(play-through-field! {:pitch/midi 60 :dur/beats 1/4} :ricochet)
```

### ITransformer integration

Spatial fields implement `ITransformer`. The triggering event passes through with
`delay-beats 0`; collision-derived events follow with computed delays.

```clojure
(start-field! ::my-room)

;; Direct transformer use
(def xf (field-transformer ::my-room))
(play-transformed! {:pitch/midi 60 :dur/beats 1/4} xf)

;; Convenience wrapper
(play-through-field! {:pitch/midi 60 :dur/beats 1/4} ::my-room)
```

### Quad-gains (VBAP spatial panning)

`quad-gains` maps a 2D position `[x y]` ∈ [0,1]² to four-channel gains
`[FL FR RL RR]` using constant-power decomposition.

```clojure
(quad-gains [0.5 0.5])   ; centre → equal gains
(quad-gains [0.0 0.5])   ; hard left
(quad-gains [0.5 1.0])   ; hard front
```

Sum of squares ≈ 1.0 across the entire field (constant-power law).

### Axis mappings

Map particle physics axes to synthesis parameters:

```clojure
;; In :modulation mode, map :x → :pan and :speed → :velocity
(defspatial-field! ::spatial-pan
  {:mode :modulation
   :sides 4.0
   :mappings {:x :pan :speed :velocity}})
```

Available axes: `:x`, `:y`, `:r` (radius from centre), `:θ` (angle),
`:vx`, `:vy`, `:speed`, `:age`.

---

## 25. Reference

### REPL commands

| Command | Description |
|---------|-------------|
| `(start! :bpm 120)` | Start the clock |
| `(stop!)` | Stop all loops and the clock |
| `(set-bpm! 140)` | Change tempo live |
| `(stop-loop! :name)` | Stop one loop after its current iteration |
| `(start-sidecar!)` | Connect MIDI output (port 0) |
| `(start-sidecar! :midi-port "IAC")` | Connect by port name substring |
| `(stop-sidecar!)` | Disconnect MIDI output |
| `(list-midi-ports)` | Enumerate MIDI output and input ports |
| `(start-server!)` | Start the HTTP ctrl-tree server (port 7177) |
| `(start-server! :port 7278)` | Start on a custom port |
| `(stop-server!)` | Stop the HTTP server |
| `(server-running?)` | Returns true if the server is running |
| `(server-port)` | Returns the current server port |

### play! step keys

| Key | Type | Description |
|-----|------|-------------|
| `:pitch/midi` | int 0–127 | MIDI note number |
| `:dur/beats` | number | Duration in beats (quarter notes) |
| `:mod/velocity` | int 0–127 | Note velocity |
| `:mod/channel` | int 1–16 | MIDI channel |
| `:pitch/bend-cents` | float | Microtonal pitch bend (set by tuning pipeline) |

### Pitch name syntax

`(play! :C4)` — note name + octave. Accidentals: `:Cs4` (C#4), `:Db4` (D♭4),
`:Bb3` (B♭3). Octave range: 0–8. MIDI 60 = `:C4`.

### Sleep durations

All durations are in **beats** (quarter notes at the current BPM).

| Expression | Duration |
|------------|----------|
| `4` | 4 beats — one 4/4 bar |
| `2` | 2 beats — half note |
| `1` | 1 beat — quarter note |
| `1/2` | half a beat — eighth note |
| `1/4` | quarter beat — sixteenth note |
| `1/8` | eighth beat — 32nd note |
| `1/3` | triplet eighth |
| `2/3` | triplet quarter |

---

*cljseq is released under EPL-2.0 (Clojure library) and LGPL-2.1 (C++ sidecar).*
*See [doc/licensing.md](licensing.md) for the full licensing strategy.*
