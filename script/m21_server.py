#!/usr/bin/env python3
# SPDX-License-Identifier: EPL-2.0
#
# cljseq Music21 persistent server — JSON-lines stdio protocol
#
# Replaces the one-shot m21_extract.py with a long-running process that
# eliminates the 2-4 second music21 startup cost on every call.
#
# Lifecycle:
#   Started lazily by cljseq.m21/ensure-server! on first corpus request.
#   Exits cleanly when stdin closes (parent JVM process died — free crash
#   detection via OS pipe semantics, no heartbeat needed).
#   Can also be terminated cleanly via {"op": "shutdown"}.
#
# Protocol:
#   Requests:  one JSON object per line on stdin
#   Responses: one JSON object per line on stdout
#
# Ops and responses:
#   {"op":"load","bwv":"371","mode":"chords"} → {"status":"ok","session":"bwv371-chords","edn":"[...]"}
#   {"op":"load","bwv":"371","mode":"parts"}  → {"status":"ok","session":"bwv371-parts","edn":"{...}"}
#   {"op":"list"}                             → {"status":"ok","data":[248,253,...]}
#   {"op":"ping"}                             → {"status":"ok"}
#   {"op":"shutdown"}                         → {"status":"ok"}  then exit(0)
#   Any error:                                → {"status":"error","message":"..."}
#
# Session cache:
#   First load of bwv+mode extracts via music21 (~100-500ms per chorale).
#   Subsequent requests for the same session return instantly from the in-memory
#   _sessions dict. The Clojure side maintains its own two-level (mem+disk) cache
#   on top of this, so Python sessions are only populated once per server lifetime.

import sys
import os
import json
import io

# Suppress music21's noisy startup banner
os.environ["MUSIC21_SUPPRESS_STARTUP"] = "1"

try:
    from music21 import corpus, note, chord
except ImportError:
    print(json.dumps({"status": "error", "message": "music21 not installed"}), flush=True)
    sys.exit(1)

# ---------------------------------------------------------------------------
# Session cache: {session_key: edn_string}
# ---------------------------------------------------------------------------

_sessions = {}

# ---------------------------------------------------------------------------
# Voice name → EDN keyword mapping (for parts mode)
# ---------------------------------------------------------------------------

_VOICE_BY_NAME = {
    "soprano": ":soprano", "s.": ":soprano",
    "alto":    ":alto",    "a.": ":alto",
    "tenor":   ":tenor",   "t.": ":tenor",
    "bass":    ":bass",    "b.": ":bass",
}
_VOICE_BY_INDEX = [":soprano", ":alto", ":tenor", ":bass"]


def _voice_key(part, index):
    name = (part.partName or "").strip().lower()
    return _VOICE_BY_NAME.get(name, _VOICE_BY_INDEX[min(index, 3)])


# ---------------------------------------------------------------------------
# EDN rendering
# ---------------------------------------------------------------------------

def _edn_map(pairs):
    """Render a list of (key, value) pairs as an EDN map string."""
    parts = []
    for k, v in pairs:
        if isinstance(v, bool):
            parts.append(f"{k} {'true' if v else 'false'}")
        elif isinstance(v, (int, float)):
            parts.append(f"{k} {v}")
        elif isinstance(v, list):
            inner = " ".join(str(x) for x in v)
            parts.append(f"{k} [{inner}]")
        else:
            parts.append(f"{k} {v}")
    return "{" + " ".join(parts) + "}"


# ---------------------------------------------------------------------------
# Corpus helpers
# ---------------------------------------------------------------------------

def _find_score(bwv_id):
    """Return parsed music21 Score for bwv_id, or raise RuntimeError."""
    try:
        return corpus.parse(f"bach/bwv{bwv_id}")
    except Exception:
        pass

    import pathlib, re
    try:
        bach_dir = pathlib.Path(corpus.getComposer("bach")[0]).parent
    except Exception:
        raise RuntimeError(f"bwv{bwv_id} not found in corpus")

    candidates = sorted(bach_dir.glob(f"bwv{bwv_id}.*.mxl"))
    if not candidates:
        candidates = sorted(bach_dir.glob(f"bwv{bwv_id}*.mxl"))
    if not candidates:
        raise RuntimeError(f"bwv{bwv_id} not found in corpus")

    try:
        return corpus.parse(str(candidates[-1]))
    except Exception as e:
        raise RuntimeError(f"bwv{bwv_id}: parse failed — {e}")


