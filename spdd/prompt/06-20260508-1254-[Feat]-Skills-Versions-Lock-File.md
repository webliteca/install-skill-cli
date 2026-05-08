---
bootstrap: true
generated_at: 2026-05-08T12:54:00-07:00
name: Skills Versions Lock File for Reproducible Installs
description: Reads, computes, and writes .skills-versions.lock so that subsequent runs reuse previously resolved coordinates and only re-resolve new or changed entries.
type: feature
---

# REASONS Canvas: Skills Versions Lock File for Reproducible Installs

## R · Requirements
- Maintain a `.skills-versions.lock` file in the working directory that
  captures the concrete coordinates resolved for each entry of
  `.skills-versions`, so a second `install-skill` run installs identical
  versions without re-querying the registry or re-checking RELEASE
  (`README.md:77-89`, `SkillLockFile.java:13-19`).
- Compare a parsed `.skills-versions` against the lock and produce a
  `ResolutionPlan` with three lists (`SkillLockFile.java:73-94, 133-162`):
  - **Reusable**: lock entry exists for the name and the manifest's
    `requestedVersion` matches the lock's stored `requestedVersion`. The
    locked GAV is reused unchanged.
  - **To resolve**: name is new in the manifest, or the requested version
    has changed since the last lock.
  - **Removed**: name appears in the lock but no longer in the manifest;
    these are dropped from the rewritten lock.
- The lock JSON has a top-level `lockVersion` integer (currently `1`) and a
  `skills` object keyed by name. Each entry stores `name`, `groupId`,
  `artifactId`, `version`, and `requestedVersion` (which may be `null` for
  "RELEASE / latest") (`SkillLockFile.java:21-22, 166-198`,
  `README.md:69-84`).
- The CLI flag `-u, --update` forces every entry to `toResolve`, ignoring
  the lock entirely (`InstallSkillCommand.java:91-93, 371-378`,
  `README.md:96-100`).
- A missing lock file is treated as "no entries locked" — `read` returns an
  empty `LinkedHashMap` (`SkillLockFile.java:100-103`).
- A malformed lock file is treated as "no entries locked" but logs
  `Warning: Failed to parse lock file (<msg>). Re-resolving all skills.`
  (`SkillLockFile.java:105-112`).
- The lock file is written after every batch install, in the order of the
  current `.skills-versions` (`InstallSkillCommand.java:447-449`,
  `SkillLockFile.java:115-121`).
- Definition of Done is exercised by `SkillLockFileTest`
  (`src/test/java/ca/weblite/installskill/SkillLockFileTest.java`):
  - Round-trip read/write with multiple entries.
  - `read` returns empty for a missing file.
  - `computeResolutionPlan` covers four cases: all-new, all-locked, mixed
    new+removed, version-changed, and removed-only.
  - JSON round trip with non-trivial version strings (e.g.
    `"1.0.0-beta.1"`).

## E · Entities
- **`SkillLockFile.LockedSkill`** (`SkillLockFile.java:30-68`) — immutable
  record of a single locked skill. Invariants:
  - `name`, `groupId`, `artifactId`, `version` are non-null
    (`SkillLockFile.java:38-43`).
  - `requestedVersion` is nullable, with `null` meaning "the user requested
    latest / RELEASE" (`SkillLockFile.java:35-36, 50`).
  - `equals/hashCode` use all five fields
    (`SkillLockFile.java:52-67`).
- **`SkillLockFile.ResolutionPlan`** (`SkillLockFile.java:73-94`) —
  immutable triple of `reusable: List<LockedSkill>`,
  `toResolve: List<SkillVersionsFile.Entry>`, `removed: List<String>`.
  Invariants:
  - Lists are non-null (`SkillLockFile.java:81-84`).
  - The plan preserves manifest order in `reusable` and `toResolve` because
    the producer iterates the manifest in order
    (`SkillLockFile.java:142-150`).
- **Lock file schema** (`SkillLockFile.java:166-198`,
  `README.md:69-84`) — JSON object:
  ```json
  {
    "lockVersion": 1,
    "skills": {
      "<name>": {
        "name": "<name>",
        "groupId": "...",
        "artifactId": "...",
        "version": "...",
        "requestedVersion": "..."  // or null
      }
    }
  }
  ```
- **Constants** (`SkillLockFile.java:21-22`):
  - `FILE_NAME = ".skills-versions.lock"`.
  - `LOCK_VERSION = 1` — written but not yet checked on read; the parser
    is forward-tolerant of unknown versions (it ignores the field).

