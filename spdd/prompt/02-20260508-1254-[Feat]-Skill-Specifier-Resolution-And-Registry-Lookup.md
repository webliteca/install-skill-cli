---
bootstrap: true
generated_at: 2026-05-08T12:54:00-07:00
name: Skill Specifier Resolution & Registry Lookup
description: Parses raw skill arguments (registry name, name@version, Maven coords, owner/repo) into resolved coordinates, including XML registry lookup.
type: feature
---

# REASONS Canvas: Skill Specifier Resolution & Registry Lookup

## R · Requirements
- Accept and parse four shapes of skill identifier from the user
  (`README.md:13-32`, `InstallSkillCommand.java:65-75`):
  1. Bare registry name: `teavm-lambda` — looked up in the skills registry.
  2. Registry name with explicit version: `teavm-lambda@0.1.2`.
  3. Maven coordinates: `groupId:artifactId[:version]`, optionally with
     `@version` override on the trailing component.
  4. GitHub repository: `owner/repo[@version]`, where `version` is a git ref
     and defaults to `HEAD`.
- The legacy `name@version` separator continues to work but is documented as
  deprecated in `README.md:55-59` and warned about by the `.skills-versions`
  parser (handled in the batch-install Canvas).
- An empty-version-after-`@` for either the registry-name or GitHub form is a
  user error and prints a specific message to `stderr`, returning `null`
  (`InstallSkillCommand.java:163-167, 202-206, 254-258`).
- Resolving a registry name that is missing from `skills.xml` prints
  `Error: Skill '<name>' not found in the skills registry.` and returns
  `null` (`InstallSkillCommand.java:226-229`).
- The skills registry XML is fetched from the URL named by system property
  `skills.registry.url`, defaulting to the public GitHub-hosted
  `skills-registry/main/skills.xml`
  (`InstallSkillCommand.java:59-60, 754-784`).
- Each `<skill>` element may contain either a Maven `groupId`/`artifactId` pair
  (with optional `version`) or a `repository` element naming a GitHub
  `owner/repo`; if `repository` is set, the resolver returns coordinates with
  groupId `"github"` and artifactId equal to that repository
  (`InstallSkillCommand.java:766-777`).
- Resolved coordinates always carry a non-null version. Defaults:
  - Maven path with no version anywhere: `"RELEASE"`
    (`InstallSkillCommand.java:184, 58`).
  - Registry name with no `<version>` and no `@` override: `"RELEASE"` for
    Maven skills, `"HEAD"` for GitHub skills
    (`InstallSkillCommand.java:236-239`).
  - GitHub `owner/repo` with no `@` version: `"HEAD"`
    (`InstallSkillCommand.java:267`).
- Definition of Done is exercised by:
  - `GitHubSkillResolutionTest`
    (`src/test/java/ca/weblite/installskill/GitHubSkillResolutionTest.java`):
    happy-path GitHub coords, version override, branch ref, and four error
    forms (empty version, multiple slashes, empty owner, empty repo).
  - `InstallSkillIT.installSkillByRegistryName`
    (`src/test/java/ca/weblite/installskill/InstallSkillIT.java:196-229`) —
    confirms registry XML lookup end-to-end.

## E · Entities
- **`SkillCoordinates`** (`SkillCoordinates.java:8`) — immutable value object
  holding `name`, `groupId`, `artifactId`, `version`. All four are
  `Objects.requireNonNull` in the constructor (`SkillCoordinates.java:15-20`).
  `name` is the original user-facing identifier (registry name, Maven coords,
  or `owner/repo`); `groupId`/`artifactId`/`version` are always concrete.
- **Registry XML schema** (consumed at
  `InstallSkillCommand.java:763-778`) — a `<skills>` root with zero or more
  `<skill>` children, each containing exactly one of:
  - `<name>` (required) plus `<repository>` and optional `<version>` for the
    GitHub form, or
  - `<name>` (required) plus `<groupId>`, `<artifactId>` and optional
    `<version>` for the Maven form.
  Invariant: if `<repository>` is non-empty, the Maven fields are ignored
  (`InstallSkillCommand.java:768-772`).