def _extract_chords(bwv_id):
    """Return EDN string for the chordified Bach chorale (Phase 1 format)."""
    score = _find_score(bwv_id)
    chordified = score.chordify()
    buf = io.StringIO()
    buf.write(f"; BWV {bwv_id}\n[\n")
    for el in chordified.flatten().notesAndRests:
        dur = float(el.duration.quarterLength)
        if dur <= 0:
            continue
        if isinstance(el, note.Rest):
            buf.write(f" {_edn_map([(chr(58)+'rest', True), (chr(58)+'dur/beats', round(dur, 6))])}\n")
        elif isinstance(el, chord.Chord):
            midis = sorted(p.midi for p in el.pitches)
            buf.write(f" {_edn_map([(chr(58)+'pitches', midis), (chr(58)+'dur/beats', round(dur, 6))])}\n")
    buf.write("]\n")
    return buf.getvalue()


def _extract_parts(bwv_id):
    """Return EDN string for per-voice SATB (Phase 2 format)."""
    score = _find_score(bwv_id)
    parts = list(score.parts)
    buf = io.StringIO()
    buf.write(f"; BWV {bwv_id} parts\n{{\n")
    for i, part in enumerate(parts):
        key = _voice_key(part, i)
        buf.write(f" {key}\n [\n")
        for el in part.flatten().notesAndRests:
            dur = float(el.duration.quarterLength)
            if dur <= 0:
                continue
            if isinstance(el, note.Rest):
                buf.write(f"  {_edn_map([(chr(58)+'rest', True), (chr(58)+'dur/beats', round(dur, 6))])}\n")
            elif isinstance(el, note.Note):
                buf.write(f"  {_edn_map([(chr(58)+'pitch', el.pitch.midi), (chr(58)+'dur/beats', round(dur, 6))])}\n")
            elif isinstance(el, chord.Chord):
                midi = sorted(p.midi for p in el.pitches)[-1]
                buf.write(f"  {_edn_map([(chr(58)+'pitch', midi), (chr(58)+'dur/beats', round(dur, 6))])}\n")
        buf.write(" ]\n")
    buf.write("}\n")
    return buf.getvalue()


def _list_chorales():
    """Return sorted list of available BWV integers."""
    import pathlib, re
    try:
        bach_dir = pathlib.Path(corpus.getComposer("bach")[0]).parent
    except Exception:
        return []
    nums = set()
    for f in bach_dir.glob("bwv*.mxl"):
        m = re.match(r"bwv(\d+)", f.stem)
        if m:
            nums.add(int(m.group(1)))
    return sorted(nums)


# ---------------------------------------------------------------------------
# Op handlers
# ---------------------------------------------------------------------------

def _handle_load(req):
    bwv_id = req.get("bwv")
    mode = req.get("mode", "chords")
    session_key = f"bwv{bwv_id}-{mode}"
    if session_key in _sessions:
        return {"status": "ok", "session": session_key, "edn": _sessions[session_key]}
    try:
        if mode == "parts":
            edn = _extract_parts(bwv_id)
        else:
            edn = _extract_chords(bwv_id)
    except Exception as e:
        return {"status": "error", "message": str(e)}
    _sessions[session_key] = edn
    return {"status": "ok", "session": session_key, "edn": edn}


def _handle_list(_req):
    try:
        return {"status": "ok", "data": _list_chorales()}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def _handle_ping(_req):
    return {"status": "ok"}


def _handle_shutdown(_req):
    return {"status": "ok", "__shutdown": True}


# ---------------------------------------------------------------------------
# MIDI file parsing — for midi-repair pipeline
# ---------------------------------------------------------------------------

