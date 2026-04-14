# install-skill-cli

A CLI tool for installing skills deployed with the [skills-jar-maven-plugin](https://github.com/webliteca/skills-jar-maven-plugin). Skills are AI assistant guidance bundles published as Maven artifacts.

## Installation

```bash
npm install -g install-skill
```

## Usage

### Install a single skill

By registry name:

```bash
install-skill teavm-lambda
```

By registry name with a specific version:

```bash
install-skill teavm-lambda@0.1.2
```

By Maven coordinates:

```bash
install-skill ca.weblite:teavm-lambda-parent:0.1.2
```

### Install from a `.skills-versions` file

When run with no arguments, `install-skill` reads a `.skills-versions` file from the current directory and installs all listed skills:

```bash
install-skill
```

This is the recommended way to manage skills for a project. Add `.skills-versions` to version control so all contributors share the same skill set.

## `.skills-versions` file

A plain text file listing skills to install, one per line:

```
# Skills for this project
teavm-lambda 0.1.2
my-other-skill 0.3.1
some-skill
```

Format rules:
- One entry per line: `name version` or just `name` (latest version)
- Names can be registry skill names or Maven coordinates (`groupId:artifactId`)
- Lines starting with `#` are comments
- Blank lines are ignored
- The legacy `name@version` format is still accepted but deprecated

Examples of valid entries:

```
# Registry skill name with pinned version
teavm-lambda 0.1.2

# Registry skill name, latest version
teavm-lambda

# Maven coordinates with version
ca.weblite:teavm-lambda-parent 0.1.2

# Maven coordinates, latest version
ca.weblite:teavm-lambda-parent
```

## `.skills-versions.lock` file

After installing from `.skills-versions`, a `.skills-versions.lock` file is created. This JSON file records the resolved Maven coordinates for each skill, enabling reproducible installs across machines and CI.

The lock file behaves similarly to `composer.lock`:

- **First install**: resolves all versions from `.skills-versions` and creates the lock file.
- **Subsequent installs**: reuses locked versions for unchanged entries. Only new or changed entries are re-resolved.
- **Version changes**: if you modify a version in `.skills-versions`, that entry is re-resolved on the next install.
- **Force re-resolution**: use `--update` to ignore the lock file and re-resolve everything.

Add `.skills-versions.lock` to version control to ensure all contributors install the exact same resolved versions.

## Options

| Option | Description |
|--------|-------------|
| `-d <dir>` | Skills installation directory (overrides `--global`) |
| `-g, --global` | Install globally to `~/.claude/skills` (default is local: `./.claude/skills`) |
| `-r <repo>` | Repository URL with optional credentials: `[user:pass@]repositoryUrl` |
| `-u, --update` | Force re-resolution of all skill versions, ignoring the lock file |
| `-h, --help` | Show help message |
| `-V, --version` | Show version |

## Examples

Install all skills from `.skills-versions` to the default directory:

```bash
install-skill
```

Install all skills to a custom directory:

```bash
install-skill -d ./my-skills
```

Install all skills globally:

```bash
install-skill --global
```

Force re-resolution of all versions (like `composer update`):

```bash
install-skill --update
```

Install a single skill from a private repository:

```bash
install-skill my-skill@1.0.0 -r user:pass@https://maven.example.com/releases
```

## How it works

1. **Single-skill mode** (`install-skill <skill>`): resolves the skill from the [skills registry](https://github.com/webliteca/skills-registry) or by Maven coordinates, creates a temporary Maven project, runs the `skills-jar-plugin:install` goal, and copies the result to the target directory.

2. **Batch mode** (`install-skill` with no arguments): reads `.skills-versions`, checks `.skills-versions.lock` for previously resolved versions, resolves any new or changed entries, installs each skill, and updates the lock file.

## Skills registry

Skills are looked up by name in the [skills registry](https://github.com/webliteca/skills-registry). The registry maps human-readable skill names to Maven coordinates. To register a new skill, open a PR against that repository.

## License

Apache License 2.0
