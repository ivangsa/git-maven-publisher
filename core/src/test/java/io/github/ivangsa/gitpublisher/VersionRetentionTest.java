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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code keepVersions} (T47) and release-aware cleanup (T48) on trees written by the real Resolver deploy. */
class VersionRetentionTest {

    private static final Set<String> CHECKSUMS = Set.of("sha1", "md5");
    private static final Set<ChecksumAlgorithm> ALGORITHMS = Set.of(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.MD5);
    private static final Path ARTIFACT = Path.of("org/acme/lib");

    @TempDir
    Path root;

    @TempDir
    Path releases;

    private void deploy(String groupId, String version, String start) {
        try (ResolverDeployer deployer = new ResolverDeployer(root, Instant.parse(start), "SHA-1", "MD5")) {
            deployer.deployJavaProject(groupId, "lib", version + "-SNAPSHOT");
        }
    }

    private static Path dir(String version) {
        return ARTIFACT.resolve(version + "-SNAPSHOT");
    }

    private MetadataModel artifactMetadata() throws Exception {
        return MetadataModel.read(root.resolve(ARTIFACT).resolve("maven-metadata.xml"));
    }

    private Set<String> directories() throws Exception {
        try (Stream<Path> list = Files.list(root.resolve(ARTIFACT))) {
            return list.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void dropsTheVersionsWithTheOldestLastUpdatedNotTheLowestNumbers() throws Exception {
        deploy("org.acme", "3.0", "2026-10-24T10:00:00Z"); // highest number, oldest
        deploy("org.acme", "1.0", "2026-10-24T11:00:00Z");
        deploy("org.acme", "2.0", "2026-10-24T12:00:00Z");

        List<Path> deleted = Retention.apply(root, List.of(dir("2.0")), new RetentionPolicy(3, 2), ALGORITHMS);

        assertThat(directories()).containsExactlyInAnyOrder("1.0-SNAPSHOT", "2.0-SNAPSHOT");
        assertThat(artifactMetadata().versions()).containsExactlyInAnyOrder("1.0-SNAPSHOT", "2.0-SNAPSHOT");
        assertThat(deleted).isNotEmpty().allSatisfy(p -> assertThat(p.startsWith(dir("3.0"))).isTrue());
        MetadataAssertions.assertValidRepository(root, CHECKSUMS);
    }

    @Test
    void movesLatestWhenItNamesADroppedVersion() throws Exception {
        deploy("org.acme", "2.0", "2026-10-24T12:00:00Z");
        deploy("org.acme", "1.0", "2026-10-24T10:00:00Z");
        // the Resolver does not write <latest> for snapshots; other tools do (Gradle, older Maven)
        Path file = root.resolve(ARTIFACT).resolve("maven-metadata.xml");
        MetadataModel model = MetadataModel.read(file);
        model.setLatest("1.0-SNAPSHOT");
        model.write(file);
        ChecksumWriter.write(file, ALGORITHMS);
        assertThat(artifactMetadata().latest()).contains("1.0-SNAPSHOT");

        Retention.apply(root, List.of(dir("2.0")), new RetentionPolicy(3, 1), ALGORITHMS);

        assertThat(directories()).containsExactly("2.0-SNAPSHOT");
        assertThat(artifactMetadata().latest()).contains("2.0-SNAPSHOT");
        MetadataAssertions.assertValidRepository(root, CHECKSUMS);
    }

    @Test
    void unlimitedAndUnderTheLimitChangeNothing() throws Exception {
        deploy("org.acme", "1.0", "2026-10-24T10:00:00Z");
        deploy("org.acme", "2.0", "2026-10-24T11:00:00Z");
        byte[] metadata = Files.readAllBytes(root.resolve(ARTIFACT).resolve("maven-metadata.xml"));

        assertThat(Retention.apply(root, List.of(dir("2.0")), new RetentionPolicy(3, -1), ALGORITHMS)).isEmpty();
        assertThat(Retention.apply(root, List.of(dir("2.0")), new RetentionPolicy(3, 2), ALGORITHMS)).isEmpty();

        assertThat(directories()).hasSize(2);
        assertThat(Files.readAllBytes(root.resolve(ARTIFACT).resolve("maven-metadata.xml")))
                .isEqualTo(metadata);
    }

    @Test
    void countsPerGroupIdAndArtifactIdAndOnlyForTouchedArtifacts() throws Exception {
        deploy("org.acme", "1.0", "2026-10-24T10:00:00Z");
        deploy("org.acme", "2.0", "2026-10-24T11:00:00Z");
        deploy("org.other", "1.0", "2026-10-24T10:00:00Z");
        deploy("org.other", "2.0", "2026-10-24T11:00:00Z");

        Retention.apply(root, List.of(dir("2.0")), new RetentionPolicy(3, 1), ALGORITHMS);

        assertThat(directories()).containsExactly("2.0-SNAPSHOT");
        try (Stream<Path> list = Files.list(root.resolve("org/other/lib"))) {
            assertThat(list.filter(Files::isDirectory)).hasSize(2); // not touched
        }
        MetadataAssertions.assertValidRepository(root, CHECKSUMS);
    }

    @Test
    void keepsReleaseDirectoriesAndOtherFilesNextToTheVersions() throws Exception {
        deploy("org.acme", "1.0", "2026-10-24T10:00:00Z");
        deploy("org.acme", "2.0", "2026-10-24T11:00:00Z");
        Files.createDirectories(root.resolve(ARTIFACT).resolve("0.9"));
        Files.writeString(root.resolve(ARTIFACT).resolve("0.9/lib-0.9.jar"), "release");
        Files.writeString(root.resolve(ARTIFACT).resolve("README.txt"), "notes");

        Retention.apply(root, List.of(dir("2.0")), new RetentionPolicy(3, 1), ALGORITHMS);

        assertThat(directories()).containsExactlyInAnyOrder("0.9", "2.0-SNAPSHOT");
        assertThat(root.resolve(ARTIFACT).resolve("README.txt")).exists();
    }

    // ---- T48 ----

    private void release(String groupPath, String version) throws Exception {
        Path dir = releases.resolve(groupPath).resolve("lib").resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("lib-" + version + ".pom"), "<project/>");
    }

    private RetentionPolicy releaseAware() {
        return new RetentionPolicy(3, -1).withReleaseRepository(releases.toUri());
    }

    @Test
    void dropsASnapshotVersionOnceItsReleaseExistsInTheReleaseRepository() throws Exception {
        deploy("org.acme", "1.0", "2026-10-24T10:00:00Z");
        deploy("org.acme", "2.0", "2026-10-24T11:00:00Z");
        deploy("org.acme", "3.0", "2026-10-24T12:00:00Z");
        release("org/acme", "1.0");
        release("org/acme", "3.0"); // the version being published stays
        release("org/other", "2.0"); // another groupId

        List<Path> deleted = Retention.apply(root, List.of(dir("3.0")), releaseAware(), ALGORITHMS);

        assertThat(directories()).containsExactlyInAnyOrder("2.0-SNAPSHOT", "3.0-SNAPSHOT");
        assertThat(artifactMetadata().versions()).containsExactlyInAnyOrder("2.0-SNAPSHOT", "3.0-SNAPSHOT");
        assertThat(deleted).isNotEmpty().allSatisfy(p -> assertThat(p.startsWith(dir("1.0"))).isTrue());
        MetadataAssertions.assertValidRepository(root, CHECKSUMS);
    }

    @Test
    void doesNothingWithoutAReleaseRepositoryOrWhenItCannotBeReached() throws Exception {
        deploy("org.acme", "1.0", "2026-10-24T10:00:00Z");
        deploy("org.acme", "2.0", "2026-10-24T11:00:00Z");
        release("org/acme", "1.0");

        Retention.apply(root, List.of(dir("2.0")), new RetentionPolicy(3, -1), ALGORITHMS);
        assertThat(directories()).hasSize(2);

        URI missing = releases.resolve("does-not-exist/").toUri();
        Retention.apply(
                root, List.of(dir("2.0")), new RetentionPolicy(3, -1).withReleaseRepository(missing), ALGORITHMS);
        assertThat(directories()).hasSize(2);
    }

    @Test
    void releaseCleanupKeepVersionsAndBuildRetentionKeepTheCanonicalFiles() throws Exception {
        for (int i = 0; i < 3; i++) {
            deploy("org.acme", "1.0", "2026-10-24T10:0" + i + ":00Z");
            deploy("org.acme", "2.0", "2026-10-24T11:0" + i + ":00Z");
        }
        deploy("org.acme", "3.0", "2026-10-24T12:00:00Z");
        Files.writeString(root.resolve(dir("2.0")).resolve("lib-2.0-SNAPSHOT.jar"), "plain");
        release("org/acme", "1.0");

        Retention.apply(
                root,
                List.of(dir("2.0"), dir("3.0")),
                new RetentionPolicy(1, 2).withReleaseRepository(releases.toUri()),
                ALGORITHMS);

        assertThat(directories()).containsExactlyInAnyOrder("2.0-SNAPSHOT", "3.0-SNAPSHOT");
        try (Stream<Path> list = Files.list(root.resolve(dir("2.0")))) {
            List<String> names = list.map(p -> p.getFileName().toString()).toList();
            assertThat(names).contains("lib-2.0-SNAPSHOT.jar");
            assertThat(names)
                    .filteredOn(n -> n.startsWith("lib-2.0-2026"))
                    .isNotEmpty()
                    .allMatch(n -> n.matches("lib-2\\.0-\\d{8}\\.\\d{6}-3[-.].*"));
        }
        MetadataAssertions.assertValidRepository(root, CHECKSUMS);
    }

    @Test
    void rejectsAReleaseRepositoryWithAnotherSchemeOrCredentials() {
        assertThatThrownBy(() -> new RetentionPolicy(3, -1).withReleaseRepository(URI.create("http://example.org/m2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new RetentionPolicy(3, -1).withReleaseRepository(URI.create("https://u:p@example.org/m2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- R36 ----

    @Test
    void versionsPublishedNowAreNeverDroppedAndCountTowardTheLimit() throws Exception {
        deploy("org.acme", "1.0", "2026-10-24T12:00:00Z"); // newest lastUpdated, not published now
        deploy("org.acme", "2.0", "2026-10-24T10:00:00Z"); // published now, oldest lastUpdated
        deploy("org.acme", "3.0", "2026-10-24T09:00:00Z"); // published now, oldest of all

        Retention.apply(root, List.of(dir("2.0"), dir("3.0")), new RetentionPolicy(3, 2), ALGORITHMS);

        // two touched versions fill N=2: every untouched version goes, the touched ones stay whatever their age
        assertThat(directories()).containsExactlyInAnyOrder("2.0-SNAPSHOT", "3.0-SNAPSHOT");
        assertThat(artifactMetadata().versions()).containsExactlyInAnyOrder("2.0-SNAPSHOT", "3.0-SNAPSHOT");
        MetadataAssertions.assertValidRepository(root, CHECKSUMS);
    }

    @Test
    void untouchedVersionsFillTheRoomLeftByTheTouchedOnes() throws Exception {
        deploy("org.acme", "1.0", "2026-10-24T10:00:00Z");
        deploy("org.acme", "2.0", "2026-10-24T11:00:00Z");
        deploy("org.acme", "3.0", "2026-10-24T09:00:00Z"); // published now

        Retention.apply(root, List.of(dir("3.0")), new RetentionPolicy(3, 2), ALGORITHMS);

        assertThat(directories()).containsExactlyInAnyOrder("2.0-SNAPSHOT", "3.0-SNAPSHOT");
    }
}
