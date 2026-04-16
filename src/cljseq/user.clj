; SPDX-License-Identifier: EPL-2.0
(ns cljseq.user
  "Convenience namespace for REPL sessions.

  Load this at the start of a session to get everything you need in scope:

    (require '[cljseq.user :refer :all])
    (session!)           ; start clock at 120 BPM, prints status
    (session! :bpm 140)  ; with custom BPM

  Everything in cljseq.core, cljseq.dsl, and the most-used theory namespaces
  is re-exported. You can also require just the pieces you need directly.

  Typical live session:

    (session!)
    (start-sidecar! :midi-port 0)   ; connect MIDI output

    ;; Simple loop
    (deflive-loop :kick {}
      (play! {:pitch/midi 36 :dur/beats 1/4})
      (sleep! 1))

    ;; Hot-swap the body any time
    (deflive-loop :kick {}
      (play! {:pitch/midi 38 :dur/beats 1/4})
      (sleep! 1/2))

    ;; Stop individual or all loops
    (stop-loop! :kick)
    (end-session!)

  See examples/ for full demonstrations."
  (:require [cljseq.analyze    :as analyze]
            [cljseq.arc        :as arc]
            [cljseq.ardour     :as ardour]
            [cljseq.link       :as link]
            [cljseq.chord      :as chord]
            [cljseq.conductor  :as conductor]
            [cljseq.core       :as core]
            [cljseq.dsl        :as dsl]
            [cljseq.fractal    :as frac]
            [cljseq.learn      :as learn]
            [cljseq.loop       :as loop-ns]
            [cljseq.mod        :as mod]
            [cljseq.pattern    :as pat]
            [cljseq.scala      :as scala]
            [cljseq.scale      :as scale]
            [cljseq.sidecar    :as sidecar]
            [cljseq.ensemble-improv :as ei]
            [cljseq.peer            :as peer]
            [cljseq.server          :as server]
            [cljseq.synth           :as synth]
            [cljseq.sc              :as sc]
            [cljseq.fm              :as fm]
            [cljseq.spectral        :as spectral]
            [cljseq.stochastic      :as stoch]
            [cljseq.texture    :as tx]
            [cljseq.trajectory :as traj]
            [cljseq.patch         :as patch]
            [cljseq.sample        :as smp]
            [cljseq.freesound     :as freesound]
            [cljseq.spatial-field :as sf]
            [cljseq.config     :as config]
            [cljseq.osc        :as osc]
            [cljseq.remote     :as remote]
            [cljseq.transform  :as xf]
            [cljseq.ivk        :as ivk]
            [cljseq.midi-in    :as midi-in]
            [cljseq.voice      :as voice]
            [cljseq.journey         :as journey]
            [cljseq.berlin          :as berlin]
            [cljseq.temporal-buffer :as tbuf]
            [cljseq.ctrl            :as ctrl-ns]
            [cljseq.supervisor      :as supervisor]))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn session!
  "Start a cljseq session: boot the system clock and print a status summary.

  Options:
    :bpm — initial BPM (default 120)

  Does nothing if the system is already running (idempotent).

  Example:
    (session!)
    (session! :bpm 140)"
  [& {:keys [bpm] :or {bpm 120}}]
  (core/start! :bpm bpm)
  (println (str "Session ready — BPM " bpm
                "\n  (start-sidecar! :midi-port 0) to connect MIDI output"
                "\n  (end-session!) to stop"))
  nil)

(defn end-session!
  "Stop all loops and shut down the system."
  []
  (core/stop!)
  nil)

;; ---------------------------------------------------------------------------
;; Re-export the most-used core API
;; ---------------------------------------------------------------------------

