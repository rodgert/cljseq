# cljseq — Claude Code Instructions

## Release checklist

Run this checklist before committing a version bump and tagging. Missing any
step is the root cause of past misses (version stayed at 0.2.0 through v0.3.0).

### 1. Version bump
- [ ] `project.clj` — `(defproject cljseq "X.Y.Z" ...)`
- [ ] Confirm the new version matches the intended tag (`vX.Y.Z`)

### 2. CHANGELOG.md
- [ ] Move items from `## [Unreleased]` into a new `## [X.Y.Z] — YYYY-MM-DD` section
- [ ] Add `### Added`, `### Fixed`, `### Changed` subsections as appropriate
- [ ] If prior releases are missing entries, back-fill them from `git log`
- [ ] Leave `## [Unreleased]` empty after the move

### 3. User manual (`doc/user-manual.md`)
- [ ] Add a section for every new namespace or major feature
- [ ] Update the Contents table with the new section numbers and anchors
- [ ] Renumber any sections that shifted

### 4. SPDX headers
- [ ] Every new `.clj` and `.cpp`/`.h` file must start with:
  ```
  ; SPDX-License-Identifier: EPL-2.0
  ```
  (or `GPL-2.0-or-later` for C++ files compiled with Link support)
- [ ] Quick audit: `grep -rL "SPDX" src/ test/` should return nothing

### 5. Test suite
- [ ] `lein test` — 0 failures, 0 errors
- [ ] Note the test/assertion counts in the commit message

### 6. Commit and tag
```
git add <all changed files>
git commit -m "Release vX.Y.Z — <one-line summary>

<body describing what landed>

Suite: N tests, 0 failures.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

git tag vX.Y.Z
```

### 7. Push (when ready for upstream)
```
git push origin main
git push origin vX.Y.Z
```

---

## Project conventions

### Namespace naming
- Clojure source: `src/cljseq/<name>.clj` → `cljseq.<name>`
- Tests: `test/cljseq/<name>_test.clj` → `cljseq.<name>-test`
- Underscores in filenames map to hyphens in ns names (`ensemble_improv.clj` → `cljseq.ensemble-improv`)

### New namespaces
Every new namespace must be:
1. Exported in `src/cljseq/user.clj` (add require + re-export `def`s)
2. Covered by a test file in `test/cljseq/`
3. Documented in `doc/user-manual.md`

### Private atom access in tests
Use `@#'namespace/atom-name` to get the Var, then deref to get the atom value.
Use `reset! (@#'ns/atom-name) value` to manipulate private state in tests.
Do NOT use `with-redefs` on protocol methods — protocol dispatch bypasses vars.

### Dynamic vars for test injection
Network-facing or IO-heavy private functions should be backed by a `^:dynamic`
var so tests can inject mocks without touching live network:
```clojure
(def ^:dynamic *http-get* http-get-impl)
```

### Java interop in JAVA_HOME
All `lein` invocations need:
```
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home lein ...
```
