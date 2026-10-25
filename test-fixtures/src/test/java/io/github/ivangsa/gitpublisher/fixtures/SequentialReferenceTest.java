/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SequentialReferenceTest {

    @TempDir
    Path tmp;

    /** 2 artifacts, 2 versions, 3 builds each. Interleaved, like a real workday. */
    static List<SequentialReference.Step> script() {
        List<SequentialReference.Step> steps = new ArrayList<>();
        for (int build = 0; build < 3; build++) {
            for (String version : List.of("2.0-SNAPSHOT", "1.0-SNAPSHOT")) {
                for (String artifact : List.of("alpha", "beta")) {
                    steps.add(SequentialReference.Step.javaProject("org.acme", artifact, version));
                }
            }
        }
        return steps;
    }

    @Test
    void sameScriptRunTwiceAtDifferentTimesIsEqualAfterNormalization() throws Exception {
        Path first = SequentialReference.replay(tmp.resolve("first"), script());
        Thread.sleep(1500); // timestamps have second resolution
        Path second = SequentialReference.replay(tmp.resolve("second"), script());

        MetadataAssertions.assertValidRepository(first);
        MetadataAssertions.assertValidRepository(second);
        // the raw trees do differ: timestamps are part of file names
        assertThat(fileNames(first)).isNotEqualTo(fileNames(second));
        TreeDiff.compare(first, second).assertNoDifference();
    }

    @Test
    void normalizationRenumbersBuildsByOrder() {
        Path repo = SequentialReference.replay(tmp.resolve("repo"), script());
        assertThat(TreeNormalizer.normalize(repo).keySet())
                .contains(
                        "org/acme/alpha/1.0-SNAPSHOT/alpha-1.0-TS-1.jar",
                        "org/acme/alpha/1.0-SNAPSHOT/alpha-1.0-TS-3-sources.jar",
                        "org/acme/alpha/1.0-SNAPSHOT/alpha-1.0-TS-2.pom");
    }

    /**
     * Records the observed ordering of A-level {@code <versions>} (D-006): Resolver appends in deploy order and does
     * not sort. The script deploys 2.0 before 1.0 and the result keeps that order. No {@code latest} is written.
     */
    @Test
    void artifactLevelVersionsKeepDeployOrder() throws IOException {
        Path repo = SequentialReference.replay(tmp.resolve("repo"), script());
        String xml = Files.readString(repo.resolve("org/acme/alpha/maven-metadata.xml"));
        assertThat(xml).containsSubsequence("<version>2.0-SNAPSHOT</version>", "<version>1.0-SNAPSHOT</version>");
    }

    @Test
    void diffReportsMissingFile() throws IOException {
        Path expected = SequentialReference.replay(tmp.resolve("expected"), script());
        Path actual = SequentialReferenceTest.copy(expected, tmp.resolve("actual"));
        Path dir = actual.resolve("org/acme/beta/1.0-SNAPSHOT");
        try (Stream<Path> files = Files.list(dir)) {
            Files.delete(files.filter(p -> p.toString().endsWith(".pom")).sorted().findFirst().orElseThrow());
        }
        TreeDiff diff = TreeDiff.compare(expected, actual);
        assertThat(diff.isEmpty()).isFalse();
        assertThat(diff.report()).contains("missing in actual: org/acme/beta/1.0-SNAPSHOT/beta-1.0-TS-1.pom");
    }

    @Test
    void diffReportsExtraVersion() throws IOException {
        Path expected = SequentialReference.replay(tmp.resolve("expected"), script());
        Path actual = copy(expected, tmp.resolve("actual"));
        Path metadata = actual.resolve("org/acme/alpha/maven-metadata.xml");
        Files.writeString(
                metadata,
                Files.readString(metadata)
                        .replace("</versions>", "  <version>3.0-SNAPSHOT</version>\n    </versions>"));
        Files.createDirectories(actual.resolve("org/acme/alpha/3.0-SNAPSHOT"));
        Files.writeString(actual.resolve("org/acme/alpha/3.0-SNAPSHOT/alpha-3.0-SNAPSHOT.pom"), "<project/>");

        TreeDiff diff = TreeDiff.compare(expected, actual);
        assertThat(diff.report())
                .contains("unexpected in actual: org/acme/alpha/3.0-SNAPSHOT/alpha-3.0-SNAPSHOT.pom")
                .contains("content differs: org/acme/alpha/maven-metadata.xml")
                .contains("+ actual:   <version>3.0-SNAPSHOT</version>");
    }

    @Test
    void diffReportsWrongLatest() throws IOException {
        Path expected = SequentialReference.replay(tmp.resolve("expected"), script());
        Path actual = copy(expected, tmp.resolve("actual"));
        Path metadata = actual.resolve("org/acme/alpha/maven-metadata.xml");
        String xml = Files.readString(metadata);
        // Resolver writes no <latest> for snapshot-only artifacts (D-021), so a stray one is a difference
        assertThat(xml).doesNotContain("<latest>");
        Files.writeString(metadata, xml.replace("<versions>", "<latest>2.0-SNAPSHOT</latest>\n    <versions>"));

        TreeDiff diff = TreeDiff.compare(expected, actual);
        assertThat(diff.report())
                .contains("content differs: org/acme/alpha/maven-metadata.xml")
                .contains("+ actual:   <latest>2.0-SNAPSHOT</latest>");
        assertThatThrownBy(diff::assertNoDifference)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("<latest>");
    }

    @Test
    void diffIgnoresLastUpdatedButNotBuildNumbers() throws IOException {
        Path expected = SequentialReference.replay(tmp.resolve("expected"), script());
        Path actual = copy(expected, tmp.resolve("actual"));
        Path metadata = actual.resolve("org/acme/alpha/maven-metadata.xml");
        Files.writeString(
                metadata,
                Files.readString(metadata)
                        .replaceAll("<lastUpdated>\\d+</lastUpdated>", "<lastUpdated>20000101000000</lastUpdated>"));
        TreeDiff.compare(expected, actual).assertNoDifference();

        Path versionMetadata = actual.resolve("org/acme/alpha/1.0-SNAPSHOT/maven-metadata.xml");
        Files.writeString(
                versionMetadata,
                Files.readString(versionMetadata)
                        .replaceAll("<buildNumber>3</buildNumber>", "<buildNumber>7</buildNumber>"));
        assertThat(TreeDiff.compare(expected, actual).report()).contains("<buildNumber>7</buildNumber>");
    }

    private static Set<String> fileNames(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString())
                    .collect(Collectors.toSet());
        }
    }

    static Path copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        return to;
    }
}