## A · Approach
- Hand-rolled JSON serialization keeps the shaded jar dependency-free and
  the file diff-friendly: the writer indents with two spaces and nests
  per-skill objects to four
  (`SkillLockFile.java:166-198`). `[INFERRED]` whether matching a real JSON
  formatter exactly is a goal — current tests only assert round-trip
  fidelity.
- The reader is intentionally permissive: any parse failure flips back to
  empty-map behaviour rather than aborting the install
  (`SkillLockFile.java:105-112`). This means a corrupted lock degrades to
  "re-resolve everything", not "user has to delete the file".
- `requestedVersion` is the cache key (`SkillLockFile.java:144-149`), not
  the resolved `version`. This is what makes RELEASE/latest predictable: a
  user who pinned `0.1.0` and a user who left `null` both get stable
  reuse, but bumping the manifest from `0.1.0` to `0.1.1` invalidates the
  lock.
- Tradeoff: name uniqueness is implicit. The schema keys by skill name in
  both the manifest and the lock; if the manifest accidentally lists the
  same name twice, the lock will end up with one entry. The parser does
  not warn about this — `[INFERRED]`.

## S · Structure
- `src/main/java/ca/weblite/installskill/SkillLockFile.java` — `LockedSkill`,
  `ResolutionPlan`, `read`, `write`, `pathIn`, `computeResolutionPlan`, and
  the JSON helpers (`toJson`, `parseJson`, `findMatchingBrace`,
  `extractJsonStringValue`, `jsonString`, `escapeJson`, `unescapeJson`).
- `src/main/java/ca/weblite/installskill/InstallSkillCommand.java` —
  `installFromVersionsFile` is the only consumer; the `--update` flag and
  the per-entry banner formatting live there
  (`InstallSkillCommand.java:91-93, 371-378, 425-445, 447-449`).

## O · Operations

### 1. Define Locked Entry — `SkillLockFile.LockedSkill`
File: `src/main/java/ca/weblite/installskill/SkillLockFile.java`

1. Responsibility: Immutable representation of one locked skill.
2. Fields / Attributes:
   - `name: String` — non-null (`SkillLockFile.java:31, 39`).
   - `groupId: String` — non-null (`SkillLockFile.java:32, 40`).
   - `artifactId: String` — non-null (`SkillLockFile.java:33, 41`).
   - `version: String` — non-null, the resolved concrete version
     (`SkillLockFile.java:34, 42`).
   - `requestedVersion: String` — nullable; matches what the user typed
     (`SkillLockFile.java:35, 43`).
3. Methods:
   - Constructor + getters for all five fields
     (`SkillLockFile.java:37-50`).
   - `equals/hashCode` over all five (`SkillLockFile.java:52-67`).

### 2. Define Resolution Plan — `SkillLockFile.ResolutionPlan`
File: `src/main/java/ca/weblite/installskill/SkillLockFile.java`

1. Responsibility: Immutable triple describing how a fresh install relates
   to the existing lock.
2. Fields / Attributes:
   - `reusable: List<LockedSkill>` (`SkillLockFile.java:74, 87`).
   - `toResolve: List<SkillVersionsFile.Entry>` (`SkillLockFile.java:75,
     90`).
   - `removed: List<String>` (`SkillLockFile.java:76, 93`).
3. Methods:
   - Constructor (non-null lists) and three accessors
     (`SkillLockFile.java:78-94`).

### 3. Compute Plan — `SkillLockFile.computeResolutionPlan`
File: `src/main/java/ca/weblite/installskill/SkillLockFile.java`

1. Responsibility: Compare a manifest against the lock map and partition
   into reusable / to-resolve / removed.
2. Methods:
   - `computeResolutionPlan(List<Entry>, Map<String, LockedSkill>):
     ResolutionPlan` (`SkillLockFile.java:133-162`)
     - Logic:
       1. For each manifest entry, look up the lock by name; if present
          and `requestedVersion` matches, add the locked entry to
          `reusable`; otherwise add the manifest entry to `toResolve`
          (`SkillLockFile.java:142-150`).
       2. For each lock key, if no manifest entry has the same name, add
          to `removed` (`SkillLockFile.java:153-159`).
3. Constraints / Invariants:
   - `Objects.equals` is used for the requested-version compare so a
     `null` manifest version matches a `null` locked requested version
     (`SkillLockFile.java:145`).

### 4. Read & Parse Lock — `SkillLockFile.read` / `parseJson`
File: `src/main/java/ca/weblite/installskill/SkillLockFile.java`

