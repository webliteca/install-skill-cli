package ca.weblite.installskill;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for installing skills from GitHub repositories.
 *
 * <p>Creates local git repositories as fixtures and tests both the skills
 * directory format and the Claude plugin marketplace format.</p>
 *
 * <p>Requires Git on PATH.</p>
 */
class GitHubInstallIT {

    private static Path reposBase;
    private static final String TEST_OWNER = "testowner";

    private static final String SKILL_MD_CONTENT =
            "---\n"
            + "name: test-skill\n"
            + "description: A test skill for integration testing.\n"
            + "---\n"
            + "\n"
            + "# Test Skill\n"
            + "\n"
            + "This is a test skill.\n";

    private static final String MARKETPLACE_JSON =
            "{\n"
            + "  \"name\": \"test-marketplace\",\n"
            + "  \"description\": \"Test marketplace\",\n"
            + "  \"plugins\": [\n"
            + "    {\n"
            + "      \"name\": \"test-plugin\",\n"
            + "      \"version\": \"1.0.0\",\n"
            + "      \"source\": \"./plugins/test-plugin\",\n"
            + "      \"category\": \"testing\"\n"
            + "    }\n"
            + "  ]\n"
            + "}\n";

    private static final String PLUGIN_JSON =
            "{\n"
            + "  \"name\": \"test-plugin\",\n"
            + "  \"version\": \"1.0.0\",\n"
            + "  \"description\": \"A test plugin\",\n"
            + "  \"skills\": \"./skills/\"\n"
            + "}\n";

    private Path skillsDir;

    @BeforeAll
    static void createFixtureRepos() throws Exception {
        reposBase = Files.createTempDirectory("github-install-test-repos-");
        createSkillsDirRepo();
        createMarketplaceRepo();
    }

    @AfterAll
    static void cleanFixtureRepos() throws IOException {
        if (reposBase != null && Files.exists(reposBase)) {
            deleteDir(reposBase);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = Files.createTempDirectory("github-install-it-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (skillsDir != null && Files.exists(skillsDir)) {
            deleteDir(skillsDir);
        }
    }

    // ---- Skills directory format tests ----

    @Test
    void installFromSkillsDirectoryFormat() throws IOException {
        System.setProperty("github.base.url", reposBase.toString() + "/");
        try {
            int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                    TEST_OWNER + "/skills-dir-repo",
                    "-d", skillsDir.toString()
            );

            assertEquals(0, exitCode, "Install from skills directory repo should succeed");

            // Verify the skill was installed
            Path skillDir = skillsDir.resolve("test-skill");
            assertTrue(Files.isDirectory(skillDir),
                    "Skill directory should exist: " + skillDir);

            Path skillMd = skillDir.resolve("SKILL.md");
            assertTrue(Files.isRegularFile(skillMd),
                    "SKILL.md should exist in installed skill directory");

            String content = Files.readString(skillMd);
            assertTrue(content.contains("test-skill"),
                    "SKILL.md should contain the skill name");
        } finally {
            System.clearProperty("github.base.url");
        }
    }

