# cljseq Ensemble Improvisation — Design Document

**Status:** Design / pre-implementation
**Author:** Thomas Rodgers
**Date:** 2026-04-07
**Branch:** TBD (`feature/ensemble-improv` proposed)

---

## 1. Motivation

cljseq live loops currently *coordinate* — they share a clock, a harmony context,
a ctrl tree. But they do not *listen* to each other. Each loop generates from its
own logic without knowledge of what the others have produced. No loop knows what
pitch classes another has recently played, whether a phrase is active, whether the
harmonic tension is rising or falling in the shared space.

The Temporal Buffer (`design-temporal-buffer.md`) introduces shared event memory.
This document asks: what happens when a live loop can read that memory and let it
influence its generative choices?

The answer is ensemble improvisation — a qualitatively different kind of musical
system. Not coordinated patterns but *listening, responding agents*. The music that
emerges is not composed in advance; it arises from the interaction of memory and
response. cljseq becomes an ensemble, not a sequencer. The loops become musicians,
not tracks.

---

## 2. Core Concept

```
Loop A plays material
    → writes events to a shared Temporal Buffer
    → Buffer is continuously analyzed → ImprovisationContext derived
Loop B reads ImprovisationContext
    → generative choices biased by what A has played
    → Loop B's output writes to the same (or a different) shared buffer
    → Loop C can now respond to both A and B
    ...
```

Three primitives make this possible:

1. **Shared Temporal Buffer** — a Temporal Buffer used as a write+query store
   rather than a playback device. Any loop can write to it; any loop can read
   from it. The buffer is the room all loops are playing in.

2. **ImprovisationContext** — a derived musical summary of recent buffer content.
   Not raw events but analyzed musical features: pitch content, tension, density,
   contour, register, phrase state. The interface between shared memory and
   generative logic.

3. **Responsive generation** — generative choices conditioned on the
   ImprovisationContext. Stochastic selection weighted by recent history; harmonic
   choices derived from current chord and tension; duration choices derived from
   phrase structure.

---

## 3. The ImprovisationContext

The ImprovisationContext is a map derived from a live Temporal Buffer by running
`cljseq.analyze` functions against the buffer's recent content. It is recomputed
on a configurable schedule (every beat, every half-beat, or on demand).

```clojure
{;; Pitch content
 :recent-pitches     #{60 64 67 71}  ; pitch classes in last N beats
 :pitch-center       64              ; mean pitch (MIDI note)
 :pitch-range        {:min 48 :max 72}

 ;; Harmonic analysis (from cljseq.analyze)
 :current-key        :C-major
 :current-chord      {:root :G :quality :dominant-7 :degree :V}
 :tension            0.72            ; [0.0, 1.0] from tension-score
 :tension-direction  :rising         ; :rising | :falling | :stable
 :borrowed?          false

 ;; Rhythm / activity
 :density            2.3             ; notes per beat (recent window)
 :density-direction  :increasing     ; :increasing | :decreasing | :stable
 :contour            :ascending      ; :ascending | :descending | :oscillating | :static

 ;; Phrase structure
 :phrase-active?     true
 :beats-since-onset  1.5            ; beats since phrase began
 :beats-since-rest   nil            ; nil if no recent rest
 :phrase-length-est  4.0            ; estimated phrase length in beats

 ;; Most recent event
 :last-note          {:pitch 67 :beat 43.5 :velocity 80 :duration 0.5}

 ;; Buffer metadata
 :buffer-id          :shared-ensemble
 :analysis-beat      44.0           ; beat at which this context was computed
 :listen-depth       4.0}           ; beats of history used
```

`cljseq.analyze` already produces: key detection, tension scoring, borrowed chord
detection, chord annotation, progression suggestion. The ImprovisationContext
wraps these in a real-time query interface against a live buffer rather than a
post-hoc score.

New analysis functions needed:
- `density` — notes per beat over a sliding window
- `contour` — direction of recent pitch motion (ascending/descending/oscillating)
- `phrase-active?` — heuristic: are notes arriving with density above threshold?
- `phrase-length-est` — estimated phrase length from recent onset patterns

