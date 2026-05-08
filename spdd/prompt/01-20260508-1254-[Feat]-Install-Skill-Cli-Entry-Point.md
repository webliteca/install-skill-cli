---
bootstrap: true
generated_at: 2026-05-08T12:54:00-07:00
name: install-skill CLI Entry Point
description: Top-level PicoCLI command, dispatch between single-skill and batch install, output directory selection, and GUI mode stub.
type: feature
---

# REASONS Canvas: install-skill CLI Entry Point

## R · Requirements
- Provide a single executable named `install-skill` that installs AI assistant
  skills published with the `skills-jar-maven-plugin`, resolved either from a
  registry name, Maven coordinates, GitHub repository, or a `.skills-versions`
  manifest in the current directory.
- When invoked with a `<skill>` argument, install that one skill
  (`InstallSkillCommand.java:117-122`).
- When invoked with no `<skill>` argument, read `.skills-versions` from the
  working directory and install every entry (`InstallSkillCommand.java:117-122`,
  delegated to `installFromVersionsFile()` at lines 340-458).
- Surface user-facing options:
  - `-d <dir>`: explicit skills install directory
    (`InstallSkillCommand.java:86-89`).
  - `-g, --global`: install to `~/.claude/skills` (default is local
    `./.claude/skills`) (`InstallSkillCommand.java:82-84`).
  - `-r <repository>`: Maven repository URL with optional `[user:pass@]`
    credentials, used only by the Maven install path
    (`InstallSkillCommand.java:77-80`).
  - `-u, --update`: ignore `.skills-versions.lock` and re-resolve everything
    (`InstallSkillCommand.java:91-93`).
  - `-h, --help`, `-V, --version`: provided automatically by
    `mixinStandardHelpOptions = true` (`InstallSkillCommand.java:50`).
- Resolve the target install directory in this priority:
  `-d` > `--global` (`~/.claude/skills`) > local `./.claude/skills`, always
  normalized to an absolute path (`InstallSkillCommand.java:463-468`).
- When launched with the system property `jdeploy.mode=gui`, show a Swing
  message dialog explaining this is a CLI tool, then exit cleanly without
  parsing arguments (`InstallSkillCommand.java:99-111`).
- Process exit code is the integer returned by `call()`
  (`InstallSkillCommand.java:112-113`).
- Definition of Done is exercised by:
  - `InstallSkillIT.installTeavmLambdaSkill`
    (`src/test/java/ca/weblite/installskill/InstallSkillIT.java:128-175`) —
    confirms the CLI installs to `-d <dir>`, produces a per-skill subdirectory,
    a populated `SKILL.md`, and a `.skill-manifest.json`.
  - `InstallSkillIT.installTeavmLambdaSkillWithDefaultVersion`
    (`InstallSkillIT.java:177-194`) — confirms running with no version still
    installs.
  - `GitHubInstallIT` — confirms `-d <dir>` works for the GitHub install path.

## E · Entities
- **`InstallSkillCommand`** (`InstallSkillCommand.java:53`) — the PicoCLI
  `Callable<Integer>`. Invariants:
  - `coordinates` is the single optional positional parameter
    (`InstallSkillCommand.java:65-75`).
  - `call()` is a strict dispatcher: if `coordinates != null`, run single-skill
    flow; otherwise run batch flow (`InstallSkillCommand.java:117-122`).
  - `workingDirectory` is package-private only so tests can override the cwd
    used to locate `.skills-versions` (`InstallSkillCommand.java:95-96`,
    `getWorkingDirectory()` at lines 470-475).
- **Constants** that pin external behavior (`InstallSkillCommand.java:55-63`):
  - `PLUGIN_GROUP_ID = "ca.weblite"`, `PLUGIN_ARTIFACT_ID = "skills-jar-plugin"`,
    `PLUGIN_VERSION = "0.1.1"` — the Maven plugin used for unpacking.
  - `DEFAULT_VERSION = "RELEASE"` — fallback version when none specified.
  - `DEFAULT_REGISTRY_URL` — points at `webliteca/skills-registry` `skills.xml`.
  - `GITHUB_GROUP_ID = "github"` (package-private) and
    `DEFAULT_GITHUB_BASE_URL = "https://github.com/"`.

## A · Approach
- One Java class houses the CLI. PicoCLI handles argument parsing and help/usage
  output via `mixinStandardHelpOptions` (`InstallSkillCommand.java:49-52`).
- Dispatch is intentionally binary at `call()`: the presence/absence of the
  positional argument selects single-skill vs. batch — there is no "install one
  skill from `.skills-versions`" sub-flow (`InstallSkillCommand.java:117-122`).
