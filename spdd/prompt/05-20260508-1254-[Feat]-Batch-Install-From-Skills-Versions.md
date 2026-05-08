---
bootstrap: true
generated_at: 2026-05-08T12:54:00-07:00
name: Batch Install from .skills-versions
description: Reads a project-level .skills-versions manifest, parses each entry, resolves and installs every skill in order, and reports per-skill success/failure with an aggregate exit code.
type: feature
---

# REASONS Canvas: Batch Install from .skills-versions

## R · Requirements
- When `install-skill` is invoked with no positional argument, look for a
  `.skills-versions` file in the working directory and install every entry
  it lists in declared order
  (`InstallSkillCommand.java:117-122, 340-458`).
- When the file is missing, exit with non-zero and print
  `Error: No <skill> argument provided and no .skills-versions file found in
  <cwd>` (`InstallSkillCommand.java:344-348`).
- When the file is empty, print `.skills-versions is empty. Nothing to
  install.` and exit with 0 (`InstallSkillCommand.java:359-362`).
- Parse the file with these rules (`SkillVersionsFile.java:65-125`,
  `README.md:43-75`):
  - One entry per line.
  - Two valid forms: `name version` (space-separated) or `name`.
  - The legacy `name@version` form is still accepted but emits a
    `Warning: .skills-versions line N: '@' separator is deprecated, use
    'name version' instead of 'name@version'`
    (`SkillVersionsFile.java:96-99`).
  - Lines whose trimmed content is empty or starts with `#` are skipped
    (`SkillVersionsFile.java:78-80`).
  - Empty name or empty version (after a space or `@`) raises an
    `IOException` with the offending line number
    (`SkillVersionsFile.java:88-95, 104-118`).
- Resolution and installation per entry:
  1. Compute the resolution plan against `.skills-versions.lock` (delegated
     to the lock-file Canvas) (`InstallSkillCommand.java:367-378`).
  2. Resolve every entry that is not reusable from the lock by composing
     `resolveSkillCoordinates` with the entry's name and optional version
     (`InstallSkillCommand.java:394-408`).
  3. Build a final ordered map preserving the order in `.skills-versions`,
     reusing locked coordinates where possible
     (`InstallSkillCommand.java:411-423`).
  4. Install each entry sequentially: GitHub entries via `installFromGitHub`,
     all others via `installResolved`
     (`InstallSkillCommand.java:425-445`).
  5. Persist the new lock file regardless of per-skill failures
     (`InstallSkillCommand.java:447-449`).
- Per-skill output uses a banner like
  `--- Installing <name> (<groupId>:<artifactId>:<version>) ---` (Maven) or
  `--- Installing <name> (github:<owner/repo>@<version>) ---` (GitHub)
  (`InstallSkillCommand.java:430-438`).
- The exit code is `1` if any single skill fails, `0` otherwise
  (`InstallSkillCommand.java:451-457`). All installs are attempted before
  exiting — the loop does not stop at the first failure
  (`InstallSkillCommand.java:441-445`).
- Definition of Done is exercised by:
  - `SkillVersionsFileTest`
    (`src/test/java/ca/weblite/installskill/SkillVersionsFileTest.java`):
    happy paths, comments, blank lines, deprecated `@` separator,
    error lines.
  - End-to-end batch install is *not* covered by an `*IT.java` (the lock
    Canvas's tests cover the resolution-plan layer; batch end-to-end is
    `[INFERRED]` to be exercised manually). This is a gap worth noting for
    SPDD's `/spdd-api-test`.

## E · Entities
- **`.skills-versions`** — text file in the working directory, parsed by
  `SkillVersionsFile.parse` into a `List<Entry>` preserving file order
  (`SkillVersionsFile.java:72-125`).
