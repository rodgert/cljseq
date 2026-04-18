# Kosmische — scratchpad

## Mix balance notes (from s42 live session)

Drone voice sits well at velocity ~70; louder and it crowds out the VCO voice.
Papa voice needs more reverb tail than the others — currently using the same
reverb bus, which is a compromise.

Ostinati work best starting sparse (one voice) and adding layers after the
drone has established a tonal centre — at least 4 bars in.

## Harmonic ideas not yet tried

- Dorian → Phrygian shift at the 32-bar mark; the flattened 2nd should feel
  like the floor dropping out
- Cluster chord (stacked minor 2nds) as a tension device before the final
  section; hold for 8+ bars

## Conductor crossfade gap

Current section transitions cut hard. The right fix is probably a
`:crossfade N` option on `defconductor!` sections that overlaps the outgoing
and incoming loops for N bars, fading volume via ctrl bindings. Not yet
designed — flagged as a future gesture type in Q68 notes.

## Frippertronics timing

The tape-delay texture works by having two live-loops with the same pattern
but offset sleep periods. The offset needs to be exactly 1/3 of the loop
length for the three-echo effect. Fragile — depends on BPM not changing mid
loop. Would be cleaner with a dedicated delay primitive.
