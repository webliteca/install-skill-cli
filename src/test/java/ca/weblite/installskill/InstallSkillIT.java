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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that installs skills for ca.weblite:teavm-lambda-parent.
 *
 * <p>The teavm-lambda project publishes skills via the skills-jar-maven-plugin.
 * This test creates a fixture skills JAR matching the expected format and installs
 * it to the local Maven repository, then verifies the install-skill CLI can
 * resolve, unpack, and copy the skill to the target directory.</p>
 *
 * <p>Requires Maven on PATH.</p>
 */
class InstallSkillIT {

    private static final String TEAVM_LAMBDA_GROUP = "ca.weblite";
    private static final String TEAVM_LAMBDA_ARTIFACT = "teavm-lambda-parent";
    private static final String TEAVM_LAMBDA_VERSION = "0.1.2";

    private static final String SKILL_MD_CONTENT =
            "---\n" +
            "name: teavm-lambda\n" +
            "description: Skill for working with teavm-lambda projects. Provides guidance on TeaVM-based serverless functions.\n" +
            "---\n" +
            "\n" +
            "# TeaVM Lambda Skill\n" +
            "\n" +
            "This skill provides guidance for working with the teavm-lambda framework.\n";

    private static Path fixtureJar;
    private Path skillsDir;

    @BeforeAll
    static void installFixture() throws Exception {
        // Create a skills JAR matching the format produced by skills-jar-plugin
        fixtureJar = Files.createTempFile("teavm-lambda-skills-", ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(fixtureJar))) {
            // skill/SKILL.md — the skills-jar plugin stores content under "skill/" prefix
            jos.putNextEntry(new JarEntry("skill/SKILL.md"));
            jos.write(SKILL_MD_CONTENT.getBytes());
            jos.closeEntry();
        }

