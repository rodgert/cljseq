# cljseq Licensing Strategy

## Overview

cljseq is a multi-language project spanning Clojure, C++, and Python. Different
components are licensed differently to reflect their nature, their dependencies,
and the obligations that arise when combined with GPL-family libraries.

---

## Component Licenses

### Clojure library (`src/`, `test/`)

**License: EPL-2.0**

The Clojure layer is licensed under the Eclipse Public License, version 2.0.
EPL-2.0 is the standard license for the Clojure ecosystem (Clojure itself,
most Clojure libraries). It is a weak copyleft license that permits use in
proprietary software provided that modifications to the EPL-covered component
are shared.

SPDX identifier: `EPL-2.0`

### Authored C++ (`cpp/libcljseq-rt/`, `cpp/cljseq-sidecar/`, `cpp/cljseq-audio/`)

**License: LGPL-2.1-or-later** (standalone builds)

The C++ runtime library and sidecar binaries are authored under the GNU
Lesser General Public License, version 2.1 or any later version. LGPL-2.1
allows use as a library by proprietary software (the "lesser" clause), while
ensuring that improvements to the library itself are shared.

SPDX identifier: `LGPL-2.1-or-later`

### Python sidecar (`python/cljseq_m21/`)

**License: LGPL-2.1-or-later**

The Music21 subprocess wrapper follows the same licensing as the C++ authored
code. Music21 itself is BSD-licensed; the cljseq wrapper does not create GPL
obligations.

SPDX identifier: `LGPL-2.1-or-later`

---

## Ableton Link — GPL Interaction

Ableton Link is licensed under GPL-2.0-or-later. When cljseq is built with
Link support enabled, the resulting binary is a GPL-2.0-or-later work.

### Opt-in build flag

Link support is controlled by a CMake option:

```cmake
option(CLJSEQ_ENABLE_LINK "Build with Ableton Link support (GPL-2.0-or-later)" OFF)
```

When `CLJSEQ_ENABLE_LINK=OFF` (the default), the cljseq binaries remain
LGPL-2.1-or-later. When `CLJSEQ_ENABLE_LINK=ON`, the combined work is
GPL-2.0-or-later and must be distributed accordingly.

The SPDX identifier for Link-enabled builds is `GPL-2.0-or-later`.

### Why this design

- Users who do not need tempo sync can run a fully LGPL build
- Users who need Link-based sync accept the GPL terms for the combined binary
- The Clojure library (EPL-2.0) is not affected; only the sidecar binary changes
- Distribution of the sidecar binary with Link enabled requires source offer

---

## SPDX Identifier Headers

Every source file carries an SPDX short-form identifier in a comment at the
top of the file:

| Language | Format                                              |
|----------|-----------------------------------------------------|
| Clojure  | `; SPDX-License-Identifier: EPL-2.0`               |
| C++      | `// SPDX-License-Identifier: LGPL-2.1-or-later`    |
| Python   | `# SPDX-License-Identifier: LGPL-2.1-or-later`     |
| CMake    | `# SPDX-License-Identifier: LGPL-2.1-or-later`     |

Files in `cpp/cljseq-audio/` that unconditionally link against Link would
carry `GPL-2.0-or-later` once that integration is implemented.

---

## License Files in the Repository

| File                   | Contents                                      |
|------------------------|-----------------------------------------------|
| `LICENSE`              | EPL-2.0 full text (canonical for the project) |
| `LICENSES/LGPL-2.1.txt`| LGPL-2.1-or-later full text (C++/Python)      |
| `LICENSES/GPL-2.0.txt` | GPL-2.0-or-later full text (Link-enabled)     |

Note: `LICENSES/` directory and secondary license files are to be added once
C++ implementation begins and the build system is finalized.

---

## Compatibility Summary

| Combined with       | Resulting obligation      | Who is affected            |
|---------------------|---------------------------|----------------------------|
| Clojure (EPL-2.0)   | EPL-2.0 (weak copyleft)   | Library modifiers          |
| C++ standalone      | LGPL-2.1-or-later         | Library/binary modifiers   |
| C++ + Link          | GPL-2.0-or-later          | Binary distributors        |
| Music21 (BSD)       | No additional obligation  | No change                  |
| SuperCollider (GPL) | GPL when combined         | SC plugin authors          |

---

## Future Considerations

- If the project ever produces a user-installable hardware appliance (see R&R §1.1),
  the GPL/LGPL sidecar binaries require a source offer to end users.
- A dual-licensing arrangement (EPL-2.0 / commercial) for the Clojure library may
  become relevant if commercial embedded products are a target market.
- VCV Rack plugins must comply with VCV Rack's GPL-3.0 licensing.
