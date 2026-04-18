# cljseq — Open Design Decisions

Active tracker for unresolved design questions. Resolved items have been moved to
`doc/archive/open-design-questions.md` (67 questions, all resolved except those here).

Each entry carries the original Q-number for cross-reference with the archive.

---

## LFO and polyrhythm

### Q30 — Continuous LFO scheduling: update rate and sidecar handoff

**Blocking**: `param-loop` LFO implementation.

**Question**: At what rate should the JVM poll and dispatch LFO values to the
sidecar for MIDI CC and OSC targets? For CLAP targets, the sidecar can compute
one value per audio buffer — no JVM polling needed.

**Recommendation** (not yet implemented):
- Default: 100 Hz (10 ms intervals), configurable per `param-loop` via `:rate hz`.
- CLAP targets: compute one value per buffer via IPC type `0x76` automation message
  with sample offset. No high-frequency JVM polling.
- MIDI CC / OSC: 100 Hz from JVM LFO thread, batched to sidecar.

---

### Q32 — Polyrhythmic phase coherence across restarts and tempo changes

**Blocking**: Nothing currently; quality issue for multi-loop live sets.

**Question**: When a `live-loop` is redefined mid-run, it can restart with an
arbitrary phase offset relative to other loops, breaking intentional polyrhythmic
relationships. Under Link, LFO loops using wall-clock rates drift when BPM changes.

**Recommendation** (partially implemented — `:restart-on-bar` exists in `deflive-loop`):
1. `:restart-on-bar` restarts at the next bar boundary — use this for phase-critical
   loops.
2. LFO rate in Hz should always derive from BPM (`freq = beats/period × BPM/60`) so
   rate self-corrects when BPM changes.
3. Full solution: global phase anchor from Link beat-1 timestamp; all period-based
   loops compute phase as `(current-beat % period) / period`.

---

## Timing and MIDI clock

### Q56 — Time signature API: does it affect `sleep!`?

**Blocking**: Bar-level `sync!`; bar-level phasor in `cljseq.loop`.

**Recommendation** (not yet implemented): `sleep!` and all beat arithmetic always
refers to quarter notes (MIDI convention). Time signature affects only bar boundaries,
bar-level `sync!`, and display. Avoids compound-time BPM ambiguity.

---

### Q57 — PPQN for MIDI Clock output: 24 fixed or configurable?

**Blocking**: MIDI Clock output implementation in `cljseq-sidecar`.

**Recommendation** (not yet implemented): Always emit at 24 PPQN for live MIDI Clock
(MIDI spec; all hardware expects it). Higher PPQN (96, 960) for SMF export only.

---

### Q58 — MTC frame rate: default and drop-frame handling

**Blocking**: MTC implementation (Phase 2).

**Recommendation** (not yet implemented): Default 25 fps (1920 samples/frame at
48 kHz — clean math). Drop-frame correction behind explicit config
`{:frame-rate 29.97 :drop-frame true}`. 30 fps non-drop as secondary North American
default.

---

## Plugin graph (future sprint)

Questions Q61–Q65 are all pre-design for `cljseq-rack` — the hosted CLAP/VST3 plugin
graph. None block current work. Grouped here for when that sprint begins.

### Q61 — Plugin graph DSL: declarative `defrack` vs. imperative API

**Recommendation**: Declarative `defrack` macro as primary form (`:nodes` + `:edges`
EDN map); imperative `add-node!` / `connect!` for interactive REPL patching.
Declarative form compiles to imperative calls internally.

### Q62 — Rackspace switching: crossfade and audio continuity

How long is the crossfade window? How are concurrent switch requests serialised? How
does plugin delay compensation change during transition?

### Q63 — Plugin graph topology: DAG only or feedback loops?

DAG-only is simpler; feedback requires automatic delay-buffer insertion and PDC
accounting. Start with DAG-only and revisit.

### Q64 — VST3 support: same sprint as CLAP or deferred?

VST3 has larger catalog than CLAP. `SynthTarget` abstraction isolates it — VST3 is a
new subclass. Decision: prioritise after `defrack` CLAP baseline is stable.

### Q65 — Live graph modification (hot-patching while audio runs)

Requires double-buffer or copy-on-write for the processing graph applied between
audio callbacks. Coordinate with Q62 crossfade design.

---

