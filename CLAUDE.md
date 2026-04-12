# CLAUDE.md

This file provides guidance for Claude Code when working in this repository.

## Repository overview

This is the **install-skill CLI** — a Java command-line tool (PicoCLI + embedded Maven) for installing AI assistant skills deployed with the skills-jar-maven-plugin. Skills are resolved from the [skills registry](https://github.com/webliteca/skills-registry) or by Maven coordinates.

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
- `resolveSkillCoordinates(String)` — parses raw input (registry name, `name@version`, or Maven coords) into `SkillCoordinates`
- `resolveRegistryName(String, String)` — looks up a skill name in the XML registry
- `installResolved(String, String, String)` — creates temp Maven project and installs a single resolved skill
- `installFromVersionsFile()` — batch flow: parse versions file, compute resolution plan against lock, resolve new entries, install all, write lock

### Lock file resolution plan

`SkillLockFile.computeResolutionPlan()` compares `.skills-versions` entries against `.skills-versions.lock`:
- **Reusable**: entry exists in lock and `requestedVersion` matches — skip resolution
- **To resolve**: new entry or `requestedVersion` changed — needs fresh resolution
- **Removed**: in lock but not in `.skills-versions` — dropped from updated lock

## `.skills-versions` format

```
# Comment
skill-name@0.1.0
skill-name
com.example:my-lib@1.0
```

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

## Distribution

The CLI is distributed via npm/jDeploy as the `install-skill` package with native bundles for macOS, Windows, and Linux.
