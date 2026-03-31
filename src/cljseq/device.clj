; SPDX-License-Identifier: EPL-2.0
(ns cljseq.device
  "cljseq MIDI device model — defdevice macro and EDN device map loading.

  Loads device maps from EDN files in resources/devices/ and registers
  devices in the control tree. The device map format is defined in
  doc/midi-device-research.md; prototype maps are in resources/devices/.

  ## Defining a device from an EDN file
    (defdevice :korg/minilogue-xd
      (load-device-map \"devices/korg-minilogue-xd.edn\"))

  ## Direct definition
    (defdevice :my-synth
      {:device/id    :my-synth
       :device/type  :synthesizer
       :midi/channel 1
       :midi/cc      [{:cc 74 :path [:filter :cutoff] :range [0 127]}]})

  ## Semantic CC control (via discrete :values breakpoints)
    ;; Instead of: (ctrl/send! [:filter :wave] 43)
    ;; Use:        (ctrl/send! [:filter :wave] :saw)
    ;; defdevice registers a resolver that maps :saw -> 43

  ## Controller binding (role :controller)
    (ctrl/bind! [:filter/cutoff]
      {:device :arturia/keystep :source :touch-strip})

  Key design decisions: MIDI device research (doc/midi-device-research.md),
  Q55 (defdevice design — see below).")

;; ---------------------------------------------------------------------------
;; Q55 — defdevice design decisions (resolved inline)
;;
;; 1. REGISTRATION TIMING: eval-time (not load-time AOT).
;;    `defdevice` registers the device in the system-state atom when the form
;;    is evaluated at the REPL or on namespace load. No compile-time magic.
;;
;; 2. DISCRETE VALUE RESOLUTION: A resolver map is built from :values
;;    breakpoints at defdevice time. ctrl/send! checks for a resolver before
;;    sending the raw CC value:
;;      (ctrl/send! [:filter :wave] :saw)
;;    The resolver maps :saw -> 43 -> CC 23 on the Minilogue XD.
;;    If no resolver entry exists, the value is sent as-is (numeric).
;;
;; 3. NRPN ENCODING: ctrl/send! detects :nrpn entries and emits the
;;    CC 99/98/6/38 sequence automatically. The caller uses the semantic
;;    path; the encoding is handled by the sidecar dispatch layer.
;;
;; 4. CONTROLLER ROLE: Devices with :role :controller contribute to
;;    ctrl/bind! source mapping rather than ctrl/send! target mapping.
;;    The :cljseq/bind-sources key in the EDN map specifies what each
;;    incoming MIDI event yields.
;;
;; 5. CONFIGURABLE CC SLOTS: Devices with :cc-type :configurable (e.g.
;;    Digitakt CC A-H, FH-2 user routing) are registered with their
;;    user-assigned CC numbers at defdevice time. The EDN map reflects
;;    the current user configuration.
;; ---------------------------------------------------------------------------

;; (defmacro defdevice [id device-map] ...)
;; (defn load-device-map [resource-path] ...)
;; (defn validate! [device-map] ...)