(def start!               core/start!)
(def stop!                core/stop!)
(def set-bpm!             core/set-bpm!)
(def get-bpm              core/get-bpm)
(def set-beats-per-bar!   core/set-beats-per-bar!)
(def get-beats-per-bar    core/get-beats-per-bar)
(def play!                core/play!)
(def sleep!               core/sleep!)
(def sync!                core/sync!)
(def stop-loop!           core/stop-loop!)
(def now                  core/now)
(defn apply-trajectory!
  "Apply an ITemporalValue trajectory.

  2-arity: (apply-trajectory! setter-fn traj) — calls setter-fn with each value.
  3-arity: (apply-trajectory! node-id param traj) — drives a live SC node parameter.

  Returns a cancel function."
  ([setter-fn traj]  (core/apply-trajectory! setter-fn traj))
  ([node-id param traj] (sc/apply-trajectory! node-id param traj)))

(defmacro deflive-loop [loop-name opts & body]
  `(core/deflive-loop ~loop-name ~opts ~@body))

(defmacro live-loop [loop-name opts & body]
  `(core/deflive-loop ~loop-name ~opts ~@body))

;; ---------------------------------------------------------------------------
;; Re-export DSL
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Trajectory / arc types
;; ---------------------------------------------------------------------------

(def trajectory       traj/trajectory)
(def apply-curve      traj/apply-curve)
(def buildup          traj/buildup)
(def trance-buildup   traj/trance-buildup)
(def breakdown        traj/breakdown)
(def anticipation     traj/anticipation)
(def groove-lock      traj/groove-lock)
(def wind-down        traj/wind-down)
(def swell            traj/swell)
(def tension-peak     traj/tension-peak)
(def arc-merge        traj/arc-merge)
(def time-warp        traj/time-warp)
(def mod-route!    mod/mod-route!)
(def mod-unroute!  mod/mod-unroute!)
(def arc-bind!        arc/arc-bind!)
(def arc-unbind!      arc/arc-unbind!)
(def arc-send!        arc/arc-send!)
(def arc-routes       arc/arc-routes)
(def defconductor!    conductor/defconductor!)
(def fire!            conductor/fire!)
(def abort!           conductor/abort!)
(def cue!             conductor/cue!)
(def conductor-state  conductor/conductor-state)
(def conductor-names  conductor/conductor-names)

;; ---------------------------------------------------------------------------
;; Texture protocol
;; ---------------------------------------------------------------------------

(def deftexture!          tx/deftexture!)
(def get-texture          tx/get-texture)
(def texture-names        tx/texture-names)
(def buffer-texture       tx/buffer-texture)
(def texture-transition!  tx/texture-transition!)
(def shadow-init!         tx/shadow-init!)
(def shadow-update!       tx/shadow-update!)
(def shadow-get           tx/shadow-get)

;; ---------------------------------------------------------------------------
;; Signal graph patches
;; ---------------------------------------------------------------------------

(def defpatch!           patch/defpatch!)
(def get-patch           patch/get-patch)
(def patch-names         patch/patch-names)
(def patch-params        patch/patch-params)
(def compile-patch       patch/compile-patch)
(def canonical->backend  patch/canonical->backend)

;; Sample player
(def defbuffer!       smp/defbuffer!)
(def buffer-id        smp/buffer-id)
(def buffer-path      smp/buffer-path)
(def sample-names     smp/sample-names)
(def load-sample!     smp/load-sample!)
(def unload-sample!   smp/unload-sample!)
(def sample!          smp/sample!)
(def loop-sample!     smp/loop-sample!)
(def granular-cloud!  smp/granular-cloud!)

;; Freesound integration — fetch-on-use sample catalog
(def set-freesound-key!         freesound/set-api-key!)
(def fetch-sample!              freesound/fetch-sample!)
(def fetch-and-load!            freesound/fetch-and-load!)
(def freesound-info             freesound/sound-info)
(def search-freesound           freesound/search-freesound)
(def load-essentials!           freesound/load-essentials!)
(def load-and-prime-essentials! freesound/load-and-prime-essentials!)
(def curate-essentials!         freesound/curate-essentials!)