## DAWProject I/O (future sprint)

### Q66 — DAWProject export scope

Phase A: tempo map + captured MIDI clips only. Phase B: automation curves + CLAP
plugin states. Decide trigger: explicit `export!` call vs. automatic on `stop!`.

### Q67 — DAWProject import granularity

Selective import (tempo map + MIDI clips) is lower risk for Phase 1. Full project
reconstruction (generating `defrack` from track hierarchy) depends on Q61 first.

---

---

## Sequencer unification and parameter locks

### Q68 — IStepSequencer protocol: unified step-generator abstraction

**Blocking**: Per-note parameter locks; composing Pattern+motif! with arp step-by-step engine; polyrhythmic seq composition.

**Context**

cljseq has two parallel note-generation lineages that do not share an interface:

- `Pattern` + `Rhythm` + `motif!` — NDLR model; two records that cycle at independent
  lengths; lcm-emergent phrase complexity; pitch resolution via `*harmony-ctx*` /
  `*chord-ctx*`; played holistically by `motif!`
- `arp/make-arp-state` + `next-step!` — mutable state atom advanced one step at a
  time; returns `{:pitch/midi :dur/beats :vel :beats}`; two formats (chord-indexed,
  Hydrasynth phrase)

Both ultimately produce timed note maps dispatched through `core/play!`. But you
cannot advance a Pattern step-by-step the way `next-step!` does for arp, and you
cannot pass a Pattern into `arp/play!`. They are parallel tracks that don't compose.

The universal event atom — the note map — is the seam that makes unification tractable.
`ITransformer` already formalises post-generation processing at this seam. A
pre-generation protocol formalising *step advancement* would complete the picture.

**Proposed protocol**

```clojure
(defprotocol IStepSequencer
  (next-event [sq ctx]
    "Advance the sequencer one step. Returns:
       {:event note-map :beats duration}  — a note to play
       {:rest  true     :beats duration}  — a rest (sleep only)
     ctx — {:harmony Scale :chord Chord} for pitch resolution.
     Mutates internal state (step index).")
  (seq-cycle-length [sq]
    "Number of steps in one full cycle. For Pattern × Rhythm this is
     lcm(pat-len, rhy-len). Used by motif! and zip to align phases."))
```

**Implementations**

| Type | `next-event` | `seq-cycle-length` |
|---|---|---|
| `Pattern` + `Rhythm` pair | resolve selector + velocity at lcm position | `lcm(pat-len, rhy-len)` |
| `ArpState` atom (from `make-arp-state`) | current `next-step!` logic | pattern step count |
| Raw note-map seq | pop front, wrap | `count` |
| Generative (fn-based) | call fn with step index | ∞ or declared period |

The arp phrase step format already carries arbitrary extra keys (`{:semi 4 :beats 1 :mod/cutoff 127}`).
`next-event` for phrase arps would merge any extra keys directly into the returned note map —
this is the parameter-lock wire-up, and it costs almost nothing.

**Refactoring surface**

`motif!` becomes a thin wrapper over `IStepSequencer`:

```clojure
(defn motif!
  ([sq] (motif! sq {}))
  ([sq opts]
   (let [n    (seq-cycle-length sq)
         ctx  {:harmony loop-ns/*harmony-ctx* :chord loop-ns/*chord-ctx*}
         xf   (:xf opts)]
     (dotimes [_ n]
       (let [{:keys [event beats rest]} (next-event sq ctx)]
         (when-not rest
           (if xf (play-transformed! xf event) (live/play! event)))
         (loop-ns/sleep! beats))))))
```

`arp/play!` and `arp/arp-loop!` would delegate to `next-event` rather than
duplicating the step logic. `arp-loop!` over an `IStepSequencer` becomes a
one-liner that any sequencer type gets for free:

```clojure
(defn seq-loop! [sq & opts] ...)  ; replaces arp-loop! with a universal version
```

---

### Q69 — Per-note parameter locks

**Blocking**: First-class Elektron/Waldorf-style step programming.

**Context**

Elektron sequencers and the Waldorf Iridium MkII implement *parameter locks*: any
sequencer step can override any parameter (filter cutoff, resonance, microtonal pitch,
envelope, LFO rate, etc.) for just that step, independently of the global value.
This is one of the most expressive features in modern hardware sequencers.

