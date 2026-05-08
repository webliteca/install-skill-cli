---
bootstrap: true
generated_at: 2026-05-08T12:54:00-07:00
name: Maven-Based Skill Installation
description: Generates a temp Maven project, runs the embedded Maven CLI to invoke skills-jar-plugin:install, and copies installed skills to the target directory. Supports custom repositories with credentials.
type: feature
---

# REASONS Canvas: Maven-Based Skill Installation

## R · Requirements
- Given resolved Maven coordinates (`groupId:artifactId:version`), install the
  skill bundle into the target directory by:
  1. Creating a temp directory.
  2. Generating a `pom.xml` declaring the skill artifact as a dependency and
     `skills-jar-plugin` in the build.
  3. Optionally generating a `settings.xml` with a server credential block.
  4. Running `<plugin-coords>:install` via the embedded Maven CLI.
  5. Copying everything under `<temp>/.claude/skills/` to the target directory.
  6. Deleting the temp directory whether the install succeeded or failed.
- Pin the plugin coordinates in code: `ca.weblite:skills-jar-plugin:0.1.1`
  (`InstallSkillCommand.java:55-57`).
- Honor the optional `-r [user:pass@]<url>` Maven repository so users can pull
  skills from a private repo. When credentials are present, generate a
  `settings.xml` in the temp project and pass it to Maven via `-s`
  (`InstallSkillCommand.java:280-308, 826-842, 919-938`).
- When `-r` is set, the custom repository is registered as both a regular
  `<repositories>` entry and a `<pluginRepositories>` entry, so the
  `skills-jar-plugin` itself can be resolved from the same repo
  (`InstallSkillCommand.java:874-901`).
- After Maven runs, fail loudly if the `.claude/skills` output directory is
  missing or empty: print
  `Error: No skills were found after installation.` and return non-zero
  (`InstallSkillCommand.java:315-321`).
- On success, print the absolute install path:
  `Skills installed successfully to <path>`
  (`InstallSkillCommand.java:325`).
- Definition of Done is exercised by `InstallSkillIT`
  (`src/test/java/ca/weblite/installskill/InstallSkillIT.java:128-229`):
  - `installTeavmLambdaSkill` — Maven coords with explicit version.
  - `installTeavmLambdaSkillWithDefaultVersion` — coords without version,
    relying on `RELEASE`.
  - `installSkillByRegistryName` — composes with the registry resolver.
  Each verifies the skill subdirectory exists, `SKILL.md` is non-empty, and a
  `.skill-manifest.json` is written.

## E · Entities
- **Temporary Maven project** at
  `Files.createTempDirectory("install-skill-")`
  (`InstallSkillCommand.java:292`). Owned by the install call; deleted in a
  `finally` block (`InstallSkillCommand.java:328-331`). Contains:
  - `pom.xml` — generated synchronously per-install
    (`InstallSkillCommand.java:849-916`).
  - `settings.xml` — generated only when credentials are supplied
    (`InstallSkillCommand.java:921-938`).
  - `.claude/skills/<artifactId>/…` — written by the `skills-jar-plugin`.
- **Repository credential triple** `[url, user, password]` returned from
  `parseRepository(String)` (`InstallSkillCommand.java:803-820`). Invariants:
  - If no `@http://` or `@https://` is found, the URL is returned untouched
    and `user`/`password` are both `null` (`InstallSkillCommand.java:817-819`).
  - When credentials are present, the colon between user and password is
    required (`InstallSkillCommand.java:812-815`).

## A · Approach
- Synthesize a Maven project per install rather than relying on Aether or a
  raw HTTP client. This keeps the resolution semantics exactly aligned with
  Maven's (mirrors, settings.xml, repository policies) and reuses the
  `skills-jar-plugin` machinery for unpacking.
- Use the bundled `MavenCli` (`org.apache.maven.cli.MavenCli`) so the JAR is
  fully self-contained and works without `mvn` on the user's PATH
  (`InstallSkillCommand.java:826-842`).
