/*
 * Copyright 2026 Ivan Garcia Sainz-Aja
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;

/**
 * Checks the invariants of docs/design.md section 5 on a Maven repository directory tree, using Maven's own
 * metadata reader (an oracle we do not control) to parse every {@code maven-metadata.xml}.
 *
 * <ul>
 *   <li>every file parses with {@code maven-repository-metadata};
 *   <li>every {@code snapshotVersion} resolves to an existing file {@code a-<value>[-classifier].<extension>};
 *   <li>{@code <snapshot>} (timestamp, buildNumber) names a build that has files in the directory;
 *   <li>every {@code version} of A-level metadata has an existing directory;
 *   <li>{@code latest} and {@code release} are members of {@code versions};
 *   <li>every metadata file has checksum files (at least one, or the configured algorithms), and every checksum
 *       file in the tree matches the file it belongs to.
 * </ul>
 */
public final class MetadataAssertions {

    private static final String METADATA = "maven-metadata.xml";

    /** Checksum file extension to {@link MessageDigest} algorithm. */
    private static final Map<String, String> CHECKSUMS =
            Map.of("md5", "MD5", "sha1", "SHA-1", "sha256", "SHA-256", "sha512", "SHA-512");

    private MetadataAssertions() {}

    /** Requires at least one checksum file next to every metadata file. */
    public static void assertValidRepository(Path root) {
        assertValidRepository(root, Set.of());
    }

