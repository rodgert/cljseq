#!/usr/bin/env python3
# SPDX-License-Identifier: EPL-2.0
#
# cljseq Music21 extractor — Phase 1 spike
#
# Loads a Bach chorale from the music21 corpus by BWV number,
# chordifies it (groups simultaneous notes into time slices), and
# prints the result as EDN on stdout.
#
# Usage:
#   python3 script/m21_extract.py <bwv-number>
#   python3 script/m21_extract.py 371
#
# Output (EDN list of chord maps):
#   [{:pitches [48 55 62 67] :dur/beats 1.0}
#    {:pitches [47 55 62 67] :dur/beats 0.5}
#    ...]
#
# :pitches  — MIDI note numbers, sorted ascending (bass to soprano)
# :dur/beats — duration in quarter-note beats (float)
#
# Rests are emitted as {:rest true :dur/beats N} so the Clojure side
# can advance virtual time without playing a note.

import sys
import os

# Suppress music21's noisy startup messages
os.environ["MUSIC21_SUPPRESS_STARTUP"] = "1"

try:
    from music21 import corpus, note, chord, stream
except ImportError:
    print('(error "music21 not installed")', flush=True)
    sys.exit(1)


def to_edn_map(pairs):
    """Render a Python dict as an EDN map string."""
    parts = []
    for k, v in pairs:
        if isinstance(v, bool):
            parts.append(f"{k} {'true' if v else 'false'}")
        elif isinstance(v, (int, float)):
            parts.append(f"{k} {v}")
        elif isinstance(v, list):
            inner = " ".join(str(x) for x in v)
            parts.append(f"{k} [{inner}]")
        elif isinstance(v, str):
            parts.append(f'{k} "{v}"')
        else:
            parts.append(f"{k} {v}")
    return "{" + " ".join(parts) + "}"


def find_score(bwv_id):
    """Locate and parse the best match for a BWV number in the corpus.

    Tries direct match first (e.g. bwv371), then searches for files whose
    basename starts with bwv<N>. For multi-movement cantatas, picks the
    last movement file (typically the closing chorale)."""
    # Direct match (standalone chorales: bwv253–bwv438, bwv371, etc.)
    try:
        return corpus.parse(f"bach/bwv{bwv_id}")
    except Exception:
        pass

    # Search for any file matching bwv<N> or bwv<N>.<movement>
    import pathlib
    bach_dir = pathlib.Path(corpus.getComposer('bach')[0]).parent
    candidates = sorted(bach_dir.glob(f"bwv{bwv_id}.*.mxl"))
    if not candidates:
        candidates = sorted(bach_dir.glob(f"bwv{bwv_id}*.mxl"))
    if not candidates:
        return None

    # For cantatas pick the last movement (closing chorale)
    chosen = candidates[-1]
    try:
        return corpus.parse(str(chosen))
    except Exception:
        return None


def extract_chorale(bwv_id):
    """Load a Bach chorale and return EDN chord sequence."""
    score = find_score(bwv_id)
    if score is None:
        print(f'(error "bwv{bwv_id} not found in corpus")', flush=True)
        sys.exit(1)

    title = score.metadata.title if score.metadata else f"BWV {bwv_id}"

    # chordify() collapses all parts into simultaneous chord slices
    chordified = score.chordify()

    results = []
    for el in chordified.flatten().notesAndRests:
        dur_beats = float(el.duration.quarterLength)
        if dur_beats <= 0:
            continue
        if isinstance(el, note.Rest):
            results.append(to_edn_map([
                (":rest",     True),
                (":dur/beats", round(dur_beats, 6)),
            ]))
        elif isinstance(el, chord.Chord):
            midi_notes = sorted(p.midi for p in el.pitches)
            results.append(to_edn_map([
                (":pitches",  midi_notes),
                (":dur/beats", round(dur_beats, 6)),
            ]))

    # Emit as EDN: metadata comment + vector of chord maps
    print(f"; BWV {bwv_id} — {title}")
    print("[")
    for r in results:
        print(f" {r}")
    print("]")


def list_chorales():
    """Print all available BWV numbers as an EDN vector."""
    import pathlib, re
    bach_dir = pathlib.Path(corpus.getComposer('bach')[0]).parent
    nums = set()
    for f in bach_dir.glob("bwv*.mxl"):
        m = re.match(r'bwv(\d+)', f.stem)
        if m:
            nums.add(int(m.group(1)))
    print(sorted(nums))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print('(error "usage: m21_extract.py <bwv-number>|list")', flush=True)
        sys.exit(1)
    if sys.argv[1] == "list":
        list_chorales()
    else:
        extract_chorale(sys.argv[1])
