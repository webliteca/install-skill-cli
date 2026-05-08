---
bootstrap: true
generated_at: 2026-05-08T12:54:00-07:00
name: GitHub Skill Installation
description: Clones a GitHub repository at a chosen ref and copies skill subdirectories into the target install dir, supporting both the skills/ layout and the Claude plugin marketplace layout.
type: feature
---

# REASONS Canvas: GitHub Skill Installation

## R · Requirements
- Given a GitHub repository identifier `owner/repo` and a git ref (branch,
  tag, or `HEAD`), clone the repository, detect which of two on-disk layouts
  it uses, and copy each skill into the resolved target directory
  (`InstallSkillCommand.java:491-555`).
- Two supported layouts (`README.md` does not document this — it is enforced
  by the code, see `InstallSkillCommand.java:536-551`):
  1. **Skills directory format**: a top-level `skills/` directory whose
     immediate subdirectories are individual skills.
  2. **Marketplace format**: a `.claude-plugin/marketplace.json` listing
     plugins; each plugin's directory contains its own `skills/` subdirectory
     (or another path declared in its `plugin.json` `"skills"` field).
- The git ref handling is:
  - `version == null` or `version == "HEAD"` → clone with `--depth 1`, no
    `--branch` flag (uses the default branch)
    (`InstallSkillCommand.java:506-509`).
  - Otherwise → add `--branch <version>` so a specific tag or branch is
    fetched (`InstallSkillCommand.java:506-509`).
- The base URL for cloning is `https://github.com/` by default but can be
  overridden by system property `github.base.url`
  (`InstallSkillCommand.java:62-63, 492`). Tests override this to point at
  local fixture repositories
  (`src/test/java/ca/weblite/installskill/GitHubInstallIT.java`).
- When neither `.claude-plugin/marketplace.json` nor `skills/` is present,
  fail with
  `Error: Repository <owner/repo> does not contain a 'skills/' directory or
  '.claude-plugin/marketplace.json'.`
  and return non-zero (`InstallSkillCommand.java:546-550`).
- Successful installs print one `Installed skill: <name>` per copied skill
  and the standard `Skills installed successfully to <dir>` summary
  (`InstallSkillCommand.java:569, 627, 578, 639`).
- Definition of Done is exercised by `GitHubInstallIT`
  (`src/test/java/ca/weblite/installskill/GitHubInstallIT.java`):
  - Skills directory format from a local fixture repo.
  - Marketplace format including the optional `plugin.json` `"skills"` path.
  - Error path when neither layout is found (covered by repo construction +
    `InstallSkillCommand.java:546-550`).
- `GitHubSkillResolutionTest`
  (`src/test/java/ca/weblite/installskill/GitHubSkillResolutionTest.java`)
  exercises the resolver path that produces inputs to this feature.

## E · Entities
- **Repository clone working tree**, rooted at `<temp>/repo`
  (`InstallSkillCommand.java:497`). Owned by the install call; deleted in
  `finally` (`InstallSkillCommand.java:552-554`).
- **Marketplace descriptor**: a JSON object at
  `.claude-plugin/marketplace.json` with a top-level `plugins` array. Each
  element MUST carry a `source` field naming a path relative to the repo
  root (`InstallSkillCommand.java:646-674`,
  `GitHubInstallIT.java:46-58`).
- **Plugin descriptor**: optional `<plugin>/.claude-plugin/plugin.json` with
  a `skills` field pointing to a directory of skills
  (`InstallSkillCommand.java:608-618`,
  `GitHubInstallIT.java:60-66`).

## A · Approach
- Use the host-installed `git` binary via `ProcessBuilder` rather than
  embedding JGit. This keeps the dependency footprint flat and inherits the
  user's `~/.gitconfig` (auth, proxies, etc.).
  Trade-off: the install path requires `git` on PATH; missing-git surfaces as
  a `ProcessBuilder` `IOException` rather than a curated message.
- JSON for `marketplace.json` and `plugin.json` is parsed with two
  hand-rolled helpers (`extractMarketplacePluginSources`,
  `extractSimpleJsonString`) instead of pulling in a JSON library
  (`InstallSkillCommand.java:646-740`). This keeps the shaded jar small at the
  cost of brittleness — only string-valued fields are recognized.
- The cloned tree is shallow (`--depth 1`) — git history is not preserved.
- Path containment for marketplace plugins is enforced by
  `pluginDir.startsWith(repoDir)` so a malicious `source` like `../etc`
  cannot escape the repo (`InstallSkillCommand.java:599-604`).

## S · Structure
- `src/main/java/ca/weblite/installskill/InstallSkillCommand.java` —
  `installFromGitHub`, `installGitHubSkillsDir`,
  `installGitHubMarketplace`, `extractMarketplacePluginSources`,
  `extractSimpleJsonString`, `findClosing`. Plus shared helpers from the
  Maven Canvas (`copyDirectory`, `deleteDirectory`, `resolveTargetDir`).
- `src/test/java/ca/weblite/installskill/GitHubInstallIT.java` — fixture
  builder for both layouts and the integration tests.

## O · Operations

### 1. Clone & Detect Layout — `InstallSkillCommand.installFromGitHub`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Shallow-clone the repo at the requested ref, detect
   whether it uses the marketplace or skills-directory layout, and dispatch.