## A · Approach
- Parse-by-content: `resolveSkillCoordinates` decides the shape of the input
  by simple character checks (`InstallSkillCommand.java:148-217`):
  - Contains `:` → Maven path (with optional trailing `@version` override).
  - Contains `/` (and not `:`) → GitHub path.
  - Otherwise → registry name lookup.
- This ordering means a string like `owner/repo:tag` would be treated as
  Maven first; the code intentionally guards by re-checking that the
  pre-`@` segment still contains `:` (`InstallSkillCommand.java:170-191`).
- The XML registry is parsed with the JDK's bundled `DocumentBuilderFactory`
  to avoid pulling in a JSON or HTTP client dependency
  (`InstallSkillCommand.java:756-778`).
- Trade-off: registry lookup happens synchronously on each install. For batch
  installs, every new entry hits the registry — there is no client-side
  caching beyond the lock file (which is the responsibility of the lock-file
  Canvas).

## S · Structure
- `src/main/java/ca/weblite/installskill/InstallSkillCommand.java` —
  `resolveSkillCoordinates`, `resolveRegistryName`, `resolveGitHubCoordinates`,
  `resolveFromRegistry`, `getElementText`.
- `src/main/java/ca/weblite/installskill/SkillCoordinates.java` — the value
  object returned by every resolver path.

## O · Operations

### 1. Define Entity — `SkillCoordinates`
File: `src/main/java/ca/weblite/installskill/SkillCoordinates.java`

1. Responsibility: Immutable, comparable holder for resolved coordinates.
2. Fields / Attributes:
   - `name: String` — the original user-facing identifier
     (`SkillCoordinates.java:10`).
   - `groupId: String` — Maven groupId, or the literal `"github"` for
     GitHub skills (`SkillCoordinates.java:11`).
   - `artifactId: String` — Maven artifactId, or `owner/repo`
     (`SkillCoordinates.java:12`).
   - `version: String` — concrete version string, never null
     (`SkillCoordinates.java:13`).
3. Methods:
   - Constructor `(String, String, String, String)`: throws if any argument
     is null (`SkillCoordinates.java:15-20`).
   - Getters for all four fields (`SkillCoordinates.java:22-36`).
   - `equals/hashCode` use all four fields (`SkillCoordinates.java:38-52`).
   - `toString()` returns `groupId:artifactId:version`
     (`SkillCoordinates.java:54-57`).
4. Constraints / Invariants:
   - All four fields are non-null (constructor enforces).
   - Value semantics — no mutators.

### 2. Resolve Specifier — `InstallSkillCommand.resolveSkillCoordinates`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Dispatch a raw user string to the correct resolver and
   return concrete coordinates, or `null` after printing an error.
2. Methods:
   - `resolveSkillCoordinates(String): SkillCoordinates`
     (`InstallSkillCommand.java:148-217`)
     - Logic:
       1. If the string contains `:`, treat as Maven coords; strip the
          trailing `@<override>` if present and use it as the version
          (`InstallSkillCommand.java:154-191`).
       2. Else if it contains `/`, delegate to `resolveGitHubCoordinates`
          (`InstallSkillCommand.java:192-194`).
       3. Else split on the first `@`, treating the prefix as a registry
          name and the suffix as a version override; delegate to
          `resolveRegistryName` (`InstallSkillCommand.java:195-208`).
       4. Validate non-empty `groupId`/`artifactId` for the Maven path
          before returning (`InstallSkillCommand.java:211-216`).
3. Constraints / Invariants:
   - The method must be safe to call before PicoCLI option binding for tests
     (the unit tests construct `new InstallSkillCommand()` directly —
     `GitHubSkillResolutionTest.java:18-26`).
   - Always returns either fully populated coordinates or `null`; never
     throws for malformed input.

### 3. Resolve Registry Name — `InstallSkillCommand.resolveRegistryName`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Look up a name in the skills registry and apply the
   optional version override.
