# cljseq User Manual

Version 0.1.0 · April 2026

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
14. [Bach Corpus (Music21)](#14-bach-corpus-music21)
15. [Reference: REPL Commands and Step Keys](#15-reference)

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
;; => {:root :C :quality :maj7 :degree 1 :in-key? true}

;; Roman numeral analysis
(chord->roman c-major (make-chord :G 4 :dom7))
;; => "V7"

;; Suggest scales that fit a set of pitch classes
(suggest-scales [0 2 4 5 7 9 11])
;; => [{:root :C :mode :major :score 1.0} ...]
```

### Key detection and analysis

```clojure
;; Detect key from a pitch-class set (0=C, 2=D, 4=E, ...)
(detect-key [0 2 4 5 7 9 11])
;; => {:root :C :mode :major :score 0.95}

;; Analyze a progression
(analyze-progression c-major
  [(make-chord :C 4 :major) (make-chord :F 4 :major)
   (make-chord :G 4 :dom7)  (make-chord :C 4 :major)])
;; => [{:chord ... :roman "I"} {:chord ... :roman "IV"}
;;     {:chord ... :roman "V7"} {:chord ... :roman "I"}]
```

### Tension and progression suggestion

```clojure
;; Score the tension of a chord (0.0 = stable, 1.0 = maximum tension)
(tension-score (make-chord :B 4 :diminished))  ; => 1.0
(tension-score (make-chord :C 4 :major))        ; => 0.0

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

## 14. Bach Corpus (Music21)

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

## 15. Reference

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