2. Methods:
   - `installFromGitHub(String, String): int`
     (`InstallSkillCommand.java:491-555`)
     - Logic:
       1. Build the clone URL from `github.base.url` plus `ownerRepo`
          (`InstallSkillCommand.java:492-493`).
       2. Create a temp dir, build a `git clone --depth 1 [--branch
          <version>] <url> <tempRepo>` command, pipe stdout+stderr together,
          read all output, await exit (`InstallSkillCommand.java:497-524`).
       3. On non-zero exit, print the captured output and return 1
          (`InstallSkillCommand.java:525-529`).
       4. Resolve the target directory and create it
          (`InstallSkillCommand.java:532-533`).
       5. Detect format: if `.claude-plugin/marketplace.json` is a file,
          run `installGitHubMarketplace`; else if `skills/` is a directory,
          run `installGitHubSkillsDir`; else error
          (`InstallSkillCommand.java:535-551`).
       6. Always delete the temp repo in `finally`
          (`InstallSkillCommand.java:552-554`).
3. Constraints / Invariants:
   - Marketplace detection takes precedence over skills-directory detection
     when both are present.
   - The version `null` is normalized to `"HEAD"` by the resolver before this
     method is called; the method only treats `"HEAD"` specially.

### 2. Install Skills Directory Layout — `installGitHubSkillsDir`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Treat each immediate subdirectory of `skills/` as a skill
   and copy it into the target.
2. Methods:
   - `installGitHubSkillsDir(Path, Path): int`
     (`InstallSkillCommand.java:561-580`)
     - Logic:
       1. List `skills/`; for every subdirectory, create a same-named
          directory under target and `copyDirectory` it
          (`InstallSkillCommand.java:563-572`).
       2. Track whether at least one was installed; if zero, error
          (`InstallSkillCommand.java:574-577`).
       3. Print the standard success summary
          (`InstallSkillCommand.java:578`).
3. Constraints / Invariants:
   - Files at the top of `skills/` (non-directories) are silently ignored.
   - Each skill keeps its directory name from the source repo.

### 3. Install Marketplace Layout — `installGitHubMarketplace`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Walk `marketplace.json` plugin sources, locate each
   plugin's skills directory (honoring its `plugin.json`), and copy.
2. Methods:
   - `installGitHubMarketplace(Path, Path): int`
     (`InstallSkillCommand.java:587-641`)
     - Logic:
       1. Read `.claude-plugin/marketplace.json` and call
          `extractMarketplacePluginSources` (`InstallSkillCommand.java:588-595`).
       2. For each `source`, resolve and normalize against `repoDir`; skip
          (with warning) any source that escapes the repo via `..`
          (`InstallSkillCommand.java:598-604`).
       3. Default the plugin's skills dir to `<plugin>/skills`; if
          `<plugin>/.claude-plugin/plugin.json` exists and contains a string
          `"skills"` field whose target is a directory, override
          (`InstallSkillCommand.java:606-618`).
       4. Iterate the skills directory; for each subdirectory, mirror it
          into the target (`InstallSkillCommand.java:620-632`).
       5. Track whether anything was installed; error if none
          (`InstallSkillCommand.java:635-638`).
       6. Print the success summary (`InstallSkillCommand.java:639`).
3. Constraints / Invariants:
   - Plugin sources outside the repo root are skipped, never installed.
   - The `plugin.json` `"skills"` value is honored only when it resolves to
     an existing directory; otherwise the default `skills/` path is used.

### 4. Extract Plugin Sources — `extractMarketplacePluginSources`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Hand-roll a JSON walk that returns the `source` string of
   every object inside the top-level `"plugins"` array.
2. Methods:
   - `extractMarketplacePluginSources(String): List<String>`
     (`InstallSkillCommand.java:646-675`)
     - Logic:
       1. Find `"plugins"`, then the next `[` and its matching `]` via
          `findClosing` (`InstallSkillCommand.java:648-657`).
       2. Walk the array body, locating each `{ … }` object and calling
          `extractSimpleJsonString(obj, "source")`
          (`InstallSkillCommand.java:659-672`).
       3. Return all collected sources in order.
3. Constraints / Invariants:
   - `findClosing` (`InstallSkillCommand.java:681-708`) handles nested
     braces/brackets and quoted strings (with backslash escapes), so the
     parser tolerates non-trivial JSON without a real parser.

### 5. Extract Simple JSON String — `extractSimpleJsonString`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Return the string value for a top-level key in a JSON
   object body, or `null` if not present.
2. Methods:
   - `extractSimpleJsonString(String, String): String`
     (`InstallSkillCommand.java:713-740`)
     - Logic:
       1. Find `"<key>"`; locate the next `:` and skip whitespace
          (`InstallSkillCommand.java:715-723`).
       2. Require an opening `"` (`InstallSkillCommand.java:724-726`).
       3. Walk until the matching unescaped `"`, return the substring
          (`InstallSkillCommand.java:727-738`).
3. Constraints / Invariants:
   - Handles only string values — non-string keys (numbers, booleans, nested
     objects) silently return `null`. This is sufficient for the two consumed
     fields (`source` and `skills`).

## N · Norms
- All filesystem I/O reuses the helpers from the Maven install Canvas
  (`copyDirectory`, `deleteDirectory`).
- Logging for each installed skill is one `Installed skill: <name>` line
  (`InstallSkillCommand.java:569, 627`), keeping the user-facing transcript
  consistent across the two layouts.

## S · Safeguards
- Path traversal: marketplace `source` paths are normalized and required to
  start with the repo root before any file operation
  (`InstallSkillCommand.java:599-604`). This is the only validation between
  the user-controlled JSON and the filesystem copy.
- Failed `git clone` aborts before any file operation
  (`InstallSkillCommand.java:525-529`).
- Empty installs (no skills found in either layout) return non-zero with a
  specific message (`InstallSkillCommand.java:574-577, 635-638`).
- Temp clones are always deleted via `finally`
  (`InstallSkillCommand.java:552-554`).
