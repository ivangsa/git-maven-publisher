/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks the invariants of docs/design.md section 5 on the tree that is about to be committed. A violation means a bug
 * (ours or the native deploy's) and the publication must fail before anything is pushed.
 *
 * <ul>
 *   <li>every {@code maven-metadata.xml} is well formed;
 *   <li>every {@code snapshotVersion} resolves to an existing file {@code a-<value>[-classifier].<extension>};
 *   <li>{@code <snapshot>} (timestamp, buildNumber) names a build that has files in the directory (D-025);
 *   <li>every {@code version} of A-level metadata has a directory with at least one regular file below it (D-031);
 *   <li>{@code latest} and {@code release} are members of {@code versions};
 *   <li>every metadata file has checksum files for the configured algorithms (at least one when none are configured),
 *       and every checksum file in the tree matches the file it belongs to.
 * </ul>
 */
final class IntegrityCheck {

    private static final String METADATA = "maven-metadata.xml";

    private IntegrityCheck() {}

    /** @throws PublishException listing every violation */
    static void verify(Path root, Set<ChecksumAlgorithm> required) throws PublishException {
        List<String> violations = violations(root, required);
        if (!violations.isEmpty()) {
            throw new PublishException("integrity check failed, " + violations.size() + " violation(s) in the tree to"
                    + " publish; nothing was pushed:\n  - " + String.join("\n  - ", violations));
        }
    }

    /**
     * @param required checksum algorithms that must exist next to every metadata file; when empty, at least one of
     *     them must
     */
    static List<String> violations(Path root, Set<ChecksumAlgorithm> required) {
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
                checkMetadata(root, file, required, violations);
            } else if (isChecksumFile(name)) {
                checkChecksumFile(root, file, violations);
            }
        }
        return violations;
    }

    private static void checkMetadata(Path root, Path file, Set<ChecksumAlgorithm> required, List<String> violations) {
        String rel = rel(root, file);
        MetadataModel metadata;
        try {
            metadata = MetadataModel.read(file);
        } catch (IOException e) {
            violations.add(rel + ": does not parse: " + String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
            return;
        }
        checkChecksumsPresent(root, file, required, violations);

        Path dir = file.getParent();
        switch (metadata.level()) {
            case VERSION -> checkVersionLevel(rel, dir, metadata, violations);
            case ARTIFACT -> checkArtifactLevel(rel, dir, metadata, violations);
            case GROUP -> {
                // The plugin directories are not necessarily below this directory, nothing to resolve.
            }
        }
    }

    private static void checkVersionLevel(String rel, Path dir, MetadataModel metadata, List<String> violations) {
        String version = metadata.version().orElseThrow();
        if (!dir.getFileName().toString().equals(version)) {
            violations.add(rel + ": <version> " + version + " does not match directory name " + dir.getFileName());
        }
        String artifactId = metadata.artifactId().orElse("");
        for (MetadataModel.SnapshotVersion sv : metadata.snapshotVersions()) {
            String classifier = sv.classifier().isEmpty() ? "" : "-" + sv.classifier();
            String expected = artifactId + "-" + sv.value() + classifier + "." + sv.extension();
            if (!Files.isRegularFile(dir.resolve(expected))) {
                violations.add(rel + ": snapshotVersion (classifier='" + sv.classifier() + "', extension='"
                        + sv.extension() + "') points at missing file " + expected);
            }
        }
        checkSnapshotElement(rel, dir, artifactId, version, metadata, violations);
    }

    /** {@code <snapshot>} (timestamp, buildNumber) must name a build that has files in the directory. */
    private static void checkSnapshotElement(
            String rel, Path dir, String artifactId, String version, MetadataModel metadata, List<String> violations) {
        var snapshot = metadata.snapshot().orElse(null);
        if (snapshot == null || snapshot.localCopy() || snapshot.timestamp() == null) {
            return;
        }
        String base = version.endsWith("-SNAPSHOT")
                ? version.substring(0, version.length() - "-SNAPSHOT".length())
                : version;
        String prefix = artifactId + "-" + base + "-" + snapshot.timestamp() + "-" + snapshot.buildNumber();
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
            violations.add(rel + ": <snapshot> " + snapshot.timestamp() + "-" + snapshot.buildNumber()
                    + " names a build that has no files in the directory");
        }
    }

    private static void checkArtifactLevel(String rel, Path dir, MetadataModel metadata, List<String> violations) {
        String artifactId = metadata.artifactId().orElseThrow();
        if (!dir.getFileName().toString().equals(artifactId)) {
            violations.add(rel + ": <artifactId> " + artifactId + " does not match directory name "
                    + dir.getFileName());
        }
        List<String> versions = metadata.versions();
        for (String version : versions) {
            Path versionDir = dir.resolve(version);
            if (!Files.isDirectory(versionDir)) {
                violations.add(rel + ": <version> " + version + " has no directory");
            } else if (!hasRegularFile(versionDir)) {
                violations.add(rel + ": <version> " + version
                        + " has no files below its directory (Git does not store empty directories)");
            }
        }
        metadata.latest().ifPresent(latest -> {
            if (!versions.contains(latest)) {
                violations.add(rel + ": <latest> " + latest + " is not in <versions> " + versions);
            }
        });
        metadata.release().ifPresent(release -> {
            if (!versions.contains(release)) {
                violations.add(rel + ": <release> " + release + " is not in <versions> " + versions);
            }
        });
    }

    private static boolean hasRegularFile(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void checkChecksumsPresent(
            Path root, Path file, Set<ChecksumAlgorithm> required, List<String> violations) {
        String rel = rel(root, file);
        if (required.isEmpty()) {
            boolean any = Stream.of(ChecksumAlgorithm.values())
                    .anyMatch(a -> Files.isRegularFile(ChecksumWriter.checksumFile(file, a)));
            if (!any) {
                violations.add(rel + ": has no checksum file");
            }
            return;
        }
        for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
            if (required.contains(algorithm) && !Files.isRegularFile(ChecksumWriter.checksumFile(file, algorithm))) {
                violations.add(rel + ": missing ." + algorithm.extension() + " checksum file");
            }
        }
    }

    private static void checkChecksumFile(Path root, Path checksumFile, List<String> violations) {
        String name = checksumFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        ChecksumAlgorithm algorithm =
                ChecksumAlgorithm.fromExtension(name.substring(dot + 1)).orElseThrow();
        Path target = checksumFile.resolveSibling(name.substring(0, dot));
        String rel = rel(root, checksumFile);
        if (!Files.isRegularFile(target)) {
            violations.add(rel + ": checksum of missing file " + target.getFileName());
            return;
        }
        try {
            String expected = ChecksumWriter.hex(algorithm, Files.readAllBytes(target));
            String content = Files.readString(checksumFile, StandardCharsets.UTF_8).trim();
            String actual = content.split("\\s+", 2)[0].toLowerCase();
            if (!expected.equals(actual)) {
                violations.add(rel + ": checksum mismatch, file has " + expected + " but checksum file says '" + actual
                        + "'");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isChecksumFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && ChecksumAlgorithm.fromExtension(fileName.substring(dot + 1)).isPresent();
    }

    private static String rel(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