- The GUI message-dialog branch exists so the same artifact can be launched as
  a desktop app via jDeploy without confusing users — it short-circuits before
  PicoCLI runs (`InstallSkillCommand.java:99-111`).
- Output directory resolution is centralized in `resolveTargetDir()` so both
  the Maven and GitHub install paths share identical placement rules
  (`InstallSkillCommand.java:463-468`).
- Trade-off: the `-r` flag is wired only into the Maven install pipeline
  (`installResolved` reads `repository`, `installFromGitHub` does not). GitHub
  installs use plain `git clone` and have no equivalent credential surface.

## S · Structure
- `src/main/java/ca/weblite/installskill/InstallSkillCommand.java` — the entry
  point class, all option fields, dispatcher, and target directory resolver.
- `pom.xml` — declares the PicoCLI and `maven-embedder` dependencies and
  configures the shaded jar (`pom.xml`).
- `package.json` — declares the npm/jDeploy command name `install-skill` and
  the main class for the launcher.

## O · Operations

### 1. Configure CLI Class — `InstallSkillCommand`
File: `src/main/java/ca/weblite/installskill/InstallSkillCommand.java`

1. Responsibility: Top-level PicoCLI command that parses arguments and
   dispatches to the correct install flow.
2. Fields / Attributes:
   - `coordinates: String` — optional positional `<skill>` argument
     (`InstallSkillCommand.java:65-75`).
   - `repository: String` — `-r` Maven repository with optional credentials
     (`InstallSkillCommand.java:77-80`).
   - `global: boolean` — `-g/--global` flag (`InstallSkillCommand.java:82-84`).
   - `skillsDir: String` — `-d` explicit install directory
     (`InstallSkillCommand.java:86-89`).
   - `update: boolean` — `-u/--update` flag (`InstallSkillCommand.java:91-93`).
   - `workingDirectory: Path` — package-private override for tests
     (`InstallSkillCommand.java:95-96`).
3. Methods:
   - `main(String[])`: void
     - Logic: if `System.getProperty("jdeploy.mode")` equals `"gui"`, schedule a
       Swing JOptionPane dialog and `System.exit(0)` after dismissal; otherwise
       run `new CommandLine(new InstallSkillCommand()).execute(args)` and
       `System.exit(exitCode)` (`InstallSkillCommand.java:98-114`).
   - `call(): Integer` (override of `Callable`)
     - Logic: if `coordinates != null`, return `installSingleSkill(coordinates)`
       (`InstallSkillCommand.java:118-119`); else return
       `installFromVersionsFile()` (`InstallSkillCommand.java:120-121`).
   - `resolveTargetDir(): Path`
     - Logic: choose `skillsDir` if set, else `~/.claude/skills` if `global`,
       else `./.claude/skills`; convert to absolute and normalized
       (`InstallSkillCommand.java:463-468`).
   - `getWorkingDirectory(): Path`
     - Logic: return `workingDirectory` if non-null (test override), else
       `Path.of("").toAbsolutePath()` (`InstallSkillCommand.java:470-475`).
4. Constraints / Invariants:
   - `call()` never inspects flags other than the coordinates string — flags
     are read inside the install methods. This keeps dispatch independent of
     option semantics.
   - The Swing dialog branch must run only when `jdeploy.mode=gui`; in any
     other mode the GUI path is dead code (`InstallSkillCommand.java:99-111`).

## N · Norms
- All user-facing logging goes to `System.out` for progress and `System.err` for
  errors. No SLF4J or other logger is used.
- Error messages are short, prefixed with `"Error: "`, and end without a
  trailing period in most cases (e.g.
  `InstallSkillCommand.java:227, 264, 310`).
- Public API surface is intentionally tiny — the CLI command class itself is
  the only entry point; internal helpers are package-private or private.

## S · Safeguards
- The empty-string positional argument is treated identically to "no argument"
  by PicoCLI's `arity = "0..1"` (`InstallSkillCommand.java:66`); when present
  but invalid the downstream resolvers print specific error messages and
  return `null`/non-zero exit codes.
- On Maven failure the CLI returns the Maven exit code, surfacing the failure
  to the shell (`InstallSkillCommand.java:308-311`).
- Temp directories created during install are deleted in `finally` blocks
  (`InstallSkillCommand.java:328-331, 552-554`); failures during cleanup are
  swallowed as best-effort because the user-visible operation has already
  completed (`InstallSkillCommand.java:968-984`).
