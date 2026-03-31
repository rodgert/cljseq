# Exemplar Analysis: Moog Labyrinth

**Sprint**: Sprint 5
**Source**: Moog Labyrinth product documentation, pre-release materials, early reviews
**Confidence**: Medium (recently released; some parameter specifics uncertain)

---

## Overview

The Moog Labyrinth (2024) is a 4-channel analog step sequencer in Eurorack format —
Moog's first dedicated sequencer since the 960 Sequential Controller of the 1970s.
It is deliberately designed as a pure CV instrument with no MIDI, referencing the
pre-MIDI modular synthesis tradition. Its architectural novelty lies in the separation
of **play head** and **write head** as independent processes on the same circular
buffer, and in the **CORRUPT** function — a real-time probabilistic bit-level mutation
of stored sequence values.

The Labyrinth is the most architecturally novel device in this survey. Its play/write
head model describes a class of generative sequencing behaviour with no close precedent
in the other exemplars, and it directly motivates a new cljseq abstraction.

---

## Play Head / Write Head Separation

### Concept

A conventional step sequencer has a single "current position" that is both the playback
pointer and the edit point. The Labyrinth separates these into two independent heads:

- **Play head**: reads from the circular step buffer and sends the current value to the
  CV output. Advances at the master clock rate.
- **Write head**: writes new values into the circular step buffer. It can advance at a
  different rate, in a different direction, or be driven by an external CV — completely
  independently of the play head.

The step buffer is a shared mutable resource. The play head reads; the write head
writes. Both circle the same buffer simultaneously.

### In practice

The play head is producing sound continuously. The write head is accepting CV input
and storing it at the current write position, then advancing. As the write head laps
the play head, stored values change. The rate of change depends on how fast the write
head is moving relative to the play head.

- **Write head slower than play head**: the stored values change slowly; the sequence
  evolves gradually over many passes.
- **Write head faster than play head**: the sequence changes rapidly; at very high
  write rates the buffer is being overwritten faster than it is being played, producing
  continuously shifting material.
- **Write head at same rate, same direction, phase offset**: the write head writes to
  positions the play head will reach a fixed number of steps later. This is a "lookahead"
  model — the write head is composing the future while the play head performs the present.

The phase relationship between the heads is continuously adjustable.

### DSL implications — the `defflux` abstraction

This model motivates a new cljseq abstraction: **`defflux`** in `cljseq.flux`.

A `defflux` sequence is a step buffer with two independent processes:

```clojure
(defflux :my-flux
  {:steps    16
   :scale    (scale :C :dorian)
   :read     {:clock     master-clock
              :direction :forward}
   :write    {:clock     (clock-div 3 master-clock)  ; write 1/3 as fast
              :source    (lfo :rate 0.1)              ; write head feeds from an LFO
              :direction :forward}
   :output   {:target :midi/synth :channel 1}})
```

The read process emits events to the target. The write process updates the buffer
at its own rate. The result is a melodic sequence that gradually evolves as the LFO
value is written into positions the play head will later reach.

**Key design decisions implied**:

1. The step buffer is a Clojure vector of atoms (one atom per step), or a single atom
   holding a vector — the write head needs to update individual positions without
   blocking the read head.
2. The read and write processes are independent `live-loop`-style threads.
3. The write source can be any `ITemporalValue` — LFO, stochastic generator, morph
   output, or even another `defflux` sequence (compositional chaining).
4. The phase offset between heads is a configurable parameter, not a derived consequence.

**Naming rationale**: "Flux" is from Latin *fluxus* (a flowing), appropriately
describing a sequence in a continuous state of change. The namespace is `cljseq.flux`;
the macro is `defflux`. No trademark conflict; the term is purely descriptive.

---

## CORRUPT Function

### Concept

CORRUPT is a real-time bit-level mutation of stored step values. The step buffer holds
digital representations of CV values (12-bit or similar). CORRUPT probabilistically
flips bits in these stored values:

- **Low intensity**: rare bit flips in low-order bits → subtle pitch drift, fine tuning
  variations