- **`SkillVersionsFile.Entry`** (`SkillVersionsFile.java:28-63`) — pair of
  `name` (non-null) and `version` (nullable; `null` means "latest /
  RELEASE"). Equality and `toString` honor both fields.
- **Working directory** — `getWorkingDirectory()` returns
  `workingDirectory` if a test has set it, otherwise the JVM's current
  directory via `Path.of("").toAbsolutePath()`
  (`InstallSkillCommand.java:470-475`).

## A · Approach
- Strict left-to-right parsing of `.skills-versions` rather than a regex —
  the file format is small enough that line-by-line splitting on the first
  space (or `@` for the deprecated form) is clearer and produces actionable
  error messages with line numbers.
- The batch flow is built as four phases (parse, plan, resolve+order,
  install+persist) with each phase producing input for the next. This
  mirrors the lock-file resolution semantics and keeps `installFromVersionsFile`
  readable (`InstallSkillCommand.java:340-458`).
- Tradeoff: failures don't short-circuit the loop. Users get a complete
  picture of what failed in one run, at the cost of doing extra work after
  the first failure.

## S · Structure
- `src/main/java/ca/weblite/installskill/SkillVersionsFile.java` — the file
  parser, the `Entry` value type, and the `pathIn`/`exists` helpers.
- `src/main/java/ca/weblite/installskill/InstallSkillCommand.java` —
  `installFromVersionsFile` (the orchestrator) and `getWorkingDirectory`.
- The lock-file machinery is described in the lock-file Canvas; this Canvas
  delegates to it via `SkillLockFile.read`,
  `SkillLockFile.computeResolutionPlan`, and `SkillLockFile.write`.

## O · Operations

### 1. Define Entry — `SkillVersionsFile.Entry`
File: `src/main/java/ca/weblite/installskill/SkillVersionsFile.java`

1. Responsibility: Immutable value type for one line of the manifest.
2. Fields / Attributes:
   - `name: String` (non-null) (`SkillVersionsFile.java:29-33`).
   - `version: String` (nullable — null means latest)
     (`SkillVersionsFile.java:30, 41-44`).
3. Methods:
   - Constructor enforces non-null `name` only
     (`SkillVersionsFile.java:32-35`).
   - `getName`, `getVersion` (`SkillVersionsFile.java:37-44`).
   - `equals/hashCode` over both fields
     (`SkillVersionsFile.java:46-57`).
   - `toString()` reconstructs the canonical `name version` form (or `name`
     alone) (`SkillVersionsFile.java:59-62`).

### 2. Parse Manifest — `SkillVersionsFile.parse`
File: `src/main/java/ca/weblite/installskill/SkillVersionsFile.java`

1. Responsibility: Read every line of the file and produce ordered entries.
2. Methods:
   - `parse(Path): List<Entry>`
     (`SkillVersionsFile.java:72-125`)
     - Logic:
       1. Read all lines (`SkillVersionsFile.java:73`).
       2. For each line, strip whitespace; skip blank or `#`-prefixed lines
          (`SkillVersionsFile.java:76-80`).
       3. If the line contains `@`, treat it as the deprecated form: split
          on the last `@`, validate non-empty name + version, log the
          deprecation warning, and add the entry
          (`SkillVersionsFile.java:83-99`).
       4. Else split on the first space; if no space, treat the whole line
          as a name (latest); else validate non-empty name + version and
          add the entry (`SkillVersionsFile.java:101-121`).
       5. Throw `IOException` with the offending line number on any
          validation failure (`SkillVersionsFile.java:88-95, 104-118`).
3. Constraints / Invariants:
   - `parse` preserves file order — order matters because installs run in
     that order and the lock file is rewritten in that order.

### 3. Locate Manifest — `SkillVersionsFile.exists`/`pathIn`
File: `src/main/java/ca/weblite/installskill/SkillVersionsFile.java`

1. Responsibility: Static helpers used by the orchestrator to check for
   and address `.skills-versions` in a given directory.
2. Methods:
   - `exists(Path): boolean` (`SkillVersionsFile.java:128-132`).
   - `pathIn(Path): Path` (`SkillVersionsFile.java:134-139`).

### 4. Orchestrate Batch — `InstallSkillCommand.installFromVersionsFile`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Wire parser, lock file, resolver, and per-skill
   installers together.
2. Methods:
   - `installFromVersionsFile(): Integer`
     (`InstallSkillCommand.java:340-458`)
     - Logic:
       1. Resolve `cwd` and `versionsPath`; bail out with the
          `No <skill> argument provided …` error when no manifest exists
          (`InstallSkillCommand.java:341-348`).
       2. Parse the manifest; on `IOException` print
          `Error: Failed to parse .skills-versions: <msg>` and return 1
          (`InstallSkillCommand.java:351-357`).
       3. Empty list → return 0 with the empty-message
          (`InstallSkillCommand.java:359-362`).
       4. Read the existing lock map via `SkillLockFile.read`
          (`InstallSkillCommand.java:367-368`).
       5. Compute the resolution plan: if `--update` is set or the lock
          is empty, treat every entry as `toResolve`; otherwise call
          `SkillLockFile.computeResolutionPlan`
          (`InstallSkillCommand.java:371-378`).
       6. Print summary lines for reusable / to-resolve / removed counts
          (`InstallSkillCommand.java:380-389`).
       7. For each `toResolve` entry, build a raw coord string
          (`name@version` or `name`) and call `resolveSkillCoordinates`;
          fail the whole run on resolver failure
          (`InstallSkillCommand.java:394-408`).
       8. Build a `LinkedHashMap` in `.skills-versions` order, mixing
          reusable locked entries and freshly resolved ones
          (`InstallSkillCommand.java:411-423`).
       9. Iterate the ordered map: for each entry, print the install
          banner, dispatch to `installFromGitHub` or `installResolved`,
          and increment `failCount` on non-zero results
          (`InstallSkillCommand.java:426-445`).
       10. Always write the new lock via `SkillLockFile.write`
           (`InstallSkillCommand.java:447-449`).
       11. Return `1` if any failure, else `0`
           (`InstallSkillCommand.java:451-457`).
3. Constraints / Invariants:
   - The order of operations matters for reproducibility: resolve all new
     entries *before* installing any, so a partial run still produces a
     coherent lock file.
   - The lock is written even when installs fail, so re-running the
     command can pick up where the previous run left off.

## N · Norms
- Console output uses the same `System.out` for progress and `System.err`
  for warnings/errors as the rest of the CLI.
- The deprecation warning for the `@` separator is emitted once per line,
  so a fully legacy file produces a per-line warning trail
  (`SkillVersionsFile.java:96-99`).

## S · Safeguards
- Parse errors include the line number of the offending entry so users can
  fix the manifest immediately (`SkillVersionsFile.java:88-95, 104-118`).
- A resolver failure mid-batch aborts before any install runs, preventing a
  partial lock-file rewrite that would mix old and new coordinates
  (`InstallSkillCommand.java:401-408`). `[INFERRED]` — the resolver-failure
  early return short-circuits before reaching `SkillLockFile.write`.
- Per-skill install failures do not abort the loop, but the aggregate exit
  code is non-zero so CI catches them
  (`InstallSkillCommand.java:441-445, 451-454`).
- The lock file is rewritten only after all attempted installs, so no skill
  is recorded as locked unless its resolution actually completed
  (`InstallSkillCommand.java:447-449`).