- The pom marks the skill artifact with `<type>pom</type>`
  (`InstallSkillCommand.java:869`), matching the conventions of skill
  publication (the plugin pulls the skills classifier).
- Trade-off: per-install temp projects mean cold-cache pulls every time
  unless Maven's local repo is reused (which it is, since `MavenCli` honors
  `~/.m2`). Lock-file behavior across runs is layered on top in the batch
  Canvas — this Canvas always does a fresh install per call.

## S · Structure
- `src/main/java/ca/weblite/installskill/InstallSkillCommand.java` —
  `installResolved`, `parseRepository`, `runMaven`, `generatePom`,
  `generateSettings`, `escapeXml`, plus the file-system helpers
  `copyDirectory`, `deleteDirectory`, `isDirectoryEmpty`.
- `pom.xml` — declares `org.apache.maven:maven-embedder` and the maven shaded
  jar configuration that bundles dependencies into the runnable artifact.

## O · Operations

### 1. Parse Credentialed Repository — `InstallSkillCommand.parseRepository`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Split a `[user:pass@]url` string into `[url, user,
   password]`.
2. Methods:
   - `parseRepository(String): String[]`
     (`InstallSkillCommand.java:803-820`)
     - Logic:
       1. Find the last occurrence of `@http://` or `@https://`
          (`InstallSkillCommand.java:805-807`) — protocol-anchored to avoid
          splitting on `@` characters that occur inside a username.
       2. If found, split into credentials and url; split credentials again
          on `:` (`InstallSkillCommand.java:809-816`).
       3. Otherwise, return `[repo, null, null]`
          (`InstallSkillCommand.java:819`).
3. Constraints / Invariants:
   - Static and pure — used only by `installResolved` but exposed
     package-private to make the parsing easy to test.

### 2. Generate Pom — `InstallSkillCommand.generatePom`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Write a `pom.xml` that pulls the skill as a `pom`
   dependency and registers `skills-jar-plugin` for execution.
2. Methods:
   - `generatePom(Path, String, String, String, String): void`
     (`InstallSkillCommand.java:849-916`)
     - Logic:
       1. Append the standard XML header and the temp project's coordinates
          (`InstallSkillCommand.java:852-861`).
       2. Append a `<dependencies>` block with the skill GAV and
          `<type>pom</type>` (`InstallSkillCommand.java:864-871`).
       3. If `repoUrl != null`, append `<repositories>` and
          `<pluginRepositories>` blocks pointing at it
          (`InstallSkillCommand.java:874-901`).
       4. Append the `<build><plugins>` block declaring `skills-jar-plugin`
          at the pinned version (`InstallSkillCommand.java:904-912`).
       5. Write the file (`InstallSkillCommand.java:915`).
3. Constraints / Invariants:
   - All user-provided values flow through `escapeXml`
     (`InstallSkillCommand.java:998-1004`) before being written.

### 3. Generate Settings — `InstallSkillCommand.generateSettings`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Emit a `settings.xml` carrying the repository credentials.
2. Methods:
   - `generateSettings(Path, String, String): void`
     (`InstallSkillCommand.java:921-938`)
     - Logic: append a fixed `<settings>` skeleton with one `<server>` whose
       `<id>` is `custom-repo` (matching the id used in the pom), then write
       the file (`InstallSkillCommand.java:922-937`).
3. Constraints / Invariants:
   - The `<id>custom-repo</id>` literal must stay in lock-step with the same
     id used by `generatePom`; changing either alone breaks credential
     resolution.

### 4. Run Embedded Maven — `InstallSkillCommand.runMaven`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Invoke `MavenCli.doMain` against the temp project to run
   `skills-jar-plugin:install`.
