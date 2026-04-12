package ca.weblite.installskill;

import java.util.Objects;

/**
 * Immutable value class holding the resolved Maven coordinates for a skill.
 */
public final class SkillCoordinates {

    private final String name;
    private final String groupId;
    private final String artifactId;
    private final String version;

    public SkillCoordinates(String name, String groupId, String artifactId, String version) {
        this.name = Objects.requireNonNull(name);
        this.groupId = Objects.requireNonNull(groupId);
        this.artifactId = Objects.requireNonNull(artifactId);
        this.version = Objects.requireNonNull(version);
    }

    public String getName() {
        return name;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SkillCoordinates)) return false;
        SkillCoordinates that = (SkillCoordinates) o;
        return name.equals(that.name)
                && groupId.equals(that.groupId)
                && artifactId.equals(that.artifactId)
                && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, groupId, artifactId, version);
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