;; SC patch lifecycle (requires (connect-sc!) first)
(def instantiate-patch!  sc/instantiate-patch!)
(def set-patch-param!    sc/set-patch-param!)
(def free-patch!         sc/free-patch!)
(def sc-bus!             sc/sc-bus!)
(def free-bus!           sc/free-bus!)
(def bus-idx             sc/bus-idx)

;; ---------------------------------------------------------------------------
;; Spatial field
;; ---------------------------------------------------------------------------

(def defspatial-field!    sf/defspatial-field!)
(def start-field!         sf/start-field!)
(def stop-field!          sf/stop-field!)
(def stop-all-fields!     sf/stop-all-fields!)
(def field-state          sf/field-state)
(def field-transformer    sf/field-transformer)
(def play-through-field!  sf/play-through-field!)
(def get-field            sf/get-field)
(def field-names          sf/field-names)
(def quad-gains           sf/quad-gains)
(def ngon-walls           sf/ngon-walls)

;; ---------------------------------------------------------------------------
;; Spectral analysis
;; ---------------------------------------------------------------------------

(def start-spectral!  spectral/start-spectral!)
(def stop-spectral!   spectral/stop-spectral!)
(def spectral-ctx     spectral/spectral-ctx)

;; ---------------------------------------------------------------------------
;; Ensemble improv
;; ---------------------------------------------------------------------------

(def start-improv!        ei/start-improv!)
(def stop-improv!         ei/stop-improv!)
(def update-improv!       ei/update-improv!)
(def improv-state         ei/improv-state)
(def improv-gesture-fn    ei/improv-gesture-fn)
(def default-improv-profile ei/default-profile)

;; ---------------------------------------------------------------------------
;; Peer discovery and ctrl-tree mounting (Topology Layer 2)
;; ---------------------------------------------------------------------------

(def set-node-profile!   peer/set-node-profile!)
(def node-profile        peer/node-profile)
(def node-id             peer/node-id)
(def start-discovery!    peer/start-discovery!)
(def stop-discovery!     peer/stop-discovery!)
(def discovery-running?  peer/discovery-running?)
(def peers               peer/peers)
(def peer-info           peer/peer-info)
(def mount-peer!         peer/mount-peer!)
(def unmount-peer!       peer/unmount-peer!)
(def mounted-peers       peer/mounted-peers)
(def peer-harmony-ctx    peer/peer-harmony-ctx)
(def peer-spectral-ctx   peer/peer-spectral-ctx)
(def ctx->serial         peer/ctx->serial)
(def serial->scale       peer/serial->scale)

;; Topology Layer 3 — backends + session profile
(def register-backend!        peer/register-backend!)
(def deregister-backend!      peer/deregister-backend!)
(def active-backends          peer/active-backends)
(def peer-backends            peer/peer-backends)
(def publish-session-profile! peer/publish-session-profile!)

;; ---------------------------------------------------------------------------
;; nREPL remote eval (Topology Layer 3)
;; ---------------------------------------------------------------------------

