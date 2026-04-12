# Design: cljseq as Studio Orchestration Layer

## Status

Design document — foundational vision. All component designs (bwosc, surface
adapters, topology) are in service of this document.

---

## The vision in one sentence

**The session is the cljseq score. Everything else is execution.**

---

## The core principle

Control surfaces are not special. DAWs are not special. They are all devices —
nodes in the cljseq session topology with protocols they speak, capabilities they
expose, and data flowing in, out, or both.

| Device | Protocol | Capabilities |
|--------|----------|--------------|
| Bitwig Studio | bwosc IPC / MCU | clips, devices, transport, synthesis |
| Harrison MixBus | MCU / OSC | tracking, mixing, Harrison channel strip |
| PreSonus FaderPort 16 | FaderPort native / MCU | 16 faders, scribble strips, transport |
| PreSonus FaderPort 8 | FaderPort native / MCU | 8 faders, scribble strips, transport |
| PreSonus FaderPort 1 | FaderPort native / MCU | 1 fader, monitor section, transport |
| SSL UF8 | MCU (via SSL 360°) | 8 faders, scribble strips, soft keys |
| SSL UF1 | MCU (via SSL 360°) | master section, monitor control |
| SSL UC1 | SSL 360° native | plugin / channel control |
| TouchOSC (iPad) | OSC | soft faders, buttons, XY pads |
| Hydrasynth | MIDI NRPN | synth voice, MPE, full NRPN map |
| Torso T-1 | MIDI / Link | euclidean sequencer, Link peer |
| Any MIDI synth | MIDI CC/NRPN | per device map |

cljseq is not in this table. cljseq is the orchestration layer above it. It
speaks all of these protocols, presents itself as any of them when needed, and
holds the session model that all devices execute.

---

## The ctrl tree as universal session state

The ctrl tree is already the ground truth for cljseq session state. Every device
is a reader and/or writer of ctrl tree paths. Protocol adapters translate between
a device's wire format and ctrl tree read/write operations.

```
Device gesture          Protocol adapter         Ctrl tree
──────────────────────────────────────────────────────────
FaderPort fader move  → FP native decoder    → [:surface :strip 3 :fader]     = 0.73
Bitwig track volume   → MCU decoder          → [:bitwig :track 3 :volume]     = 0.8
T-1 note on           → MIDI-in handler      → [:ivk :current-pitch]          = 62
journey bar tick      → bar counter          → [:journey :current-bar]        = 47
harmonic tension calc → harmony engine       → [:harmony :tension]            = 0.6
LFO tick              → mod runner           → [:berlin :ost-a :mutation-rate] = 0.12
```

A fader assigned to `[:harmony :tension]` reads that path and sends motorised
position updates as the tension changes. When you move that fader, you write to
that path and every subscriber — the journey conductor, the filter trajectory,
the mutation rate, the Bitwig device param — responds.

This is indistinguishable from riding a fader on a vocal in a traditional mix.
The gesture is identical. What the fader *means* is entirely different.

---

## Channel strips as compositional controls

A channel strip is not a mixer channel. It is a physical control surface for
one dimension of the session — whatever dimension is assigned to it.

Conventional assignment:
```
Strip 3 → Bitwig track 3 volume/pan/mute/solo/arm
```

cljseq assignment — any of these, live-switchable:
```
Strip 1 → [:harmony :tension]           "ride the tension arc"
Strip 2 → [:berlin :ost-a :mutation-rate] "ride ostinato A crystallisation"
Strip 3 → [:ambient :field :probability] "ride generative field density"
Strip 4 → [:berlin :phase-offset :a :b]  "ride phase alignment between voices"
Strip 5 → [:journey :current-bar]        "display-only: journey position"
Strip 6 → [:bitwig :track 3 :volume]     "conventional: Bitwig track 3"
Strip 7 → [:mixbus :track 3 :fader]      "conventional: MixBus track 3"
Strip 8 → [:link :tempo]                 "ride tempo"
```

The scribble strip above each fader shows the path name and current value, not
a track name. `TENSION 0.73`, `MUTATION 0.12`, `PROB FIELD 0.04`.

**The FaderPort 1 as focus strip:** The single-strip FP1 is always assigned to
whichever compositional parameter is most load-bearing at this moment in the arc.
Switch focus with a button; the fader follows. One hand on the most important
dimension of the session, always.

---

## The dual-DAW model

Bitwig and MixBus serve different roles. Neither drives the session — cljseq does.

```
┌─────────────────────────────────────────────────────────────┐
│  cljseq — session orchestration layer                       │
│  score (Clojure value) · ctrl tree · journey conductor      │
│  harmony engine · generative fields · trajectory curves     │
└──────────┬───────────────────────────┬──────────────────────┘
           │ bwosc IPC / MCU           │ MCU / OSC
    ┌──────▼──────┐              ┌─────▼──────┐
    │   Bitwig    │              │  MixBus    │
    │  creative   │              │  tracking  │
    │  synthesis  │              │  mixing    │
    │  clips      │              │  Harrison  │
    │  devices    │              │  ch-strip  │
    └─────────────┘              └────────────┘
```

