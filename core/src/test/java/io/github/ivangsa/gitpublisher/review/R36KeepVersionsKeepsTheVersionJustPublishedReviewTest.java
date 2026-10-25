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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-25-250f684, F1. Maven stamps V-level {@code lastUpdated} with the session start time, not the deploy
 * time (the same Resolver behaviour D-025 relies on for builds). A long build of {@code 2.0-SNAPSHOT} that started
 * before a short build of {@code 1.0-SNAPSHOT} but deploys after it therefore has the older {@code lastUpdated}. With
 * {@code keepVersions=1} the publication of {@code 2.0-SNAPSHOT} deletes the very version it just deployed, keeps the
 * one it did not touch, and still pushes: a successful deploy whose artifact is not on the branch. The brief asks to
 * drop "old" versions; the version published now is not old.
 */
class R36KeepVersionsKeepsTheVersionJustPublishedReviewTest {

    private static final Set<ChecksumAlgorithm> ALGORITHMS = Set.of(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.MD5);
    private static final Path ARTIFACT = Path.of("org/acme/lib");

    @TempDir
    Path root;

    private void deploy(String version, String sessionStart) {
        try (ResolverDeployer deployer = new ResolverDeployer(root, Instant.parse(sessionStart), "SHA-1", "MD5")) {
            deployer.deployJavaProject("org.acme", "lib", version);
        }
    }

    private Set<String> versionDirectories() throws Exception {
        try (Stream<Path> list = Files.list(root.resolve(ARTIFACT))) {
            return list.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void aVersionWhoseBuildStartedEarlierButDeploysNowIsNotDropped() throws Exception {
        deploy("1.0-SNAPSHOT", "2026-10-24T10:10:00Z"); // short build on a maintenance branch, deployed first
        deploy("2.0-SNAPSHOT", "2026-10-24T10:00:00Z"); // long build, started earlier, deployed now

        Retention.apply(
                root, List.of(ARTIFACT.resolve("2.0-SNAPSHOT")), new RetentionPolicy(3, 1), ALGORITHMS);

        assertThat(versionDirectories())
                .as("keepVersions must not delete the -SNAPSHOT version this publication just deployed")
                .contains("2.0-SNAPSHOT");
        assertThat(MetadataModel.read(root.resolve(ARTIFACT).resolve("maven-metadata.xml")).versions())
                .contains("2.0-SNAPSHOT");
        MetadataAssertions.assertValidRepository(root, Set.of("sha1", "md5"));
    }
}