2. Methods:
   - `resolveRegistryName(String, String): SkillCoordinates`
     (`InstallSkillCommand.java:219-242`)
     - Logic:
       1. Reject empty name with an error (`InstallSkillCommand.java:220-223`).
       2. Print a "Looking up …" progress line
          (`InstallSkillCommand.java:224`).
       3. Call `resolveFromRegistry`; if `null`, print
          `Error: Skill '<name>' not found in the skills registry.` and
          return `null` (`InstallSkillCommand.java:225-229`).
       4. Choose version: `versionOverride` if non-null, else the registry's
          `<version>`, else `"HEAD"` for GitHub or `"RELEASE"` for Maven
          (`InstallSkillCommand.java:233-239`).
       5. Print a "Resolved to …" progress line and return coordinates
          (`InstallSkillCommand.java:240-241`).
3. Constraints / Invariants:
   - Distinguishes GitHub vs Maven default version by the registry-returned
     `groupId`, not by the original input shape.

### 4. Resolve GitHub Specifier — `InstallSkillCommand.resolveGitHubCoordinates`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Parse `owner/repo[@version]` into coordinates with
   `groupId == "github"`.
2. Methods:
   - `resolveGitHubCoordinates(String): SkillCoordinates`
     (`InstallSkillCommand.java:248-270`)
     - Logic:
       1. Strip a trailing `@<version>` and reject empty version
          (`InstallSkillCommand.java:251-259`).
       2. Validate that the remaining string contains exactly one `/`,
          with non-empty owner and repo segments
          (`InstallSkillCommand.java:261-266`).
       3. Default version to `"HEAD"` when none is supplied
          (`InstallSkillCommand.java:267`).
       4. Return `new SkillCoordinates(ownerRepo, GITHUB_GROUP_ID, ownerRepo,
          version)` (`InstallSkillCommand.java:269`).
3. Constraints / Invariants:
   - The same string is used for both `name` and `artifactId`, by design — it
     keeps GitHub skills locatable in the lock file by `owner/repo`.

### 5. Read Registry XML — `InstallSkillCommand.resolveFromRegistry`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Fetch the registry XML and locate a matching `<skill>`.
2. Methods:
   - `resolveFromRegistry(String): String[]`
     (`InstallSkillCommand.java:754-784`)
     - Logic:
       1. Read URL from `System.getProperty("skills.registry.url",
          DEFAULT_REGISTRY_URL)` (`InstallSkillCommand.java:755`).
       2. Open a stream and parse via JDK `DocumentBuilder`
          (`InstallSkillCommand.java:757-761`).
       3. Iterate `<skill>` elements; for the first match by `<name>`, return
          either `[GITHUB_GROUP_ID, repository, version]` if `<repository>` is
          present, else `[groupId, artifactId, version]`
          (`InstallSkillCommand.java:763-778`).
       4. On exception, print a warning to `stderr` and return `null`
          (`InstallSkillCommand.java:780-782`).
3. Constraints / Invariants:
   - Returns `null` for both "not found" and "fetch failed" — the caller
     translates the latter via the warning that has already been printed.
   - `version` element in the array may be `null` (registry omits the field).

## N · Norms
- The system property `skills.registry.url` is the only registry override
  surface — no CLI flag exposes it. Tests override it directly
  (`InstallSkillIT.java:215`).
- XML parsing relies on the JDK's default `DocumentBuilderFactory` —
  external XXE hardening is not currently configured. `[INFERRED]` whether
  this is acceptable depends on registry trust assumptions; the registry URL
  defaults to a project-controlled GitHub raw file.

## S · Safeguards
- Every malformed input form returns `null` after a specific `stderr`
  message, never an unchecked exception
  (`InstallSkillCommand.java:165-167, 174-176, 203-206, 211-214, 256-258, 264-266`).
- Registry fetch failures fall back to a warning rather than an error so that
  Maven coordinates and GitHub specifiers continue to work without network
  access (`InstallSkillCommand.java:780-782`).
- Empty `groupId`/`artifactId` from explicit Maven coords is rejected before
  returning (`InstallSkillCommand.java:211-214`).
