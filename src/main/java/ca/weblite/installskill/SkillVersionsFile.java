package ca.weblite.installskill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses {@code .skills-versions} files.
 *
 * <p>File format: one entry per line as {@code name[@version]}.
 * Blank lines and lines starting with {@code #} are ignored.
 * Names can be registry skill names or Maven coordinates ({@code groupId:artifactId}).</p>
 */
public final class SkillVersionsFile {

    private static final String FILE_NAME = ".skills-versions";

    private SkillVersionsFile() {
    }

    /**
     * An entry from a {@code .skills-versions} file.
     */
    public static final class Entry {
        private final String name;
        private final String version;

        public Entry(String name, String version) {
            this.name = Objects.requireNonNull(name);
            this.version = version; // nullable — null means latest/RELEASE
        }

        public String getName() {
            return name;
        }

        /** Returns the requested version, or {@code null} if latest/RELEASE is desired. */
        public String getVersion() {
            return version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Entry)) return false;
            Entry entry = (Entry) o;
            return name.equals(entry.name) && Objects.equals(version, entry.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, version);
        }

        @Override
        public String toString() {
            return version != null ? name + "@" + version : name;
        }
    }

    /**
     * Parses a {@code .skills-versions} file.
     *
     * @param path path to the file
     * @return ordered list of entries (preserves file order)
     * @throws IOException if the file cannot be read or contains malformed entries
     */
    public static List<Entry> parse(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<Entry> entries = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int lineNumber = i + 1;
            int atIdx = line.lastIndexOf('@');
            if (atIdx < 0) {
                // No version specified
                if (line.isEmpty()) {
                    throw new IOException("Line " + lineNumber + ": skill name must not be empty");
                }
                entries.add(new Entry(line, null));
            } else {
                String name = line.substring(0, atIdx).trim();
                String version = line.substring(atIdx + 1).trim();
                if (name.isEmpty()) {
                    throw new IOException("Line " + lineNumber + ": skill name must not be empty");
                }
                if (version.isEmpty()) {
                    throw new IOException("Line " + lineNumber
                            + ": version must not be empty when '@' is present (use '"
                            + name + "' without '@' for latest)");
                }
                entries.add(new Entry(name, version));
            }
        }

        return entries;
    }

    /**
     * Checks if a {@code .skills-versions} file exists in the given directory.
     */
    public static boolean exists(Path directory) {
        return Files.isRegularFile(directory.resolve(FILE_NAME));
    }

    /**
     * Returns the path to {@code .skills-versions} in the given directory.
     */
    public static Path pathIn(Path directory) {
        return directory.resolve(FILE_NAME);
    }
}
