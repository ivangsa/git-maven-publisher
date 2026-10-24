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
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.InterleavedPublishScenario;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import io.github.ivangsa.gitpublisher.fixtures.SequentialReference;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
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
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = new InterleavedPublishScenario(bare, BRANCH, tmp.resolve("work"), a, b)
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
    void theSameSnapshotVersionConflicts() {
        var outcome = run(
                SequentialReference.Step.javaProject("org.acme", "alpha", "1.0-SNAPSHOT"),
                SequentialReference.Step.javaProject("org.acme", "alpha", "1.0-SNAPSHOT"),
                dir -> {});

        // build 1 of the same second collides on the files; another second collides on the version metadata
        assertDiagnostic(outcome.errorA(), "org/acme/alpha/1.0-SNAPSHOT/");
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