cljseq's note map is already an open map — there is no structural reason this cannot
be supported. The question is where the data lives and how it reaches the synth.

**Data model**

*Phrase arp steps* (the `{:semi N :beats N}` format) already support this trivially —
extra keys in the step map are just not forwarded today. The fix is one merge in
`play!` / `next-event`:

```clojure
;; Phrase step with parameter lock
{:semi 4 :beats 1 :mod/cutoff 127 :mod/resonance 64}
;; Microtonal lock (Waldorf Iridium MkII / MTS style)
{:semi 0 :beats 1 :pitch/microtone 33}   ; 33 cents sharp
```

*Chord arp patterns* need a parallel `:params` vector alongside `:order` and
`:rhythm`:

```clojure
{:type    :chord
 :order   [0 1 2 1 0]
 :rhythm  [1 1/2 1/2 1 1]
 :params  [{} {:mod/cutoff 127} {} {:mod/resonance 32} {}]}
;; nil or {} = no lock on this step; merged into note map when present
```

*Pattern+Rhythm* (motif!) needs a parallel locks structure on either `Pattern` or as
a standalone `Locks` record (preferred — keeps Pattern unchanged):

```clojure
(defrecord Locks [data])
;; data — vector of maps (nil = no lock), cycles independently like Pattern and Rhythm
;; lcm computation becomes lcm(pat-len, rhy-len, locks-len) if Locks is provided

;; Usage:
(motif! pat rhy {:locks (locks [{} {:mod/cutoff 127} {} {}])})
```

The three-way lcm opens interesting territory: a 4-step lock sequence against a
5-note pattern against a 3-step rhythm doesn't repeat for 60 steps.

**Dispatch — how locked params reach the synth**

The note map already carries the locked values. The dispatch question is whether
`core/play!` should route them automatically via ctrl bindings:

```
step data + param locks
        │
        ▼
    note map  {... :mod/cutoff 127 :mod/resonance 64 ...}
        │
        ▼
  core/play! — for each :mod/* key, check ctrl binding
        │  if bound to MIDI CC on a device → send CC before note-on
        │  if bound to NRPN → send full 14-bit NRPN sequence
        │  if bound to OSC / SC param → dispatch as parameter message
        ▼
    ITransformer chain (existing post-processing)
        │
        ▼
      MIDI note-on / SC play / OSC note
```

This means defining the binding once via `ctrl/bind!` is sufficient — parameter
locks flow through the same routing without special-casing.

*Restoration after the step*: optional, synth-dependent. Two modes:
- `:lock/restore false` (default) — fire and forget; locked value persists until
  something else changes it (Elektron model for most params)
- `:lock/restore true` — snapshot current ctrl value before the step, restore
  after `dur/beats` (needed for synths where a stepped lock would otherwise
  corrupt the patch)

**Microtonal parameter locks**

`:pitch/microtone N` (cents offset) maps to MTS (MIDI Tuning Standard) sysex or
pitch-bend-per-note on MPE channels. Waldorf Iridium MkII supports MTS-SysEx;
Hydrasynth supports MTS. This makes the phrase step format the natural place to
express microtonality without needing a full scala scale — per-step inflection.

**What this enables at the compositional level**

- Bass line where every 4th note pushes the filter open for one step
- Arp where the downbeat of each bar has a brighter velocity + sharper attack
- Phrase pattern where step 3 is 17 cents flat for a blues inflection
- Iridium-style wavetable position locked to specific steps
- Humanisation as explicit data (rather than random jitter) — intentional
  microtonal locks per step that feel "played"

**Relationship to IStepSequencer (Q68)**

Parameter locks are data carried by the step; `IStepSequencer/next-event` surfaces
them in the returned note map. The dispatch mechanism in `core/play!` then routes
them. The two questions are therefore separable but both required for the full story:
Q68 unifies *when* and *how* steps are generated; Q69 defines *what* they can carry.

---

---

## Literate compositional workflow

### Q70 — contrib/ob-cljseq: org-babel integration for musical notebooks

**Blocking**: `doc/explorations/*/session.org` as the canonical exploration format.

**Context**

The exploration format (`doc/explorations/`) is intended to be both narrative
and runnable. Plain `.clj` session files work but separate prose from code.
org-babel closes that gap: a `session.org` file is simultaneously a composer's
notebook, a tutorial, and a live performance score — prose and music in one
document, evaluable block by block.

