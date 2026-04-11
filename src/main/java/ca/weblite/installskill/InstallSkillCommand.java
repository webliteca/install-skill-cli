package ca.weblite.installskill;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * CLI tool that installs skills deployed with the skills-jar-maven-plugin.
 *
 * <p>Sets up a temporary Maven project with the specified skill artifact as a dependency,
 * runs the skills-jar plugin install goal to resolve and unpack skill bundles,
 * then copies the installed skills to the target directory.</p>
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

    @Parameters(index = "0",
            description = "Skill coordinates: groupId:artifactId[:version]. Version defaults to RELEASE (latest release).",
            paramLabel = "<groupId:artifactId[:version]>")
    private String coordinates;

    @Option(names = {"-r"},
            description = "Repository URL with optional credentials: [user:pass@]repositoryUrl",
            paramLabel = "<repository>")
    private String repository;

    @Option(names = {"-d"},
            description = "Skills installation directory (default: ./claude/skills).",
            paramLabel = "<skillsDir>",
            defaultValue = "./claude/skills")
    private String skillsDir;

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
        // 1. Parse coordinates
        String[] parts = coordinates.split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            System.err.println("Error: Invalid coordinates format. Expected: groupId:artifactId[:version]");
            return 1;
        }

        String groupId = parts[0].trim();
        String artifactId = parts[1].trim();
        String version = (parts.length == 3 && !parts[2].trim().isEmpty())
                ? parts[2].trim() : DEFAULT_VERSION;

        if (groupId.isEmpty() || artifactId.isEmpty()) {
            System.err.println("Error: groupId and artifactId must not be empty.");
            return 1;
        }

        // 2. Parse repository option
        String repoUrl = null;
        String repoUser = null;
        String repoPass = null;
        if (repository != null && !repository.isEmpty()) {
            String[] repoParts = parseRepository(repository);
            repoUrl = repoParts[0];
            repoUser = repoParts[1];
            repoPass = repoParts[2];
        }

        // 3. Verify Maven is available
        String mvnCommand = findMavenCommand();
        if (mvnCommand == null) {
            System.err.println("Error: Maven (mvn) is not available on the PATH. Please install Maven first.");
            return 1;
        }

        // 4. Create temp directory
        Path tempDir = Files.createTempDirectory("install-skill-");
        System.out.println("Setting up temporary Maven project...");

        try {
            // 5. Generate pom.xml in the temp project
            generatePom(tempDir, groupId, artifactId, version, repoUrl);

            // 6. Generate settings.xml if credentials are provided
            boolean hasSettings = false;
            if (repoUser != null) {
                generateSettings(tempDir, repoUser, repoPass);
                hasSettings = true;
            }

            // 7. Run Maven to install skills
            System.out.println("Resolving skill " + groupId + ":" + artifactId + ":" + version + "...");
            int exitCode = runMaven(mvnCommand, tempDir, hasSettings);
            if (exitCode != 0) {
                System.err.println("Error: Maven execution failed (exit code " + exitCode + ").");
                return exitCode;
            }

            // 8. Copy installed skills to target directory
            Path installedSkillsDir = tempDir.resolve(".claude").resolve("skills");
            Path targetDir = Paths.get(skillsDir).toAbsolutePath().normalize();

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

    /**
     * Finds the Maven command (mvn) on the system PATH.
     */
    private String findMavenCommand() {
        // Try mvn directly
        try {
            Process p = new ProcessBuilder("mvn", "--version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0) {
                return "mvn";
            }
        } catch (Exception ignored) {
        }

        // Try mvn.cmd for Windows
        try {
            Process p = new ProcessBuilder("mvn.cmd", "--version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0) {
                return "mvn.cmd";
            }
        } catch (Exception ignored) {
        }

        return null;
    }

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

    /**
     * Runs the Maven skills-jar:install goal in the temporary project directory.
     */
    private int runMaven(String mvnCommand, Path projectDir, boolean hasSettings)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(mvnCommand);
        command.add("-B"); // batch mode

        if (hasSettings) {
            command.add("-s");
            command.add(projectDir.resolve("settings.xml").toString());
        }

        command.add(PLUGIN_GROUP_ID + ":" + PLUGIN_ARTIFACT_ID + ":"
                + PLUGIN_VERSION + ":install");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Stream Maven output to stdout
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        return process.waitFor();
    }

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
