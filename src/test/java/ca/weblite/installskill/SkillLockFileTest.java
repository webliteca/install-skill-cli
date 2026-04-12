package ca.weblite.installskill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillLockFileTest {

    @TempDir
    Path tempDir;

    private static SkillLockFile.LockedSkill locked(
            String name, String groupId, String artifactId,
            String version, String requestedVersion) {
        return new SkillLockFile.LockedSkill(name, groupId, artifactId,
                version, requestedVersion);
    }

    @Test
    void writeAndReadRoundTrip() throws IOException {
        Path lockFile = tempDir.resolve(".skills-versions.lock");

        Map<String, SkillLockFile.LockedSkill> entries = new LinkedHashMap<>();
        entries.put("my-skill", locked("my-skill", "com.example",
                "my-skill-lib", "0.1.0", "0.1.0"));
        entries.put("other-skill", locked("other-skill", "org.example",
                "other-skill", "2.3.1", null));

        SkillLockFile.write(lockFile, entries);
        Map<String, SkillLockFile.LockedSkill> read = SkillLockFile.read(lockFile);

        assertEquals(2, read.size());

        SkillLockFile.LockedSkill mySkill = read.get("my-skill");
        assertNotNull(mySkill);
        assertEquals("my-skill", mySkill.getName());
        assertEquals("com.example", mySkill.getGroupId());
        assertEquals("my-skill-lib", mySkill.getArtifactId());
        assertEquals("0.1.0", mySkill.getVersion());
        assertEquals("0.1.0", mySkill.getRequestedVersion());

        SkillLockFile.LockedSkill otherSkill = read.get("other-skill");
        assertNotNull(otherSkill);
        assertEquals("other-skill", otherSkill.getName());
        assertEquals("org.example", otherSkill.getGroupId());
        assertEquals("other-skill", otherSkill.getArtifactId());
        assertEquals("2.3.1", otherSkill.getVersion());
        assertNull(otherSkill.getRequestedVersion());
    }

    @Test
    void readNonExistentFileReturnsEmpty() throws IOException {
        Path lockFile = tempDir.resolve("nonexistent.lock");

        Map<String, SkillLockFile.LockedSkill> result = SkillLockFile.read(lockFile);

        assertTrue(result.isEmpty());
    }

    @Test
    void computeResolutionPlanAllNew() {
        List<SkillVersionsFile.Entry> requested = Arrays.asList(
                new SkillVersionsFile.Entry("skill-a", "1.0"),
                new SkillVersionsFile.Entry("skill-b", null)
        );
        Map<String, SkillLockFile.LockedSkill> locked = Collections.emptyMap();

        SkillLockFile.ResolutionPlan plan =
                SkillLockFile.computeResolutionPlan(requested, locked);

        assertTrue(plan.getReusable().isEmpty());
        assertEquals(2, plan.getToResolve().size());
        assertTrue(plan.getRemoved().isEmpty());
    }

    @Test
    void computeResolutionPlanAllLocked() {
        List<SkillVersionsFile.Entry> requested = Arrays.asList(
                new SkillVersionsFile.Entry("skill-a", "1.0"),
                new SkillVersionsFile.Entry("skill-b", null)
        );
        Map<String, SkillLockFile.LockedSkill> locked = new LinkedHashMap<>();
        locked.put("skill-a", locked("skill-a", "com.ex", "a", "1.0", "1.0"));
        locked.put("skill-b", locked("skill-b", "com.ex", "b", "2.0", null));

        SkillLockFile.ResolutionPlan plan =
                SkillLockFile.computeResolutionPlan(requested, locked);

        assertEquals(2, plan.getReusable().size());
        assertTrue(plan.getToResolve().isEmpty());
        assertTrue(plan.getRemoved().isEmpty());
    }

    @Test
    void computeResolutionPlanMixed() {
        List<SkillVersionsFile.Entry> requested = Arrays.asList(
                new SkillVersionsFile.Entry("existing", "1.0"),
                new SkillVersionsFile.Entry("new-skill", "2.0")
        );
        Map<String, SkillLockFile.LockedSkill> locked = new LinkedHashMap<>();
        locked.put("existing", locked("existing", "com.ex", "ex", "1.0", "1.0"));
        locked.put("removed-skill", locked("removed-skill", "com.ex", "rm", "3.0", "3.0"));

        SkillLockFile.ResolutionPlan plan =
                SkillLockFile.computeResolutionPlan(requested, locked);

        assertEquals(1, plan.getReusable().size());
        assertEquals("existing", plan.getReusable().get(0).getName());
        assertEquals(1, plan.getToResolve().size());
        assertEquals("new-skill", plan.getToResolve().get(0).getName());
        assertEquals(1, plan.getRemoved().size());
        assertEquals("removed-skill", plan.getRemoved().get(0));
    }

    @Test
    void computeResolutionPlanVersionChanged() {
        List<SkillVersionsFile.Entry> requested = Collections.singletonList(
                new SkillVersionsFile.Entry("skill-a", "2.0")
        );
        Map<String, SkillLockFile.LockedSkill> locked = new LinkedHashMap<>();
        locked.put("skill-a", locked("skill-a", "com.ex", "a", "1.0", "1.0"));

        SkillLockFile.ResolutionPlan plan =
                SkillLockFile.computeResolutionPlan(requested, locked);

        assertTrue(plan.getReusable().isEmpty());
        assertEquals(1, plan.getToResolve().size());
        assertEquals("skill-a", plan.getToResolve().get(0).getName());
    }

    @Test
    void computeResolutionPlanNewEntry() {
        List<SkillVersionsFile.Entry> requested = Collections.singletonList(
                new SkillVersionsFile.Entry("brand-new", "1.0")
        );
        Map<String, SkillLockFile.LockedSkill> locked = new LinkedHashMap<>();
        locked.put("other", locked("other", "com.ex", "o", "1.0", "1.0"));

        SkillLockFile.ResolutionPlan plan =
                SkillLockFile.computeResolutionPlan(requested, locked);

        assertTrue(plan.getReusable().isEmpty());
        assertEquals(1, plan.getToResolve().size());
        assertEquals("brand-new", plan.getToResolve().get(0).getName());
    }

    @Test
    void computeResolutionPlanRemovedEntry() {
        List<SkillVersionsFile.Entry> requested = Collections.emptyList();
        Map<String, SkillLockFile.LockedSkill> locked = new LinkedHashMap<>();
        locked.put("gone-skill", locked("gone-skill", "com.ex", "g", "1.0", "1.0"));

        SkillLockFile.ResolutionPlan plan =
                SkillLockFile.computeResolutionPlan(requested, locked);

        assertTrue(plan.getReusable().isEmpty());
        assertTrue(plan.getToResolve().isEmpty());
        assertEquals(1, plan.getRemoved().size());
        assertEquals("gone-skill", plan.getRemoved().get(0));
    }

    @Test
    void jsonRoundTripWithSpecialCharacters() throws IOException {
        Path lockFile = tempDir.resolve(".skills-versions.lock");

        Map<String, SkillLockFile.LockedSkill> entries = new LinkedHashMap<>();
        entries.put("my-skill", locked("my-skill", "com.example",
                "my-skill", "1.0.0-beta.1", "1.0.0-beta.1"));

        SkillLockFile.write(lockFile, entries);
        Map<String, SkillLockFile.LockedSkill> read = SkillLockFile.read(lockFile);

        assertEquals(1, read.size());
        assertEquals("1.0.0-beta.1", read.get("my-skill").getVersion());
    }
}