---

## 4. Musical Improvisation Behaviors

### 4.1 Call and Response

The most immediate ensemble behavior. One loop plays a phrase (the "call"); another
waits for the phrase to end (detected as a density drop below a threshold) and
enters with a related phrase (the "response").

```clojure
;; Response loop — waits for Loop A to rest, then responds
(deflive-loop :response {:listen :shared-ensemble :listen-depth 4}
  (let [ctx (improv-context :shared-ensemble)]
    (if (:phrase-active? ctx)
      (sleep! 0.5)                          ; wait — Loop A is playing
      (do
        (play-response! ctx {:mode    :harmonic
                             :register :above
                             :length  (:phrase-length-est ctx)})
        (sleep! (:phrase-length-est ctx))))))
```

The Threshold Extractor (`design-threshold-extractor.md`) is naturally suited to
detecting phrase boundaries: watch the buffer density value, fire an event when
density crosses below a threshold — that event triggers the response loop.

**Call and response variations:**
- *Imitation* — response begins with the same pitch as the call ended
- *Inversion* — response inverts the contour of the call
- *Completion* — response resolves harmonically where the call left unresolved tension
- *Extension* — response continues the trajectory of the call (same direction, new material)

### 4.2 Motif Development

Loop B extracts a motivic fragment from the buffer and transforms it. The
transformations planned for MIDI repair Phase 3 (`to-score`, `transpose`,
`retrograde`, `mode-shift`) apply directly to buffer snapshots:

```clojure
(deflive-loop :developer {}
  (let [ctx      (improv-context :shared-ensemble)
        snapshot (buffer-snapshot :shared-ensemble {:depth 2})  ; last 2 beats
        motif    (extract-motif snapshot {:min-notes 3 :max-notes 6})]
    (when motif
      (play-transformed! motif
        (choose-transform ctx
          [:transpose {:semitones 7}]      ; up a fifth
          [:retrograde]                    ; play backwards
          [:augment   {:factor 2}]         ; double durations
          [:mode-shift {:to :dorian}])))))  ; recolor harmonically
```

`choose-transform` selects the transformation based on context: high tension →
prefer resolution transforms (transpose to tonic); low tension → prefer
developmental transforms (augment, retrograde).

### 4.3 Harmonic Complement

Loop B reads the current chord from the context and generates notes that fit —
but specifically notes *not* already prominent in the buffer's recent pitch content.
It fills harmonic space rather than doubling existing voices.

```clojure
(deflive-loop :harmonic-fill {}
  (let [ctx           (improv-context :shared-ensemble)
        chord-tones   (chord-tones (:current-chord ctx))
        recent-used   (:recent-pitches ctx)
        available     (remove #(recent-used (mod % 12)) chord-tones)]
    (when (seq available)
      (play! (rand-nth available) 60 0.75)
      (sleep! 1))))
```

### 4.4 Rhythmic Complement

Loop B reads the rhythmic density and temporal pattern of the buffer and places
events in the gaps — the note-event equivalent of a drummer filling between a
bass line:

```clojure
(deflive-loop :rhythmic-fill {}
  (let [ctx     (improv-context :shared-ensemble)
        density (:density ctx)]
    (cond
      (> density 3.0) (sleep! 1)           ; dense — stay out
      (< density 1.0) (play-burst! ctx 3)  ; sparse — fill actively
      :else           (play-offbeat! ctx))));fill on the offbeats
```

### 4.5 Contrast

The stochastic Markov extension (see §7) in `:repel` mode. The generator reads
recent pitch and rhythmic content and biases *away* from it — consciously different
register, contrary contour, contrasting density. Creates tension through opposition,
the way a good improviser plays against the prevailing material before resolving.

### 4.6 Dynamic Balance

Loop B reads density and velocity from the context and adjusts its own output
level proportionally — backing off when the ensemble is loud and dense, coming
forward when it's sparse and quiet. An automatic mixing intelligence emerging
from the shared buffer:

