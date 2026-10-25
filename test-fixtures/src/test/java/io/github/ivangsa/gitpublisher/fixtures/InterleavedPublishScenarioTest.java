/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Self-tests of the interleaving harness: it must catch the failures it exists to catch (D-044). */
class InterleavedPublishScenarioTest {

    private static final String BRANCH = "gh-pages";

    private static final SequentialReference.Step ALPHA =
            SequentialReference.Step.javaProject("org.acme", "alpha", "1.0-SNAPSHOT");
    private static final SequentialReference.Step BETA =
            SequentialReference.Step.javaProject("org.acme", "beta", "1.0-SNAPSHOT");

    @TempDir
    Path tmp;

    private InterleavedPublishScenario scenario(
            BareRepo bare, SequentialReference.Step a, SequentialReference.Step b) {
        return new InterleavedPublishScenario(bare, BRANCH, tmp.resolve("work"), a, b);
    }

    @Test
    void publisherWithoutLeaseIsCaughtLosingBsFilesOnDistinctCoordinates() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, BETA).run(ReferencePublishers.forcePushWithoutLease());

            assertThat(outcome.interleaved()).isTrue();
            assertThat(outcome.errorA()).isNull();
            assertThat(outcome.errorB()).isNull();
            assertThat(outcome.lostFiles()).isNotEmpty().allMatch(path -> path.startsWith("org/acme/beta/"));
            assertThatThrownBy(outcome::assertAllDeployedFilesPresent)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("deployed files are missing from branch 'gh-pages'");
            assertThatThrownBy(() -> outcome.assertMatchesSequentialReference(tmp.resolve("c"), tmp.resolve("r")))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("sequential reference");
            assertThatThrownBy(() -> outcome.assertBothPublished(tmp.resolve("all")))
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void globalLockPublisherPassesAndMatchesSequentialReference() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, BETA).run(ReferencePublishers.globalLock());

            outcome.assertBothPublished(tmp.resolve("check"));
            assertThat(outcome.interleaved()).isFalse();
            assertThat(outcome.lostFiles()).isEmpty();
            assertThat(outcome.deployedFileCount()).isGreaterThan(2);
            assertThat(bare.commitCount(BRANCH)).isEqualTo(2);
        }
    }

    @Test
    void nonInterleavingPublisherFailsTheInterleavedAssertionButPassesTheLockPath() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, BETA).run(ReferencePublishers.globalLock());

            assertThat(outcome.interleaved()).isFalse();
            outcome.assertBothPublished(tmp.resolve("lock"));
            assertThatThrownBy(() -> outcome.assertInterleavedAndBothPublished(tmp.resolve("interleaved")))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("interleaving point");
        }
    }

    @Test
    void replayingPublisherKeepsBothPublicationsWhenInterleaved() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, BETA).run(ReferencePublishers.replayOrFail());

            assertThat(outcome.interleaved()).isTrue();
            outcome.assertInterleavedAndBothPublished(tmp.resolve("check"));
            assertThat(bare.readTree(BRANCH).keySet())
                    .anyMatch(p -> p.startsWith("org/acme/alpha/"))
                    .anyMatch(p -> p.startsWith("org/acme/beta/"));
        }
    }

    @Test
    void sharedPathConflictLeavesBsFilesUntouchedAndFailsA() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, ALPHA).run(ReferencePublishers.replayOrFail());

            outcome.assertSharedPathFailsSafely();
            assertThat(outcome.errorA()).hasMessageContaining("conflict").hasMessageContaining("org/acme/alpha/");
            assertThat(bare.commitCount(BRANCH)).isEqualTo(1);
        }
    }

    @Test
    void overwritingPublisherFailsTheSharedPathCheck() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, ALPHA).run(ReferencePublishers.forcePushWithoutLease());

            assertThat(outcome.errorA()).isNull();
            assertThatThrownBy(outcome::assertSharedPathFailsSafely)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("should report a conflict");
            assertThat(outcome.lostFiles()).isNotEmpty();
        }
    }

    @Test
    void publisherThatReportsAnErrorButAlreadyOverwroteIsCaught() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            InterleavedPublishScenario.Publisher overwriteThenFail = publication -> {
                ReferencePublishers.forcePushWithoutLease().publish(publication);
                if (publication.workDirectory().endsWith("publisher-a")) {
                    throw new IllegalStateException("conflict, sorry");
                }
            };
            var outcome = scenario(bare, ALPHA, ALPHA).run(overwriteThenFail);

            assertThat(outcome.errorA()).isNotNull();
            assertThatThrownBy(outcome::assertSharedPathFailsSafely)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("differs from what publisher B pushed");
        }
    }

    @Test
    void sameSecondPublicationsOfOneSnapshotAreNotCountedAsNothingLost() {
        Instant sameSecond = Instant.parse("2026-10-24T09:00:00Z");
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = new InterleavedPublishScenario(
                            bare, BRANCH, tmp.resolve("work"), ALPHA, ALPHA, () -> sameSecond)
                    .run(ReferencePublishers.forcePushWithoutLease());

            assertThat(outcome.errorA()).isNull();
            assertThat(outcome.lostFiles()).anyMatch(path -> path.endsWith("-1.jar"));
            assertThatThrownBy(outcome::assertAllDeployedFilesPresent)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("deployed files are missing");
        }
    }

    @Test
    void wrongContentOnTheBranchIsDetected() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            InterleavedPublishScenario.Publisher corrupting = publication -> {
                Path repo = Files.createDirectories(publication.workDirectory().resolve("repo"));
                publication.deploy().deployInto(repo);
                Map<String, byte[]> files = new java.util.TreeMap<>(publication.remote().readTree(BRANCH));
                ReferencePublishers.readFiles(repo)
                        .forEach((path, content) ->
                                files.put(path, path.endsWith(".jar") ? "corrupt".getBytes() : content));
                publication.remote().commitFiles(BRANCH, files, "corrupt");
            };
            var outcome = scenario(bare, ALPHA, BETA).run(corrupting);

            assertThat(outcome.errorA()).isNull();
            assertThat(outcome.lostFiles()).isNotEmpty().allMatch(path -> path.endsWith(".jar"));
        }
    }

    @Test
    void publicationsOfEqualCoordinatesHaveDistinctContent() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, ALPHA).run(ReferencePublishers.globalLock());

            outcome.assertNoErrors().assertAllDeployedFilesPresent();
            long jars = bare.readTree(BRANCH).keySet().stream()
                    .filter(p -> p.startsWith("org/acme/alpha/1.0-SNAPSHOT/")
                            && p.endsWith(".jar")
                            && !p.contains("sources"))
                    .count();
            assertThat(jars).isEqualTo(2);
        }
    }

    @Test
    void publisherFailuresAreReportedNotSwallowed() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, BETA).run(publication -> {
                throw new IllegalStateException("boom");
            });
            assertThat(outcome.errorA()).hasMessage("boom");
            assertThat(outcome.errorB()).hasMessage("boom");
            assertThatThrownBy(outcome::assertNoErrors).isInstanceOf(AssertionError.class).hasMessageContaining("boom");
        }
    }

    @Test
    void sharedPathCheckSaysSoWhenTheInterleavingPointWasNeverReached() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, ALPHA).run(ReferencePublishers.globalLock());
            assertThatThrownBy(outcome::assertSharedPathFailsSafely)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("never reached the interleaving point");
        }
    }

    @Test
    void checkingOutTwiceIsAPublisherError() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, BETA).run(publication -> {
                publication.checkedOut();
                publication.checkedOut();
            });
            assertThat(outcome.errorA()).hasMessageContaining("called twice");
        }
    }
}
