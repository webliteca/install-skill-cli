package ca.weblite.installskill;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GitHub skill coordinate resolution.
 */
class GitHubSkillResolutionTest {

    @Test
    void resolveGitHubSkillBasic() {
        InstallSkillCommand cmd = new InstallSkillCommand();
        SkillCoordinates coords = cmd.resolveSkillCoordinates("owner/repo");

        assertNotNull(coords);
        assertEquals("owner/repo", coords.getName());
        assertEquals(InstallSkillCommand.GITHUB_GROUP_ID, coords.getGroupId());
        assertEquals("owner/repo", coords.getArtifactId());
        assertEquals("HEAD", coords.getVersion());
    }

    @Test
    void resolveGitHubSkillWithVersion() {
        InstallSkillCommand cmd = new InstallSkillCommand();
        SkillCoordinates coords = cmd.resolveSkillCoordinates("myorg/my-skill@v1.0.0");

        assertNotNull(coords);
        assertEquals("myorg/my-skill", coords.getName());
        assertEquals(InstallSkillCommand.GITHUB_GROUP_ID, coords.getGroupId());
        assertEquals("myorg/my-skill", coords.getArtifactId());
        assertEquals("v1.0.0", coords.getVersion());
    }

    @Test
    void resolveGitHubSkillWithBranchVersion() {
        InstallSkillCommand cmd = new InstallSkillCommand();
        SkillCoordinates coords = cmd.resolveSkillCoordinates("owner/repo@main");

        assertNotNull(coords);
        assertEquals("main", coords.getVersion());
    }

    @Test
    void resolveGitHubSkillEmptyVersionFails() {
        InstallSkillCommand cmd = new InstallSkillCommand();
        SkillCoordinates coords = cmd.resolveSkillCoordinates("owner/repo@");

        assertNull(coords, "Empty version after @ should return null");
    }

    @Test
    void resolveGitHubSkillMultipleSlashesFails() {
        InstallSkillCommand cmd = new InstallSkillCommand();
        SkillCoordinates coords = cmd.resolveSkillCoordinates("owner/repo/extra");

        assertNull(coords, "Multiple slashes should return null");
    }

    @Test
    void resolveGitHubSkillEmptyOwnerFails() {
        InstallSkillCommand cmd = new InstallSkillCommand();
        SkillCoordinates coords = cmd.resolveSkillCoordinates("/repo");

        assertNull(coords, "Empty owner should return null");
    }

    @Test
    void resolveGitHubSkillEmptyRepoFails() {
        InstallSkillCommand cmd = new InstallSkillCommand();
        SkillCoordinates coords = cmd.resolveSkillCoordinates("owner/");

        assertNull(coords, "Empty repo should return null");
    }

    @Test
    void mavenCoordinatesNotTreatedAsGitHub() {
        // groupId:artifactId contains ':', so it should not be treated as GitHub
        InstallSkillCommand cmd = new InstallSkillCommand();
        SkillCoordinates coords = cmd.resolveSkillCoordinates("com.example:my-lib:1.0");

        assertNotNull(coords);
        assertNotEquals(InstallSkillCommand.GITHUB_GROUP_ID, coords.getGroupId());
        assertEquals("com.example", coords.getGroupId());
        assertEquals("my-lib", coords.getArtifactId());
    }

    @Test
    void gitHubSkillVersionWithAtSign() {
        InstallSkillCommand cmd = new InstallSkillCommand();
        SkillCoordinates coords = cmd.resolveSkillCoordinates("org-name/skill-repo@2.3.4");

        assertNotNull(coords);
        assertEquals("org-name/skill-repo", coords.getName());
        assertEquals("2.3.4", coords.getVersion());
    }

    // ---- Registry-based GitHub resolution tests ----

    @Test
    void resolveGitHubSkillFromRegistry() throws IOException {
        Path registryFile = Files.createTempFile("skills-registry-", ".xml");
        try {
            Files.writeString(registryFile,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<skills>\n"
                    + "  <skill>\n"
                    + "    <name>my-gh-skill</name>\n"
                    + "    <repository>someorg/somerepo</repository>\n"
                    + "    <version>v2.0</version>\n"
                    + "    <description>Test</description>\n"
                    + "  </skill>\n"
                    + "</skills>\n");

            System.setProperty("skills.registry.url", registryFile.toUri().toString());
            try {
                InstallSkillCommand cmd = new InstallSkillCommand();
                String[] resolved = cmd.resolveFromRegistry("my-gh-skill");

                assertNotNull(resolved);
                assertEquals(InstallSkillCommand.GITHUB_GROUP_ID, resolved[0]);
                assertEquals("someorg/somerepo", resolved[1]);
                assertEquals("v2.0", resolved[2]);
            } finally {
                System.clearProperty("skills.registry.url");
            }
        } finally {
            Files.deleteIfExists(registryFile);
        }
    }

    @Test
    void resolveGitHubSkillFromRegistryNoVersion() throws IOException {
        Path registryFile = Files.createTempFile("skills-registry-", ".xml");
        try {
            Files.writeString(registryFile,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<skills>\n"
                    + "  <skill>\n"
                    + "    <name>gh-no-ver</name>\n"
                    + "    <repository>owner/repo</repository>\n"
                    + "    <description>Test</description>\n"
                    + "  </skill>\n"
                    + "</skills>\n");

            System.setProperty("skills.registry.url", registryFile.toUri().toString());
            try {
                InstallSkillCommand cmd = new InstallSkillCommand();
                SkillCoordinates coords = cmd.resolveSkillCoordinates("gh-no-ver");

                assertNotNull(coords);
                assertEquals(InstallSkillCommand.GITHUB_GROUP_ID, coords.getGroupId());
                assertEquals("owner/repo", coords.getArtifactId());
                assertEquals("HEAD", coords.getVersion(),
                        "GitHub skills without version should default to HEAD");
            } finally {
                System.clearProperty("skills.registry.url");
            }
        } finally {
            Files.deleteIfExists(registryFile);
        }
    }

    @Test
    void resolveMavenSkillFromRegistryStillWorks() throws IOException {
        Path registryFile = Files.createTempFile("skills-registry-", ".xml");
        try {
            Files.writeString(registryFile,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<skills>\n"
                    + "  <skill>\n"
                    + "    <name>maven-skill</name>\n"
                    + "    <groupId>com.example</groupId>\n"
                    + "    <artifactId>my-lib</artifactId>\n"
                    + "    <version>1.0</version>\n"
                    + "    <description>Test</description>\n"
                    + "  </skill>\n"
                    + "</skills>\n");

            System.setProperty("skills.registry.url", registryFile.toUri().toString());
            try {
                InstallSkillCommand cmd = new InstallSkillCommand();
                String[] resolved = cmd.resolveFromRegistry("maven-skill");

                assertNotNull(resolved);
                assertEquals("com.example", resolved[0]);
                assertEquals("my-lib", resolved[1]);
                assertEquals("1.0", resolved[2]);
            } finally {
                System.clearProperty("skills.registry.url");
            }
        } finally {
            Files.deleteIfExists(registryFile);
        }
    }
}