(def connect-peer!    remote/connect!)
(def disconnect-peer! remote/disconnect!)
(def remote-eval!     remote/remote-eval!)
(def eval-on-peer!    remote/eval-on-peer!)
(defmacro with-peer [conn & body] `(remote/with-peer ~conn ~@body))

;; ---------------------------------------------------------------------------
;; Note transformers
;; ---------------------------------------------------------------------------

(def play-transformed!   xf/play-transformed!)
(def compose-xf          xf/compose-xf)
(def velocity-curve      xf/velocity-curve)
(def quantize            xf/quantize)
(def harmonize           xf/harmonize)
(def echo                xf/echo)
(def note-repeat         xf/note-repeat)
(def strum               xf/strum)
(def dribble             xf/dribble)
(def latch               xf/latch)

;; ---------------------------------------------------------------------------
;; Analysis
;; ---------------------------------------------------------------------------

(def pitch-class-dist          analyze/pitch-class-dist)
(def detect-key               analyze/detect-key)
(def detect-key-candidates    analyze/detect-key-candidates)
(def detect-mode              analyze/detect-mode)
(def identify-chord           analyze/identify-chord)
(def identify-chord-candidates analyze/identify-chord-candidates)
(def chord->roman             analyze/chord->roman)
(def analyze-progression      analyze/analyze-progression)
(def scale-fitness            analyze/scale-fitness)
(def suggest-scales           analyze/suggest-scales)
(def tension-score            analyze/tension-score)
(def progression-tension      analyze/progression-tension)
(def borrowed-chord?          analyze/borrowed-chord?)
(def annotate-progression     analyze/annotate-progression)
(def suggest-progression      analyze/suggest-progression)

;; ---------------------------------------------------------------------------
;; MIDI Learn — device map authoring
;; ---------------------------------------------------------------------------

(def start-learn-session!  learn/start-learn-session!)
(def learn-notes!          learn/learn-notes!)
(def learn-cc!             learn/learn-cc!)
(def learn-sequence!       learn/learn-sequence!)
(def cancel-learn!         learn/cancel-learn!)
(def learning?             learn/learning?)
(def learn-session-state   learn/learn-session-state)
(def clear-session!        learn/clear-session!)
(def export-device-map!    learn/export-device-map!)

;; ---------------------------------------------------------------------------
;; Re-export DSL
;; ---------------------------------------------------------------------------

(defmacro with-dur [dur & body] `(dsl/with-dur ~dur ~@body))
(def use-harmony!   dsl/use-harmony!)

(defmacro with-harmony [scale & body]
  `(dsl/with-harmony ~scale ~@body))

(def use-chord!     dsl/use-chord!)
(def use-synth!     dsl/use-synth!)
(def use-tuning!    dsl/use-tuning!)
(def play-chord!    dsl/play-chord!)
(def play-voicing!  dsl/play-voicing!)
(def arp!           dsl/arp!)
(defmacro phrase! [& args] `(dsl/phrase! ~@args))
(def ring           dsl/ring)
(def tick!          dsl/tick!)