**Bitwig** — the synthesis and performance engine. cljseq launches clips,
automates device parameters, drives Grid patches and VST instruments. Bitwig
does not know what a tension arc is. It executes the MIDI notes and parameter
values that cljseq sends.

**MixBus** — the tracking and mixing engine. Sessions that need Harrison's
channel strip DSP and Ardour's tracking infrastructure run here. cljseq can
automate sends, fader levels, and routing decisions, timed to the compositional
arc (send-journey!, strip-down!).

Both DAWs are peers in the topology. Surface strips can be assigned to either
or to cljseq's own state. Context switching between DAWs is a ctrl tree
operation, not a hardware reconfiguration.

---

## Surface topology — studio and travel instances

### Full studio

```
cljseq (Mac Mini)
├── FaderPort 16   — 16 compositional or conventional strips
├── FaderPort 1    — focus strip, always on most important parameter
├── SSL UF8        — 8 additional strips via SSL 360°/MCU
├── SSL UF1        — master section / monitor control
├── SSL UC1        — plugin / device param deep control
├── TouchOSC       — iPad soft surface (OSC, LAN)
├── Bitwig         — creative DAW (Ubuntu, LAN peer)
└── MixBus         — tracking DAW (Ubuntu, LAN peer)
```

### Travel / gig minimum

```
cljseq (laptop)
├── FaderPort 8    — 8 compositional strips, scribble strips, transport
└── ivk keyboard   — note/interval input, harmonic pivots
```

The travel instance is not a reduced version of the studio. It is the same
compositional vocabulary, the same orchestration logic, the same ctrl tree
intelligence — in a carry-on bag. The studio adds execution power and physical
surface area. The orchestration is identical.

---

## Protocol adapters

Protocol adapters translate between a device's wire format and ctrl tree
operations. They are not special — they are device maps that happen to cover
a bidirectional protocol rather than a unidirectional one.

### MCU (Mackie Control Universal)
- Fader: pitch bend messages, ch 0–7, 14-bit
- Buttons: note on/off with defined note numbers
- Scribble strips: SysEx LCD write, 2 lines × 7 chars
- Meters: channel pressure
- Transport: dedicated note numbers

### HUI (Human User Interface)
- Pro Tools heritage; zone/port addressing model
- Same conceptual strip model as MCU; different wire encoding
- Fader: CC pairs (zone select + value)

### FaderPort native
- SysEx header: `F0 00 01 06` + `16` (FP16) / `02` (FP8/2)
- Fader: pitch bend ch 0–N, 14-bit (0–16368)
- Touch: note on/off, notes 0x68–0x77 (FP16)
- Scribble strip: SysEx cmd 0x12, strip ID, line (0–3), ≤8 chars, 4 lines
- LED colour: status 0x91/0x92 RGB triplets
- Heartbeat (FP16→host): `A0 00 00` every second
- FP8 and FP16 share one implementation; SysEx ID distinguishes them

### OSC (TouchOSC, bwosc, any OSC client)
- Address-based: `/cljseq/strip/3/fader`, `/cljseq/harmony/tension`, etc.
- TouchOSC templates designed against published cljseq OSC address space
- bwosc (github.com/cljseq/bwosc): Bitwig-specific OSC bridge; Bitwig device
  params become OSC addresses; see `design-bwosc.md`

### Capability degradation
Strips declare capabilities at registration. MCU strips have 2-line LCD;
FaderPort native strips have 4-line scribble. Session code written against
the abstract strip model degrades gracefully — no conditional logic at
the composition layer.

---

## The surface model — abstract layer

```clojure
;; Abstract surface — protocol-agnostic
{:surface/id   :fp8-travel
 :surface/name "FaderPort 8 (travel)"
 :strips
 [{:strip/id 0 :capabilities #{:fader :touch :scribble :mute :solo :select}}
  {:strip/id 1 :capabilities #{:fader :touch :scribble :mute :solo :select}}
  ;; ...
  {:strip/id 7 :capabilities #{:fader :touch :scribble :mute :solo :select}}]
 :transport    {:play true :stop true :record true :loop true}
 :navigation   {:bank true :channel true :zoom true}
 :encoders     [{:encoder/id 0} {:encoder/id 1}]}

;; Strip assignment — maps ctrl tree paths to physical strips
{:assignments
 [{:strip 0 :path [:harmony :tension]            :label "TENSION"}
  {:strip 1 :path [:berlin :ost-a :mutation-rate] :label "MUTATION A"}
  {:strip 2 :path [:ambient :field :probability]  :label "PROB FIELD"}
  {:strip 3 :path [:berlin :phase-offset :a :b]   :label "PHASE A/B"}
  {:strip 4 :path [:journey :current-bar]         :label "BAR" :read-only true}
  {:strip 5 :path [:link :tempo]                  :label "TEMPO"}
  {:strip 6 :path [:bitwig :track 1 :volume]      :label "KICK"}
  {:strip 7 :path [:mixbus :master :fader]        :label "MASTER"}]}
```