1. Responsibility: Load `.skills-versions.lock` into an ordered map.
2. Methods:
   - `read(Path): Map<String, LockedSkill>`
     (`SkillLockFile.java:100-113`)
     - Logic:
       1. If the file doesn't exist, return an empty `LinkedHashMap`
          (`SkillLockFile.java:101-103`).
       2. Read the file contents and call `parseJson`
          (`SkillLockFile.java:105-107`).
       3. On any parse exception, log a warning to `stderr` and return
          empty (`SkillLockFile.java:108-112`).
   - `parseJson(String): Map<String, LockedSkill>`
     (`SkillLockFile.java:200-254`)
     - Logic:
       1. Locate the `"skills"` object using `findMatchingBrace`
          (`SkillLockFile.java:204-219`).
       2. Walk the object body finding each key/value pair where the value
          is itself an object, and pull `name`, `groupId`, `artifactId`,
          `version`, `requestedVersion` via `extractJsonStringValue`
          (`SkillLockFile.java:223-251`).
       3. Insert into a `LinkedHashMap` keyed by `name` (preserves source
          order) (`SkillLockFile.java:245-248`).
3. Constraints / Invariants:
   - Skill objects whose `name`, `groupId`, `artifactId` or `version` are
     missing are silently skipped (`SkillLockFile.java:245`). This lets the
     parser ignore future schema additions while keeping the cache intact.
   - `extractJsonStringValue` recognizes a literal `null` token (returning
     `null`) so `requestedVersion: null` round-trips
     (`SkillLockFile.java:300-302`).

### 5. Write Lock — `SkillLockFile.write` / `toJson`
File: `src/main/java/ca/weblite/installskill/SkillLockFile.java`

1. Responsibility: Serialize the ordered map of locked skills as JSON.
2. Methods:
   - `write(Path, Map<String, LockedSkill>): void`
     (`SkillLockFile.java:115-121`) — delegates to `toJson` and writes the
     string.
   - `toJson(Map<String, LockedSkill>): String`
     (`SkillLockFile.java:166-198`)
     - Logic:
       1. Emit the `lockVersion` field and an opening `"skills": {`
          (`SkillLockFile.java:167-170`).
       2. For each entry in iteration order, write the indented object
          with the five fields; render `requestedVersion` as the literal
          `null` when null, otherwise as a quoted string
          (`SkillLockFile.java:172-189`).
       3. Close the braces (`SkillLockFile.java:192-196`).
3. Constraints / Invariants:
   - Iteration order is the order of the supplied map. The orchestrator
     supplies a `LinkedHashMap` keyed in `.skills-versions` order
     (`InstallSkillCommand.java:411-423`).
   - String values are escaped via `escapeJson`
     (`SkillLockFile.java:316-322`); the inverse `unescapeJson` is symmetric
     (`SkillLockFile.java:324-330`).

### 6. Locate Lock — `SkillLockFile.pathIn`
File: `src/main/java/ca/weblite/installskill/SkillLockFile.java`

1. Responsibility: Static helper returning the lock path within a given
   directory.
2. Methods:
   - `pathIn(Path): Path` (`SkillLockFile.java:124-128`).

### 7. Force Re-resolution — `--update`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: User-controlled override that bypasses the lock.
2. Methods:
   - The flag is declared on the CLI command
     (`InstallSkillCommand.java:91-93`) and read by
     `installFromVersionsFile`
     (`InstallSkillCommand.java:371-378`):
     - Logic: when `update` is true, build a `ResolutionPlan` whose
       `toResolve` is the entire manifest and whose other lists are
       empty, instead of calling `computeResolutionPlan`.
3. Constraints / Invariants:
   - `--update` does not delete the lock; it just bypasses it on the read
     path. The next write replaces it with newly resolved entries.

## N · Norms
- The lock file uses two-space indentation in the outer object and
  four-space indentation per skill, ending each `toJson` output with a
  trailing newline (`SkillLockFile.java:166-197`). This matches what a
  human-edited file is expected to look like and keeps git diffs minimal.
- All JSON parsing inside this Canvas (and the marketplace JSON in the
  GitHub Canvas) is hand-rolled. There is no shared JSON library
  dependency; both pieces use similar `findMatchingBrace`/
  `extractSimpleJsonString` style helpers but the implementations are not
  factored together. `[INFERRED]` whether unifying them is a goal.

## S · Safeguards
- Corrupt lock files degrade to "re-resolve everything" rather than
  aborting (`SkillLockFile.java:108-112`).
- Missing lock files are normal first-run state, not an error
  (`SkillLockFile.java:100-103`).
- Removed entries from `.skills-versions` are explicitly listed in the
  plan so the orchestrator can log them
  (`InstallSkillCommand.java:386-389`,
  `SkillLockFile.java:153-159`).
- `requestedVersion` is the cache key, so a manifest change always
  invalidates the relevant entry — the lock cannot pin a stale RELEASE
  silently when the manifest has switched to a concrete version
  (`SkillLockFile.java:144-149`).
