/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.InterleavedPublishScenario;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import io.github.ivangsa.gitpublisher.fixtures.SequentialReference;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Conflict detection (T21b): publications that share a path fail safely, with a diagnostic and nothing pushed. */
class GitPublisherConflictTest {

    private static final String BRANCH = "gh-pages";
    private static final RetryPolicy FAST = new RetryPolicy(5, Duration.ofMillis(1), Duration.ofMillis(5));
    private static final char[] TOKEN = "s3cr3t-t0ken-value".toCharArray();

    @TempDir
    Path tmp;

    private InterleavedPublishScenario.Publisher publisher(Consumer<Path> extraDeploy) {
        return publication -> {
            PublishConfig config = new PublishConfig(
                    publication.remote().uri(),
                    BRANCH,
                    Credentials.token(TOKEN),
                    publication.workDirectory(),
                    RetentionPolicy.defaults(),
                    FAST,
                    PublishConfig.defaultAuthor(),
                    false,
                    false,
                    PublishConfig.DEFAULT_CHECKSUMS);
            try (Publication git = new GitPublisher(config, PublisherLog.silent()).checkout()) {
                publication.checkedOut();
                publication.deploy().deployInto(git.repositoryDirectory());
                extraDeploy.accept(git.repositoryDirectory());
                git.publish(new PublishInfo(List.of(publication.step().coordinates()), Optional.empty()));
            }
        };
    }

    private InterleavedPublishScenario.Outcome run(
            SequentialReference.Step a, SequentialReference.Step b, Consumer<Path> extraDeploy) {
        return run(a, b, extraDeploy, Instant::now);
    }

    private InterleavedPublishScenario.Outcome run(
            SequentialReference.Step a,
            SequentialReference.Step b,
            Consumer<Path> extraDeploy,
            Supplier<Instant> clock) {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = new InterleavedPublishScenario(bare, BRANCH, tmp.resolve("work"), a, b, clock)
                    .run(publisher(extraDeploy));
            outcome.assertSharedPathFailsSafely();
            assertThat(bare.commitCount(BRANCH)).isEqualTo(1);
            assertThat(outcome.errorA()).isInstanceOf(PublishException.class);
            return outcome;
        }
    }

    private static void assertDiagnostic(Throwable error, String... paths) {
        assertThat(error.getMessage())
                .contains("conflict", "nothing was pushed", "serialized externally")
                .contains(paths)
                .doesNotContain(new String(TOKEN));
    }

    @Test
    void differentVersionsOfTheSameArtifactConflictOnTheArtifactMetadata() {
        var outcome = run(
                SequentialReference.Step.javaProject("org.acme", "alpha", "1.0"),
                SequentialReference.Step.javaProject("org.acme", "alpha", "2.0"),
                dir -> {});

        assertDiagnostic(outcome.errorA(), "org/acme/alpha/maven-metadata.xml");
    }

    @Test
    void theSameSnapshotInTheSameSecondConflictsOnTheBuildFiles() {
        Instant start = Instant.parse("2026-10-24T10:00:00Z");
        var outcome = run(
                SequentialReference.Step.javaProject("org.acme", "alpha", "1.0-SNAPSHOT"),
                SequentialReference.Step.javaProject("org.acme", "alpha", "1.0-SNAPSHOT"),
                dir -> {},
                () -> start);

        assertDiagnostic(
                outcome.errorA(),
                "org/acme/alpha/1.0-SNAPSHOT/alpha-1.0-20261024.100000-1.jar",
                "org/acme/alpha/1.0-SNAPSHOT/alpha-1.0-20261024.100000-1.pom");
    }

    @Test
    void theSameSnapshotInAnotherSecondConflictsOnTheVersionMetadata() {
        Instant[] starts = {Instant.parse("2026-10-24T10:00:00Z"), Instant.parse("2026-10-24T10:00:05Z")};
        int[] next = {0};
        var outcome = run(
                SequentialReference.Step.javaProject("org.acme", "alpha", "1.0-SNAPSHOT"),
                SequentialReference.Step.javaProject("org.acme", "alpha", "1.0-SNAPSHOT"),
                dir -> {},
                () -> starts[next[0]++ % 2]);

        assertDiagnostic(outcome.errorA(), "org/acme/alpha/1.0-SNAPSHOT/maven-metadata.xml");
        assertThat(outcome.errorA().getMessage()).doesNotContain("alpha-1.0-20261024.100000-1.jar");
    }

    @Test
    void aPathWeChangedAndTheRemoteDeletedIsAConflict() throws Exception {
        try (BareRepo bare = BareRepo.create(tmp.resolve("deleted.git"))) {
            bare.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}, "keep.txt", new byte[] {9}), "first");
            PublishConfig config = new PublishConfig(
                    bare.uri(),
                    BRANCH,
                    Credentials.none(),
                    tmp.resolve("work-deleted"),
                    RetentionPolicy.defaults(),
                    FAST,
                    PublishConfig.defaultAuthor(),
                    false,
                    false,
                    PublishConfig.DEFAULT_CHECKSUMS);
            try (Publication publication = new GitPublisher(config, PublisherLog.silent()).checkout()) {
                ObjectId competing = bare.commitFiles(BRANCH, Map.of("keep.txt", new byte[] {9}), "deletes a.txt");
                Files.writeString(publication.repositoryDirectory().resolve("a.txt"), "ours");

                assertThatThrownBy(() -> publication.publish(new PublishInfo(List.of(), Optional.empty())))
                        .isInstanceOf(PublishException.class)
                        .hasMessageContaining("conflict")
                        .hasMessageContaining("a.txt")
                        .hasMessageContaining("nothing was pushed");
                assertThat(bare.branchTip(BRANCH)).contains(competing);
                assertThat(bare.readTree(BRANCH)).containsOnlyKeys("keep.txt");
            }
        }
    }

    @Test
    void twoMavenPluginsOfOneGroupConflictOnTheGroupMetadata() {
        // each publication deploys its own plugin: the group-level maven-metadata.xml differs
        var a = SequentialReference.Step.javaProject("org.acme", "alpha-maven-plugin", "1.0");
        var b = SequentialReference.Step.javaProject("org.acme", "beta-maven-plugin", "1.0");
        int[] calls = {0};
        var outcome = run(a, b, dir -> {
            try (ResolverDeployer deployer = new ResolverDeployer(dir)) {
                String name = calls[0]++ == 0 ? "beta" : "alpha";
                deployer.deployPluginGroupMetadata("org.acme", name, name + "-maven-plugin");
            }
        });

        assertDiagnostic(outcome.errorA(), "org/acme/maven-metadata.xml");
        assertThat(outcome.errorA().getMessage()).doesNotContain("org/acme/alpha-maven-plugin/1.0/alpha");
    }

    @Test
    void theMessageNeverCarriesUserinfoOrTheToken() {
        var outcome = run(
                SequentialReference.Step.javaProject("org.acme", "alpha", "1.0"),
                SequentialReference.Step.javaProject("org.acme", "alpha", "2.0"),
                dir -> {});

        URI uri = tmp.resolve("remote.git").toUri();
        assertThat(outcome.errorA().getMessage()).doesNotContain("@").doesNotContain(uri.toString());
    }
}
