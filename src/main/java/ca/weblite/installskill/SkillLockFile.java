package ca.weblite.installskill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads and writes {@code .skills-versions.lock} files.
 *
 * <p>The lock file captures the resolved Maven coordinates for each skill
 * listed in {@code .skills-versions}, enabling reproducible installs.
 * Format is JSON with a {@code lockVersion} field for forward compatibility.</p>
 */
public final class SkillLockFile {

    private static final String FILE_NAME = ".skills-versions.lock";
    private static final int LOCK_VERSION = 1;

    private SkillLockFile() {
    }

    /**
     * A locked skill entry recording the resolved coordinates.
     */
    public static final class LockedSkill {
        private final String name;
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String requestedVersion; // nullable — null means latest/RELEASE was requested

        public LockedSkill(String name, String groupId, String artifactId,
                           String version, String requestedVersion) {
            this.name = Objects.requireNonNull(name);
            this.groupId = Objects.requireNonNull(groupId);
            this.artifactId = Objects.requireNonNull(artifactId);
            this.version = Objects.requireNonNull(version);
            this.requestedVersion = requestedVersion;
        }

        public String getName() { return name; }
        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public String getVersion() { return version; }
        public String getRequestedVersion() { return requestedVersion; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LockedSkill)) return false;
            LockedSkill that = (LockedSkill) o;
            return name.equals(that.name)
                    && groupId.equals(that.groupId)
                    && artifactId.equals(that.artifactId)
                    && version.equals(that.version)
                    && Objects.equals(requestedVersion, that.requestedVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, groupId, artifactId, version, requestedVersion);
        }
    }

    /**
     * Result of comparing {@code .skills-versions} against {@code .skills-versions.lock}.
     */
    public static final class ResolutionPlan {
        private final List<LockedSkill> reusable;
        private final List<SkillVersionsFile.Entry> toResolve;
        private final List<String> removed;

        public ResolutionPlan(List<LockedSkill> reusable,
                              List<SkillVersionsFile.Entry> toResolve,
                              List<String> removed) {
            this.reusable = Objects.requireNonNull(reusable);
            this.toResolve = Objects.requireNonNull(toResolve);
            this.removed = Objects.requireNonNull(removed);
        }

        /** Entries whose locked version can be reused as-is. */
        public List<LockedSkill> getReusable() { return reusable; }

        /** Entries that need fresh resolution (new or changed). */
        public List<SkillVersionsFile.Entry> getToResolve() { return toResolve; }

        /** Skill names that were in the lock but are no longer in {@code .skills-versions}. */
        public List<String> getRemoved() { return removed; }
    }

    /**
     * Reads a lock file. Returns an empty map if the file doesn't exist.
     * Prints a warning and returns empty on parse errors.
     */
    public static Map<String, LockedSkill> read(Path lockFilePath) throws IOException {
        if (!Files.isRegularFile(lockFilePath)) {
            return new LinkedHashMap<>();
        }

        String content = Files.readString(lockFilePath);
        try {
            return parseJson(content);
        } catch (Exception e) {
            System.err.println("Warning: Failed to parse lock file ("
                    + e.getMessage() + "). Re-resolving all skills.");
            return new LinkedHashMap<>();
        }
    }

    /**
     * Writes lock file entries as formatted JSON.
     */
    public static void write(Path lockFilePath, Map<String, LockedSkill> entries)
            throws IOException {
        Files.writeString(lockFilePath, toJson(entries));
    }

    /**
     * Returns the path to {@code .skills-versions.lock} in the given directory.
     */
    public static Path pathIn(Path directory) {
        return directory.resolve(FILE_NAME);
    }

    /**
     * Determines which entries need re-resolution vs. which can use locked versions.
     */
    public static ResolutionPlan computeResolutionPlan(
            List<SkillVersionsFile.Entry> requested,
            Map<String, LockedSkill> locked) {

        List<LockedSkill> reusable = new ArrayList<>();
        List<SkillVersionsFile.Entry> toResolve = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        // Check each requested entry against the lock
        for (SkillVersionsFile.Entry entry : requested) {
            LockedSkill lockedEntry = locked.get(entry.getName());
            if (lockedEntry != null
                    && Objects.equals(entry.getVersion(), lockedEntry.getRequestedVersion())) {
                reusable.add(lockedEntry);
            } else {
                toResolve.add(entry);
            }
        }

        // Find entries that were in the lock but are no longer requested
        for (String lockedName : locked.keySet()) {
            boolean stillRequested = requested.stream()
                    .anyMatch(e -> e.getName().equals(lockedName));
            if (!stillRequested) {
                removed.add(lockedName);
            }
        }

        return new ResolutionPlan(reusable, toResolve, removed);
    }

    // ---- JSON serialization (hand-rolled, no external dependency) ----

    static String toJson(Map<String, LockedSkill> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"lockVersion\": ").append(LOCK_VERSION).append(",\n");
        sb.append("  \"skills\": {");

        boolean first = true;
        for (Map.Entry<String, LockedSkill> e : entries.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\n");
            LockedSkill s = e.getValue();
            sb.append("    ").append(jsonString(s.getName())).append(": {\n");
            sb.append("      \"name\": ").append(jsonString(s.getName())).append(",\n");
            sb.append("      \"groupId\": ").append(jsonString(s.getGroupId())).append(",\n");
            sb.append("      \"artifactId\": ").append(jsonString(s.getArtifactId())).append(",\n");
            sb.append("      \"version\": ").append(jsonString(s.getVersion())).append(",\n");
            sb.append("      \"requestedVersion\": ")
                    .append(s.getRequestedVersion() != null
                            ? jsonString(s.getRequestedVersion()) : "null")
                    .append("\n");
            sb.append("    }");
        }

        if (!entries.isEmpty()) {
            sb.append("\n  ");
        }
        sb.append("}\n");
        sb.append("}\n");
        return sb.toString();
    }

    static Map<String, LockedSkill> parseJson(String json) {
        Map<String, LockedSkill> result = new LinkedHashMap<>();

        // Find "skills" object
        int skillsIdx = json.indexOf("\"skills\"");
        if (skillsIdx < 0) {
            return result;
        }

        // Find the opening brace of the skills object
        int braceStart = json.indexOf('{', skillsIdx);
        if (braceStart < 0) {
            return result;
        }

        // Find matching closing brace
        int braceEnd = findMatchingBrace(json, braceStart);
        if (braceEnd < 0) {
            return result;
        }

        String skillsBlock = json.substring(braceStart + 1, braceEnd);

        // Parse each skill entry by finding inner objects
        int pos = 0;
        while (pos < skillsBlock.length()) {
            // Find key (skill name)
            int keyStart = skillsBlock.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd = skillsBlock.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;

            // Find opening brace of this skill's object
            int objStart = skillsBlock.indexOf('{', keyEnd);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(skillsBlock, objStart);
            if (objEnd < 0) break;

            String objContent = skillsBlock.substring(objStart + 1, objEnd);
            String name = extractJsonStringValue(objContent, "name");
            String groupId = extractJsonStringValue(objContent, "groupId");
            String artifactId = extractJsonStringValue(objContent, "artifactId");
            String version = extractJsonStringValue(objContent, "version");
            String requestedVersion = extractJsonStringValue(objContent, "requestedVersion");

            if (name != null && groupId != null && artifactId != null && version != null) {
                result.put(name, new LockedSkill(name, groupId, artifactId,
                        version, requestedVersion));
            }

            pos = objEnd + 1;
        }

        return result;
    }

    private static int findMatchingBrace(String s, int openPos) {
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
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static String extractJsonStringValue(String obj, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = obj.indexOf(search);
        if (keyIdx < 0) return null;

        int colonIdx = obj.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return null;

        // Skip whitespace after colon
        int valueStart = colonIdx + 1;
        while (valueStart < obj.length() && Character.isWhitespace(obj.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= obj.length()) return null;

        // Check for null
        if (obj.startsWith("null", valueStart)) {
            return null;
        }

        // Expect a quoted string
        if (obj.charAt(valueStart) != '"') return null;
        int strEnd = obj.indexOf('"', valueStart + 1);
        if (strEnd < 0) return null;

        return unescapeJson(obj.substring(valueStart + 1, strEnd));
    }

    private static String jsonString(String value) {
        return "\"" + escapeJson(value) + "\"";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
