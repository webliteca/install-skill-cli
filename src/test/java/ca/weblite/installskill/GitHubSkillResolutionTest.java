package ca.weblite.installskill;

import org.junit.jupiter.api.Test;

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
}