```clojure
(deflive-loop :dynamic-listener {}
  (let [ctx      (improv-context :shared-ensemble)
        velocity (int (* 127 (- 1.0 (/ (:density ctx) 5.0))))]  ; inverse density
    (play! :C4 (max 20 velocity) 1)
    (sleep! 1)))
```

### 4.7 Phrase Following — Listen and Wait

A loop that only plays in silences. Enters when the shared buffer goes quiet;
stops when another loop becomes active. Creates natural space and breathing room:

```clojure
(deflive-loop :space-filler {}
  (let [ctx (improv-context :shared-ensemble)]
    (if (:phrase-active? ctx)
      (sleep! 0.25)                        ; someone else is playing — wait
      (do
        (play-melodic-phrase! ctx {:bars 2})
        (sleep! 2)))))
```

---

## 5. The Shared Buffer

A Temporal Buffer designated as a shared ensemble memory store rather than a
playback device. Multiple loops write to it; any loop can query it.

```clojure
;; Define a shared ensemble buffer
(deftemporal-buffer :shared-ensemble
  {:mode     :shared           ; write+query; no automatic playback
   :depth    16                ; 16 beats of history
   :decay    {:mode :age       ; older events drop off as new ones arrive
              :max-age 16}
   :analysis {:schedule  :beat ; recompute ImprovisationContext every beat
              :depth      4}}) ; analyze last 4 beats

;; Any loop writes to it:
(temporal-buffer-send! :shared-ensemble note velocity duration)

;; Any loop queries it:
(improv-context :shared-ensemble)
(buffer-snapshot :shared-ensemble {:depth 2})
```

**Shared vs. playback buffers:**
A playback Temporal Buffer (Note Mimeophon, Memory Field) reads back its own
content through cursors. A shared ensemble buffer is query-only — it accumulates
events and provides analysis, but does not play them back automatically. The
distinction is in the mode, not the architecture — both use the same buffer
infrastructure.

**Multiple shared buffers:**
An ensemble can have multiple shared buffers serving different purposes:

```clojure
:harmonic-memory   ; longer depth (16+ beats); tracks harmonic progression
:rhythmic-memory   ; shorter depth (2-4 beats); tracks recent rhythmic pattern
:global-memory     ; full performance history; structural-level analysis
```

---

## 6. Ensemble Roles

A natural role structure emerges when multiple loops share a buffer:

```
Loop :bass
  Role: foundation — plays root motion, long notes
  Writes to: :shared-ensemble
  Reads from: nil (or :global for large-scale shape)
  Improv mode: generative (stochastic with harmonic constraint)

Loop :harmony
  Role: harmonic fill — plays chords and inner voices
  Writes to: :shared-ensemble
  Reads from: :shared-ensemble
  Improv mode: harmonic-complement (fills pitch space above :bass)

Loop :melody
  Role: lead voice — phrases, motifs, development
  Writes to: :shared-ensemble
  Reads from: :shared-ensemble
  Improv mode: call-response + development (responds to :bass and :harmony)

Loop :texture
  Role: color — sustained notes, drones, density shading
  Writes to: :shared-ensemble
  Reads from: :shared-ensemble
  Improv mode: dynamic-balance + phrase-following (fills gaps, backs off density)

Loop :rhythm (if driving a percussive voice)
  Role: rhythmic articulation
  Reads from: :shared-ensemble (rhythmic density and phrase state)
  Improv mode: rhythmic-complement (fills between other loops' events)
```

Roles are not rigid — a loop can switch its improvisation mode in response to
context. If `:melody` detects very high tension for several bars, it might
shift from development to contrast mode, or rest entirely.

---

## 7. Markov Stochastic Extension

Responsive generation requires that stochastic contexts know their recent history.
The `cljseq.stochastic` namespace currently generates each event independently.
A Markov extension adds memory:

```clojure
(defstochastic :responsive-melody
  {:pitches    [:C4 :D4 :E4 :F4 :G4 :A4 :B4]
   :memory
   {:depth     4           ; remember last 4 choices
    :mode      :attract    ; :attract = develop patterns; :repel = seek contrast
    :influence 0.3}})      ; how strongly history biases next choice
```