    /**
     * @param requiredChecksums checksum extensions ({@code sha1}, {@code md5}, {@code sha256}, {@code sha512})
     *     that must exist next to every metadata file; when empty, at least one of them must
     */
    public static void assertValidRepository(Path root, Set<String> requiredChecksums) {
        List<String> violations = violations(root, requiredChecksums);
        if (!violations.isEmpty()) {
            throw new AssertionError(violations.size() + " repository invariant violation(s) in " + root + ":\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    /** Same checks as {@link #assertValidRepository(Path, Set)}, returned as messages instead of thrown. */
    public static List<String> violations(Path root, Set<String> requiredChecksums) {
        List<String> violations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.equals(METADATA)) {
                checkMetadata(root, file, requiredChecksums, violations);
            } else if (isChecksumFile(name)) {
                checkChecksumFile(root, file, violations);
            }
        }
        return violations;
    }

    private static void checkMetadata(Path root, Path file, Set<String> required, List<String> violations) {
        String rel = rel(root, file);
        Metadata metadata;
        try (InputStream in = Files.newInputStream(file)) {
            metadata = new MetadataXpp3Reader().read(in, true);
        } catch (Exception e) {
            violations.add(rel + ": does not parse with maven-repository-metadata: " + e.getMessage());
            return;
        }
        checkChecksumsPresent(root, file, required, violations);

        Path dir = file.getParent();
        Versioning versioning = metadata.getVersioning();
        if (metadata.getVersion() != null) {
            checkVersionLevel(rel, dir, metadata, versioning, violations);
        } else if (metadata.getArtifactId() != null) {
            checkArtifactLevel(rel, dir, metadata, versioning, violations);
        }
        // G-level (plugins): the plugin directories are not necessarily below this directory, nothing to resolve.
    }

    private static void checkVersionLevel(
            String rel, Path dir, Metadata metadata, Versioning versioning, List<String> violations) {
        if (!dir.getFileName().toString().equals(metadata.getVersion())) {
            violations.add(rel + ": <version> " + metadata.getVersion() + " does not match directory name "
                    + dir.getFileName());
        }
        if (versioning == null) {
            return;
        }
        for (SnapshotVersion sv : versioning.getSnapshotVersions()) {
            String classifier = sv.getClassifier() == null || sv.getClassifier().isEmpty()
                    ? ""
                    : "-" + sv.getClassifier();
            String expected = metadata.getArtifactId() + "-" + sv.getVersion() + classifier + "." + sv.getExtension();
            if (!Files.isRegularFile(dir.resolve(expected))) {
                violations.add(rel + ": snapshotVersion (classifier='" + nullToEmpty(sv.getClassifier())
                        + "', extension='" + sv.getExtension() + "') points at missing file " + expected);
            }
        }
        checkSnapshotElement(rel, dir, metadata, versioning, violations);
    }

    /** {@code <snapshot>} (timestamp, buildNumber) must name a build that has files in the directory. */
    private static void checkSnapshotElement(
            String rel, Path dir, Metadata metadata, Versioning versioning, List<String> violations) {
        var snapshot = versioning.getSnapshot();
        if (snapshot == null || snapshot.isLocalCopy() || snapshot.getTimestamp() == null) {
            return;
        }
        String version = metadata.getVersion();
        String base = version.endsWith("-SNAPSHOT") ? version.substring(0, version.length() - "-SNAPSHOT".length()) : version;
        String prefix = metadata.getArtifactId() + "-" + base + "-" + snapshot.getTimestamp() + "-"
                + snapshot.getBuildNumber();
        boolean found;
        try (Stream<Path> files = Files.list(dir)) {
            found = files.map(p -> p.getFileName().toString())
                    .anyMatch(n -> n.startsWith(prefix)
                            && (n.length() == prefix.length()
                                    || n.charAt(prefix.length()) == '.'
                                    || n.charAt(prefix.length()) == '-'));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!found) {
            violations.add(rel + ": <snapshot> " + snapshot.getTimestamp() + "-" + snapshot.getBuildNumber()
                    + " names a build that has no files in the directory");
        }
    }

    private static boolean hasRegularFile(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void checkArtifactLevel(
            String rel, Path dir, Metadata metadata, Versioning versioning, List<String> violations) {
        if (!dir.getFileName().toString().equals(metadata.getArtifactId())) {
            violations.add(rel + ": <artifactId> " + metadata.getArtifactId() + " does not match directory name "
                    + dir.getFileName());
        }
        if (versioning == null) {
            return;
        }
        List<String> versions = versioning.getVersions();
        for (String version : versions) {
            Path versionDir = dir.resolve(version);
            if (!Files.isDirectory(versionDir)) {
                violations.add(rel + ": <version> " + version + " has no directory");
            } else if (!hasRegularFile(versionDir)) {
                violations.add(rel + ": <version> " + version
                        + " has no files below its directory (Git does not store empty directories)");
            }
        }
        if (versioning.getLatest() != null && !versions.contains(versioning.getLatest())) {
            violations.add(rel + ": <latest> " + versioning.getLatest() + " is not in <versions> " + versions);
        }
        if (versioning.getRelease() != null && !versions.contains(versioning.getRelease())) {
            violations.add(rel + ": <release> " + versioning.getRelease() + " is not in <versions> " + versions);
        }
    }

    private static void checkChecksumsPresent(Path root, Path file, Set<String> required, List<String> violations) {
        String rel = rel(root, file);
        if (required.isEmpty()) {
            boolean any = CHECKSUMS.keySet().stream().anyMatch(ext -> Files.isRegularFile(sibling(file, ext)));
            if (!any) {
                violations.add(rel + ": has no checksum file");
            }
            return;
        }
        for (String ext : required) {
            if (!CHECKSUMS.containsKey(ext)) {
                throw new IllegalArgumentException("unknown checksum extension: " + ext);
            }
            if (!Files.isRegularFile(sibling(file, ext))) {
                violations.add(rel + ": missing ." + ext + " checksum file");
            }
        }
    }

    private static void checkChecksumFile(Path root, Path checksumFile, List<String> violations) {
        String name = checksumFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = name.substring(dot + 1);
        Path target = checksumFile.resolveSibling(name.substring(0, dot));
        String rel = rel(root, checksumFile);
        if (!Files.isRegularFile(target)) {
            violations.add(rel + ": checksum of missing file " + target.getFileName());
            return;
        }
        try {
            String expected = hex(CHECKSUMS.get(ext), Files.readAllBytes(target));
            String content = Files.readString(checksumFile, StandardCharsets.UTF_8).trim();
            String actual = content.split("\\s+", 2)[0].toLowerCase();
            if (!expected.equals(actual)) {
                violations.add(rel + ": checksum mismatch, file has " + expected + " but checksum file says '"
                        + actual + "'");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isChecksumFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && CHECKSUMS.containsKey(fileName.substring(dot + 1));
    }

    private static Path sibling(Path file, String extension) {
        return file.resolveSibling(file.getFileName() + "." + extension);
    }

    private static String hex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rel(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