        // Install the fixture JAR into the local Maven repository with the
        // classifier "skills", matching what the skills-jar-plugin produces
        ProcessBuilder pb = new ProcessBuilder(
                "mvn", "-B",
                "install:install-file",
                "-Dfile=" + fixtureJar,
                "-DgroupId=" + TEAVM_LAMBDA_GROUP,
                "-DartifactId=" + TEAVM_LAMBDA_ARTIFACT,
                "-Dversion=" + TEAVM_LAMBDA_VERSION,
                "-Dclassifier=skills",
                "-Dpackaging=jar",
                "-DgeneratePom=false"
        );
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
        assertEquals(0, exitCode,
                "Failed to install fixture skills JAR to local repo:\n" + output);
    }

    @AfterAll
    static void cleanFixture() throws IOException {
        if (fixtureJar != null) {
            Files.deleteIfExists(fixtureJar);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = Files.createTempDirectory("install-skill-it-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (skillsDir != null && Files.exists(skillsDir)) {
            Files.walkFileTree(skillsDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Test
    void installTeavmLambdaSkill() throws IOException {
        int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                TEAVM_LAMBDA_GROUP + ":" + TEAVM_LAMBDA_ARTIFACT + ":" + TEAVM_LAMBDA_VERSION,
                "-d", skillsDir.toString()
        );

        assertEquals(0, exitCode, "install-skill should exit successfully");

        // Verify the skill directory was created (named after the artifactId)
        Path skillDir = skillsDir.resolve(TEAVM_LAMBDA_ARTIFACT);
        assertTrue(Files.isDirectory(skillDir),
                "Skill directory should exist: " + skillDir);

        // Verify SKILL.md exists and has content
        Path skillMd = skillDir.resolve("SKILL.md");
        assertTrue(Files.isRegularFile(skillMd),
                "SKILL.md should exist in installed skill directory");

        String skillContent = Files.readString(skillMd);
        assertFalse(skillContent.isBlank(), "SKILL.md should not be empty");
        assertTrue(skillContent.startsWith("---"),
                "SKILL.md should start with YAML frontmatter delimiter");
        assertTrue(skillContent.contains("name:"),
                "SKILL.md frontmatter should contain a name field");
        assertTrue(skillContent.contains("description:"),
                "SKILL.md frontmatter should contain a description field");
        assertTrue(skillContent.contains("teavm-lambda"),
                "SKILL.md should reference teavm-lambda");

        // Verify the manifest was created
        Path manifest = skillsDir.resolve(".skill-manifest.json");
        assertTrue(Files.isRegularFile(manifest),
                ".skill-manifest.json should exist");

        String manifestContent = Files.readString(manifest);
        assertTrue(manifestContent.contains(
                        TEAVM_LAMBDA_GROUP + ":" + TEAVM_LAMBDA_ARTIFACT + ":" + TEAVM_LAMBDA_VERSION),
                "Manifest should contain the GAV coordinates");

        // Verify at least the SKILL.md was installed
        long fileCount;
        try (Stream<Path> files = Files.walk(skillDir)) {
            fileCount = files.filter(Files::isRegularFile).count();
        }
        assertTrue(fileCount >= 1,
                "Installed skill should contain at least 1 file, found: " + fileCount);
    }

    @Test
    void installTeavmLambdaSkillWithDefaultVersion() throws IOException {
        // Test using RELEASE (default version) — should resolve the latest available version
        int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                TEAVM_LAMBDA_GROUP + ":" + TEAVM_LAMBDA_ARTIFACT,
                "-d", skillsDir.toString()
        );

        assertEquals(0, exitCode, "install-skill with default version should exit successfully");

        Path skillDir = skillsDir.resolve(TEAVM_LAMBDA_ARTIFACT);
        assertTrue(Files.isDirectory(skillDir),
                "Skill directory should exist when using default version");

        Path skillMd = skillDir.resolve("SKILL.md");
        assertTrue(Files.isRegularFile(skillMd),
                "SKILL.md should exist when using default version");
    }

    @Test
    void installSkillByRegistryName() throws IOException {
        // Create a temporary registry XML with the test skill
        Path registryFile = Files.createTempFile("skills-registry-", ".xml");
        try {
            String registryXml =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<skills>\n" +
                    "  <skill>\n" +
                    "    <name>teavm-lambda</name>\n" +
                    "    <groupId>" + TEAVM_LAMBDA_GROUP + "</groupId>\n" +
                    "    <artifactId>" + TEAVM_LAMBDA_ARTIFACT + "</artifactId>\n" +
                    "    <version>" + TEAVM_LAMBDA_VERSION + "</version>\n" +
                    "    <description>Test skill</description>\n" +
                    "  </skill>\n" +
                    "</skills>\n";
            Files.writeString(registryFile, registryXml);

            // Point the registry URL to the local file
            System.setProperty("skills.registry.url", registryFile.toUri().toString());
            try {
                int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                        "teavm-lambda",
                        "-d", skillsDir.toString()
                );

                assertEquals(0, exitCode, "install-skill by name should exit successfully");

                Path skillDir = skillsDir.resolve(TEAVM_LAMBDA_ARTIFACT);
                assertTrue(Files.isDirectory(skillDir),
                        "Skill directory should exist when installed by name");

                Path skillMd = skillDir.resolve("SKILL.md");
                assertTrue(Files.isRegularFile(skillMd),
                        "SKILL.md should exist when installed by name");
            } finally {
                System.clearProperty("skills.registry.url");
            }
        } finally {
            Files.deleteIfExists(registryFile);
        }
    }

    @Test
    void installSkillByRegistryNameWithVersionOverride() throws IOException {
        // Create a temporary registry XML — the registry version differs from the override
        Path registryFile = Files.createTempFile("skills-registry-", ".xml");
        try {
            String registryXml =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<skills>\n" +
                    "  <skill>\n" +
                    "    <name>teavm-lambda</name>\n" +
                    "    <groupId>" + TEAVM_LAMBDA_GROUP + "</groupId>\n" +
                    "    <artifactId>" + TEAVM_LAMBDA_ARTIFACT + "</artifactId>\n" +
                    "    <version>9.9.9</version>\n" +
                    "    <description>Test skill</description>\n" +
                    "  </skill>\n" +
                    "</skills>\n";
            Files.writeString(registryFile, registryXml);

            // Point the registry URL to the local file
            System.setProperty("skills.registry.url", registryFile.toUri().toString());
            try {
                // Use name@version syntax — the version should override the registry's 9.9.9
                int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                        "teavm-lambda@" + TEAVM_LAMBDA_VERSION,
                        "-d", skillsDir.toString()
                );

                assertEquals(0, exitCode, "install-skill by name@version should exit successfully");

                Path skillDir = skillsDir.resolve(TEAVM_LAMBDA_ARTIFACT);
                assertTrue(Files.isDirectory(skillDir),
                        "Skill directory should exist when installed by name@version");

                Path skillMd = skillDir.resolve("SKILL.md");
                assertTrue(Files.isRegularFile(skillMd),
                        "SKILL.md should exist when installed by name@version");

                // Verify the manifest uses the overridden version, not the registry version
                Path manifest = skillsDir.resolve(".skill-manifest.json");
                assertTrue(Files.isRegularFile(manifest),
                        ".skill-manifest.json should exist");
                String manifestContent = Files.readString(manifest);
                assertTrue(manifestContent.contains(
                                TEAVM_LAMBDA_GROUP + ":" + TEAVM_LAMBDA_ARTIFACT + ":" + TEAVM_LAMBDA_VERSION),
                        "Manifest should contain the overridden version, not the registry version");
                assertFalse(manifestContent.contains("9.9.9"),
                        "Manifest should NOT contain the registry version 9.9.9");
            } finally {
                System.clearProperty("skills.registry.url");
            }
        } finally {
            Files.deleteIfExists(registryFile);
        }
    }

    @Test
    void installSkillByRegistryNameWithEmptyVersionFails() throws IOException {
        // Create a temporary registry XML
        Path registryFile = Files.createTempFile("skills-registry-", ".xml");
        try {
            String registryXml =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<skills>\n" +
                    "  <skill>\n" +
                    "    <name>teavm-lambda</name>\n" +
                    "    <groupId>" + TEAVM_LAMBDA_GROUP + "</groupId>\n" +
                    "    <artifactId>" + TEAVM_LAMBDA_ARTIFACT + "</artifactId>\n" +
                    "    <version>" + TEAVM_LAMBDA_VERSION + "</version>\n" +
                    "    <description>Test skill</description>\n" +
                    "  </skill>\n" +
                    "</skills>\n";
            Files.writeString(registryFile, registryXml);

            System.setProperty("skills.registry.url", registryFile.toUri().toString());
            try {
                // name@ with empty version should fail
                int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                        "teavm-lambda@",
                        "-d", skillsDir.toString()
                );

                assertEquals(1, exitCode, "install-skill with empty version after @ should fail");
            } finally {
                System.clearProperty("skills.registry.url");
            }
        } finally {
            Files.deleteIfExists(registryFile);
        }
    }

    @Test
    void installSkillByRegistryNameNotFound() throws IOException {
        // Create a temporary registry XML without the requested skill
        Path registryFile = Files.createTempFile("skills-registry-", ".xml");
        try {
            String registryXml =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<skills>\n" +
                    "</skills>\n";
            Files.writeString(registryFile, registryXml);

            System.setProperty("skills.registry.url", registryFile.toUri().toString());
            try {
                int exitCode = new CommandLine(new InstallSkillCommand()).execute(
                        "nonexistent-skill",
                        "-d", skillsDir.toString()
                );

                assertEquals(1, exitCode, "install-skill should fail for unknown skill name");
            } finally {
                System.clearProperty("skills.registry.url");
            }
        } finally {
            Files.deleteIfExists(registryFile);
        }
    }
}