This is not core sequencer functionality. It lives in `contrib/ob-cljseq` — a
thin layer on top of `ob-clojure` + CIDER. Users who don't use Emacs are
unaffected; users who do get a first-class literate workflow.

**Spike questions**

The first spike is deliberately minimal: can evaluating a
`#+BEGIN_SRC clojure :session cljseq` block against a running CIDER/nREPL
session play a note? If yes, how much musical workflow falls out for free?

1. **What ob-clojure already provides** — `:session` tagging routes all blocks
   in a file to the same running nREPL session; results appear inline. This may
   be sufficient for basic use with zero custom code.

2. **What a cljseq-specific layer adds**:
   - Convenient session startup (`ob-cljseq-connect`) that finds or starts a
     cljseq nREPL process and wires the `:session` header automatically
   - Results rendering: ctrl-tree snapshot → org table; loop-status → inline list
   - A minor mode (`cljseq-org-mode`) with keybindings: panic (`C-c C-p`),
     BPM nudge, stop-all-loops — musical operations that shouldn't require
     locating a specific block

3. **Long-term**: inline visualisation via org SVG/HTML results blocks —
   timeline, waveform, ctrl-tree heatmap embedded in the document.

**Relationship to explorations**

When this lands, `doc/explorations/README.md` should be updated to name
`session.org` as the preferred format. Existing `session.clj` placeholders
can be migrated or remain as plain-Clojure alternatives for non-Emacs users.

---

## Audio target model

### Q71 — ITarget / ITriggerTarget / IParamTarget: unified audio target protocol

**Blocking**: `PdTarget`; formal target registry in `core/play!`; topology-driven
target construction; `param-live?` health checks on FX processors.

**Context**

`core/play!` currently routes note events via two ad-hoc atoms (`@sc-dispatch`,
`@sample-dispatch`) keyed off the presence of `:synth` / `:sample` in the event
map. Parameter control flows through the ctrl tree (`ctrl/set!` + `ctrl/bind!`)
with no formal device abstraction. These are the same conceptual gap approached
from opposite sides: one handles triggered events without a protocol, the other
handles continuous parameter events without a named face on the device.

Every audio target in cljseq (synthesizer, FX processor, sample player, Pd patch,
external hardware) receives two kinds of event:

- **Triggered** — note-on with pitch/velocity/duration; voice has a lifecycle
- **Continuous** — parameter change at a path/value; no lifecycle, always-running

These are dual. Formalizing one without the other produces an asymmetric model.
The ctrl tree already *is* `IParamTarget` in practice — `param-root` and
`send-param!` are the formal face it has been missing.

**Proposed protocols** (implemented in `cljseq.target`):

```clojure
(defprotocol ITarget
  "Base: any addressable audio/synthesis target."
  (target-id    [t])     ; keyword name in the target registry
  (target-live? [t]))    ; is the underlying transport reachable?

(defprotocol ITriggerTarget
  "Capability: accepts triggered note events with voice lifecycle."
  (trigger-note!  [t event])    ; fire note; returns opaque handle or nil
  (release-note!  [t handle]))  ; explicit release (sustained/held notes)

(defprotocol IParamTarget
  "Capability: accepts continuous parameter control via the ctrl tree."
  (param-root   [t])               ; ctrl-tree path root for this device
  (send-param!  [t path value]))   ; set param at path (relative to param-root)
```

A target implements whichever combination matches its nature:

| Target | ITriggerTarget | IParamTarget |
|---|:---:|:---:|
| `SCTarget` | ✓ (sc-synth! per note) | ✓ (ctrl subtree) |
| `MidiTarget` | ✓ (sidecar note-on/off) | ✓ (CC/NRPN via ctrl) |
| `PdTarget` (Q72) | ✓ (OSC note messages) | ✓ (OSC param messages) |
| `OscFxTarget` (NightSky etc.) | — | ✓ (OSC parameter control) |
| Future: `ClapTarget` | ✓ | ✓ (automation via IPC) |

**`IParamTarget` relationship to ctrl tree**

`send-param!` in almost all cases delegates to `ctrl/set!` at
`(conj (param-root t) relative-path)`. The protocol adds:

1. Relative addressing — `(send-param! nightsky [:reverb :mix] 0.7)` rather
   than needing to know the full `[:studio :nightsky :reverb :mix]` path
