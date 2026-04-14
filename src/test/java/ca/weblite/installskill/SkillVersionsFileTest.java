package ca.weblite.installskill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillVersionsFileTest {

    @TempDir
    Path tempDir;

    private Path writeVersionsFile(String content) throws IOException {
        Path file = tempDir.resolve(".skills-versions");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void parseBasicEntries() throws IOException {
        Path file = writeVersionsFile(
                "my-skill 0.1.0\n" +
                "other-skill 1.2.3\n" +
                "third-skill\n"
        );

        List<SkillVersionsFile.Entry> entries = SkillVersionsFile.parse(file);

        assertEquals(3, entries.size());
        assertEquals("my-skill", entries.get(0).getName());
        assertEquals("0.1.0", entries.get(0).getVersion());
        assertEquals("other-skill", entries.get(1).getName());
        assertEquals("1.2.3", entries.get(1).getVersion());
        assertEquals("third-skill", entries.get(2).getName());
        assertNull(entries.get(2).getVersion());
    }

    @Test
    void parseSkipsBlankLinesAndComments() throws IOException {
        Path file = writeVersionsFile(
                "# This is a comment\n" +
                "\n" +
                "my-skill 0.1.0\n" +
                "  \n" +
                "# Another comment\n" +
                "other-skill\n"
        );

        List<SkillVersionsFile.Entry> entries = SkillVersionsFile.parse(file);

        assertEquals(2, entries.size());
        assertEquals("my-skill", entries.get(0).getName());
        assertEquals("other-skill", entries.get(1).getName());
    }

    @Test
    void parseEmptyFile() throws IOException {
        Path file = writeVersionsFile("");

        List<SkillVersionsFile.Entry> entries = SkillVersionsFile.parse(file);

        assertTrue(entries.isEmpty());
    }

    @Test
    void parseVersionlessEntry() throws IOException {
        Path file = writeVersionsFile("some-skill\n");

        List<SkillVersionsFile.Entry> entries = SkillVersionsFile.parse(file);

        assertEquals(1, entries.size());
        assertEquals("some-skill", entries.get(0).getName());
        assertNull(entries.get(0).getVersion());
    }

    @Test
    void parseRejectsEmptyVersion() throws IOException {
        Path file = writeVersionsFile("skill@\n");

        IOException ex = assertThrows(IOException.class,
                () -> SkillVersionsFile.parse(file));
        assertTrue(ex.getMessage().contains("Line 1"));
        assertTrue(ex.getMessage().contains("version must not be empty"));
    }

    @Test
    void parseRejectsEmptyName() throws IOException {
        Path file = writeVersionsFile("@1.0\n");

        IOException ex = assertThrows(IOException.class,
                () -> SkillVersionsFile.parse(file));
        assertTrue(ex.getMessage().contains("Line 1"));
        assertTrue(ex.getMessage().contains("name must not be empty"));
    }

    @Test
    void parseMavenCoordinatesFormat() throws IOException {
        Path file = writeVersionsFile("com.example:my-lib 1.0.0\n");

        List<SkillVersionsFile.Entry> entries = SkillVersionsFile.parse(file);

        assertEquals(1, entries.size());
        assertEquals("com.example:my-lib", entries.get(0).getName());
        assertEquals("1.0.0", entries.get(0).getVersion());
    }

    @Test
    void existsReturnsTrueWhenFilePresent() throws IOException {
        writeVersionsFile("skill 1.0\n");

        assertTrue(SkillVersionsFile.exists(tempDir));
    }

    @Test
    void existsReturnsFalseWhenFileMissing() {
        assertFalse(SkillVersionsFile.exists(tempDir));
    }

    @Test
    void pathInReturnsCorrectPath() {
        Path expected = tempDir.resolve(".skills-versions");
        assertEquals(expected, SkillVersionsFile.pathIn(tempDir));
    }

    @Test
    void entryToStringWithVersion() {
        SkillVersionsFile.Entry entry = new SkillVersionsFile.Entry("my-skill", "0.1.0");
        assertEquals("my-skill 0.1.0", entry.toString());
    }

    @Test
    void entryToStringWithoutVersion() {
        SkillVersionsFile.Entry entry = new SkillVersionsFile.Entry("my-skill", null);
        assertEquals("my-skill", entry.toString());
    }

    // ---- Backward compatibility: deprecated '@' separator ----

    @Test
    void parseDeprecatedAtSeparator() throws IOException {
        Path file = writeVersionsFile(
                "my-skill@0.1.0\n" +
                "other-skill@1.2.3\n"
        );

        List<SkillVersionsFile.Entry> entries = SkillVersionsFile.parse(file);

        assertEquals(2, entries.size());
        assertEquals("my-skill", entries.get(0).getName());
        assertEquals("0.1.0", entries.get(0).getVersion());
        assertEquals("other-skill", entries.get(1).getName());
        assertEquals("1.2.3", entries.get(1).getVersion());
    }

    @Test
    void parseDeprecatedAtWithMavenCoordinates() throws IOException {
        Path file = writeVersionsFile("com.example:my-lib@1.0.0\n");

        List<SkillVersionsFile.Entry> entries = SkillVersionsFile.parse(file);

        assertEquals(1, entries.size());
        assertEquals("com.example:my-lib", entries.get(0).getName());
        assertEquals("1.0.0", entries.get(0).getVersion());
    }

    @Test
    void parseMixedFormats() throws IOException {
        Path file = writeVersionsFile(
                "new-skill 2.0.0\n" +
                "legacy-skill@1.0.0\n" +
                "no-version-skill\n"
        );

        List<SkillVersionsFile.Entry> entries = SkillVersionsFile.parse(file);

        assertEquals(3, entries.size());
        assertEquals("new-skill", entries.get(0).getName());
        assertEquals("2.0.0", entries.get(0).getVersion());
        assertEquals("legacy-skill", entries.get(1).getName());
        assertEquals("1.0.0", entries.get(1).getVersion());
        assertEquals("no-version-skill", entries.get(2).getName());
        assertNull(entries.get(2).getVersion());
    }
}
