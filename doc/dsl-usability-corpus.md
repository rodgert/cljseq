# cljseq DSL Usability Corpus

**Status**: Living document — grows as the DSL design matures
**Sprint**: Sprint 4 (initial population)
**Purpose**: Systematic comparison of equivalent musical concepts across cljseq,
Sonic Pi, Overtone, and TidalCycles. Identifies syntactic sugar opportunities
and conceptual gaps.

Each entry records the canonical form in each system, annotates verbosity
asymmetries, and flags sugar candidates for the cljseq DSL backlog.

---

## How to Read This Document

- **cljseq (proposed)** forms are the current design-level API, not yet implemented
- **Sugar opportunity** marks cases where cljseq requires meaningfully more
  keystrokes than a comparator for the same musical intent — these are candidates
  for alias macros or reader conveniences
- **Advantage** marks cases where cljseq's Clojure foundation is expressively
  richer than comparators
- **Gap** marks musical concepts well-covered by comparators with no current
  cljseq equivalent

---

## 1. Basic Note Playback

### Play a single note

| System      | Form                                         | Notes |
|-------------|----------------------------------------------|-------|
| Sonic Pi    | `play 60` / `play :C4`                      | Integer or keyword pitch |
| Overtone    | `(play (midi->hz 60))` / `(note :C4)`       | Requires synth context |
| TidalCycles | `d1 $ note "c"`                              | Pattern context always |
| cljseq      | `(play! {:pitch/midi 60 :dur/beats 1})`      | Explicit step map |

**Sugar opportunity**: `(play! :C4)` / `(play! 60)` as shorthands that expand to
a step map with the system default duration. The explicit step map is the right
foundation but the shorthand is essential for interactive use.

### Play a note with duration

| System      | Form                                               |
|-------------|----------------------------------------------------|
| Sonic Pi    | `play :C4, sustain: 0.5`                           |
| Overtone    | `(at (now) #(piano :note 60)) (at (+ (now) 500) #(piano :note 60 :gate 0))` |
| TidalCycles | `d1 $ note "c" # sustain 0.5`                      |
| cljseq      | `(play! {:pitch/midi 60 :dur/beats 1/2})`          |

**Advantage**: cljseq's `dur/beats` is music-time-native; Sonic Pi's `sustain:` is in seconds.

---

## 2. Sleeping / Timing

### Sleep for one beat

| System      | Form              | Notes |
|-------------|-------------------|-------|
| Sonic Pi    | `sleep 1`         | Seconds (not beats — tempo-independent) |
| Overtone    | `(Thread/sleep 1000)` | Raw Java; no musical abstraction |
| TidalCycles | (implicit — cycle-based, no explicit sleep) | |
| cljseq      | `(sleep! 1)`      | Beat-relative; tied to tempo via Link |

**Advantage**: cljseq and Sonic Pi both sleep in music time (though Sonic Pi uses
seconds, requiring `use_bpm` for tempo-relative notation). cljseq's `sleep!` is
always in beats.

### Sleep for a fraction of a beat

| System      | Form              |
|-------------|-------------------|
| Sonic Pi    | `sleep 0.25`      |
| TidalCycles | (implicit)        |
| cljseq      | `(sleep! 1/4)`    |

**Advantage**: Clojure's ratio literals (`1/4`, `3/16`) are exact and readable.
No floating-point imprecision accumulation across many sleeps.

---

## 3. Sequences / Patterns

### Play a melodic sequence

| System      | Form |
|-------------|------|
| Sonic Pi    | `[:C4, :E4, :G4, :C5].each { \|n\| play n; sleep 0.25 }` |
| Overtone    | `(doseq [n [60 64 67 72]] (play (midi->hz n)) (Thread/sleep 250))` |
| TidalCycles | `d1 $ note "c e g c5"` |
| cljseq      | `(doseq [n [60 64 67 72]] (play! {:pitch/midi n :dur/beats 1/4}) (sleep! 1/4))` |

**Sugar opportunity**: `(phrase! [[60 1/4] [64 1/4] [67 1/4] [72 1/4]])` or
a note-sequence literal form. TidalCycles' mini-notation is extremely compact;
cljseq needs a concise form for writing melodic lines interactively.

**Candidate sugar**:
```clojure
;; Proposed: pitch/duration pairs
(phrase! [:C4 1/4  :E4 1/4  :G4 1/4  :C5 1/4])

;; Proposed: uniform duration
(phrase! [:C4 :E4 :G4 :C5] :dur 1/4)
```

### Ring / cycling pattern

| System      | Form |
|-------------|------|
| Sonic Pi    | `ring(:C4, :E4, :G4).tick` (inside live_loop) |
| TidalCycles | `d1 $ note "c e g"` (cycles automatically) |
| cljseq      | `(nth (cycle [:C4 :E4 :G4]) step)` or `defonce` ring buffer |