(defmacro with-tuning [ctx & body]
  `(dsl/with-tuning ~ctx ~@body))

;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------
;; Scala microtonal scales
;; ---------------------------------------------------------------------------

(def parse-scl         scala/parse-scl)
(def load-scl          scala/load-scl)
(def degree-count      scala/degree-count)
(def degree->cents     scala/degree->cents)
(def degree->note      scala/degree->note)
(def degree->step      scala/degree->step)
(def interval-cents    scala/interval-cents)
(def parse-kbm         scala/parse-kbm)
(def load-kbm          scala/load-kbm)
(def midi->note        scala/midi->note)
(def midi->step        scala/midi->step)
(def scale->mts-bytes  scala/scale->mts-bytes)

;; ---------------------------------------------------------------------------
;; Ardour DAW integration
;; ---------------------------------------------------------------------------

(def connect-ardour!       ardour/connect!)
(def transport-play!       ardour/transport-play!)
(def transport-stop!       ardour/transport-stop!)
(def transport-record!     ardour/transport-record!)
(def goto-start!           ardour/goto-start!)
(def add-marker!           ardour/add-marker!)
(def ardour-save!          ardour/save!)
(def capture!              ardour/capture!)
(def capture-stop!         ardour/capture-stop!)
(def capture-discard!      ardour/capture-discard!)
(def capture-status        ardour/capture-status)
(def recording?            ardour/recording?)

;; ---------------------------------------------------------------------------
;; Synthesis vocabulary
;; ---------------------------------------------------------------------------

(def defsynth!      synth/defsynth!)
(def get-synth      synth/get-synth)
(def synth-names    synth/synth-names)
(def load-synth-map synth/load-synth-map)
(def compile-synth  synth/compile-synth)
(def map-graph      synth/map-graph)
(def synth-ugens    synth/synth-ugens)
(def transpose-synth synth/transpose-synth)
(def scale-amp      synth/scale-amp)
(def replace-arg    synth/replace-arg)

;; FM synthesis vocabulary
(def def-fm!       fm/def-fm!)
(def get-fm        fm/get-fm)
(def fm-names      fm/fm-names)
(def fm-algorithm  fm/fm-algorithm)
(def compile-fm    fm/compile-fm)

;; SuperCollider backend
(def connect-sc!         sc/connect-sc!)
(def disconnect-sc!      sc/disconnect-sc!)
(def start-sc!           sc/start-sc!)
(def stop-sc!            sc/stop-sc!)
(def sc-restart!         sc/sc-restart!)
(def sc-connected?       sc/sc-connected?)
(def synthdef-str        sc/synthdef-str)
(def send-synthdef!      sc/send-synthdef!)
(def send-all-synthdefs! sc/send-all-synthdefs!)
(def ensure-synthdef!    sc/ensure-synthdef!)
(def sc-synth!         sc/sc-synth!)
(def set-param!        sc/set-param!)
(def free-synth!       sc/free-synth!)
(def kill-synth!       sc/kill-synth!)
(def sc-play!          sc/sc-play!)
(def ramp-param!       sc/ramp-param!)
(def sc-status         sc/sc-status)
(def bind-spectral!    sc/bind-spectral!)
(def unbind-spectral!  sc/unbind-spectral!)

;; ---------------------------------------------------------------------------
;; HTTP control server + WebSocket broadcast
;; ---------------------------------------------------------------------------

(def start-server!    server/start-server!)
(def stop-server!     server/stop-server!)
(def server-port      server/server-port)
(def server-running?  server/server-running?)

;; Global ctrl-tree watchers — fire on every set!/send! regardless of path.
;; Primary use: server-side WebSocket broadcast, but open to any subscriber.
(def watch-global!    ctrl-ns/watch-global!)
(def unwatch-global!  ctrl-ns/unwatch-global!)

;; ---------------------------------------------------------------------------
;; OSC receive + push-subscribe (§17 Phase 2/3)
;; ---------------------------------------------------------------------------

(def start-osc-server!       osc/start-osc-server!)
(def stop-osc-server!        osc/stop-osc-server!)
(def osc-running?            osc/osc-running?)
(def osc-port                osc/osc-port)
(def osc-send!               osc/osc-send!)
(def osc-subscribe!          osc/subscribe!)
(def osc-unsubscribe!        osc/unsubscribe!)
(def osc-unsubscribe-all!    osc/unsubscribe-all!)
(def osc-subscribers         osc/subscribers)
(def ctrl-path->osc-address  osc/ctrl-path->osc-address)

;; ---------------------------------------------------------------------------
;; Sidecar shorthand
;; ---------------------------------------------------------------------------

(def diag-on!            sidecar/diag-on!)
(def diag-off!           sidecar/diag-off!)
(def set-diag!           sidecar/set-diag!)
(def sidecar-verbose!    sidecar/sidecar-verbose!)
(def sidecar-quiet!      sidecar/sidecar-quiet!)

(def list-midi-ports         sidecar/list-midi-ports)
(def find-midi-port          sidecar/find-midi-port)
(def send-cc!                sidecar/send-cc!)
(def send-pitch-bend!        sidecar/send-pitch-bend!)
(def send-channel-pressure!  sidecar/send-channel-pressure!)
(def await-midi-message      sidecar/await-midi-message)

(defn start-sidecar!
  "Connect the MIDI sidecar.

  Options:
    :midi-port    — MIDI output port: integer index or name substring (default 0)
    :midi-in-port — MIDI input port: integer index or name substring (default nil)
    :kbd?         — when true, pass --kbd to the sidecar binary to enable
                    global keyboard capture (CGEventTap, macOS; requires
                    Accessibility permission).  After start-sidecar! returns,
                    call start-kbd! to register the 0x21 KbdEvent handler.
    :binary       — explicit path to the sidecar binary (default: searches build/).
                    Needed when running from a git worktree that has no build/ dir.

  Use (list-midi-ports) to discover available ports.

  Example:
    (start-sidecar!)
    (start-sidecar! :midi-port 1)
    (start-sidecar! :midi-port \"IAC\")
    (start-sidecar! :midi-port \"Hydra\" :midi-in-port \"Hydra\")
    (start-sidecar! :kbd? true)   ; keyboard rig — no MIDI hardware needed
    (start-sidecar! :binary \"/path/to/cljseq-sidecar\")  ; worktree use"
  [& {:keys [binary midi-port midi-in-port kbd?] :or {midi-port 0}}]
  (sidecar/start-sidecar! :binary binary :midi-port midi-port
                           :midi-in-port midi-in-port :kbd? kbd?)
  (println (str "Sidecar started on MIDI port " midi-port))
  nil)

(defn stop-sidecar!
  "Disconnect the MIDI sidecar."
  []
  (sidecar/stop-sidecar!)
  nil)

(def send-sysex! sidecar/send-sysex!)
(def send-mts!   sidecar/send-mts!)

;; ---------------------------------------------------------------------------
;; Keyboard layout (cljseq.ivk)
;; ---------------------------------------------------------------------------

(def start-kbd!         ivk/start-kbd!)
(def stop-kbd!          ivk/stop-kbd!)
(def register-layout!   ivk/register-layout!)
(def set-layout!        ivk/set-layout!)
(def set-pitch!         ivk/set-pitch!)
(def current-pitch      ivk/current-pitch)
(def start-arp!         ivk/start-arp!)
(def stop-arp!          ivk/stop-arp!)
(def render-layout      ivk/render-layout)

;; ---------------------------------------------------------------------------
;; MIDI input (cljseq.midi-in)
;; ---------------------------------------------------------------------------

(def open-input!              midi-in/open-input!)
(def close-input!             midi-in/close-input!)
(def close-all-inputs!        midi-in/close-all-inputs!)
(def open-inputs              midi-in/open-inputs)
(def register-note-handler!   midi-in/register-note-handler!)
(def unregister-note-handler! midi-in/unregister-note-handler!)

;; ---------------------------------------------------------------------------
;; Ableton Link (Phase 1 + Phase 2)
;; ---------------------------------------------------------------------------

(def link-enable!             link/enable!)
(def link-disable!            link/disable!)
(def link-active?             link/active?)
(def link-bpm                 link/bpm)
(def link-peers               link/peers)
(def link-playing?            link/playing?)
(def link-set-bpm!            link/set-bpm!)
(def link-start-transport!    link/start-transport!)
(def link-stop-transport!     link/stop-transport!)
(def on-transport-change!     link/on-transport-change!)
(def remove-transport-hook!   link/remove-transport-hook!)
(def link-state               link/link-state)
(def link-timeline            link/link-timeline)
(def next-quantum-beat        link/next-quantum-beat)

;; ---------------------------------------------------------------------------
;; Configuration registry (§25)
;; ---------------------------------------------------------------------------

(def get-config       config/get-config)
(def set-config!      config/set-config!)
(def all-configs      config/all-configs)
(def all-param-keys   config/all-param-keys)
(def param-info       config/param-info)

;; ---------------------------------------------------------------------------
;; Handy theory shortcuts at the top level
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Kosmische vocabulary — cljseq.journey + cljseq.berlin
;; ---------------------------------------------------------------------------

;; Journey conductor
(def start-bar-counter!  journey/start-bar-counter!)
(def stop-bar-counter!   journey/stop-bar-counter!)
(def reset-bar-counter!  journey/reset-bar-counter!)
(def current-bar         journey/current-bar)
(def start-journey!      journey/start-journey!)
(def stop-journey!       journey/stop-journey!)
(def phase-pair          journey/phase-pair)
(def humanise            journey/humanise)
(def phaedra-arc         journey/phaedra-arc)

;; Berlin School / Kosmische ostinato + performance tools
(def ostinato            berlin/ostinato)
(def next-step!          berlin/next-step!)
(def reset-ostinato!     berlin/reset-ostinato!)
(def freeze-ostinato!    berlin/freeze-ostinato!)
(def thaw-ostinato!      berlin/thaw-ostinato!)
(def deflect-ostinato!   berlin/deflect-ostinato!)
(def crystallize!        berlin/crystallize!)
(def dissolve!           berlin/dissolve!)
(def set-mutation-trajectory! berlin/set-mutation-trajectory!)
(def set-portamento!     berlin/set-portamento!)
(defmacro with-portamento [channel time-ms & body]
  `(berlin/with-portamento ~channel ~time-ms ~@body))
