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

import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer.Part;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Retention on trees written by the real Resolver deploy (design section 6). */
class RetentionTest {

    private static final Path V_DIR = Path.of("org/acme/lib/1.0-SNAPSHOT");
    private static final Set<String> CHECKSUMS = Set.of("sha1", "md5");
    private static final Set<ChecksumAlgorithm> ALGORITHMS = Set.of(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.MD5);
    private static final Pattern BUILD = Pattern.compile("(\\d{8}\\.\\d{6}-\\d+)");

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void keepsExactlyTheNewestBuilds(int builds) throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(root, "SHA-1", "MD5")) {
            for (int i = 0; i < builds; i++) {
                deployWithModule(deployer);
            }
        }
        Set<String> before = buildIds();
        assertThat(before).hasSize(builds);

        for (int keep : new int[] {3, 2, 1}) {
            Retention.apply(root, List.of(V_DIR), new RetentionPolicy(keep, -1), ALGORITHMS);

            assertThat(buildIds()).hasSize(Math.min(builds, keep));
            assertThat(buildIds()).isSubsetOf(before);
            MetadataAssertions.assertValidRepository(root, CHECKSUMS);
        }
        // the survivor is the highest build number
        assertThat(buildIds()).singleElement().asString().endsWith("-" + builds);
    }

    @Test
    void deletesAllFilesOfOldBuildsAndKeepsAllFilesOfNewOnes() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(root, "SHA-1", "MD5")) {
            for (int i = 0; i < 4; i++) {
                deployWithModule(deployer);
            }
        }
        List<Path> deleted = Retention.apply(root, List.of(V_DIR), new RetentionPolicy(2, -1), ALGORITHMS);

        // 2 old builds x (jar, pom, module, sources) x (file + sha1 + md5)
        assertThat(deleted).hasSize(2 * 4 * 3).allSatisfy(p -> assertThat(p.getParent()).isEqualTo(V_DIR));
        assertThat(files()).filteredOn(n -> n.endsWith("-3.module") || n.endsWith("-4.module")).hasSize(2);
        assertThat(files()).filteredOn(n -> n.endsWith("-4-sources.jar.sha1")).hasSize(1);
        assertThat(files())
                .noneMatch(n -> n.matches(".*\\d{6}-[12][-.].*"));
    }

    @Test
    void dropsMetadataEntriesOfDeletedFilesAndRegeneratesChecksums() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(root, "SHA-1", "MD5")) {
            deployer.deploy(
                    "org.acme",
                    "lib",
                    "1.0-SNAPSHOT",
                    Part.of("", "jar", "jar"),
                    Part.of("javadoc", "jar", "javadoc"));
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
        }
        MetadataModel before = MetadataModel.read(root.resolve(V_DIR).resolve("maven-metadata.xml"));
        assertThat(before.snapshotVersions()).anyMatch(sv -> sv.classifier().equals("javadoc"));

        Retention.apply(root, List.of(V_DIR), new RetentionPolicy(1, -1), ALGORITHMS);

        MetadataModel after = MetadataModel.read(root.resolve(V_DIR).resolve("maven-metadata.xml"));
        assertThat(after.snapshotVersions()).noneMatch(sv -> sv.classifier().equals("javadoc"));
        assertThat(after.snapshot()).isEqualTo(before.snapshot());
        MetadataAssertions.assertValidRepository(root, CHECKSUMS);
    }

    @Test
    void leavesMetadataBytesUntouchedWhenNothingIsRemoved() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(root, "SHA-1", "MD5")) {
            for (int i = 0; i < 5; i++) {
                deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
            }
        }
        Path metadata = root.resolve(V_DIR).resolve("maven-metadata.xml");
        byte[] bytes = Files.readAllBytes(metadata);

        Retention.apply(root, List.of(V_DIR), new RetentionPolicy(3, -1), ALGORITHMS);

        assertThat(Files.readAllBytes(metadata)).isEqualTo(bytes);
    }

    @Test
    void ordersByBuildNumberBeforeTimestamp() throws Exception {
        try (ResolverDeployer first =
                new ResolverDeployer(root, Instant.parse("2026-10-24T10:00:00Z"), "SHA-1", "MD5")) {
            first.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
        }
        // started earlier, deployed later: older timestamp, higher build number (D-025)
        try (ResolverDeployer second =
                new ResolverDeployer(root, Instant.parse("2026-10-24T09:00:00Z"), "SHA-1", "MD5")) {
            second.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
        }
        assertThat(buildIds()).hasSize(2);

        Retention.apply(root, List.of(V_DIR), new RetentionPolicy(1, -1), ALGORITHMS);

        assertThat(buildIds()).singleElement().asString().startsWith("20261024.09").endsWith("-2");
        MetadataAssertions.assertValidRepository(root, CHECKSUMS);
    }

    @Test
    void neverDeletesNonTimestampedFilesAndOnlyTouchesTheGivenDirectories() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(root, "SHA-1", "MD5")) {
            for (int i = 0; i < 3; i++) {
                deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
                deployer.deployJavaProject("org.acme", "lib", "2.0-SNAPSHOT");
            }
        }
        Files.writeString(root.resolve(V_DIR).resolve("lib-1.0-SNAPSHOT.jar"), "plain");
        Files.writeString(root.resolve(V_DIR).resolve("README.txt"), "notes");

        Retention.apply(
                root,
                List.of(V_DIR, Path.of("org/acme/lib/missing-SNAPSHOT"), Path.of("org/acme/lib")),
                new RetentionPolicy(1, -1),
                ALGORITHMS);

        assertThat(files()).contains("lib-1.0-SNAPSHOT.jar", "README.txt");
        try (Stream<Path> other = Files.list(root.resolve("org/acme/lib/2.0-SNAPSHOT"))) {
            assertThat(other.map(p -> BUILD.matcher(p.getFileName().toString()))
                            .filter(Matcher::find)
                            .map(m -> m.group(1))
                            .distinct())
                    .hasSize(3);
        }
    }

    private static void deployWithModule(ResolverDeployer deployer) {
        deployer.deploy(
                "org.acme",
                "lib",
                "1.0-SNAPSHOT",
                Part.of("", "jar", "jar"),
                Part.of("", "pom", ResolverDeployer.pom("org.acme", "lib", "1.0-SNAPSHOT")),
                Part.of("", "module", "{\"formatVersion\":\"1.1\"}"),
                Part.of("sources", "jar", "sources"));
    }

    private Set<String> buildIds() throws IOException {
        Set<String> ids = new TreeSet<>();
        for (String name : files()) {
            Matcher m = BUILD.matcher(name);
            if (m.find()) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }

    private List<String> files() throws IOException {
        try (Stream<Path> list = Files.list(root.resolve(V_DIR))) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