**Sugar opportunity**: Sonic Pi's `ring` + `tick` is a clean idiom. cljseq should
offer `(ring :C4 :E4 :G4)` that returns an infinite cycling seq, usable inside a
`deflive-loop` with an internal step counter.

---

## 4. Live Loops

### Define and start a live loop

| System      | Form |
|-------------|------|
| Sonic Pi    | `live_loop :name do ... end` |
| Overtone    | (no native live-loop; requires `overtone.music.time/apply-at` + recursion) |
| TidalCycles | `d1 $ ...` (all patterns are inherently live) |
| cljseq      | `(deflive-loop :name {} body)` / `(live-loop :name body)` |

cljseq and Sonic Pi are directly comparable here. Overtone has a notable gap —
live coding requires manual `apply-at` recursion, which is why Overtone is
largely superseded by SuperCollider + TidalCycles for live performance.

### Sync two loops to a bar boundary

| System      | Form |
|-------------|------|
| Sonic Pi    | `sync :name` (cue/sync mechanism) |
| Overtone    | Manual scheduling arithmetic |
| TidalCycles | Loops are always cycle-synced; no explicit sync needed |
| cljseq      | `(sync! :bar)` |

**Advantage**: cljseq's `sync!` is beat-aware via Link; it is meaningful in a
multi-machine ensemble context (Sonic Pi's `sync` is within-process only).

### Modify a running loop

| System      | Form |
|-------------|------|
| Sonic Pi    | Re-evaluate `live_loop` block in editor |
| Overtone    | Re-evaluate function at REPL |
| TidalCycles | Re-evaluate `d1 $ ...` expression |
| cljseq      | Re-evaluate `deflive-loop` form at nREPL |

All systems use re-evaluation. No syntactic difference; the cljseq advantage
is Clojure's structural editor support and mature nREPL tooling.

---

## 5. Chords

### Play a chord

| System      | Form |
|-------------|------|
| Sonic Pi    | `play chord(:C4, :major)` |
| Overtone    | `(chord :C4 :major)` → MIDI note vector → play each |
| TidalCycles | `d1 $ note "c'maj"` |
| cljseq      | `(play! {:harmony/chord :I :harmony/mode :major})` |

**Note**: cljseq's chord representation is functional-harmony-relative (Roman numeral)
rather than absolute pitch. This is richer but requires an active key context.
For interactive use without a key context, an absolute form is needed.

**Sugar opportunity**:
```clojure
;; Absolute chord (no harmonic context required)
(play-chord! :C4 :major)

;; In a harmonic context (already works)
(with-harmony {:key :C :mode :major}
  (play! {:harmony/chord :I}))
```

### Arpeggiate a chord

| System      | Form |
|-------------|------|
| Sonic Pi    | `play_pattern_timed chord(:C4, :major), [0.25, 0.25, 0.25, 0.25]` |
| TidalCycles | `d1 $ arp "up" $ note "c'maj"` |
| cljseq      | `(deffractal ... :ornament-types [:arp-up])` or `defensemble` with `:strum` |

**Gap**: cljseq has no concise one-liner for arpeggiation outside of ensemble/fractal
contexts. A `(arp! chord direction dur)` helper should be in the sugar layer.

---

## 6. Scales and Modes

### Select a scale

| System      | Form |
|-------------|------|
| Sonic Pi    | `use_scale :C, :dorian` / `scale :C, :major` |
| Overtone    | `(scale :C :major)` → MIDI note vector |
| TidalCycles | `d1 $ note (scale "dorian" "0 2 4 6")` |
| cljseq      | `(scale :C :dorian)` / `:harmony/mode :dorian` in control tree |

Parity. cljseq's scale system (including `.scl`/`.kbm` microtonality) is a
superset of all three comparators.

### Constrain random notes to a scale

| System      | Form |
|-------------|------|
| Sonic Pi    | `play scale(:C, :major).choose` |
| TidalCycles | `d1 $ note (scale "major" $ irand 7)` |
| cljseq      | `(sample (stochastic-sequence :scale (scale :C :major)))` |

**Sugar opportunity**: `(choose-from-scale :C :major)` as a one-liner for
interactive improvisation. The stochastic-sequence form is powerful but verbose
for simple "random note in scale" use.

---

## 7. Randomness and Probability

### Choose from a list

| System      | Form |
|-------------|------|
| Sonic Pi    | `choose [60, 64, 67]` |
| Overtone    | `(choose [60 64 67])` |
| TidalCycles | `d1 $ note (choose ["c" "e" "g"])` |
| cljseq      | `(rand-nth [60 64 67])` (Clojure core) |

cljseq inherits `rand-nth` from Clojure. No sugar needed — Clojure core
is already concise. The cljseq-specific value-add is seeded/reproducible
randomness via `defstochastic`.

### Conditional trigger (probability gate)

| System      | Form |
|-------------|------|
| Sonic Pi    | `play 60 if one_in(4)` |
| TidalCycles | `d1 $ sometimesBy 0.25 (fast 2) $ s "bd"` |
| cljseq      | `(when (< (rand) 0.25) (play! {:pitch/midi 60}))` |

**Sugar opportunity**: `(one-in 4 (play! ...))` matching Sonic Pi's idiom.
The Clojure `when` form is readable but `one-in` expresses musical intent
more directly at the REPL.

### DEJA VU / loop locking

| System      | Form |
|-------------|------|
| Sonic Pi    | No direct equivalent (use `ring` + external mutation) |
| TidalCycles | No direct equivalent |
| Mutable Instruments Marbles | Hardware knob (0=random, 1=locked) |
| cljseq      | `(defstochastic ... :deja-vu 0.8)` |

**Advantage**: cljseq's `defstochastic` DEJA VU is unique among the software
comparators — a first-class locking/memory mechanism for probabilistic patterns.

---

## 8. Repetition and Variation

### Repeat a pattern N times

| System      | Form |
|-------------|------|
| Sonic Pi    | `4.times do ... end` |
| Overtone    | `(dotimes [_ 4] ...)` |
| TidalCycles | `d1 $ fast 4 $ note "c"` |
| cljseq      | `(dotimes [_ 4] ...)` (Clojure core) |

Clojure's `dotimes` is already concise. No sugar needed for simple repetition.

### Every N cycles, do something different

| System      | Form |
|-------------|------|
| Sonic Pi    | `every 4 ... do` |
| TidalCycles | `every 4 (fast 2) $ d1 $ ...` |
| cljseq      | `(when (zero? (mod beat 4)) ...)` |

**Sugar opportunity**: `(every 4 beats form)` macro — a clean conditional that
fires every N beats within a live loop. Sonic Pi and TidalCycles both have this
as a first-class form; cljseq's Clojure `mod`-based form is correct but less
expressive of musical intent.

### Vary on every other bar

| System      | Form |
|-------------|------|
| Sonic Pi    | `if bools(1, 0).tick` |
| TidalCycles | `d1 $ every 2 rev $ note "c d e f"` |
| cljseq      | `(if (odd? bar) form-a form-b)` |

**Advantage**: Clojure's `if`/`when`/`cond` are natural here. `bar` is just
a value in scope.

---

## 9. Multi-Voice / Ensemble

### Define multiple concurrent voices

| System      | Form |
|-------------|------|
| Sonic Pi    | Multiple `live_loop` blocks (independent) |
| Overtone    | Multiple `future` threads (manual) |
| TidalCycles | `d1 $ ...`, `d2 $ ...`, ... (numbered tracks) |
| cljseq      | `(defensemble :name {:voices {:bass ... :melody ...}})` |

**Advantage**: cljseq's `defensemble` encapsulates shared harmonic context across
voices. Sonic Pi's parallel live_loops are independent (no shared harmonic state).
TidalCycles' numbered tracks (`d1`, `d2`) have no harmonic coupling.

### Play chord with strum

| System      | Form |
|-------------|------|
| Sonic Pi    | `play_pattern_timed chord(:C4, :major), [0, 0.05, 0.1, 0.15]` |
| TidalCycles | No direct equivalent |
| cljseq      | `(defensemble :guitar {:articulation {:strum :asc :rate 1/32}})` |

**Advantage**: cljseq's strum is a named articulation parameter separable from
harmonic content. This matches the NDLR's model.

---

## 10. Parameter Control / Modulation

### Bind an LFO to a parameter

| System      | Form |
|-------------|------|
| Sonic Pi    | No direct equivalent |
| Overtone    | Manual: schedule parameter updates with `apply-at` |
| TidalCycles | `d1 $ note "c" # cutoff (range 200 2000 $ slow 4 sine)` |
| cljseq      | `(ctrl/bind! [:filter/cutoff] (lfo :rate 0.25))` |

**Advantage**: cljseq's `ctrl/bind!` is continuous and sample-accurate; TidalCycles'
pattern-rate parameter modulation is cycle-quantized. Sonic Pi has no equivalent.

### Set a parameter live

| System      | Form |
|-------------|------|
| Sonic Pi    | `set :cutoff, 800` / `get :cutoff` |
| Overtone    | `(ctl synth :cutoff 800)` |
| TidalCycles | `d1 $ note "c" # cutoff 800` |
| cljseq      | `(ctrl/set! [:filter/cutoff] 800)` |

All systems support live parameter setting. cljseq's path-based tree addressing
is more structured than Sonic Pi's global key store; more composable than
TidalCycles' per-pattern parameters.

### XY pad / multi-axis morph

| System      | Form |
|-------------|------|
| Sonic Pi    | No equivalent |
| TidalCycles | No equivalent |
| cljseq      | `(defmorph :xy {:inputs {:x [:float 0 1] :y [:float 0 1]} :targets [...]})` |

**Advantage**: unique to cljseq.

---

## 11. Effects and Synthesis

### Apply reverb

| System      | Form |
|-------------|------|
| Sonic Pi    | `with_fx :reverb, mix: 0.5 do ... end` |
| Overtone    | `(effect :freeverb synth :room 0.8)` |
| TidalCycles | `d1 $ s "bd" # room 0.5` |
| cljseq      | `(ctrl/set! [:fx/reverb :mix] 0.5)` (control tree) |

**Note**: cljseq routes effects through the control tree (targeting a CLAP plugin
or SC synth). No syntactic wrapping required; the routing is configured once per
voice, not per call. This is a different model — closer to a mixing desk than an
effect block.

### Load a synth / instrument

| System      | Form |
|-------------|------|
| Sonic Pi    | `use_synth :piano` |
| Overtone    | `(definst piano ...)` |
| TidalCycles | `d1 $ s "piano"` |
| cljseq      | `(defpatch :piano {...})` / `(patch! :piano)` |

**Gap**: cljseq lacks a concise "use this synth for all following notes" idiom
within a live loop. `use-synth!` as a loop-local binding could be useful.

---

## 12. Song Structure / Navigation

### Move to next section

| System      | Form |
|-------------|------|
| Sonic Pi    | Re-evaluate with different parameters |
| TidalCycles | Re-evaluate pattern |
| cljseq      | `(next-patch!)` / `(goto-patch! :chorus)` |

**Advantage**: cljseq's `deflive-set` / patch navigation is the most structured
approach — named sections with beat-aware transitions, undoable.

### Save and restore a state snapshot

| System      | Form |
|-------------|------|
| Sonic Pi    | No equivalent |
| TidalCycles | No equivalent |
| cljseq      | `(checkpoint! :verse)` / `(panic!)` |

**Advantage**: unique to cljseq.

---

## Summary: Syntactic Sugar Backlog

Candidates identified from this corpus, in rough priority order:

| Sugar form | Replaces | Priority |
|------------|----------|----------|
| `(play! :C4)` / `(play! 60)` | `(play! {:pitch/midi 60 :dur/beats 1})` | High |
| `(phrase! [:C4 :E4 :G4] :dur 1/4)` | `doseq` + step maps | High |
| `(every 4 beats form)` | `(when (zero? (mod beat 4)) form)` | High |
| `(one-in 4 form)` | `(when (< (rand) 0.25) form)` | Medium |
| `(ring :C4 :E4 :G4)` | `(cycle [...])` + index | Medium |
| `(play-chord! :C4 :major)` | `(play! {:harmony/chord :I})` with context | Medium |
| `(choose-from-scale :C :major)` | `(sample (stochastic-sequence :scale ...))` | Medium |
| `(arp! chord :up 1/16)` | `defensemble` with strum | Medium |
| `(use-synth! :piano)` | `defpatch` + `patch!` | Low |

---

## Conceptual Gaps (no cljseq equivalent yet)

| Concept | Comparator | Notes |
|---------|-----------|-------|
| Mini-notation pattern string | TidalCycles `"[a b] c [d e f]"` | Powerful shorthand for polyrhythm; could be a reader extension |
| Sample playback | Sonic Pi `sample :bd_haus` | Not in scope for current design (MIDI/OSC focus) |
| Built-in effects chain | Sonic Pi `with_fx` | cljseq targets external effects via CLAP/SC; no built-in FX |
| Pattern transformation operators | TidalCycles `rev`, `fast`, `slow`, `jux` | Fractal sequencer covers some of this; explicit operator library not designed |
| Generative grammar / L-system | — | Not in current scope; possible future `cljseq.grammar` namespace |

---

## Notes on Comparator Scope

- **Sonic Pi** is the closest live-coding comparator in terms of UX target audience
  (musicians, not programmers). Its strengths are conciseness and approachability;
  its weaknesses are the seconds-based timing model and limited multi-voice composition.
- **Overtone** is the Clojure precedent. Its nREPL workflow is directly ancestral to
  cljseq's; its weakness is the absence of native live-loop support.
- **TidalCycles** is the strongest comparator for pattern manipulation and structural
  conciseness. Its mini-notation is uniquely powerful. Its weakness is the functional/Haskell
  programming model being a barrier for musicians.
- **cljseq** targets a sweet spot: Sonic Pi's approachability + Overtone's Clojure
  integration + TidalCycles' structural depth, with hardware sequencer-grade timing
  (via Link) and a persistent control tree that none of the comparators have.
