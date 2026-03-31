// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once

#include <cstdint>
#include <cmath>

namespace cljseq {

// ---------------------------------------------------------------------------
// Beat — rational beat position
// ---------------------------------------------------------------------------

/// Rational beat position (numerator / denominator).
/// Rational beat arithmetic happens on the Clojure side; the C++ scheduler
/// receives double beat positions and converts via from_double.
struct Beat {
    int64_t num;
    int64_t den;

    constexpr double to_double() const noexcept {
        return static_cast<double>(num) / static_cast<double>(den);
    }

    /// Approximate a double as a rational with denominator 1,000,000.
    static constexpr Beat from_double(double d) noexcept {
        return Beat{ static_cast<int64_t>(d * 1'000'000), 1'000'000 };
    }
};

// ---------------------------------------------------------------------------
// ITemporalValue — C++ mirror of the Clojure protocol
// ---------------------------------------------------------------------------

/// A value that varies over musical time. Mirrors cljseq.clock/ITemporalValue.
class ITemporalValue {
public:
    virtual ~ITemporalValue() = default;

    /// Sample the value at `beat`. Pure; allocation-free on the hot path.
    virtual double sample(Beat beat) const = 0;

    /// Return the beat of the next 0→1 rising edge strictly after `beat`.
    virtual Beat next_edge(Beat beat) const = 0;
};

} // namespace cljseq

// ---------------------------------------------------------------------------
// cljseq::phasor — pure phasor operations
//
// A unipolar phasor is frac(beat × rate + offset) ∈ [0.0, 1.0).
// All operations are constexpr where C++17 allows; shape functions that
// depend on std::sin are inline noexcept.
// ---------------------------------------------------------------------------

namespace cljseq::phasor {

inline constexpr double pi = 3.14159265358979323846;

namespace detail {
    /// constexpr floor — std::floor is not constexpr in C++17.
    constexpr double floor(double x) noexcept {
        int64_t n = static_cast<int64_t>(x);
        return (x < 0.0 && x != static_cast<double>(n))
               ? static_cast<double>(n - 1)
               : static_cast<double>(n);
    }
} // namespace detail

/// Wrap x into [0.0, 1.0) — fundamental phasor normalisation.
constexpr double wrap(double x) noexcept {
    return x - detail::floor(x);
}

/// Fold p into [0.0, 1.0] with triangle/ping-pong reflection.
/// Input is assumed already wrapped to [0.0, 1.0).
constexpr double fold(double p) noexcept {
    double t = 2.0 * p - 1.0;
    return 1.0 - (t < 0.0 ? -t : t);
}

/// Map p ∈ [0.0, 1.0) linearly to [lo, hi).
constexpr double scale(double p, double lo, double hi) noexcept {
    return lo + p * (hi - lo);
}

/// Integer step index for phase p in an n-step sequence. Result ∈ [0, n).
constexpr int index(double p, int n) noexcept {
    return static_cast<int>(detail::floor(p * static_cast<double>(n)));
}

/// Quantise p to n equal steps. Returns a staircase value in [0.0, 1.0).
constexpr double step(double p, int n) noexcept {
    return detail::floor(p * static_cast<double>(n)) / static_cast<double>(n);
}

/// Return 1 if a wrap occurred between p_prev and p (p < p_prev), else 0.
/// Fires exactly once per phasor cycle at the 0-crossing.
constexpr int delta(double p, double p_prev) noexcept {
    return p < p_prev ? 1 : 0;
}

/// Reset phase to 0.0 if trigger > 0, otherwise pass p through unchanged.
/// Models a hard-sync input on an analogue oscillator.
constexpr double phase_reset(double p, double trigger) noexcept {
    return trigger > 0.0 ? 0.0 : p;
}

// ---------------------------------------------------------------------------
// Shape functions — inline noexcept (std::sin is not constexpr in C++17)
// ---------------------------------------------------------------------------

/// Unipolar sine: phasor → [0.0, 1.0].
inline double sine_uni(double p) noexcept {
    return 0.5 * (1.0 + std::sin(2.0 * pi * p));
}

/// Bipolar sine: phasor → [-1.0, 1.0].
inline double sine_bi(double p) noexcept {
    return std::sin(2.0 * pi * p);
}

/// Unipolar triangle: equivalent to fold(p). Phasor → [0.0, 1.0].
inline double triangle(double p) noexcept { return fold(p); }

/// Upward sawtooth: the phasor itself. [0.0, 1.0) → [0.0, 1.0).
inline double saw_up(double p) noexcept { return p; }

/// Downward sawtooth: inverse phasor. [0.0, 1.0) → (0.0, 1.0].
inline double saw_down(double p) noexcept { return 1.0 - p; }

/// Return a square wave function capturing pulse width pw ∈ [0.0, 1.0).
/// Usage: auto sq = square(0.5); double v = sq(p);
inline auto square(double pw) noexcept {
    return [pw](double p) noexcept -> double { return p < pw ? 1.0 : 0.0; };
}

} // namespace cljseq::phasor

// ---------------------------------------------------------------------------
// cljseq::Phasor — canonical ITemporalValue implementation
// ---------------------------------------------------------------------------

namespace cljseq {

/// A unipolar phasor: frac(beat × rate + phase_offset) ∈ [0.0, 1.0).
/// Mirrors the Clojure Phasor record from cljseq.clock (R&R §28).
struct Phasor : public ITemporalValue {
    double rate;          ///< cycles per beat
    double phase_offset;  ///< initial phase shift ∈ [0.0, 1.0)

    // Explicit constructor required: Phasor has virtual functions so it is
    // not a C++17 aggregate and cannot use brace aggregate initialisation.
    constexpr Phasor(double rate_, double phase_offset_) noexcept
        : rate(rate_), phase_offset(phase_offset_) {}

    double sample(Beat beat) const override {
        return phasor::wrap(rate * beat.to_double() + phase_offset);
    }

    Beat next_edge(Beat beat) const override {
        double accumulated = rate * beat.to_double() + phase_offset;
        double next_n      = phasor::detail::floor(accumulated) + 1.0;
        return Beat::from_double((next_n - phase_offset) / rate);
    }
};

// Named constructors — mirror cljseq.clock Clojure API
inline Phasor master_clock()                       { return {1.0,      0.0   }; }
inline Phasor clock_div(double n)                  { return {1.0 / n,  0.0   }; }
inline Phasor clock_mul(double n)                  { return {n,        0.0   }; }
inline Phasor clock_shift(double n, double offset) { return {1.0 / n,  offset}; }

} // namespace cljseq
