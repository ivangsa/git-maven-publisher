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
package io.github.ivangsa.gitpublisher.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R38: {@code releaseRepository} of the {@code gitMavenPublisher} block, against a {@code file:} release repository. */
class ReleaseCleanupTest {

    @TempDir
    Path temp;

    @Test
    void aSnapshotVersionWhoseReleaseExistsIsDroppedAndTheOthersStay() throws IOException {
        Path seed = temp.resolve("seed");
        try (ResolverDeployer deployer = new ResolverDeployer(seed, Instant.parse("2026-10-24T10:00:00Z"), "SHA-1", "MD5")) {
            deployer.deployJavaProject("com.acme", "a", "0.9-SNAPSHOT"); // released
            deployer.deployJavaProject("com.acme", "a", "0.95-SNAPSHOT"); // not released
        }
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(seed)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(seed.relativize(file).toString().replace(java.io.File.separatorChar, '/'), Files.readAllBytes(file));
            }
        }
        Path releases = temp.resolve("releases");
        Files.createDirectories(releases.resolve("com/acme/a/0.9"));
        Files.writeString(releases.resolve("com/acme/a/0.9/a-0.9.pom"), "<project/>");

        try (BareRepo remote = BareRepo.create(temp.resolve("remote.git"))) {
            remote.commitFiles(PublisherBuild.BRANCH, files, "seed");
            PublisherBuild build = new PublisherBuild(temp.resolve("project"), remote)
                    .publisherSettings("releaseRepository = '" + releases.toUri() + "'")
                    .withProjects("a");

            build.run(List.of("--no-configuration-cache"), "publishAllPublicationsToGitPublisherRepository");

            Path checkout = remote.checkout(PublisherBuild.BRANCH, temp.resolve("checkout"));
            assertFalse(Files.exists(checkout.resolve("com/acme/a/0.9-SNAPSHOT")), "the released version is gone");
            assertTrue(Files.isDirectory(checkout.resolve("com/acme/a/0.95-SNAPSHOT")));
            assertTrue(Files.isDirectory(checkout.resolve("com/acme/a/1.0-SNAPSHOT")));
            String metadata = Files.readString(checkout.resolve("com/acme/a/maven-metadata.xml"));
            assertFalse(metadata.contains("<version>0.9-SNAPSHOT</version>"), metadata);
            assertTrue(metadata.contains("<version>0.95-SNAPSHOT</version>"), metadata);
            assertTrue(metadata.contains("<version>1.0-SNAPSHOT</version>"), metadata);
            MetadataAssertions.assertValidRepository(checkout, Set.of("sha1", "md5"));
        }
    }

    @Test
    void projectsWithDifferentReleaseRepositoriesFailBeforeAnythingRuns() {
        try (BareRepo remote = BareRepo.create(temp.resolve("remote.git"))) {
            PublisherBuild build = new PublisherBuild(temp.resolve("project"), remote)
                    .withProjects("a", "b")
                    .configure("b", "gitMavenPublisher { releaseRepository = 'central' }");

            BuildResult result = build.runAndFail(
                    List.of("--no-configuration-cache"), "publishAllPublicationsToGitPublisherRepository");

            assertTrue(result.getOutput().contains("releaseRepository"), result.getOutput());
            assertTrue(result.getOutput().contains("differ"), result.getOutput());
            assertTrue(remote.branchTip(PublisherBuild.BRANCH).isEmpty());
        }
    }
}