**Attract mode:** Recent choices increase in probability — patterns tend to
persist and develop. Creates motivic consistency; a melody that remembers what
it just played and continues in that direction.

**Repel mode:** Recent choices decrease in probability — the generator avoids
repeating itself. Creates variety and contrast; useful for texture loops that
should avoid monotony.

**Context-conditioned Markov:**
When both buffer context and Markov memory are active, they combine:

```clojure
;; Weight = base_probability × markov_factor × context_factor
;; context_factor: high for chord tones when tension is high; low for non-chord-tones
;; markov_factor: high for pitches not recently played (repel) or recently played (attract)
```

This produces generation that is both harmonically aware (from context) and
self-aware (from Markov memory) — the two layers working together to produce
coherent, developing musical material.

---

## 8. Structural Memory — The Conductor's Ear

The conductor currently knows what section is active but not how previous sections
performed. With a global Temporal Buffer, the conductor can listen:

```clojure
(defscore :adaptive-performance
  {:sections
   {:build    {:bars 8
               :on-transition
               (fn [from to ctx]
                 ;; Did tension actually build?
                 (when (< (:peak-tension ctx) 0.6)
                   (cue! :build)))}   ; repeat build if it didn't peak high enough
    :peak     {:bars 4}
    :dissolve {:bars 8}}})
```

The conductor can repeat a section if the intended arc wasn't achieved, skip a
section if the ensemble has already arrived there spontaneously, or extend a
resolution if tension is still high. The performance becomes adaptive — not just
a script with some randomness, but a system that listens to itself at the structural
level and responds.

**Structural memory features:**
- Peak tension achieved per section
- Number of times each section repeated
- Whether a phrase resolved harmonically or ended suspended
- Overall density arc (did it build and release as intended?)

---

## 9. The Deep Implication — Composition from Interaction

When loops listen and respond to shared memory, the music that emerges is not
predetermined. It arises from the *interaction* of agents with their shared
history. This is compositionally distinct from:

- **Generative music** — random within constraints; no memory
- **Algorithmic composition** — deterministic; no response to what was played
- **Live looping** — fixed repetition; no listening
- **Coordinated improvisation** — shared cues but no musical content sharing

The cljseq ensemble is none of these. It is closer to how human improvisers work:
each musician listens to the shared space, understands what has happened (pitch
content, tension, phrase state, density), and makes choices that relate to that
understanding.

The composition-as-data value proposition extends: not just "the score is a Clojure
value" but "the performance generates a value (the shared buffer + derived context)
that shapes subsequent performance." The piece is not written in advance. It unfolds
through the recursive interaction of memory and response.

This also closes the loop on the Shipwreck Piano story. That recording was four
NDLR voices playing simultaneously without listening to each other — they shared
a clock but not memory. An ensemble improvisation system could produce a similar
texture but with each voice aware of what the others are playing, producing
material that is coherent rather than independently generated.

---

## 10. Relationship to Other Design Artifacts

| Artifact | Role in Ensemble Improvisation |
|----------|-------------------------------|
| `design-temporal-buffer.md` | The shared memory store; buffer infrastructure |
| `design-threshold-extractor.md` | Phrase boundary detection (density threshold crossing) |
| `cljseq.analyze` | Produces ImprovisationContext from buffer content |
| `cljseq.stochastic` | Extended with Markov memory for responsive generation |
| `cljseq.harmony / *harmony-ctx*` | Harmonic complement and chord tone selection |
| `cljseq.conductor` | Extended with structural memory and adaptive branching |
| `cljseq.trajectory` | Shapes the improvisation arc (density, tension target) |
| MIDI repair: `to-score`, `retrograde`, `transpose` | Motif transformation tools |

---

## 11. Open Design Questions

1. **Write policy:** When multiple loops write to the same shared buffer, do
   their events intermingle freely, or are they tagged by source loop?
   Source tagging enables per-loop analysis ("what has :bass played recently?")
   vs. what the whole ensemble has played.
   *Proposed: events tagged with `:source-loop`; context queries can filter
   by source or aggregate across all sources.*

