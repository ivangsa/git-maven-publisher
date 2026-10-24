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
package io.github.ivangsa.gitpublisher.fixtures.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-24-f65192f, R07. IntegrityCheck runs on {@code repo/} before the commit and must agree with
 * MetadataAssertions (T17). Git does not store empty directories, so a {@code <version>} whose directory is empty
 * in {@code repo/} has no directory on the pushed branch. The "versions have a directory" invariant has to reject it
 * before the push, not after.
 */
class R07EmptyVersionDirectoryTest {

    @TempDir
    Path root;

    @Test
    void versionWhoseDirectoryIsEmptyIsAViolation() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(root)) {
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
            deployer.deployJavaProject("org.acme", "lib", "2.0-SNAPSHOT");
        }
        Path version2 = root.resolve("org/acme/lib/2.0-SNAPSHOT");
        try (Stream<Path> files = Files.list(version2)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
        assertThat(version2).isEmptyDirectory();

        assertThat(MetadataAssertions.violations(root, Set.of()))
                .anySatisfy(v -> assertThat(v).contains("org/acme/lib/maven-metadata.xml").contains("2.0-SNAPSHOT"));
    }
}
