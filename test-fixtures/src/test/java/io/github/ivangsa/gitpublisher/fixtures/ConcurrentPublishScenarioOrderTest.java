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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review F4 (R04): publications of one snapshot must not collapse into one build unnoticed, and the sequential
 * reference must replay the order in which publications were pushed, not the order their deploys finished.
 */
class ConcurrentPublishScenarioOrderTest {

    private static final String A_LEVEL = "org/acme/lib/maven-metadata.xml";

    @TempDir
    Path tmp;

    @Test
    void twoSameSecondPublicationsOfOneSnapshotAreNotCountedAsNothingLost() {
        Instant sameSecond = Instant.parse("2026-10-24T09:00:00Z");
        CyclicBarrier bothDeployed = new CyclicBarrier(2);
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var steps = java.util.List.of(
                    SequentialReference.Step.javaProject("org.acme", "lib", "1.0-SNAPSHOT"),
                    SequentialReference.Step.javaProject("org.acme", "lib", "1.0-SNAPSHOT"));
            var scenario =
                    new ConcurrentPublishScenario(bare, "gh-pages", tmp.resolve("work"), steps, () -> sameSecond);
            var outcome = scenario.run(ReferencePublishers.overlayWithoutRenumbering(() -> {
                try {
                    bothDeployed.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));

            assertThat(outcome.errors()).isEmpty();
            assertThat(outcome.lostFiles()).anyMatch(path -> path.endsWith("-1.jar"));
            assertThatThrownBy(outcome::assertAllDeployedFilesPresent)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("deployed files are missing");
        }
    }

    @Test
    void publicationsOfEqualCoordinatesHaveDistinctContent() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var scenario = new ConcurrentPublishScenario(
                    bare, "gh-pages", tmp.resolve("work"), ConcurrentPublishScenario.mixedSteps(4));
            var outcome = scenario.run(ReferencePublishers.globalLock());
            outcome.assertConsistent(tmp.resolve("checkout"));

            Map<String, byte[]> tree = bare.readTree("gh-pages");
            long jars = tree.keySet().stream()
                    .filter(p -> p.startsWith("org/acme/shared/1.0-SNAPSHOT/") && p.endsWith(".jar") && !p.contains("sources"))
                    .count();
            assertThat(jars).isEqualTo(2);
        }
    }

    @Test
    void sequentialReferenceReplaysPushOrderNotDeployOrder() {
        CountDownLatch firstDeployed = new CountDownLatch(1);
        CountDownLatch secondPushed = new CountDownLatch(1);
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var steps = java.util.List.of(
                    SequentialReference.Step.javaProject("org.acme", "lib", "1.0-SNAPSHOT"),
                    SequentialReference.Step.javaProject("org.acme", "lib", "2.0-SNAPSHOT"));
            var scenario = new ConcurrentPublishScenario(bare, "gh-pages", tmp.resolve("work"), steps);
            // 1.0 deploys first but pushes last; 2.0 deploys second and pushes first.
            var outcome = scenario.run(publication -> {
                boolean first = publication.step().version().startsWith("1.0");
                Path repo = Files.createDirectories(publication.workDirectory().resolve("repo"));
                if (!first) {
                    firstDeployed.await();
                }
                publication.deploy().deployInto(repo);
                if (first) {
                    firstDeployed.countDown();
                    secondPushed.await();
                    bare.commitFiles("gh-pages", mergeOnto(bare.readTree("gh-pages"), repo), "Publish 1.0");
                    publication.pushed();
                } else {
                    bare.commitFiles("gh-pages", ReferencePublishers.readFiles(repo), "Publish 2.0");
                    publication.pushed();
                    secondPushed.countDown();
                }
            });

            outcome.assertConsistent(tmp.resolve("checkout"));
            assertThat(outcome.deployOrder()).extracting(SequentialReference.Step::version)
                    .containsExactly("1.0-SNAPSHOT", "2.0-SNAPSHOT");
            assertThat(outcome.pushOrder()).extracting(SequentialReference.Step::version)
                    .containsExactly("2.0-SNAPSHOT", "1.0-SNAPSHOT");

            outcome.assertMatchesSequentialReference(tmp.resolve("checkout-2"), tmp.resolve("reference"));

            // The deploy order would give the A-level <versions> in the other order: the old check was wrong here.
            Path wrong = SequentialReference.replay(tmp.resolve("reference-deploy-order"), outcome.deployOrder());
            assertThatThrownBy(() -> TreeDiff.compare(wrong, tmp.resolve("checkout-2")).assertNoDifference())
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void referenceComparisonFailsClearlyWhenThePublisherNeverReportsPushes() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var scenario = new ConcurrentPublishScenario(
                    bare, "gh-pages", tmp.resolve("work"), ConcurrentPublishScenario.mixedSteps(1));
            var outcome = scenario.run(publication -> {
                Path repo = Files.createDirectories(publication.workDirectory().resolve("repo"));
                publication.deploy().deployInto(repo);
                bare.commitFiles("gh-pages", ReferencePublishers.readFiles(repo), "no pushed() call");
            });
            outcome.assertNoErrors();
            assertThatThrownBy(() -> outcome.assertMatchesSequentialReference(tmp.resolve("c"), tmp.resolve("r")))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("pushed()");
        }
    }

    /** Minimal merge for this test: the tip plus our files, with a union of the A-level versions. */
    private static Map<String, byte[]> mergeOnto(Map<String, byte[]> tip, Path repo) throws Exception {
        Map<String, byte[]> result = new TreeMap<>(tip);
        Map<String, byte[]> own = ReferencePublishers.readFiles(repo);
        MetadataXpp3Reader reader = new MetadataXpp3Reader();
        Metadata merged = reader.read(new ByteArrayInputStream(tip.get(A_LEVEL)));
        Metadata ours = reader.read(new ByteArrayInputStream(own.get(A_LEVEL)));
        for (String version : ours.getVersioning().getVersions()) {
            if (!merged.getVersioning().getVersions().contains(version)) {
                merged.getVersioning().addVersion(version);
            }
        }
        if (ours.getVersioning().getLatest() != null) {
            merged.getVersioning().setLatest(ours.getVersioning().getLatest());
        }
        if (ours.getVersioning().getRelease() != null) {
            merged.getVersioning().setRelease(ours.getVersioning().getRelease());
        }
        merged.getVersioning().setLastUpdated(ours.getVersioning().getLastUpdated());
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        new MetadataXpp3Writer().write(xml, merged);

        own.forEach((path, content) -> {
            if (!path.equals(A_LEVEL) && !path.startsWith(A_LEVEL + ".")) {
                result.put(path, content);
            }
        });
        result.put(A_LEVEL, xml.toByteArray());
        for (String path : tip.keySet()) {
            if (path.startsWith(A_LEVEL + ".")) {
                result.put(path, checksum(path.substring(A_LEVEL.length() + 1), xml.toByteArray()));
            }
        }
        return result;
    }

    private static byte[] checksum(String extension, byte[] content) throws Exception {
        String algorithm =
                switch (extension) {
                    case "md5" -> "MD5";
                    case "sha1" -> "SHA-1";
                    case "sha256" -> "SHA-256";
                    case "sha512" -> "SHA-512";
                    default -> throw new IllegalStateException("unexpected checksum " + extension);
                };
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance(algorithm).digest(content))
                .getBytes(StandardCharsets.UTF_8);
    }
}