(def filter-journey!     berlin/filter-journey!)
(def phase-drift!        berlin/phase-drift!)
(def clear-drift!        berlin/clear-drift!)
(def tuning-morph!       berlin/tuning-morph!)
(def tape-drift          berlin/tape-drift)
(def tick-tape!          berlin/tick-tape!)
(def frippertronics!     berlin/frippertronics!)
(def sos-send!           berlin/sos-send!)

;; Temporal buffer — direct access for Hold, Flip, Color, Feedback, zone switching
(def deftemporal-buffer       tbuf/deftemporal-buffer)
(def deftemporal-buffer-preset tbuf/deftemporal-buffer-preset)
(def temporal-buffer-send!    tbuf/temporal-buffer-send!)
(def temporal-buffer-zone!    tbuf/temporal-buffer-zone!)
(def temporal-buffer-hold!    tbuf/temporal-buffer-hold!)
(def temporal-buffer-flip!    tbuf/temporal-buffer-flip!)
(def temporal-buffer-rate!    tbuf/temporal-buffer-rate!)
(def temporal-buffer-color!   tbuf/temporal-buffer-color!)
(def temporal-buffer-feedback! tbuf/temporal-buffer-feedback!)
(def temporal-buffer-halo!    tbuf/temporal-buffer-halo!)
(def temporal-buffer-info     tbuf/temporal-buffer-info)
(def temporal-buffer-set!     tbuf/temporal-buffer-set!)
(def stop-temporal-buffer!    tbuf/stop!)
(def color-presets            tbuf/color-presets)
(def buffer-presets           tbuf/presets)