- **Medium intensity**: more frequent flips across mid-order bits → semitone and whole
  tone jumps, melodic variation
- **High intensity**: frequent flips in high-order bits → large register jumps, octave
  leaps, noise-like behaviour

CORRUPT writes its mutations back to the buffer. Combined with the play head, the
mutations become audible the next time the play head reaches those positions.

A **CORRUPT CV input** modulates intensity in real time, enabling modulation-driven
mutation rate (e.g., a slow LFO sweeps the CORRUPT intensity, gradually increasing
and decreasing the degree of sequence degradation/evolution).

### DSL implications

CORRUPT is a mutation strategy in `cljseq.morph`, distinct from the BeatStep Pro's
"one-shot randomize" because it operates **continuously and probabilistically**, driven
by an `ITemporalValue` intensity source:

```clojure
;; CORRUPT-style continuous mutation on a defflux buffer
(defflux :evolving
  {:steps   16
   :corrupt {:enabled   true
             :intensity (lfo :rate 0.05)  ; slow LFO drives corruption intensity
             :clock     (clock-div 4 master-clock)}}) ; evaluated every 4 beats
```

The mutation strategy for CORRUPT is **bit-level perturbation** of the stored integer
value. In Clojure, this operates on the integer MIDI note value or a fixed-point
pitch representation:

```clojure
;; Bit-flip mutation (simplified model)
(defn corrupt-value [v intensity]
  (let [flip-mask (reduce bit-or 0
                   (for [bit (range 7)
                         :when (< (rand) (* intensity (/ 1.0 (inc bit))))]
                     (bit-shift-left 1 bit)))]
    (bit-xor v flip-mask)))
```

High-order bits (bit 5, 6 = octave) are less likely to flip than low-order bits
(bit 0, 1 = semitone detail) at any given intensity — matching the hardware behaviour
where corruption starts with pitch drift and escalates to register jumps.

This is a named mutation function that `defflux`'s corruption process applies on
each clock tick.

---

## BIT FLIP CV Output

### Concept

A trigger output that fires each time a CORRUPT bit-flip event occurs. This output
can be patched to any Eurorack module that accepts a trigger — envelope generators,
sample-and-hold, clock dividers, etc. The density of BIT FLIP triggers is proportional
to CORRUPT intensity.

### DSL implications

This is a **side-channel event** — an event stream generated as a by-product of an
internal state change, rather than by the primary sequencer clock.

In cljseq:

```clojure
;; Side-channel events from a defflux mutation process
;; A listener can react to corruption events
(flux/on-corrupt! :evolving
  (fn [step-idx old-val new-val]
    ;; Fire an envelope, trigger a secondary loop, etc.
    (ctrl/send! [:env/trig] 1)))
```

The `:on-corrupt!` callback is the DSL equivalent of patching the BIT FLIP output to
another module. It receives the step index and old/new values, enabling reactive
secondary behaviour driven by mutation events.

---

## EG TRIG MIX

### Concept

A routing parameter controlling which trigger sources are combined into the envelope
generator trigger output. Sources include: the play head's step gate, external gate
inputs, and the BIT FLIP trigger. EG TRIG MIX enables the envelope to fire on:
- Normal steps only (conventional behaviour)
- CORRUPT events only (envelope fires on mutations)
- Both combined (the sequence mutates the rhythm of the EG itself)

### DSL implications

EG TRIG MIX is a **trigger merge/routing** concept. In cljseq terms:

```clojure
;; Merge multiple trigger sources for a voice's envelope
(trig-mix! :bass-voice
  {:sources [:step-gate           ; fires on every note step
             :corrupt-events]     ; also fires when CORRUPT mutates
   :mode    :or})                 ; any source triggers the EG
```

This maps to combining multiple event streams at the `ctrl/send!` level. It is a
future capability — not blocked by current design, but not yet specified.

---

## Scale Mode and CV-Controlled Scale Selection

- Built-in scale quantization at the output stage (stored values unchanged)
- ~16–24 scale modes including chromatic (no quantization), major/minor modes,
  pentatonic, blues, and others
