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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResolverDeployerTest {

    @TempDir
    Path repo;

    @Test
    void threeDeploysOfTheSameSnapshotGiveBuildNumbersOneToThree() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(repo)) {
            for (int i = 0; i < 3; i++) {
                deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
            }
        }
        Path vDir = repo.resolve("org/acme/lib/1.0-SNAPSHOT");
        Metadata v = read(vDir.resolve("maven-metadata.xml"));
        assertThat(v.getVersioning().getSnapshot().getBuildNumber()).isEqualTo(3);

        List<Integer> builds;
        try (var files = Files.list(vDir)) {
            builds = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("lib-1\\.0-\\d{8}\\.\\d{6}-\\d+\\.jar"))
                    .map(n -> Integer.valueOf(n.substring(n.lastIndexOf('-') + 1, n.length() - ".jar".length())))
                    .sorted()
                    .toList();
        }
        assertThat(builds).containsExactly(1, 2, 3);

        // jar, pom and the sources classifier all point at the newest build
        assertThat(v.getVersioning().getSnapshotVersions())
                .extracting(SnapshotVersion::getExtension, sv -> String.valueOf(sv.getClassifier()))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("jar", ""),
                        org.assertj.core.groups.Tuple.tuple("pom", ""),
                        org.assertj.core.groups.Tuple.tuple("jar", "sources"));
        assertThat(read(repo.resolve("org/acme/lib/maven-metadata.xml")).getVersioning().getVersions())
                .containsExactly("1.0-SNAPSHOT");

        MetadataAssertions.assertValidRepository(repo, Set.of("sha1", "md5"));
    }

    @Test
    void classifierAndGradleModuleArtifactsAreDeployed() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(repo)) {
            deployer.deploy(
                    "org.acme",
                    "kmp",
                    "2.0-SNAPSHOT",
                    ResolverDeployer.Part.of("", "jar", "jar"),
                    ResolverDeployer.Part.of("", "pom", ResolverDeployer.pom("org.acme", "kmp", "2.0-SNAPSHOT")),
                    ResolverDeployer.Part.of("", "module", "{\"formatVersion\":\"1.1\"}"),
                    ResolverDeployer.Part.of("javadoc", "jar", "javadoc"));
        }
        Metadata v = read(repo.resolve("org/acme/kmp/2.0-SNAPSHOT/maven-metadata.xml"));
        assertThat(v.getVersioning().getSnapshotVersions())
                .extracting(SnapshotVersion::getExtension)
                .contains("module", "pom", "jar");
        MetadataAssertions.assertValidRepository(repo);
    }

    @Test
    void checksumAlgorithmsAreConfigurable() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(repo, "SHA-256", "SHA-512")) {
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
        }
        Path metadata = repo.resolve("org/acme/lib/1.0-SNAPSHOT/maven-metadata.xml");
        assertThat(metadata.resolveSibling("maven-metadata.xml.sha256")).exists();
        assertThat(metadata.resolveSibling("maven-metadata.xml.sha512")).exists();
        assertThat(metadata.resolveSibling("maven-metadata.xml.sha1")).doesNotExist();
        assertThat(metadata.resolveSibling("maven-metadata.xml.md5")).doesNotExist();
        MetadataAssertions.assertValidRepository(repo, Set.of("sha256", "sha512"));
    }

    private static Metadata read(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return new MetadataXpp3Reader().read(in, true);
        } catch (IOException e) {
            throw e;
        }
    }
}
