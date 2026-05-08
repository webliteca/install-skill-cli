# CLAUDE.md

This file provides guidance for Claude Code when working in this repository.

## Repository overview

This is the **install-skill CLI** — a Java command-line tool (PicoCLI + embedded Maven) for installing AI assistant skills deployed with the skills-jar-maven-plugin. Skills are resolved from the [skills registry](https://github.com/webliteca/skills-registry), by Maven coordinates, or from GitHub repositories.

## Key files

- `src/main/java/ca/weblite/installskill/InstallSkillCommand.java` — Main CLI entry point and command implementation. Handles single-skill install and batch install from `.skills-versions`.
- `src/main/java/ca/weblite/installskill/SkillCoordinates.java` — Immutable value class for resolved Maven coordinates (name, groupId, artifactId, version).
- `src/main/java/ca/weblite/installskill/SkillVersionsFile.java` — Parser for `.skills-versions` files.
- `src/main/java/ca/weblite/installskill/SkillLockFile.java` — Read/write for `.skills-versions.lock` (JSON). Includes resolution plan computation (comparing desired vs locked state).
- `pom.xml` — Maven build configuration. Java 11 target, PicoCLI + Maven Embedder dependencies.
- `package.json` — npm/jDeploy configuration for distribution as a native CLI.

## Architecture

### Two execution modes

1. **Single-skill mode** (`install-skill <skill>`): resolves one skill, creates a temp Maven project, runs `skills-jar-plugin:install`, copies result to target directory. No interaction with `.skills-versions` or lock files.

2. **Batch mode** (`install-skill` with no arguments): reads `.skills-versions` from the working directory, uses `.skills-versions.lock` for reproducible resolution, installs all listed skills sequentially.

### Key method flow in `InstallSkillCommand`

- `call()` — dispatcher: delegates to `installSingleSkill()` or `installFromVersionsFile()`
- `resolveSkillCoordinates(String)` — parses raw input (registry name, `name@version`, Maven coords, or `owner/repo[@version]`) into `SkillCoordinates`
- `resolveGitHubCoordinates(String)` — parses `owner/repo[@version]` into coordinates with `github` as groupId
- `resolveRegistryName(String, String)` — looks up a skill name in the XML registry
- `installResolved(String, String, String)` — creates temp Maven project and installs a single resolved Maven skill
- `installFromGitHub(String, String)` — clones a GitHub repo, detects format (skills dir or marketplace), copies skills to target
- `installGitHubSkillsDir(Path, Path)` — copies skills from `skills/` subdirectories
- `installGitHubMarketplace(Path, Path)` — parses `.claude-plugin/marketplace.json` and copies skills from plugins
- `installFromVersionsFile()` — batch flow: parse versions file, compute resolution plan against lock, resolve new entries, install all, write lock

### Lock file resolution plan

`SkillLockFile.computeResolutionPlan()` compares `.skills-versions` entries against `.skills-versions.lock`:
- **Reusable**: entry exists in lock and `requestedVersion` matches — skip resolution
- **To resolve**: new entry or `requestedVersion` changed — needs fresh resolution
- **Removed**: in lock but not in `.skills-versions` — dropped from updated lock

### GitHub skill installation

When a skill specifier contains `/` but not `:`, it is treated as a GitHub repository reference (`owner/repo[@version]`). The CLI clones the repository and detects one of two formats:

1. **Skills directory format**: The repo has a `skills/` directory at root with subdirectories for each skill (e.g., `skills/my-skill/SKILL.md`).
2. **Marketplace format**: The repo has `.claude-plugin/marketplace.json` listing plugins, each containing a `skills/` directory with skill subdirectories.

GitHub skills use `"github"` as the groupId and `"owner/repo"` as the artifactId in the lock file. The version is the git ref (tag/branch) or `"HEAD"` for the default branch.

The GitHub base URL defaults to `https://github.com/` and can be overridden via `System.setProperty("github.base.url", ...)` for testing.

## `.skills-versions` format

```
# Comment
skill-name 0.1.0
skill-name
com.example:my-lib 1.0
owner/repo v1.0
owner/repo
```

The legacy `@` separator (e.g., `skill-name@0.1.0`) is still accepted but deprecated.

## `.skills-versions.lock` format (JSON)

```json
{
  "lockVersion": 1,
  "skills": {
    "skill-name": {
      "name": "skill-name",
      "groupId": "com.example",
      "artifactId": "my-lib",
      "version": "0.1.0",
      "requestedVersion": "0.1.0"
    }
  }
}
```

## Build and test commands

```bash
# Compile
mvn compile

# Run unit tests
mvn test

# Run integration tests (requires Maven on PATH)
mvn verify

# Package as shaded JAR
mvn package
```

## Testing patterns

- Unit tests (`*Test.java`): test parsing and logic in isolation using `@TempDir`.
- Integration tests (`*IT.java`): install a fixture skills JAR to the local Maven repo in `@BeforeAll`, then exercise the CLI via `new CommandLine(new InstallSkillCommand()).execute(...)`.
- For batch-mode tests, set `cmd.workingDirectory` to control where `.skills-versions` is looked up.
- For registry tests, set `System.setProperty("skills.registry.url", ...)` to a local file URI.
- For GitHub install tests, create local git repos as fixtures and set `System.setProperty("github.base.url", ...)` to the fixture directory.

## Distribution

The CLI is distributed via npm/jDeploy as the `install-skill` package with native bundles for macOS, Windows, and Linux.

## Structured Prompt-Driven Development (SPDD)

This repository uses Structured Prompt-Driven Development (SPDD). Canvases (REASONS files) under `spdd/prompt/` are the source of truth for behavior. Generated files (code, tests, configuration that implements a Canvas) live under `requirements/`, `spdd/analysis/`, `spdd/prompt/`, **and anywhere a Canvas's REASONS-Implements section points** — typically `src/`. If a source file is produced by a Canvas, it is generated, even though it lives outside `spdd/`.

## Hard rules — read before editing any file

Hand-editing rules depend on whether the change touches observable behavior:

1. **For behavior changes, never hand-edit a generated source file.** Use `/spdd-prompt-update` followed by `/spdd-generate` instead — no matter how small the change looks (one line, one file, "trivial" rename of a user-visible string — all still go through the Canvas).
2. **For non-behavior changes (refactor, rename internal symbol, restructure with identical observable behavior), hand-editing is the correct move** — then run `/spdd-sync` in the same turn so the Canvas's Structure/Operations/Norms sections reflect the new shape of the code.
3. **Never skip `/spdd-generate` after `/spdd-prompt-update`.** They are one operation in two steps. A turn that updates a Canvas without regenerating leaves the repo in a broken, half-applied state.
4. **Never skip `/spdd-sync` after a non-behavior code edit.** Same drift problem in reverse: code moved, Canvas didn't.

## Anti-rationalization checklist

Before you reach for `Edit` or `Write` on a source file, stop and answer these out loud:

- Is this file referenced by a Canvas under `spdd/prompt/`?   If yes, it is generated. Treat it as read-only output.
- Am I about to argue that `/spdd-generate` is "overkill"   because only one or two files need to change? **That   reasoning is wrong and is the single most common way   this workflow gets broken.** `/spdd-generate` is   idempotent and file-scoped: it diffs each target against   what the Canvas implies and only rewrites files that   actually need to change. Running it on a one-line fix   costs you nothing and produces the same one-line diff a   hand-edit would — plus it keeps the Canvas as the source   of truth. There is no scenario where skipping it because   "only a few files are affected" is correct.
- Am I about to argue that hand-editing is "faster" or   "more surgical"? Also wrong. The slash commands exist   precisely so you don't have to make that judgement call.   Run them.
- Am I about to argue that the Canvas change is "obvious"   and the code change is "mechanical", so I can just do   both manually? Wrong again — the whole point of   `/spdd-generate` is that the mapping from Canvas to code   is mechanical, so let the tool do it.

If any of those rationalizations crossed your mind, you **must** use the slash commands. Treat the urge to hand-edit as a signal that you are about to violate the workflow.

## Decision framework

Ask: "does this change observable behavior?"

- **Yes — behavior/logic change** (bug in requirements, new business rule, changed user-visible behavior, changed API contract, changed error message a user sees):   1. Run `/spdd-prompt-update` to amend the Canvas.   2. In the **same turn**, run `/spdd-generate` to push      the change into code.   No exceptions. If the change is large enough to warrant   re-deriving the story, run `/spdd-story` and   `/spdd-analysis` first, but `/spdd-generate` is still   the step that touches code.
- **No — non-behavior change** (refactor, rename internal symbol, reformat, restructure with identical observable behavior):   1. Edit the code directly.   2. In the **same turn**, run `/spdd-sync` to reconcile      the Canvas with the new code shape.

If you are unsure which bucket a change falls into, treat it as a behavior change and go through `/spdd-prompt-update` + `/spdd-generate`. The cost of using the workflow when you didn't strictly need to is zero; the cost of skipping it when you needed it is a drifted Canvas.

These pairings are non-optional. The Canvas and the code must land in a consistent state in **every** commit; the slash commands are the only mechanism that guarantees this. If `/spdd-generate` would touch more than the change warrants, still invoke it, then narrow the diff or check with the user — do not bypass it.

## Lifecycle for new work

`/spdd-story` → `/spdd-analysis` → `/spdd-reasons-canvas` → `/spdd-generate` → `/spdd-api-test`

When planning work, identify Canvases under `spdd/prompt/` that are affected, populate the task's references with their paths, and lay out which SPDD operations to perform **before** you start editing anything.