- Scale selection is CV-controllable in real time — the applied scale can be
  continuously modulated

### Key insight: scale as `ITemporalValue`

CV-controlled scale selection means the output transform has a **time-varying
parameter**. In cljseq terms, the scale applied to pitch quantization is an
`ITemporalValue` — a value that changes over time:

```clojure
;; Scale selection morphs between dorian and mixolydian over 16 beats
(defmorph :scale-selector
  {:inputs  {:x [:float 0.0 1.0]}
   :targets [{:path [:output/scale]
              :fn   (fn [{:keys [x]}]
                      (if (< x 0.5) :dorian :mixolydian))}]})
```

Or more fluidly:

```clojure
;; Scale as an ITemporalValue (slow LFO selects from a set)
(ctrl/bind! [:output/scale]
  (fn [beat]
    (nth [:major :dorian :mixolydian :phrygian]
         (mod (long (* beat 0.1)) 4))))
```

This is an important design datum: the output stage should accept an `ITemporalValue`
for scale selection, not only a static keyword.

---

## CV-Agnostic Sequencing

The Labyrinth sequences **voltage**, not specifically pitch. The meaning of the output
value is determined by what is patched to the CV output, not by the sequencer.

### DSL implications

A cljseq step sequence should be semantically agnostic about what its `:value` controls.
The routing — not the step — determines the parameter. This reinforces the existing
control tree design: `ctrl/send!` routes a value to a path; the path determines
the output adapter.

```clojure
;; Same sequence value, three different routings
(ctrl/send! [:vco/pitch]        step-value)  ; pitch
(ctrl/send! [:filter/cutoff]    step-value)  ; filter
(ctrl/send! [:reverb/room-size] step-value)  ; reverb
```

---

## The `defflux` Abstraction — Summary

`defflux` is a new cljseq sequencer archetype motivated by the Moog Labyrinth.

| Dimension | `defflux` |
|-----------|-----------|
| Attribution | Moog Labyrinth (Labyrinth-Inspired) |
| Namespace | `cljseq.flux` |
| Macro | `defflux` |
| R&R section | §26 (to be written) |
| Core concept | Circular step buffer with independent read and write heads |
| Mutation | Optional `defflux` `:corrupt` block (continuous probabilistic mutation) |
| Side-channel | `flux/on-corrupt!` callback |
| Write source | Any `ITemporalValue` |
| Scale | Accepts `ITemporalValue` for dynamic scale selection |
| Concurrency | Read and write are independent `live-loop`-style threads on shared atoms |

---

## Attribution

```
doc/attribution.md naming convention table: Labyrinth → defflux (to be updated)
```

The R&R §26 section title will be: **"Flux Sequence Architecture (Labyrinth-Inspired)"**

---

## New Design Questions Raised

**Q53 — `defflux` read/write head concurrency model**

The step buffer is a shared resource accessed concurrently by the read head
(emitting events), the write head (updating stored values), and the CORRUPT
process (perturbing stored values). Choose:

- **Option A**: Vector of atoms — each step position is an independent atom;
  reader and writer never contend on the same atom unless directly at the same
  position.
- **Option B**: Single atom holding a vector — `swap!` replaces the whole
  vector; lock-free but creates allocation pressure at high mutation rates.
- **Option C**: Persistent vector with structural sharing (same as Option B, explicit)

Option A is preferred for the hot path: independent atoms per step means zero
contention unless heads are at the same position, and position overlap is a
musically interesting event (not an error). Spec the implementation detail in
the Phase 6+ plan.

**Q54 — Scale as `ITemporalValue` in the output stage**

Should the output stage's scale quantization accept an `ITemporalValue` in addition
to a static keyword? Required for the Labyrinth-style dynamic scale morphing.
This extends Q8 (control tree node types) — a `:scale` node in the tree can hold
either a keyword or an `ITemporalValue`.

Recommendation: yes, with a wrapper that samples the `ITemporalValue` on each step
output and applies the resulting scale keyword. Static keyword remains the common
case.