2. **Analysis schedule:** How often is the ImprovisationContext recomputed?
   Per beat (cheap, slightly stale); per event (expensive, always fresh);
   on-demand (caller's responsibility).
   *Proposed: per beat default; on-demand query available for
   time-critical loops.*

3. **Context depth:** How many beats of history should the context analyze?
   Short depth (2-4 beats) → responsive to immediate gestures.
   Long depth (16+ beats) → reflects broader structural state.
   *Proposed: configurable per buffer; multiple depths can be queried
   simultaneously (`:rhythmic-depth 2 :harmonic-depth 8`).*

4. **Circular influence:** If Loop A influences Loop B and Loop B writes to the
   same buffer that Loop A reads from, the system is circular. Is this desirable?
   *Proposed: yes — circular influence is the mechanism of ensemble
   conversation. Runaway feedback is managed by density thresholds and Hold mode.*

5. **Improvisation mode as a first-class concept:** Should `:improv-mode` be
   a parameter of `deflive-loop` or a library of functions the loop body calls?
   *Proposed: library of functions — keeps `deflive-loop` unchanged; improv
   behavior is just Clojure code inside the loop body.*

6. **Motif extraction:** What constitutes a "motif" in the buffer? A minimum
   sequence length? Rhythmically coherent subphrase? Most recently played N notes?
   *Proposed: most recently played N notes (configurable) as the simplest
   definition; rhythmic coherence as a filter option.*

7. **Role coordination:** Should roles be formally declared, or emerge from
   loop behavior? Formal declaration enables conflict detection (two loops both
   trying to be lead voice). Emergent roles are more flexible.
   *Proposed: optional formal role declaration with a soft conflict warning;
   no enforcement.*

---

## 12. Implementation Phases

### Phase 1 — Shared buffer and context
- Shared mode for Temporal Buffer (`:mode :shared`)
- `improv-context` function (derives ImprovisationContext from buffer)
- New analysis functions: `density`, `contour`, `phrase-active?`, `pitch-center`
- Tests: context derivation, analysis accuracy, multi-loop write

### Phase 2 — Basic response behaviors
- `play-response!` — harmonically related phrase after detected rest
- `play-complement!` — fills available pitch/rhythmic space
- Phrase boundary detection via density threshold (Threshold Extractor integration)
- Tests: call-and-response timing, harmonic complement correctness

### Phase 3 — Markov stochastic extension
- `defstochastic` memory parameters (`:depth`, `:mode`, `:influence`)
- Attract and repel modes
- Context-conditioned probability weighting
- Tests: Markov convergence, attract vs. repel character, context integration

### Phase 4 — Motif development
- `buffer-snapshot` — extract recent events as a score fragment
- `extract-motif` — select a motivic subsequence from snapshot
- `choose-transform` — context-conditioned transform selection
- Integration with MIDI repair `retrograde`, `transpose`, `mode-shift`
- Tests: motif extraction, transform correctness, context-conditioned selection

### Phase 5 — Structural memory and adaptive conductor
- Global Temporal Buffer for full performance history
- Structural analysis: peak tension per section, phrase resolution tracking
- Conductor `:on-transition` with structural context argument
- Adaptive section repetition and branching
- Tests: structural analysis accuracy, conductor adaptation

### Phase 6 — Ensemble integration
- Multi-loop ensemble examples
- Role declaration (optional)
- Dynamic balance and phrase-following behaviors
- Full ensemble test: 3+ loops sharing buffer, demonstrating call-response

---

## 13. References

- **design-temporal-buffer.md** — the shared memory infrastructure
- **design-threshold-extractor.md** — phrase boundary detection
- **cljseq.analyze** — key, tension, chord, progression analysis
- **cljseq.stochastic** — to be extended with Markov memory
- **NDLR / Shipwreck Piano** — the motivating failure case: four voices without
  shared memory, producing incoherent texture despite shared clock. The ensemble
  improvisation system is the answer to that recording's structural problem.
- **Human ensemble improvisation** — the behavioral model: listening, responding,
  developing, contrasting, balancing. The musical behaviors in §4 are derived
  from observing how human improvisers interact, not from algorithmic composition
  theory.
