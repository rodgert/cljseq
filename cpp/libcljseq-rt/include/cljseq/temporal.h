// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once

#include <cstdint>

namespace cljseq {

/// Rational beat position (numerator / denominator).
struct Beat {
    int64_t num;
    int64_t den;

    double to_double() const { return static_cast<double>(num) / den; }
};

/// C++ mirror of the Clojure ITemporalValue protocol.
/// Implementations: Clock, Lfo, Envelope, SwingModulator, etc.
class ITemporalValue {
public:
    virtual ~ITemporalValue() = default;

    /// Sample the value at the given beat position.
    virtual double sample(Beat beat) const = 0;

    /// Return the beat position of the next 0→1 rising edge after `beat`.
    /// Used by clock-div and beat-sync primitives.
    virtual Beat next_edge(Beat beat) const = 0;
};

} // namespace cljseq
