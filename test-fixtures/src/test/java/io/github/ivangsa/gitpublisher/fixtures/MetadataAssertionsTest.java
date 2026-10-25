/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Self-test: the harness accepts a valid tree and names each broken invariant precisely. */
class MetadataAssertionsTest {

    private static final String A_META = "org/acme/lib/maven-metadata.xml";
    private static final String V_META = "org/acme/lib/1.0-SNAPSHOT/maven-metadata.xml";
    private static final String V = "1.0-20261024.090000-1";

    @TempDir
    Path root;

    @Test
    void validTreePasses() throws IOException {
        validTree();
        assertThatCode(() -> MetadataAssertions.assertValidRepository(root)).doesNotThrowAnyException();
        assertThatCode(() -> MetadataAssertions.assertValidRepository(root, Set.of("sha1", "md5")))
                .doesNotThrowAnyException();
    }

    @Test
    void requiredChecksumAlgorithmMissing() throws IOException {
        validTree();
        assertThatThrownBy(() -> MetadataAssertions.assertValidRepository(root, Set.of("sha256")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(A_META + ": missing .sha256 checksum file")
                .hasMessageContaining(V_META + ": missing .sha256 checksum file");
    }

    @Test
    void malformedXml() throws IOException {
        validTree();
        writeWithChecksum(A_META, "<metadata><groupId>org.acme</groupId><versioning>");
        assertBroken(A_META + ": does not parse with maven-repository-metadata");
    }

    @Test
    void snapshotVersionPointsAtMissingFile() throws IOException {
        validTree();
        Files.delete(root.resolve("org/acme/lib/1.0-SNAPSHOT/lib-" + V + "-sources.jar"));
        Files.delete(root.resolve("org/acme/lib/1.0-SNAPSHOT/lib-" + V + "-sources.jar.sha1"));
        Files.delete(root.resolve("org/acme/lib/1.0-SNAPSHOT/lib-" + V + "-sources.jar.md5"));
        assertBroken(V_META + ": snapshotVersion (classifier='sources', extension='jar') points at missing file lib-"
                + V + "-sources.jar");
    }

    @Test
    void versionWithoutDirectory() throws IOException {
        validTree();
        Files.delete(root.resolve("org/acme/lib/1.0-SNAPSHOT/maven-metadata.xml"));
        Files.delete(root.resolve("org/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.sha1"));
        Files.delete(root.resolve("org/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.md5"));
        deleteTree(root.resolve("org/acme/lib/1.0-SNAPSHOT"));
        assertBroken(A_META + ": <version> 1.0-SNAPSHOT has no directory");
    }

    @Test
    void latestNotInVersions() throws IOException {
        validTree();
        writeWithChecksum(A_META, aLevel("<latest>9.9</latest>", ""));
        assertBroken(A_META + ": <latest> 9.9 is not in <versions>");
    }

    @Test
    void releaseNotInVersions() throws IOException {
        validTree();
        writeWithChecksum(A_META, aLevel("<latest>1.0-SNAPSHOT</latest>", "<release>2.0</release>"));
        assertBroken(A_META + ": <release> 2.0 is not in <versions>");
    }

    @Test
    void checksumMismatch() throws IOException {
        validTree();
        write(V_META + ".sha1", "0000000000000000000000000000000000000000");
        assertBroken(V_META + ".sha1: checksum mismatch");
    }

    @Test
    void metadataWithoutAnyChecksum() throws IOException {
        validTree();
        Files.delete(root.resolve(A_META + ".sha1"));
        Files.delete(root.resolve(A_META + ".md5"));
        assertBroken(A_META + ": has no checksum file");
    }

    @Test
    void checksumOfMissingFile() throws IOException {
        validTree();
        Files.delete(root.resolve("org/acme/lib/1.0-SNAPSHOT/lib-" + V + ".jar"));
        assertThatThrownBy(() -> MetadataAssertions.assertValidRepository(root))
                .hasMessageContaining("lib-" + V + ".jar.sha1: checksum of missing file lib-" + V + ".jar");
    }

    @Test
    void reportsEveryViolationAtOnce() throws IOException {
        validTree();
        write(V_META + ".sha1", "bad");
        writeWithChecksum(A_META, aLevel("<latest>9.9</latest>", ""));
        assertThatThrownBy(() -> MetadataAssertions.assertValidRepository(root))
                .hasMessageContaining("2 repository invariant violation(s)");
    }

    private void assertBroken(String message) {
        assertThatThrownBy(() -> MetadataAssertions.assertValidRepository(root))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("1 repository invariant violation(s)")
                .hasMessageContaining(message);
    }

    private void validTree() throws IOException {
        writeWithChecksum(A_META, aLevel("<latest>1.0-SNAPSHOT</latest>", ""));
        writeWithChecksum(
                V_META,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata modelVersion="1.1.0">
                  <groupId>org.acme</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0-SNAPSHOT</version>
                  <versioning>
                    <snapshot><timestamp>20261024.090000</timestamp><buildNumber>1</buildNumber></snapshot>
                    <lastUpdated>20261024090000</lastUpdated>
                    <snapshotVersions>
                      <snapshotVersion><extension>jar</extension><value>%1$s</value><updated>20261024090000</updated></snapshotVersion>
                      <snapshotVersion><classifier>sources</classifier><extension>jar</extension><value>%1$s</value><updated>20261024090000</updated></snapshotVersion>
                    </snapshotVersions>
                  </versioning>
                </metadata>
                """
                        .formatted(V));
        writeWithChecksum("org/acme/lib/1.0-SNAPSHOT/lib-" + V + ".jar", "jar bytes");
        writeWithChecksum("org/acme/lib/1.0-SNAPSHOT/lib-" + V + "-sources.jar", "sources bytes");
    }

    private static String aLevel(String latest, String release) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>org.acme</groupId>
                  <artifactId>lib</artifactId>
                  <versioning>
                    %s
                    %s
                    <versions><version>1.0-SNAPSHOT</version></versions>
                    <lastUpdated>20261024090000</lastUpdated>
                  </versioning>
                </metadata>
                """
                .formatted(latest, release);
    }

    private void write(String rel, String content) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, UTF_8);
    }

    private void writeWithChecksum(String rel, String content) throws IOException {
        write(rel, content);
        try {
            write(rel + ".sha1", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content.getBytes(UTF_8))));
            write(rel + ".md5", HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content.getBytes(UTF_8))));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