;; Supervisor — service health, events, watchdog
(def register-service!      supervisor/register!)
(def deregister-service!    supervisor/deregister!)
(def register-sc!           supervisor/register-sc!)
(def register-sidecar!      supervisor/register-sidecar!)
(def start-watchdog!        supervisor/start-watchdog!)
(def stop-watchdog!         supervisor/stop-watchdog!)
(def service-status         supervisor/service-status)
(def all-service-statuses   supervisor/all-statuses)
(def on-supervisor-event!   supervisor/on-event!)
(def off-supervisor-event!  supervisor/off-event!)
(def restart-loop!          loop-ns/restart-loop!)

(defn make-scale
  "Build a Scale record. Common modal shorthand.

  Examples:
    (make-scale :C 4 :major)
    (make-scale :D 4 :dorian)
    (make-scale :A 3 :pentatonic-minor)"
  [root octave mode]
  (scale/scale root octave mode))

(defn make-chord
  "Build a Chord record.

  Examples:
    (make-chord :C 4 :maj7)
    (make-chord :G 3 :dominant7)"
  [root octave quality]
  (chord/chord root octave quality))

(defn progression
  "Build a sequence of chords from Roman numeral keywords.

  Example:
    (progression (make-scale :C 4 :major) [:I :IV :V7 :I])"
  [scale degrees]
  (chord/progression scale degrees))
