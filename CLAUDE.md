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
