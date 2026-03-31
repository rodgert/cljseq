# cljseq Documentation Strategy

## Overview

cljseq documentation is split across three audiences:

1. **API reference** — for developers integrating or extending cljseq
2. **C++ internals** — for contributors working on the runtime and sidecars
3. **User guide** — for musicians and live coders using cljseq at the REPL

Each audience gets a dedicated toolchain. The source-of-truth for design
decisions remains the `doc/` directory in this repository.

---

## Toolchain

### Codox — Clojure API Reference

**Tool**: [Codox](https://github.com/weavejester/codox)
**Output**: HTML, browsable API reference
**Scope**: All public vars in `cljseq.*` namespaces (excluding `cljseq.spike.*`)

Codox generates API documentation directly from docstrings in Clojure source.
It is configured in `project.clj`:

```clojure
:plugins [[lein-codox "0.10.8"]]
:codox {:output-path "target/codox"
        :namespaces [cljseq.clock cljseq.ctrl cljseq.fractal cljseq.loop
                     cljseq.m21 cljseq.marbles cljseq.mod cljseq.morph
                     cljseq.sidecar cljseq.timing]
        :source-uri "https://github.com/YOUR_ORG/cljseq/blob/{version}/{filepath}#L{line}"}
```

Run with: `lein codox` or `make docs-clj`

**Docstring conventions**:
- First sentence: one-line summary (appears in namespace index)
- Parameter docs: list `:param` / `:returns` inline or as a `## Parameters` block
- Include example forms where the call site behaviour is non-obvious
- Protocols: document each method with the contract, not the implementation

### Doxygen — C++ API Reference

**Tool**: [Doxygen](https://www.doxygen.nl/)
**Output**: HTML, browsable C++ API reference
**Scope**: `cpp/libcljseq-rt/` public headers; internal headers excluded by default

Doxygen configuration lives in `cpp/Doxyfile`. Generate with: `make docs-cpp`

**Comment conventions**:
```cpp
/// @brief One-line summary.
///
/// Longer description if needed.
///
/// @param beat  Current beat position as a rational number.
/// @returns     Sampled value at this beat.
```

Internal implementation files (`*_impl.h`, `*_detail.h`) are excluded from
public docs. Use plain `//` comments for implementation notes.

### mdBook — User Guide

**Tool**: [mdBook](https://rust-lang.github.io/mdBook/)
**Output**: HTML, deployable static site
**Scope**: Narrative documentation for musicians and live coders

The user guide source lives in `doc/book/` (to be created when content is
ready). Structure:

```
doc/book/
  book.toml
  src/
    SUMMARY.md
    introduction.md
    getting-started/
    sequencing/
    timing/
    integration/
    reference/
```

Run with: `make docs-guide` (runs `mdbook build doc/book`)

---

## Extracting Design Document Content

Design documents in `doc/` contain rationale that is useful in the user guide
but should not be duplicated. The convention is:

- **Design docs** (`doc/open-design-questions.md`, sprint summaries, R&R): source
  of truth for *why* decisions were made. Not published directly.
- **User guide** (`doc/book/`): distills the *what* from design docs into
  user-facing language. Links back to design docs for rationale.
- **API docs** (Codox/Doxygen): covers the *how* — call signatures, contracts,
  examples.

When a design decision fixes a user-visible behaviour, the corresponding user
guide page should be updated. The sprint summary serves as a change log for
tracking which design decisions need user guide coverage.

---

## Makefile Targets

| Target          | Action                                   |
|-----------------|------------------------------------------|
| `make docs`     | Build all documentation                  |
| `make docs-clj` | Run Codox for Clojure API reference      |
| `make docs-cpp` | Run Doxygen for C++ API reference        |
| `make docs-guide`| Build mdBook user guide                 |

---

## Versioning

API reference docs are versioned alongside the library. Each release tag
produces a versioned snapshot of the Codox and Doxygen output. The user guide
is published continuously from `main`.