2. Methods:
   - `runMaven(Path, boolean): int`
     (`InstallSkillCommand.java:826-842`)
     - Logic:
       1. Set `maven.multiModuleProjectDirectory` system property to the
          temp project path — required by `MavenCli`
          (`InstallSkillCommand.java:828`).
       2. Build the args list: `-B`, optionally `-s <settings.xml>` if
          `hasSettings`, then the explicit goal
          `ca.weblite:skills-jar-plugin:0.1.1:install`
          (`InstallSkillCommand.java:830-837`).
       3. Call `new MavenCli().doMain(args, projectDir.toString(),
          System.out, System.err)` and return its exit code
          (`InstallSkillCommand.java:839-841`).
3. Constraints / Invariants:
   - Forces batch mode (`-B`) so Maven never prompts for input.
   - Always runs from the temp project directory so Maven's
     working-directory-sensitive lookups behave predictably.

### 5. Orchestrate Install — `InstallSkillCommand.installResolved`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Wire the helpers above together for one resolved skill.
2. Methods:
   - `installResolved(String, String, String): int`
     (`InstallSkillCommand.java:278-332`)
     - Logic:
       1. If `repository` is set, call `parseRepository` to extract URL/
          user/password (`InstallSkillCommand.java:281-289`).
       2. Create the temp directory and print a progress line
          (`InstallSkillCommand.java:292-293`).
       3. Call `generatePom` (`InstallSkillCommand.java:297`); if
          credentials are present, call `generateSettings` and remember to
          pass `-s` (`InstallSkillCommand.java:300-304`).
       4. Print "Resolving …" and call `runMaven`; bail out on non-zero
          exit (`InstallSkillCommand.java:307-312`).
       5. Resolve the target directory via `resolveTargetDir()` and verify
          `<temp>/.claude/skills` is a non-empty directory; otherwise return
          1 (`InstallSkillCommand.java:315-321`).
       6. Create the target directory and `copyDirectory` from the temp
          location (`InstallSkillCommand.java:323-325`).
       7. Always `deleteDirectory(tempDir)` in `finally`
          (`InstallSkillCommand.java:328-331`).
3. Constraints / Invariants:
   - The exit codes from `runMaven` propagate unchanged so users can react
     to specific Maven failure modes.
   - The temp directory is the only mutable state outside the target
     directory; cleanup is best-effort.

### 6. Copy & Cleanup Helpers
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Filesystem utilities used by every install path.
2. Methods:
   - `copyDirectory(Path, Path): void` — recursive copy that preserves the
     directory shape and overwrites existing files
     (`InstallSkillCommand.java:945-963`).
   - `deleteDirectory(Path): void` — best-effort recursive delete in
     reverse-sorted order; swallows IO errors so cleanup failures don't mask
     the real result (`InstallSkillCommand.java:968-984`).
   - `isDirectoryEmpty(Path): boolean` — used to detect zero-skill installs
     (`InstallSkillCommand.java:989-993`).
   - `escapeXml(String): String` — small string-replacement helper for the
     pom and settings generators (`InstallSkillCommand.java:998-1004`).
3. Constraints / Invariants:
   - `copyDirectory` uses `StandardCopyOption.REPLACE_EXISTING`
     (`InstallSkillCommand.java:959`), so re-installing a skill replaces its
     files in place.

## N · Norms
- The Maven plugin GAV is hard-coded in three constants
  (`InstallSkillCommand.java:55-57`); upgrading the plugin requires editing
  those constants, not configuration. `[INFERRED]` whether this is the long-
  term plan — it matches the current code.
- All emitted XML files run through `escapeXml`
  (`InstallSkillCommand.java:998-1004`) before user values are inserted.

## S · Safeguards
- The temp project's existence is bracketed by `try/finally` so a thrown
  exception does not leak directories under `/tmp`
  (`InstallSkillCommand.java:295-331`).
- Maven failures (non-zero exit code) abort before any copy occurs
  (`InstallSkillCommand.java:309-312`).
- An empty post-install `.claude/skills` directory is treated as a failure,
  not a silent success (`InstallSkillCommand.java:318-321`).
- Repository credentials are written to a temp `settings.xml` and removed
  with the rest of the temp project; they never land in the user's
  `~/.m2/settings.xml` (`InstallSkillCommand.java:300-304, 921-938,
  328-331`).