Assignments are EDN data. Load a different assignment file to reconfigure the
surface for a different compositional context. A "tension arc" layout and a
"conventional mixing" layout are both valid; switch between them live.

---

## Composition drives execution — the full stack

```
cljseq score (Clojure value)
  ↓ journey conductor fires transition at bar 48
    ↓ crystallize! ost-a — mutation rate drops toward zero
    ↓ filter-journey! ch1 — filter opens over next 32 bars
    ↓ bwosc: launch Bitwig clip track 2 slot 3
    ↓ MixBus: send-journey! reverb send track 1 0→0.8 over 16 bars
    ↓ surface: strip 1 fader motors to new mutation rate position
    ↓ surface: scribble strips update to "CRYST 0.02"
    ↓ MCP: Claude observes transition via get-ctrl, get-harmony-ctx
```

One compositional event. Every execution engine responds. No device is
driving the session — the score is.

---

## The MCP bridge connection

The MCP bridge (cljseq.mcp) gives Claude read/write access to the ctrl tree.
From the surface perspective, Claude is just another device — one that can
read scribble strip values, set fader positions, fire journey transitions, and
observe how execution engines respond.

The surface, the AI collaboration layer, and the compositional vocabulary are
all the same bet placed at different layers of the stack. They compound:
deeper vocabulary → richer surface control → more AI-legible session state
→ more capable compositional collaboration.

---

## Build order

The surface model is only as expressive as the compositional vocabulary
beneath it. Build order is therefore:

1. **`cljseq.journey`** — bar counter, journey conductor, phase-pair, humanise.
   Cross-cutting infrastructure. Unlocks structural arc control on surfaces.
   *(feature/journey, off main)*

2. **`cljseq.berlin`** — ostinato mutation, filter-journey!, phase-drift.
   First real compositional parameters worth putting on a fader.
   *(explore/kosmische, off main)*

3. **Surface protocol adapters** — FaderPort native (FP8 + FP16), MCU.
   Once journey + berlin exist, there is something meaningful to assign to strips.
   *(feature/surface-adapters, off main)*

4. **Strip assignment model** — EDN-driven ctrl tree path → physical strip mapping.
   Layout files. Live switching. The surface becomes reconfigurable without code.

5. **`cljseq.ambient` + `cljseq.dub`** — deepen the vocabulary available on surfaces.
   *(explore/eno-field, explore/dub-techno)*

6. **bwosc** — Bitwig as a device in the tree.
   *(github.com/cljseq/bwosc, standalone repo)*

7. **MixBus integration** — MCU adapter pointed at MixBus; tracking session control.

8. **Distributed surface topology** — surfaces on remote peers via OSC + mDNS.
   The studio model becomes fully distributed.

---

## Open questions

1. **`cljseq.surface` or separate org repo?** The protocol adapters and strip
   model could live in cljseq or as a standalone `cljseq/surface` repo usable
   without cljseq. Given that the strip assignment model is ctrl-tree-dependent,
   keeping it in cljseq is simpler for now.

2. **SSL 360° mediation** — SSL UF8/UF1/UC1 require SSL 360° middleware. Does
   cljseq speak MCU to 360° (which speaks to the hardware), or is there a path
   to the hardware directly? For now: speak MCU to 360°, treat the SSL family
   as MCU devices.

3. **Assignment persistence** — assignment EDN files per session/project, or
   part of the cljseq score? Probably both: a default layout per surface, with
   per-session overrides.

4. **FaderPort 1 focus strip protocol** — the FP1 is the smallest of the family.
   Confirm it shares the native protocol with FP8/FP16 or requires separate adapter.

5. **Bitwig / MixBus simultaneous control** — when both DAWs are running, surface
   strips assigned to each need to track the correct DAW's state independently.
   The ctrl tree path namespace (`:bitwig/*` vs `:mixbus/*`) handles this at the
   data level; the surface adapter needs to route correctly.

---

## Reference implementations

- **Ardour `libs/surfaces/faderport8/`** — FaderPort native protocol (C++)
- **DrivenByMoss** — multi-surface Bitwig controller framework (Java)
- **bwosc design** — `doc/design-bwosc.md` (Bitwig OSC bridge)
- **IPC sidecar design** — `doc/design-bitwig-sidecar.md` (deeper Bitwig integration)
- **Topology design** — `doc/design-distributed-embedded.md`
