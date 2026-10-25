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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-24-f65192f, R01. V-level metadata whose {@code <snapshot>} names a build that has no files and
 * whose {@code <snapshotVersions>} no longer cover the artifact cannot be resolved by Maven (it falls back to
 * {@code <snapshot>} and gets a 404). This is exactly the state design section 6 retention produces when a build that
 * started earlier deploys last (Maven stamps with the session start time). MetadataAssertions, and therefore the
 * production IntegrityCheck that must agree with it (T17), accept it today.
 */
class R01SnapshotElementInvariantTest {

    private static final String V_META = "org/acme/lib/1.0-SNAPSHOT/maven-metadata.xml";

    @TempDir
    Path root;

    @Test
    void snapshotElementPointingAtDeletedBuildIsAViolation() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(root, "SHA-1", "MD5")) {
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
        }
        assertThat(MetadataAssertions.violations(root, Set.of("sha1", "md5"))).isEmpty();

        // What retention ordered by timestamp does to the build the metadata references (here: build 2):
        // delete its files and drop the <snapshotVersion> entries that pointed at them; <snapshot> is left unchanged.
        Path dir = root.resolve("org/acme/lib/1.0-SNAPSHOT");
        String build2 = buildId(dir, "-2.pom");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(p -> p.getFileName().toString().contains(build2)).toList()) {
                Files.delete(p);
            }
        }
        Path metadata = root.resolve(V_META);
        String xml = Files.readString(metadata)
                .replaceAll("(?s)\\s*<snapshotVersion>(?:(?!</snapshotVersion>).)*</snapshotVersion>", "");
        assertThat(xml).contains("<buildNumber>2</buildNumber>").doesNotContain("<value>");
        Files.writeString(metadata, xml);
        rewriteChecksums(metadata);

        List<String> violations = MetadataAssertions.violations(root, Set.of("sha1", "md5"));
        assertThat(violations)
                .as("<snapshot> names build %s, which has no files left", build2)
                .anySatisfy(v -> assertThat(v).contains(V_META).contains("snapshot"));
    }

    /** {@code 20261024.091500-2} from the file name ending with {@code suffix}. */
    private static String buildId(Path dir, String suffix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            String name = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(suffix))
                    .findFirst()
                    .orElseThrow();
            return name.substring("lib-1.0-".length(), name.length() - ".pom".length());
        }
    }

    private static void rewriteChecksums(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        Files.writeString(file.resolveSibling(file.getFileName() + ".sha1"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)));
        Files.writeString(file.resolveSibling(file.getFileName() + ".md5"),
                HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)));
    }
}
