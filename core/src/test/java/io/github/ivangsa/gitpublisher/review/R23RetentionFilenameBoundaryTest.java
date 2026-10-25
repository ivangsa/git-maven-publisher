/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Retention must recognize the timestamp after the complete artifactId, never inside it or an unrelated name. */
class R23RetentionFilenameBoundaryTest {
    @TempDir
    Path root;

    @Test
    void aTimestampLikeFragmentInALegalArtifactIdDoesNotHideRealBuilds() throws Exception {
        String artifactId = "lib-1.0-20200101.010101-99";
        Path version = Path.of("org/acme", artifactId, "1.0-SNAPSHOT");
        try (ResolverDeployer deployer = new ResolverDeployer(root, Instant.parse("2026-10-24T10:00:00Z"))) {
            for (int build = 0; build < 3; build++) {
                deployer.deployJavaProject("org.acme", artifactId, "1.0-SNAPSHOT");
            }
        }
        MetadataAssertions.assertValidRepository(root);

        Retention.apply(root, List.of(version), new RetentionPolicy(1, -1), PublishConfig.DEFAULT_CHECKSUMS);

        try (var files = Files.list(root.resolve(version))) {
            assertThat(files.filter(p -> p.toString().endsWith(".jar")))
                    .as("keepSnapshots=1 keeps main jar and sources jar of only the newest Resolver build")
                    .hasSize(2);
        }
        MetadataAssertions.assertValidRepository(root);
    }

    @Test
    void aNonArtifactFileContainingAVersionAndTimestampIsNeverDeleted() throws Exception {
        Path version = Path.of("org/acme/lib/1.0-SNAPSHOT");
        try (ResolverDeployer deployer = new ResolverDeployer(root, Instant.parse("2026-10-24T10:00:00Z"))) {
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
        }
        Path notes = Files.writeString(root.resolve(version).resolve("notes-1.0-20200101.010101-0.txt"), "keep me");

        Retention.apply(root, List.of(version), new RetentionPolicy(1, -1), PublishConfig.DEFAULT_CHECKSUMS);

        assertThat(notes).as("design section 6: non-timestamped artifact files are never removed").exists();
        MetadataAssertions.assertValidRepository(root);
    }
}
