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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Snapshot retention (design section 6): per touched {@code -SNAPSHOT} version directory keep the newest
 * {@code keepSnapshots} builds, delete the files of the older ones and drop their entries from the version-level
 * metadata.
 */
final class Retention {

    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    private Retention() {}

    /** A build: files sharing {@code <yyyyMMdd.HHmmss>-<buildNumber>}. */
    private record Build(String timestamp, int buildNumber) {
        static final Comparator<Build> NEWEST_FIRST = Comparator.comparingInt(Build::buildNumber)
                .thenComparing(Build::timestamp)
                .reversed();
    }

    /**
     * Applies retention to the given version directories.
     *
     * @param root the served tree
     * @param touchedVersionDirectories directories of {@code -SNAPSHOT} versions, absolute or relative to
     *     {@code root}; other directories and missing ones are ignored
     * @param checksums configured checksum algorithms, used when metadata is rewritten
     * @return the deleted files, relative to {@code root}, sorted
     */
    static List<Path> apply(
            Path root, Iterable<Path> touchedVersionDirectories, RetentionPolicy policy, Set<ChecksumAlgorithm> checksums)
            throws IOException {
        List<Path> deleted = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Path touched : touchedVersionDirectories) {
            Path dir = root.resolve(touched).normalize();
            if (seen.add(dir) && Files.isDirectory(dir)) {
                deleted.addAll(applyTo(root, dir, policy.keepSnapshots(), checksums));
            }
        }
        deleted.sort(Comparator.naturalOrder());
        return deleted;
    }

    private static List<Path> applyTo(Path root, Path dir, int keep, Set<ChecksumAlgorithm> checksums)
            throws IOException {
        String version = dir.getFileName().toString();
        if (!version.endsWith(SNAPSHOT_SUFFIX)) {
            return List.of();
        }
        String base = version.substring(0, version.length() - SNAPSHOT_SUFFIX.length());
        // Anchored: the complete artifactId and base version come first (design section 6).
        String artifactId = dir.getParent().getFileName().toString();
        Pattern buildPattern = Pattern.compile(
                "^" + Pattern.quote(artifactId + "-" + base) + "-(\\d{8}\\.\\d{6})-(\\d+)(?=[-.])");

        Map<Build, List<Path>> builds = new TreeMap<>(Build.NEWEST_FIRST);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Matcher m = buildPattern.matcher(file.getFileName().toString());
                if (m.find()) { // anchored at the start: complete artifactId and base version first
                    builds.computeIfAbsent(new Build(m.group(1), Integer.parseInt(m.group(2))), b -> new ArrayList<>())
                            .add(file);
                }
            }
        }
        if (builds.size() <= keep) {
            return List.of();
        }

        List<Path> deleted = new ArrayList<>();
        Set<String> deletedNames = new HashSet<>();
        int index = 0;
        for (List<Path> files : builds.values()) {
            if (index++ >= keep) {
                for (Path file : files) {
                    Files.delete(file);
                    deletedNames.add(file.getFileName().toString());
                    deleted.add(root.relativize(file));
                }
            }
        }
        rewriteMetadata(dir, deletedNames, checksums);
        return deleted;
    }

    private static void rewriteMetadata(Path dir, Set<String> deletedNames, Set<ChecksumAlgorithm> checksums)
            throws IOException {
        Path file = dir.resolve("maven-metadata.xml");
        if (!Files.isRegularFile(file)) {
            return;
        }
        MetadataModel metadata = MetadataModel.read(file);
        String artifactId = metadata.artifactId().orElse(dir.getParent().getFileName().toString());
        int removed = metadata.removeSnapshotVersionsIf(sv -> {
            String suffix = sv.classifier().isEmpty() ? "" : "-" + sv.classifier();
            return deletedNames.contains(artifactId + "-" + sv.value() + suffix + "." + sv.extension());
        });
        if (removed > 0) {
            metadata.write(file);
            ChecksumWriter.write(file, checksums);
        }
    }
}