def _midi_note_to_edn(n, offset_beats, tempo_bpm):
    """Render a music21 Note as an EDN step map string."""
    midi = n.pitch.midi
    dur  = float(n.quarterLength)
    vel  = n.volume.velocity if n.volume.velocity else 64
    chan = (n.activeSite.id if hasattr(n.activeSite, 'id') else 0) or 0
    return _edn_map([
        (":pitch/midi",  midi),
        (":start/beats", round(float(offset_beats), 6)),
        (":dur/beats",   round(dur, 6)),
        (":velocity",    vel),
        (":channel",     chan),
    ])


def _handle_parse_midi(req):
    """Parse a MIDI file and return all note events as EDN.

    Request:  {"op":"parse-midi","path":"/abs/path/to/file.mid"}
    Response: {"status":"ok","notes":"[{:pitch/midi 60 ...} ...]",
               "tempo":120.0,"time-sig":"4/4","bars":32}
    """
    path = req.get("path", "")
    if not path:
        return {"status": "error", "message": "parse-midi requires 'path'"}
    try:
        from music21 import converter as m21conv, tempo as m21tempo, meter
        import os
        if not os.path.isfile(path):
            return {"status": "error", "message": f"file not found: {path}"}

        score = m21conv.parse(path)
        flat  = score.flatten()

        # Extract tempo
        tempos = flat.getElementsByClass(m21tempo.MetronomeMark)
        bpm    = float(tempos[0].number) if tempos else 120.0

        # Extract time signature
        tsigs  = flat.getElementsByClass(meter.TimeSignature)
        tsig   = tsigs[0].ratioString if tsigs else "4/4"

        # Extract all notes (not rests) with beat offsets
        notes_edn = []
        for n in flat.notes:
            if hasattr(n, 'pitch'):                   # Note
                notes_edn.append(_midi_note_to_edn(n, n.offset, bpm))
            elif hasattr(n, 'pitches') and n.pitches: # Chord
                for p in n.pitches:
                    import music21.note as m21note
                    fake = m21note.Note(p)
                    fake.quarterLength = n.quarterLength
                    fake.volume        = n.volume
                    notes_edn.append(_midi_note_to_edn(fake, n.offset, bpm))

        # Total bars (quarterLength / beats-per-bar)
        beats_per_bar = float(tsigs[0].numerator) if tsigs else 4.0
        total_beats   = float(score.highestTime)
        total_bars    = int(total_beats / beats_per_bar) + 1

        edn = "[" + " ".join(notes_edn) + "]"
        return {"status":   "ok",
                "notes":    edn,
                "tempo":    bpm,
                "time-sig": tsig,
                "bars":     total_bars}
    except Exception as e:
        return {"status": "error", "message": str(e)}


_HANDLERS = {
    "load":        _handle_load,
    "list":        _handle_list,
    "ping":        _handle_ping,
    "shutdown":    _handle_shutdown,
    "parse-midi":  _handle_parse_midi,
}

# ---------------------------------------------------------------------------
# Main loop — exits on stdin EOF (parent JVM died) or explicit shutdown
# ---------------------------------------------------------------------------

def main():
    # Wrap stdin with line buffering. stdout is line-buffered by default
    # when a ProcessBuilder redirects it; flush= in print() is the safe belt.
    for raw_line in sys.stdin:
        raw_line = raw_line.strip()
        if not raw_line:
            continue
        try:
            req = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            print(json.dumps({"status": "error", "message": f"bad JSON: {exc}"}), flush=True)
            continue

        op = req.get("op", "")
        handler = _HANDLERS.get(op)
        if handler is None:
            resp = {"status": "error", "message": f"unknown op: {op!r}"}
        else:
            resp = handler(req)

        # Propagate request id for correlation if caller sends one
        req_id = req.get("id")
        if req_id is not None:
            resp["id"] = req_id

        shutdown = resp.pop("__shutdown", False)
        print(json.dumps(resp), flush=True)
        if shutdown:
            break   # stdin loop ends → process exits


if __name__ == "__main__":
    main()
