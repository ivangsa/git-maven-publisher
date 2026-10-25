/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R02 self-test: a build that started earlier and deploys later gets an older timestamp and a higher build number. */
class ResolverDeployerStartTimeTest {

    @TempDir
    Path root;

    @Test
    void startedEarlierDeployedLaterHasOlderTimestampAndHigherBuildNumber() throws Exception {
        Instant now = Instant.now();
        try (ResolverDeployer later = new ResolverDeployer(root, now.minusSeconds(60))) {
            later.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT"); // started 1 min ago, deployed first
        }
        try (ResolverDeployer earlier = new ResolverDeployer(root, now.minusSeconds(600))) {
            earlier.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT"); // started 10 min ago, deployed last
        }
        String xml = Files.readString(root.resolve("org/acme/lib/1.0-SNAPSHOT/maven-metadata.xml"));
        Matcher m = Pattern.compile("<snapshot>\\s*<timestamp>([0-9.]+)</timestamp>\\s*<buildNumber>(\\d+)</buildNumber>")
                .matcher(xml);
        assertThat(m.find()).isTrue();
        String timestamp = m.group(1);
        // Maven points <snapshot> at the last deployed build: the one with the OLDER timestamp and build number 2.
        assertThat(m.group(2)).isEqualTo("2");
        try (var files = Files.list(root.resolve("org/acme/lib/1.0-SNAPSHOT"))) {
            var jars = files.map(p -> p.getFileName().toString()).filter(n -> n.matches("lib-1\\.0-.*\\.jar")).sorted().toList();
            assertThat(jars).hasSize(4); // jar + sources, two builds
            assertThat(jars).anyMatch(n -> n.contains(timestamp + "-2"));
            assertThat(jars).anyMatch(n -> n.contains("-1.jar") && !n.contains(timestamp));
        }
        assertThat(MetadataAssertions.violations(root, Set.of())).isEmpty();
    }
}