2. `param-live?` (via `target-live?`) — transport connectivity check without
   querying the ctrl tree directly
3. Formal device identity — the ctrl subtree has an owner; the topology loader
   can construct the right concrete type from `:type` declarations

**`core/play!` migration**

The `@sc-dispatch` and `@sample-dispatch` atoms become a target registry
(`defonce ^:private target-registry (atom {})`). Dispatch looks up the target
by the `:synth` or `:target` key in the event map and calls `trigger-note!`.
The `:synth` key remains as the event-map signal; the dispatch mechanism
becomes protocol-based rather than atom-based.

```clojure
;; Register targets (done in cljseq.sc / cljseq.target setup)
(target/register! :sc    (->SCTarget ...))
(target/register! :midi  (->MidiTarget ...))

;; Event routing in play!
(if-let [tgt (target/lookup (:synth event))]
  (trigger-note! tgt event)
  (midi-dispatch event))        ; fall-through to sidecar
```

**Topology integration**

Studio topology entries gain a `:target-type` key that the topology loader uses
to construct and register the right concrete `ITarget` at session start:

```clojure
{:id          :iridium
 :type        [:synth :fx]
 :target-type :midi
 :ctrl-path   [:studio :iridium]}

{:id          :nightsky
 :type        [:fx]
 :target-type :osc-fx
 :osc-host    "192.168.1.10"
 :osc-port    8000
 :ctrl-path   [:studio :nightsky]}
```

**Implementation order**

1. `cljseq.target` namespace — protocols + target registry + `SCTarget` +
   `MidiTarget`; `core/play!` dispatch migrated (this Q)
2. `OscFxTarget` — thin `IParamTarget`-only implementation; replaces the
   ad-hoc OSC calls in `cljseq.arc` NightSky / Strymon arcs (follow-on)
3. `PdTarget` — OSC-backed `ITriggerTarget` + `IParamTarget`; no compile step;
   configurable address convention (Q72)
4. Topology loader: construct + register targets from `:target-type`
   declarations at session start (Q73)

---

### Q72 — PdTarget: Pure Data as an OSC-driven synthesis backend

**Blocking**: Nothing currently; Pure Data as a first-class cljseq target.

**Context**

Pure Data is a visual DSP environment. Its patch format (`.pd`) is the instrument
definition. At runtime, cljseq drives a running Pd patch via OSC or MIDI — the
patch itself is authored separately (by hand or via offline generation tools).

Unlike SC, there is no "compile and send SynthDef" step at runtime. The
`compile-synth` multimethod and hiccup graph IR do not apply to Pd. The right
model is an `ITriggerTarget` + `IParamTarget` that speaks OSC to a running patch:

```clojure
;; Note-on: OSC to /note/on with freq, amp, dur
;; Note-off: OSC to /note/off with the same note id
;; Param: OSC to /param/<path> with value
(register-target! :pd-pad
  (->PdTarget {:host "127.0.0.1"
               :port 7400
               :note-on-addr  "/pad/note/on"
               :note-off-addr "/pad/note/off"
               :param-root    [:pd :pad]}))
```

Polyphony is handled inside the Pd patch (`[poly]`, `[clone~]` etc.); cljseq
sends note-on messages and the patch manages voice allocation.

**Pd `.pd` file generation** (longer term) — the hiccup graph IR could compile
to `.pd` native text format for common UGen subsets. This is a separate tool,
not part of the runtime protocol. The UGen vocabulary mapping (`:sin-osc` →
`[osc~]`, `:env-gen` → `[vline~]`-based recipe) is lossy but tractable for
standard oscillators/filters. Polyphony generation requires explicit design.

---

## Deferred

### Q50 — P2P control plane: peer discovery and remote tree composition

**Status**: Deferred; `cljseq.peer` implements single-peer HTTP polling. Full P2P
(mDNS discovery, distributed ctrl-tree merge, conductor pattern) deferred until the
ctrl tree is stable and a multi-musician use case is concrete.

**Design is not foreclosed**: OSC addresses are stable, tree serial number enables
stale-snapshot detection, EDN serialisation allows peer state transmission. When the
time comes: mDNS/Bonjour for discovery; discovered peers appear as device subtrees at
`[:cljseq :peers <peer-id>]`.
