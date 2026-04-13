package ca.weblite.installskill;

import org.apache.maven.cli.MavenCli;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * CLI tool that installs skills deployed with the skills-jar-maven-plugin.
 *
 * <p>Sets up a temporary Maven project with the specified skill artifact as a dependency,
 * runs the skills-jar plugin install goal to resolve and unpack skill bundles,
 * then copies the installed skills to the target directory.</p>
 *
 * <p>When invoked with no arguments, reads from a {@code .skills-versions} file in the
 * current directory and installs all listed skills. A {@code .skills-versions.lock} file
 * records the resolved versions for reproducible installs.</p>
 */
@Command(name = "install-skill",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Install skills deployed with the skills-jar-maven-plugin.")
public class InstallSkillCommand implements Callable<Integer> {

    private static final String PLUGIN_GROUP_ID = "ca.weblite";
    private static final String PLUGIN_ARTIFACT_ID = "skills-jar-plugin";
    private static final String PLUGIN_VERSION = "0.1.1";
    private static final String DEFAULT_VERSION = "RELEASE";
    private static final String DEFAULT_REGISTRY_URL =
            "https://raw.githubusercontent.com/webliteca/skills-registry/main/skills.xml";

    static final String GITHUB_GROUP_ID = "github";
    private static final String DEFAULT_GITHUB_BASE_URL = "https://github.com/";

    @Parameters(index = "0",
            arity = "0..1",
            description = "Skill name, Maven coordinates (groupId:artifactId[:version]), "
                    + "or GitHub repository (owner/repo[@version]). "
                    + "If omitted, reads from .skills-versions file in the current directory. "
                    + "A '/' indicates a GitHub repository skill. "
                    + "A ':' indicates Maven coordinates. "
                    + "Otherwise, the skill is looked up by name in the skills registry. "
                    + "Use @version to pin a specific version.",
            paramLabel = "<skill>")
    private String coordinates;

    @Option(names = {"-r"},
            description = "Repository URL with optional credentials: [user:pass@]repositoryUrl",
            paramLabel = "<repository>")
    private String repository;

    @Option(names = {"-g", "--global"},
            description = "Install globally to ~/.claude/skills (default is local: ./.claude/skills).")
    private boolean global;

    @Option(names = {"-d"},
            description = "Skills installation directory (overrides --global).",
            paramLabel = "<skillsDir>")
    private String skillsDir;

    @Option(names = {"-u", "--update"},
            description = "Force re-resolution of all skill versions, ignoring the lock file.")
    private boolean update;

    /** Working directory for locating .skills-versions. Package-private for testability. */
    Path workingDirectory;

    public static void main(String[] args) {
        String mode = System.getProperty("jdeploy.mode", null);
        if ("gui".equals(mode)) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                javax.swing.JOptionPane.showMessageDialog(
                    null,
                    "Install Skill CLI v1.0.0\n\nThis is a command-line tool.\nRun 'install-skill' in a terminal for usage.",
                    "About Install Skill CLI",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE
                );
                System.exit(0);
            });
            return;
        }
        int exitCode = new CommandLine(new InstallSkillCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (coordinates != null) {
            return installSingleSkill(coordinates);
        } else {
            return installFromVersionsFile();
        }
    }

    // ---- Single-skill install (existing behavior) ----

    /**
     * Installs a single skill given a raw coordinate string.
     * This preserves the original CLI behavior for explicit skill arguments.
     */
    private Integer installSingleSkill(String rawCoordinates) throws Exception {
        SkillCoordinates coords = resolveSkillCoordinates(rawCoordinates);
        if (coords == null) {
            return 1;
        }
        if (GITHUB_GROUP_ID.equals(coords.getGroupId())) {
            return installFromGitHub(coords.getArtifactId(), coords.getVersion());
        }
        return installResolved(coords.getGroupId(), coords.getArtifactId(), coords.getVersion());
    }

    /**
     * Resolves a raw skill specifier to concrete Maven coordinates.
     * Handles both Maven coordinate format and registry name lookup.
     *
     * @return resolved coordinates, or null on error (after printing error message)
     */
    SkillCoordinates resolveSkillCoordinates(String rawCoordinates) {
        String groupId;
        String artifactId;
        String version;
        String name;

        if (rawCoordinates.contains(":")) {
            // Maven coordinates: groupId:artifactId[:version]
            // But first check if the colon is part of a name@version pattern
            // e.g., "com.example:my-lib@1.0" — the @ splits name from version
            String coordinatesPart = rawCoordinates;
            String versionOverride = null;
            int atIdx = rawCoordinates.lastIndexOf('@');
            if (atIdx >= 0) {
                versionOverride = rawCoordinates.substring(atIdx + 1).trim();
                coordinatesPart = rawCoordinates.substring(0, atIdx).trim();
                if (versionOverride.isEmpty()) {
                    System.err.println("Error: Version must not be empty in name@version format.");
                    return null;
                }
            }

            if (coordinatesPart.contains(":")) {
                // Pure Maven coordinates (possibly with @version override)
                String[] parts = coordinatesPart.split(":", -1);
                if (parts.length < 2 || parts.length > 3) {
                    System.err.println("Error: Invalid coordinates format. Expected: groupId:artifactId[:version]");
                    return null;
                }
                groupId = parts[0].trim();
                artifactId = parts[1].trim();
                if (versionOverride != null) {
                    version = versionOverride;
                } else if (parts.length == 3 && !parts[2].trim().isEmpty()) {
                    version = parts[2].trim();
                } else {
                    version = DEFAULT_VERSION;
                }
                name = groupId + ":" + artifactId;
            } else {
                // The colon was actually inside a @version part somehow — treat as registry name
                // This shouldn't normally happen, but handle gracefully
                return resolveRegistryName(coordinatesPart, versionOverride);
            }
        } else if (rawCoordinates.contains("/")) {
            // GitHub format: owner/repo[@version]
            return resolveGitHubCoordinates(rawCoordinates);
        } else {
            // Skill name — look up in registry
            String skillName = rawCoordinates.trim();
            String versionOverride = null;
            int atIdx = skillName.indexOf('@');
            if (atIdx >= 0) {
                versionOverride = skillName.substring(atIdx + 1).trim();
                skillName = skillName.substring(0, atIdx).trim();
                if (versionOverride.isEmpty()) {
                    System.err.println("Error: Version must not be empty in name@version format.");
                    return null;
                }
            }
            return resolveRegistryName(skillName, versionOverride);
        }

        if (groupId.isEmpty() || artifactId.isEmpty()) {
            System.err.println("Error: groupId and artifactId must not be empty.");
            return null;
        }

        return new SkillCoordinates(name, groupId, artifactId, version);
    }

    private SkillCoordinates resolveRegistryName(String skillName, String versionOverride) {
        if (skillName.isEmpty()) {
            System.err.println("Error: Skill name must not be empty.");
            return null;
        }
        System.out.println("Looking up skill '" + skillName + "' in registry...");
        String[] resolved = resolveFromRegistry(skillName);
        if (resolved == null) {
            System.err.println("Error: Skill '" + skillName + "' not found in the skills registry.");
            return null;
        }
        String groupId = resolved[0];
        String artifactId = resolved[1];
        String version;
        if (versionOverride != null) {
            version = versionOverride;
        } else if (resolved[2] != null) {
            version = resolved[2];
        } else {
            version = GITHUB_GROUP_ID.equals(groupId) ? "HEAD" : DEFAULT_VERSION;
        }
        System.out.println("Resolved to " + groupId + ":" + artifactId + ":" + version);
        return new SkillCoordinates(skillName, groupId, artifactId, version);
    }

    /**
     * Resolves a GitHub skill specifier to coordinates.
     * Format: owner/repo[@version]
     */
    private SkillCoordinates resolveGitHubCoordinates(String rawCoordinates) {
        String ghCoord = rawCoordinates.trim();
        String versionOverride = null;
        int atIdx = ghCoord.lastIndexOf('@');
        if (atIdx >= 0) {
            versionOverride = ghCoord.substring(atIdx + 1).trim();
            ghCoord = ghCoord.substring(0, atIdx).trim();
            if (versionOverride.isEmpty()) {
                System.err.println("Error: Version must not be empty in owner/repo@version format.");
                return null;
            }
        }
        // Validate owner/repo format: exactly one slash, non-empty parts
        int slashIdx = ghCoord.indexOf('/');
        if (slashIdx <= 0 || slashIdx >= ghCoord.length() - 1
                || ghCoord.indexOf('/', slashIdx + 1) >= 0) {
            System.err.println("Error: Invalid GitHub skill format. Expected: owner/repo[@version]");
            return null;
        }
        String version = versionOverride != null ? versionOverride : "HEAD";
        System.out.println("GitHub skill: " + ghCoord + " (version: " + version + ")");
        return new SkillCoordinates(ghCoord, GITHUB_GROUP_ID, ghCoord, version);
    }

    /**
     * Installs a single skill given resolved Maven coordinates.
     * Creates a temp Maven project, resolves, and copies the skill to the target directory.
     *
     * @return 0 on success, non-zero on failure
     */
    private int installResolved(String groupId, String artifactId, String version)
            throws Exception {
        // Parse repository option
        String repoUrl = null;
        String repoUser = null;
        String repoPass = null;
        if (repository != null && !repository.isEmpty()) {
            String[] repoParts = parseRepository(repository);
            repoUrl = repoParts[0];
            repoUser = repoParts[1];
            repoPass = repoParts[2];
        }

        // Create temp directory
        Path tempDir = Files.createTempDirectory("install-skill-");
        System.out.println("Setting up temporary Maven project...");

        try {
            // Generate pom.xml in the temp project
            generatePom(tempDir, groupId, artifactId, version, repoUrl);

            // Generate settings.xml if credentials are provided
            boolean hasSettings = false;
            if (repoUser != null) {
                generateSettings(tempDir, repoUser, repoPass);
                hasSettings = true;
            }

            // Run embedded Maven to install skills
            System.out.println("Resolving skill " + groupId + ":" + artifactId + ":" + version + "...");
            int exitCode = runMaven(tempDir, hasSettings);
            if (exitCode != 0) {
                System.err.println("Error: Maven execution failed (exit code " + exitCode + ").");
                return exitCode;
            }

            // Copy installed skills to target directory
            Path installedSkillsDir = tempDir.resolve(".claude").resolve("skills");
            Path targetDir = resolveTargetDir();

            if (!Files.exists(installedSkillsDir) || isDirectoryEmpty(installedSkillsDir)) {
                System.err.println("Error: No skills were found after installation.");
                return 1;
            }

            Files.createDirectories(targetDir);
            copyDirectory(installedSkillsDir, targetDir);
            System.out.println("Skills installed successfully to " + targetDir);

            return 0;
        } finally {
            // Clean up temp directory
            deleteDirectory(tempDir);
        }
    }

    // ---- Batch install from .skills-versions ----

    /**
     * Installs skills from a {@code .skills-versions} file in the working directory.
     * Uses the lock file for reproducible resolution when available.
     */
    private Integer installFromVersionsFile() throws Exception {
        Path cwd = getWorkingDirectory();
        Path versionsPath = SkillVersionsFile.pathIn(cwd);

        if (!SkillVersionsFile.exists(cwd)) {
            System.err.println("Error: No <skill> argument provided and no .skills-versions file "
                    + "found in " + cwd);
            return 1;
        }

        // 1. Parse .skills-versions
        List<SkillVersionsFile.Entry> entries;
        try {
            entries = SkillVersionsFile.parse(versionsPath);
        } catch (IOException e) {
            System.err.println("Error: Failed to parse .skills-versions: " + e.getMessage());
            return 1;
        }

        if (entries.isEmpty()) {
            System.out.println(".skills-versions is empty. Nothing to install.");
            return 0;
        }

        System.out.println("Found " + entries.size() + " skill(s) in .skills-versions");

        // 2. Read existing lock file (if any)
        Path lockPath = SkillLockFile.pathIn(cwd);
        Map<String, SkillLockFile.LockedSkill> locked = SkillLockFile.read(lockPath);

        // 3. Compute resolution plan
        SkillLockFile.ResolutionPlan plan;
        if (update || locked.isEmpty()) {
            // --update or no lock file: resolve everything fresh
            plan = new SkillLockFile.ResolutionPlan(
                    Collections.emptyList(), entries, Collections.emptyList());
        } else {
            plan = SkillLockFile.computeResolutionPlan(entries, locked);
        }

        if (!plan.getReusable().isEmpty()) {
            System.out.println("Using locked versions for " + plan.getReusable().size() + " skill(s)");
        }
        if (!plan.getToResolve().isEmpty()) {
            System.out.println("Resolving " + plan.getToResolve().size() + " skill(s)...");
        }
        if (!plan.getRemoved().isEmpty()) {
            System.out.println("Removing " + plan.getRemoved().size()
                    + " skill(s) no longer in .skills-versions");
        }

        // 4. Build final coordinates map (preserving .skills-versions order)
        Map<String, SkillLockFile.LockedSkill> newLock = new LinkedHashMap<>();

        // First, resolve all new/changed entries
        Map<String, SkillLockFile.LockedSkill> resolvedNew = new LinkedHashMap<>();
        for (SkillVersionsFile.Entry entry : plan.getToResolve()) {
            String rawCoord = entry.getVersion() != null
                    ? entry.getName() + "@" + entry.getVersion()
                    : entry.getName();
            SkillCoordinates coords = resolveSkillCoordinates(rawCoord);
            if (coords == null) {
                System.err.println("Error: Failed to resolve skill '" + entry.getName() + "'.");
                return 1;
            }
            resolvedNew.put(entry.getName(), new SkillLockFile.LockedSkill(
                    entry.getName(), coords.getGroupId(), coords.getArtifactId(),
                    coords.getVersion(), entry.getVersion()));
        }

        // Build lock map in .skills-versions order
        Map<String, SkillLockFile.LockedSkill> reusableMap = new LinkedHashMap<>();
        for (SkillLockFile.LockedSkill ls : plan.getReusable()) {
            reusableMap.put(ls.getName(), ls);
        }
        for (SkillVersionsFile.Entry entry : entries) {
            SkillLockFile.LockedSkill skill = reusableMap.get(entry.getName());
            if (skill == null) {
                skill = resolvedNew.get(entry.getName());
            }
            if (skill != null) {
                newLock.put(entry.getName(), skill);
            }
        }

        // 5. Install each skill sequentially
        int failCount = 0;
        for (SkillLockFile.LockedSkill skill : newLock.values()) {
            int result;
            if (GITHUB_GROUP_ID.equals(skill.getGroupId())) {
                System.out.println("\n--- Installing " + skill.getName()
                        + " (github:" + skill.getArtifactId()
                        + "@" + skill.getVersion() + ") ---");
                result = installFromGitHub(skill.getArtifactId(), skill.getVersion());
            } else {
                System.out.println("\n--- Installing " + skill.getName()
                        + " (" + skill.getGroupId() + ":" + skill.getArtifactId()
                        + ":" + skill.getVersion() + ") ---");
                result = installResolved(
                        skill.getGroupId(), skill.getArtifactId(), skill.getVersion());
            }
            if (result != 0) {
                System.err.println("Error: Failed to install skill '" + skill.getName() + "'.");
                failCount++;
            }
        }

        // 6. Write updated lock file
        SkillLockFile.write(lockPath, newLock);
        System.out.println("\nLock file updated: " + lockPath);

        if (failCount > 0) {
            System.err.println(failCount + " skill(s) failed to install.");
            return 1;
        }

        System.out.println("All " + newLock.size() + " skill(s) installed successfully.");
        return 0;
    }

    /**
     * Resolves the target directory for skill installation based on CLI options.
     */
    private Path resolveTargetDir() {
        String resolvedDir = skillsDir != null ? skillsDir
                : global ? Paths.get(System.getProperty("user.home"), ".claude", "skills").toString()
                : ".claude/skills";
        return Paths.get(resolvedDir).toAbsolutePath().normalize();
    }

    private Path getWorkingDirectory() {
        if (workingDirectory != null) {
            return workingDirectory;
        }
        return Path.of("").toAbsolutePath();
    }

    // ---- GitHub skill installation ----

    /**
     * Installs skills from a GitHub repository.
     * Supports two formats:
     * <ol>
     *   <li>Skills directory: {@code skills/<skill-name>/SKILL.md}</li>
     *   <li>Marketplace: {@code .claude-plugin/marketplace.json} with plugin directories</li>
     * </ol>
     *
     * @param ownerRepo GitHub owner/repo identifier
     * @param version   git ref (tag, branch) or "HEAD" for default branch
     * @return 0 on success, non-zero on failure
     */
    private int installFromGitHub(String ownerRepo, String version) throws Exception {
        String githubBaseUrl = System.getProperty("github.base.url", DEFAULT_GITHUB_BASE_URL);
        String repoUrl = githubBaseUrl + ownerRepo;

        Path tempDir = Files.createTempDirectory("install-skill-github-");
        try {
            Path repoDir = tempDir.resolve("repo");

            // Clone the repository
            System.out.println("Cloning repository " + ownerRepo + "...");
            List<String> cloneCmd = new ArrayList<>();
            cloneCmd.add("git");
            cloneCmd.add("clone");
            cloneCmd.add("--depth");
            cloneCmd.add("1");
            if (version != null && !"HEAD".equals(version)) {
                cloneCmd.add("--branch");
                cloneCmd.add(version);
            }
            cloneCmd.add(repoUrl);
            cloneCmd.add(repoDir.toString());

            ProcessBuilder pb = new ProcessBuilder(cloneCmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("Error: Failed to clone repository " + ownerRepo
                        + ":\n" + output);
                return 1;
            }

            // Determine target directory
            Path targetDir = resolveTargetDir();
            Files.createDirectories(targetDir);

            // Detect format and install
            Path marketplaceJson = repoDir.resolve(".claude-plugin")
                    .resolve("marketplace.json");
            Path skillsDirPath = repoDir.resolve("skills");

            if (Files.isRegularFile(marketplaceJson)) {
                System.out.println("Detected marketplace format.");
                return installGitHubMarketplace(repoDir, targetDir);
            } else if (Files.isDirectory(skillsDirPath)) {
                System.out.println("Detected skills directory format.");
                return installGitHubSkillsDir(skillsDirPath, targetDir);
            } else {
                System.err.println("Error: Repository " + ownerRepo
                        + " does not contain a 'skills/' directory or "
                        + "'.claude-plugin/marketplace.json'.");
                return 1;
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * Installs skills from a repository using the skills directory format.
     * Each subdirectory of {@code skills/} is treated as a skill.
     */
    private int installGitHubSkillsDir(Path skillsSrcDir, Path targetDir) throws IOException {
        boolean installed = false;
        try (Stream<Path> entries = Files.list(skillsSrcDir)) {
            for (Path entry : entries.collect(Collectors.toList())) {
                if (Files.isDirectory(entry)) {
                    Path targetSkillDir = targetDir.resolve(entry.getFileName());
                    Files.createDirectories(targetSkillDir);
                    copyDirectory(entry, targetSkillDir);
                    System.out.println("Installed skill: " + entry.getFileName());
                    installed = true;
                }
            }
        }
        if (!installed) {
            System.err.println("Error: No skill directories found in skills/");
            return 1;
        }
        System.out.println("Skills installed successfully to " + targetDir);
        return 0;
    }

    /**
     * Installs skills from a repository using the Claude plugin marketplace format.
     * Reads {@code .claude-plugin/marketplace.json} for plugin source paths, then
     * copies skill directories from each plugin.
     */
    private int installGitHubMarketplace(Path repoDir, Path targetDir) throws IOException {
        Path marketplaceFile = repoDir.resolve(".claude-plugin").resolve("marketplace.json");
        String json = Files.readString(marketplaceFile);

        List<String> pluginSources = extractMarketplacePluginSources(json);
        if (pluginSources.isEmpty()) {
            System.err.println("Error: No plugins found in marketplace.json");
            return 1;
        }

        boolean installed = false;
        for (String source : pluginSources) {
            Path pluginDir = repoDir.resolve(source).normalize();
            if (!pluginDir.startsWith(repoDir)) {
                System.err.println("Warning: Skipping plugin with source outside repo: "
                        + source);
                continue;
            }

            // Determine skills directory — check plugin.json first, fall back to skills/
            Path pluginSkillsDir = pluginDir.resolve("skills");
            Path pluginJson = pluginDir.resolve(".claude-plugin").resolve("plugin.json");
            if (Files.isRegularFile(pluginJson)) {
                String pjContent = Files.readString(pluginJson);
                String skillsPath = extractSimpleJsonString(pjContent, "skills");
                if (skillsPath != null) {
                    Path resolved = pluginDir.resolve(skillsPath).normalize();
                    if (Files.isDirectory(resolved)) {
                        pluginSkillsDir = resolved;
                    }
                }
            }

            if (Files.isDirectory(pluginSkillsDir)) {
                try (Stream<Path> entries = Files.list(pluginSkillsDir)) {
                    for (Path entry : entries.collect(Collectors.toList())) {
                        if (Files.isDirectory(entry)) {
                            Path targetSkillDir = targetDir.resolve(entry.getFileName());
                            Files.createDirectories(targetSkillDir);
                            copyDirectory(entry, targetSkillDir);
                            System.out.println("Installed skill: " + entry.getFileName());
                            installed = true;
                        }
                    }
                }
            }
        }

        if (!installed) {
            System.err.println("Error: No skills found in marketplace plugins.");
            return 1;
        }
        System.out.println("Skills installed successfully to " + targetDir);
        return 0;
    }

    /**
     * Extracts "source" values from the "plugins" array in marketplace.json.
     */
    private List<String> extractMarketplacePluginSources(String json) {
        List<String> sources = new ArrayList<>();
        int pluginsIdx = json.indexOf("\"plugins\"");
        if (pluginsIdx < 0) return sources;

        int arrayStart = json.indexOf('[', pluginsIdx);
        if (arrayStart < 0) return sources;

        int arrayEnd = findClosing(json, arrayStart);
        if (arrayEnd < 0) return sources;

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);

        int pos = 0;
        while (pos < arrayContent.length()) {
            int objStart = arrayContent.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findClosing(arrayContent, objStart);
            if (objEnd < 0) break;

            String objContent = arrayContent.substring(objStart + 1, objEnd);
            String source = extractSimpleJsonString(objContent, "source");
            if (source != null) {
                sources.add(source);
            }
            pos = objEnd + 1;
        }

        return sources;
    }

    /**
     * Finds the matching closing bracket or brace for an opening one.
     * Handles nested brackets/braces and quoted strings.
     */
    private static int findClosing(String s, int openPos) {
        char openChar = s.charAt(openPos);
        char closeChar = openChar == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // skip escaped character
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == openChar) {
                    depth++;
                } else if (c == closeChar) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Extracts a JSON string value for a given key from a JSON object body.
     */
    private static String extractSimpleJsonString(String objContent, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = objContent.indexOf(search);
        if (keyIdx < 0) return null;
        int colonIdx = objContent.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return null;
        int valueStart = colonIdx + 1;
        while (valueStart < objContent.length()
                && Character.isWhitespace(objContent.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= objContent.length() || objContent.charAt(valueStart) != '"') {
            return null;
        }
        int pos = valueStart + 1;
        while (pos < objContent.length()) {
            char c = objContent.charAt(pos);
            if (c == '\\') {
                pos += 2;
                continue;
            }
            if (c == '"') {
                return objContent.substring(valueStart + 1, pos);
            }
            pos++;
        }
        return null;
    }

    // ---- Registry resolution ----

    /**
     * Resolves a skill name to coordinates via the skills registry.
     *
     * <p>Supports both Maven skills (groupId + artifactId) and GitHub skills (repository).
     * For GitHub skills, returns {@code [GITHUB_GROUP_ID, "owner/repo", version]}.
     *
     * @param skillName the skill name to look up
     * @return array of [groupId, artifactId, version] or null if not found;
     *         version may be null if omitted in the registry
     */
    String[] resolveFromRegistry(String skillName) {
        String registryUrl = System.getProperty("skills.registry.url", DEFAULT_REGISTRY_URL);
        try {
            URL url = new URL(registryUrl);
            try (InputStream is = url.openStream()) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(is);

                NodeList skills = doc.getElementsByTagName("skill");
                for (int i = 0; i < skills.getLength(); i++) {
                    Element skill = (Element) skills.item(i);
                    String name = getElementText(skill, "name");
                    if (skillName.equals(name)) {
                        String repository = getElementText(skill, "repository");
                        if (repository != null && !repository.isEmpty()) {
                            String ver = getElementText(skill, "version");
                            return new String[]{GITHUB_GROUP_ID, repository, ver};
                        }
                        String gid = getElementText(skill, "groupId");
                        String aid = getElementText(skill, "artifactId");
                        String ver = getElementText(skill, "version");
                        return new String[]{gid, aid, ver};
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to query skills registry: " + e.getMessage());
        }
        return null;
    }

    private static String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            String text = nodes.item(0).getTextContent();
            return text != null ? text.trim() : null;
        }
        return null;
    }

    // ---- Repository credential parsing ----

    /**
     * Parses a repository string that may contain credentials.
     * Format: [user:pass@]repositoryUrl
     *
     * @return array of [url, user, password] where user/password may be null
     */
    static String[] parseRepository(String repo) {
        // Look for credentials pattern: text@http:// or text@https://
        int httpAt = repo.indexOf("@http://");
        int httpsAt = repo.indexOf("@https://");
        int atIdx = Math.max(httpAt, httpsAt);

        if (atIdx > 0) {
            String credentials = repo.substring(0, atIdx);
            String url = repo.substring(atIdx + 1);
            int colonIdx = credentials.indexOf(':');
            if (colonIdx > 0) {
                return new String[]{url, credentials.substring(0, colonIdx),
                        credentials.substring(colonIdx + 1)};
            }
        }

        return new String[]{repo, null, null};
    }

    // ---- Maven execution ----

    /**
     * Runs the Maven skills-jar:install goal using the embedded Maven runtime.
     */
    private int runMaven(Path projectDir, boolean hasSettings) {
        System.setProperty("maven.multiModuleProjectDirectory", projectDir.toString());

        List<String> args = new ArrayList<>();
        args.add("-B");
        if (hasSettings) {
            args.add("-s");
            args.add(projectDir.resolve("settings.xml").toString());
        }
        args.add(PLUGIN_GROUP_ID + ":" + PLUGIN_ARTIFACT_ID + ":"
                + PLUGIN_VERSION + ":install");

        MavenCli cli = new MavenCli();
        return cli.doMain(args.toArray(new String[0]),
                projectDir.toString(), System.out, System.err);
    }

    // ---- POM and settings generation ----

    /**
     * Generates a temporary pom.xml with the skill artifact as a dependency.
     */
    private void generatePom(Path projectDir, String groupId, String artifactId,
                             String version, String repoUrl) throws IOException {
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ");
        pom.append("http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n\n");
        pom.append("    <groupId>install-skill-temp</groupId>\n");
        pom.append("    <artifactId>install-skill-temp</artifactId>\n");
        pom.append("    <version>1.0.0</version>\n");
        pom.append("    <packaging>pom</packaging>\n\n");

        // Dependencies
        pom.append("    <dependencies>\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>").append(escapeXml(groupId)).append("</groupId>\n");
        pom.append("            <artifactId>").append(escapeXml(artifactId)).append("</artifactId>\n");
        pom.append("            <version>").append(escapeXml(version)).append("</version>\n");
        pom.append("            <type>pom</type>\n");
        pom.append("        </dependency>\n");
        pom.append("    </dependencies>\n\n");

        // Repositories (if custom repo specified)
        if (repoUrl != null) {
            pom.append("    <repositories>\n");
            pom.append("        <repository>\n");
            pom.append("            <id>custom-repo</id>\n");
            pom.append("            <url>").append(escapeXml(repoUrl)).append("</url>\n");
            pom.append("            <releases>\n");
            pom.append("                <enabled>true</enabled>\n");
            pom.append("            </releases>\n");
            pom.append("            <snapshots>\n");
            pom.append("                <enabled>true</enabled>\n");
            pom.append("            </snapshots>\n");
            pom.append("        </repository>\n");
            pom.append("    </repositories>\n\n");

            // Also add as plugin repository so the skills-jar-plugin can be resolved
            pom.append("    <pluginRepositories>\n");
            pom.append("        <pluginRepository>\n");
            pom.append("            <id>custom-repo</id>\n");
            pom.append("            <url>").append(escapeXml(repoUrl)).append("</url>\n");
            pom.append("            <releases>\n");
            pom.append("                <enabled>true</enabled>\n");
            pom.append("            </releases>\n");
            pom.append("            <snapshots>\n");
            pom.append("                <enabled>true</enabled>\n");
            pom.append("            </snapshots>\n");
            pom.append("        </pluginRepository>\n");
            pom.append("    </pluginRepositories>\n\n");
        }

        // Build with skills-jar-plugin
        pom.append("    <build>\n");
        pom.append("        <plugins>\n");
        pom.append("            <plugin>\n");
        pom.append("                <groupId>").append(PLUGIN_GROUP_ID).append("</groupId>\n");
        pom.append("                <artifactId>").append(PLUGIN_ARTIFACT_ID).append("</artifactId>\n");
        pom.append("                <version>").append(PLUGIN_VERSION).append("</version>\n");
        pom.append("            </plugin>\n");
        pom.append("        </plugins>\n");
        pom.append("    </build>\n");
        pom.append("</project>\n");

        Files.writeString(projectDir.resolve("pom.xml"), pom.toString());
    }

    /**
     * Generates a Maven settings.xml with server credentials for the custom repository.
     */
    private void generateSettings(Path projectDir, String user, String password) throws IOException {
        StringBuilder settings = new StringBuilder();
        settings.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        settings.append("<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\"\n");
        settings.append("          xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        settings.append("          xsi:schemaLocation=\"http://maven.apache.org/SETTINGS/1.0.0 ");
        settings.append("http://maven.apache.org/xsd/settings-1.0.0.xsd\">\n");
        settings.append("    <servers>\n");
        settings.append("        <server>\n");
        settings.append("            <id>custom-repo</id>\n");
        settings.append("            <username>").append(escapeXml(user)).append("</username>\n");
        settings.append("            <password>").append(escapeXml(password)).append("</password>\n");
        settings.append("        </server>\n");
        settings.append("    </servers>\n");
        settings.append("</settings>\n");

        Files.writeString(projectDir.resolve("settings.xml"), settings.toString());
    }

    // ---- File system utilities ----

    /**
     * Recursively copies a directory tree from source to target.
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Path targetPath = target.resolve(source.relativize(dir));
                Files.createDirectories(targetPath);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path targetPath = target.resolve(source.relativize(file));
                Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Recursively deletes a directory tree.
     */
    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // best-effort cleanup
                        }
                    });
        } catch (IOException e) {
            // best-effort cleanup
        }
    }

    /**
     * Checks if a directory is empty.
     */
    private boolean isDirectoryEmpty(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }

    /**
     * Escapes special XML characters in a string.
     */
    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
