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
package io.github.ivangsa.gitpublisher;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Retention per {@code groupId:artifactId} (design section 6): drops {@code -SNAPSHOT} versions whose release exists
 * on the release repository, then the oldest versions beyond {@code keepVersions}, and fixes the artifact-level
 * metadata. Whole version directories go; the files next to them, release directories and the artifact-level metadata
 * itself are never deleted.
 */
final class VersionRetention {

    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    private VersionRetention() {}

    /**
     * @param artifactDirectory {@code <root>/<groupPath>/<artifactId>}
     * @param touched the {@code -SNAPSHOT} directories published now; they are never dropped (D-107)
     * @return the deleted files, relative to {@code root}
     */
    static List<Path> apply(
            Path root,
            Path artifactDirectory,
            Set<Path> touched,
            RetentionPolicy policy,
            Set<ChecksumAlgorithm> checksums,
            ReleaseCheck releaseCheck)
            throws IOException {
        boolean byRelease = policy.releaseRepository() != null;
        boolean byCount = policy.keepVersions() != RetentionPolicy.UNLIMITED;
        if (!byRelease && !byCount) {
            return List.of();
        }
        List<Path> versions = snapshotDirectories(artifactDirectory);
        Set<Path> drop = new LinkedHashSet<>();

        if (byRelease) {
            String artifactId = artifactDirectory.getFileName().toString();
            String groupPath = root.relativize(artifactDirectory.getParent()).toString().replace('\\', '/');
            for (Path version : versions) {
                if (!touched.contains(version) && releaseExists(releaseCheck, policy.releaseRepository(), groupPath, artifactId, version)) {
                    drop.add(version);
                }
            }
        }
        if (byCount) {
            List<Path> remaining = new ArrayList<>(versions);
            remaining.removeAll(drop);
            // Versions published now are never dropped and count toward N (D-107); the others are ranked by
            // lastUpdated, newest first (none counts as oldest, ties by name), and the oldest beyond N go.
            List<Path> others = new ArrayList<>(remaining);
            others.removeAll(touched);
            int room = Math.max(0, policy.keepVersions() - (remaining.size() - others.size()));
            if (others.size() > room) {
                others.sort(Comparator.comparing(VersionRetention::lastUpdated)
                        .thenComparing(p -> p.getFileName().toString())
                        .reversed());
                drop.addAll(others.subList(room, others.size()));
            }
        }
        if (drop.isEmpty()) {
            return List.of();
        }

        List<Path> deleted = new ArrayList<>();
        for (Path version : drop) {
            try (Stream<Path> walk = Files.walk(version)) {
                walk.filter(Files::isRegularFile).forEach(f -> deleted.add(root.relativize(f)));
            }
            FileTrees.deleteRecursively(version);
        }
        rewriteMetadata(artifactDirectory, drop, checksums);
        return deleted;
    }

    private static List<Path> snapshotDirectories(Path artifactDirectory) throws IOException {
        try (Stream<Path> list = Files.list(artifactDirectory)) {
            return list.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().endsWith(SNAPSHOT_SUFFIX))
                    .sorted()
                    .toList();
        }
    }

    private static String lastUpdated(Path versionDirectory) {
        Path file = versionDirectory.resolve("maven-metadata.xml");
        try {
            return Files.isRegularFile(file) ? MetadataModel.read(file).lastUpdated().orElse("") : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static void rewriteMetadata(Path artifactDirectory, Set<Path> dropped, Set<ChecksumAlgorithm> checksums)
            throws IOException {
        Path file = artifactDirectory.resolve("maven-metadata.xml");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        dropped.forEach(p -> names.add(p.getFileName().toString()));
        MetadataModel metadata = MetadataModel.read(file);
        if (metadata.removeVersions(names) == 0) {
            return;
        }
        List<String> remaining = metadata.versions();
        if (metadata.latest().filter(names::contains).isPresent()) {
            metadata.setLatest(remaining.isEmpty() ? null : remaining.get(remaining.size() - 1));
        }
        if (metadata.release().filter(names::contains).isPresent()) { // a release is never a snapshot; defensive
            metadata.setRelease(null);
        }
        metadata.write(file);
        ChecksumWriter.write(file, checksums);
    }

    private static boolean releaseExists(
            ReleaseCheck check, URI repository, String groupPath, String artifactId, Path snapshotDirectory) {
        String version = snapshotDirectory.getFileName().toString();
        String base = version.substring(0, version.length() - SNAPSHOT_SUFFIX.length());
        return !base.isEmpty() && check.exists(repository, groupPath, artifactId, base);
    }
}