    @Test
    void installFromSkillsDirectoryFormatWithVersion() throws IOException {
        System.setProperty("github.base.url", reposBase.toString() + "/");
        try {
            int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                    TEST_OWNER + "/skills-dir-repo@v1.0",
                    "-d", skillsDir.toString()
            );

            assertEquals(0, exitCode, "Install with version tag should succeed");

            Path skillDir = skillsDir.resolve("test-skill");
            assertTrue(Files.isDirectory(skillDir),
                    "Skill directory should exist for tagged version");
        } finally {
            System.clearProperty("github.base.url");
        }
    }

    // ---- Marketplace format tests ----

    @Test
    void installFromMarketplaceFormat() throws IOException {
        System.setProperty("github.base.url", reposBase.toString() + "/");
        try {
            int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                    TEST_OWNER + "/marketplace-repo",
                    "-d", skillsDir.toString()
            );

            assertEquals(0, exitCode, "Install from marketplace repo should succeed");

            // Verify the skill from the marketplace plugin was installed
            Path skillDir = skillsDir.resolve("test-plugin-skill");
            assertTrue(Files.isDirectory(skillDir),
                    "Skill directory should exist: " + skillDir);

            Path skillMd = skillDir.resolve("SKILL.md");
            assertTrue(Files.isRegularFile(skillMd),
                    "SKILL.md should exist in marketplace skill directory");
        } finally {
            System.clearProperty("github.base.url");
        }
    }

    // ---- Registry resolution tests ----

    @Test
    void installGitHubSkillByRegistryName() throws IOException {
        // Create a registry XML with a GitHub skill entry
        Path registryFile = Files.createTempFile("skills-registry-", ".xml");
        try {
            String registryXml =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<skills>\n"
                    + "  <skill>\n"
                    + "    <name>gh-test-skill</name>\n"
                    + "    <repository>" + TEST_OWNER + "/skills-dir-repo</repository>\n"
                    + "    <version>v1.0</version>\n"
                    + "    <description>A GitHub skill in the registry</description>\n"
                    + "  </skill>\n"
                    + "</skills>\n";
            Files.writeString(registryFile, registryXml);

            System.setProperty("skills.registry.url", registryFile.toUri().toString());
            System.setProperty("github.base.url", reposBase.toString() + "/");
            try {
                int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                        "gh-test-skill",
                        "-d", skillsDir.toString()
                );

                assertEquals(0, exitCode,
                        "Install GitHub skill by registry name should succeed");

                Path skillDir = skillsDir.resolve("test-skill");
                assertTrue(Files.isDirectory(skillDir),
                        "Skill directory should exist after registry-based GitHub install");

                Path skillMd = skillDir.resolve("SKILL.md");
                assertTrue(Files.isRegularFile(skillMd),
                        "SKILL.md should exist after registry-based GitHub install");
            } finally {
                System.clearProperty("skills.registry.url");
                System.clearProperty("github.base.url");
            }
        } finally {
            Files.deleteIfExists(registryFile);
        }
    }

    @Test
    void installGitHubSkillByRegistryNameWithVersionOverride() throws IOException {
        // Registry has no version; user overrides with @v1.0
        Path registryFile = Files.createTempFile("skills-registry-", ".xml");
        try {
            String registryXml =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<skills>\n"
                    + "  <skill>\n"
                    + "    <name>gh-skill-no-ver</name>\n"
                    + "    <repository>" + TEST_OWNER + "/skills-dir-repo</repository>\n"
                    + "    <description>GitHub skill without version</description>\n"
                    + "  </skill>\n"
                    + "</skills>\n";
            Files.writeString(registryFile, registryXml);

            System.setProperty("skills.registry.url", registryFile.toUri().toString());
            System.setProperty("github.base.url", reposBase.toString() + "/");
            try {
                int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                        "gh-skill-no-ver@v1.0",
                        "-d", skillsDir.toString()
                );

                assertEquals(0, exitCode,
                        "Install GitHub skill with version override should succeed");

                Path skillDir = skillsDir.resolve("test-skill");
                assertTrue(Files.isDirectory(skillDir),
                        "Skill directory should exist with version override");
            } finally {
                System.clearProperty("skills.registry.url");
                System.clearProperty("github.base.url");
            }
        } finally {
            Files.deleteIfExists(registryFile);
        }
    }

    // ---- Batch install (.skills-versions) tests ----

    @Test
    void installGitHubSkillFromVersionsFile() throws IOException {
        System.setProperty("github.base.url", reposBase.toString() + "/");
        try {
            // Create .skills-versions with a GitHub skill entry
            Path versionsFile = skillsDir.resolve(".skills-versions");
            Files.writeString(versionsFile,
                    TEST_OWNER + "/skills-dir-repo v1.0\n");

            Path installTarget = skillsDir.resolve("output");
            Files.createDirectories(installTarget);

            InstallSkillCommand cmd = new InstallSkillCommand();
            cmd.workingDirectory = skillsDir;
            int exitCode = new CommandLine(cmd).execute(
                    "-d", installTarget.toString()
            );

            assertEquals(0, exitCode,
                    "Batch install with GitHub skill should succeed");

            // Verify the skill was installed
            Path skillDir = installTarget.resolve("test-skill");
            assertTrue(Files.isDirectory(skillDir),
                    "Skill directory should exist after batch install");

            // Verify lock file was created with GitHub coordinates
            Path lockFile = skillsDir.resolve(".skills-versions.lock");
            assertTrue(Files.isRegularFile(lockFile),
                    ".skills-versions.lock should be created");

            String lockContent = Files.readString(lockFile);
            assertTrue(lockContent.contains("\"github\""),
                    "Lock file should contain github as groupId");
            assertTrue(lockContent.contains(TEST_OWNER + "/skills-dir-repo"),
                    "Lock file should contain the repo name");
        } finally {
            System.clearProperty("github.base.url");
        }
    }

    @Test
    void installNonExistentGitHubRepoFails() {
        System.setProperty("github.base.url", reposBase.toString() + "/");
        try {
            int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                    TEST_OWNER + "/nonexistent-repo",
                    "-d", skillsDir.toString()
            );

            assertEquals(1, exitCode,
                    "Install from nonexistent repo should fail");
        } finally {
            System.clearProperty("github.base.url");
        }
    }

    // ---- Fixture setup ----

    /**
     * Creates a local git repo with the skills directory format:
     * <pre>
     * skills/
     *   test-skill/
     *     SKILL.md
     * </pre>
     */
    private static void createSkillsDirRepo() throws Exception {
        Path repoDir = reposBase.resolve(TEST_OWNER).resolve("skills-dir-repo");
        Files.createDirectories(repoDir);

        // Create skills directory structure
        Path skillDir = repoDir.resolve("skills").resolve("test-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), SKILL_MD_CONTENT);

        // Initialize git repo and commit
        runGit(repoDir, "init");
        runGit(repoDir, "config", "commit.gpgsign", "false");
        runGit(repoDir, "config", "user.name", "Test");
        runGit(repoDir, "config", "user.email", "test@test.com");
        runGit(repoDir, "add", ".");
        runGit(repoDir, "commit", "-m", "Initial commit");
        runGit(repoDir, "tag", "v1.0");
    }

    /**
     * Creates a local git repo with the marketplace format:
     * <pre>
     * .claude-plugin/
     *   marketplace.json
     * plugins/
     *   test-plugin/
     *     .claude-plugin/
     *       plugin.json
     *     skills/
     *       test-plugin-skill/
     *         SKILL.md
     * </pre>
     */
    private static void createMarketplaceRepo() throws Exception {
        Path repoDir = reposBase.resolve(TEST_OWNER).resolve("marketplace-repo");
        Files.createDirectories(repoDir);

        // Create marketplace structure
        Path claudePlugin = repoDir.resolve(".claude-plugin");
        Files.createDirectories(claudePlugin);
        Files.writeString(claudePlugin.resolve("marketplace.json"), MARKETPLACE_JSON);

        // Create plugin
        Path pluginDir = repoDir.resolve("plugins").resolve("test-plugin");
        Path pluginClaudeDir = pluginDir.resolve(".claude-plugin");
        Files.createDirectories(pluginClaudeDir);
        Files.writeString(pluginClaudeDir.resolve("plugin.json"), PLUGIN_JSON);

        Path pluginSkillDir = pluginDir.resolve("skills").resolve("test-plugin-skill");
        Files.createDirectories(pluginSkillDir);
        Files.writeString(pluginSkillDir.resolve("SKILL.md"), SKILL_MD_CONTENT);

        // Initialize git repo and commit
        runGit(repoDir, "init");
        runGit(repoDir, "config", "commit.gpgsign", "false");
        runGit(repoDir, "config", "user.name", "Test");
        runGit(repoDir, "config", "user.email", "test@test.com");
        runGit(repoDir, "add", ".");
        runGit(repoDir, "commit", "-m", "Initial commit");
    }

    private static void runGit(Path workDir, String... args) throws Exception {
        String[] fullArgs = new String[args.length + 1];
        fullArgs[0] = "git";
        System.arraycopy(args, 0, fullArgs, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(fullArgs);
        pb.directory(workDir.toFile());
        pb.environment().put("GIT_AUTHOR_NAME", "Test");
        pb.environment().put("GIT_AUTHOR_EMAIL", "test@test.com");
        pb.environment().put("GIT_COMMITTER_NAME", "Test");
        pb.environment().put("GIT_COMMITTER_EMAIL", "test@test.com");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
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
            throw new RuntimeException("git " + String.join(" ", args)
                    + " failed (exit " + exitCode + "):\n" + output);
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc)
                    throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
