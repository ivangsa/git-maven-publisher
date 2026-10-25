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
import io.github.ivangsa.gitpublisher.fixtures.SequentialReference;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Retry loop with independent replay (T21): lease failures, transport errors, authentication failures, budget. */
class GitPublisherRetryTest {

    private static final String BRANCH = "gh-pages";
    private static final RetryPolicy FAST = new RetryPolicy(5, Duration.ofMillis(1), Duration.ofMillis(5));
    private static final SequentialReference.Step ALPHA =
            SequentialReference.Step.javaProject("org.acme", "alpha", "1.0-SNAPSHOT");
    private static final SequentialReference.Step BETA =
            SequentialReference.Step.javaProject("org.acme", "beta", "1.0-SNAPSHOT");

    @TempDir
    Path tmp;

    private Server server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    /** Collects log lines. */
    private static final class RecordingLog implements PublisherLog {
        final List<String> infos = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        Runnable onInfo = () -> {};

        @Override
        public void info(String message) {
            infos.add(message);
            onInfo.run();
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void debug(String message) {}
    }

    private PublishConfig config(
            URI repository, Path work, RetryPolicy retry, RetentionPolicy retention, boolean nojekyll) {
        return new PublishConfig(
                repository,
                BRANCH,
                Credentials.none(),
                work,
                retention,
                retry,
                PublishConfig.defaultAuthor(),
                false,
                nojekyll,
                PublishConfig.DEFAULT_CHECKSUMS);
    }

    private InterleavedPublishScenario.Publisher gitPublisher(RetentionPolicy retention, boolean nojekyll) {
        return publication -> {
            PublishConfig config =
                    config(publication.remote().uri(), publication.workDirectory(), FAST, retention, nojekyll);
            try (Publication git = new GitPublisher(config, PublisherLog.silent()).checkout()) {
                publication.checkedOut();
                publication.deploy().deployInto(git.repositoryDirectory());
                PublishResult result = git.publish(
                        new PublishInfo(List.of(publication.step().coordinates()), Optional.empty()));
                assertThat(result.pushed()).isTrue();
            }
        };
    }

    private InterleavedPublishScenario scenario(
            BareRepo bare, SequentialReference.Step a, SequentialReference.Step b) {
        return new InterleavedPublishScenario(bare, BRANCH, tmp.resolve("work"), a, b);
    }

    @Test
    void aCompetingPushOnDisjointCoordinatesEndsWithBothPublications() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, BETA).run(gitPublisher(RetentionPolicy.defaults(), false));

            outcome.assertInterleavedAndBothPublished(tmp.resolve("check"));
            assertThat(bare.commitCount(BRANCH)).isEqualTo(1);
            assertThat(bare.readTree(BRANCH).keySet())
                    .anyMatch(p -> p.startsWith("org/acme/alpha/"))
                    .anyMatch(p -> p.startsWith("org/acme/beta/"));
        }
    }

    @Test
    void theSameInterleavingWithRetentionDisabledAlsoKeepsBoth() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, BETA, ALPHA)
                    .run(gitPublisher(new RetentionPolicy(Integer.MAX_VALUE, RetentionPolicy.UNLIMITED), false));

            outcome.assertInterleavedAndBothPublished(tmp.resolve("check"));
        }
    }

    @Test
    void twoFirstPublicationsOnAMissingBranchBothSucceedWithAnIdenticalNojekyll() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var outcome = scenario(bare, ALPHA, BETA).run(gitPublisher(RetentionPolicy.defaults(), true));

            outcome.assertNoErrors().assertAllDeployedFilesPresent();
            assertThat(outcome.interleaved()).isTrue();
            outcome.assertValidTree(tmp.resolve("check"));
            Map<String, byte[]> tree = bare.readTree(BRANCH);
            assertThat(tree).containsKey(".nojekyll");
            assertThat(tree.get(".nojekyll")).isEmpty();
            assertThat(tree.keySet())
                    .anyMatch(p -> p.startsWith("org/acme/alpha/"))
                    .anyMatch(p -> p.startsWith("org/acme/beta/"));
            assertThat(bare.commitCount(BRANCH)).isEqualTo(1);
        }
    }

    @Test
    void theResultCountsTheAttempts() throws Exception {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            bare.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "first");
            try (Publication publication = new GitPublisher(
                            config(bare.uri(), tmp.resolve("work"), FAST, RetentionPolicy.defaults(), false),
                            PublisherLog.silent())
                    .checkout()) {
                bare.commitFiles(
                        BRANCH, Map.of("a.txt", new byte[] {1}, "b.txt", new byte[] {2}), "competing");
                Files.writeString(publication.repositoryDirectory().resolve("c.txt"), "3");

                PublishResult result = publication.publish(new PublishInfo(List.of(), Optional.empty()));

                assertThat(result.attempts()).isEqualTo(2);
                assertThat(bare.readTree(BRANCH)).containsOnlyKeys("a.txt", "b.txt", "c.txt");
                assertThat(bare.commitCount(BRANCH)).isEqualTo(1);
            }
        }
    }

    @Test
    void anExhaustedBudgetRaisesPublishExceptionAndPushesNothing() throws Exception {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            bare.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "first");
            RecordingLog log = new RecordingLog();
            AtomicInteger competing = new AtomicInteger();
            // After every replay (just before the push) another publisher moves the branch again.
            log.onInfo = () -> {
                if (log.infos.get(log.infos.size() - 1).contains("replayed")) {
                    bare.commitFiles(
                            BRANCH, Map.of("x" + competing.incrementAndGet() + ".txt", new byte[] {9}), "again");
                }
            };
            RetryPolicy three = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(2));
            try (Publication publication = new GitPublisher(
                            config(bare.uri(), tmp.resolve("work"), three, RetentionPolicy.defaults(), false), log)
                    .checkout()) {
                bare.commitFiles(BRANCH, Map.of("b.txt", new byte[] {2}), "competing");
                Files.writeString(publication.repositoryDirectory().resolve("c.txt"), "3");

                assertThatThrownBy(() -> publication.publish(new PublishInfo(List.of(), Optional.empty())))
                        .isInstanceOf(PublishException.class)
                        .hasMessageContaining("after 3 attempts")
                        .hasMessageContaining("changed on the remote");
                assertThat(bare.readTree(BRANCH)).doesNotContainKey("c.txt");
                assertThat(competing.get()).isEqualTo(2);
                assertThat(log.warnings).hasSize(2);
            }
        }
    }

    @Test
    void aConflictFailsFastWithoutRetries() throws Exception {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            bare.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "first");
            RecordingLog log = new RecordingLog();
            try (Publication publication = new GitPublisher(
                            config(bare.uri(), tmp.resolve("work"), FAST, RetentionPolicy.defaults(), false), log)
                    .checkout()) {
                ObjectId competing = bare.commitFiles(BRANCH, Map.of("a.txt", new byte[] {2}), "changes a.txt");
                // the lease failure is the only failed attempt; the replay finds the conflict and stops
                Files.writeString(publication.repositoryDirectory().resolve("a.txt"), "ours");

                assertThatThrownBy(() -> publication.publish(new PublishInfo(List.of(), Optional.empty())))
                        .isInstanceOf(PublishException.class)
                        .hasMessageContaining("conflict")
                        .hasMessageContaining("a.txt");
                assertThat(bare.branchTip(BRANCH)).contains(competing);
                assertThat(log.warnings).hasSize(1);
                assertThat(log.infos).noneMatch(m -> m.contains("replayed"));
            }
        }
    }

    // --- authentication failures and transport errors, with real JGit exceptions from a real HTTP server ---

    private final AtomicInteger httpStatus = new AtomicInteger(401);

    private String startServer() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/");
        context.addServlet(
                new ServletHolder(new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                        int status = httpStatus.get();
                        if (status == 401) {
                            resp.setHeader("WWW-Authenticate", "Basic realm=\"git\"");
                        }
                        resp.sendError(status);
                    }
                }),
                "/*");
        server.setHandler(context);
        server.start();
        return "http://127.0.0.1:" + connector.getLocalPort() + "/remote.git";
    }

    /** The exception JGit really throws when the server answers with this status. */
    private IOException jgitFailure(int status) throws Exception {
        httpStatus.set(status);
        String url = startServer();
        try {
            Git.lsRemoteRepository().setRemote(url).setHeads(true).call();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            assertThat(e.getCause()).isInstanceOf(IOException.class);
            assertThat(GitPublication.isFatal(e)).isEqualTo(status == 401 || status == 403);
            return (IOException) e.getCause();
        }
        throw new AssertionError("the server should have failed the request");
    }

    /** A remote whose push fails with {@code failure} for the first {@code failures} calls, then really pushes. */
    private static final class FlakyRemote extends GitRemote {
        final IOException failure;
        final int failures;
        final AtomicInteger pushes = new AtomicInteger();

        FlakyRemote(PublishConfig config, IOException failure, int failures) {
            super(config);
            this.failure = failure;
            this.failures = failures;
        }

        @Override
        PushOutcome push(Repository git, ObjectId commit, ObjectId tree, Optional<ObjectId> expectedOld)
                throws IOException {
            if (pushes.incrementAndGet() <= failures) {
                throw failure;
            }
            return super.push(git, commit, tree, expectedOld);
        }
    }

    @Test
    void anAuthenticationFailureMakesExactlyOneAttempt() throws Exception {
        IOException unauthorized = jgitFailure(401);
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            RecordingLog log = new RecordingLog();
            PublishConfig config = config(bare.uri(), tmp.resolve("work"), FAST, RetentionPolicy.defaults(), false);
            try (Publication publication = new GitPublisher(config, log).checkout()) {
                FlakyRemote remote = new FlakyRemote(config, unauthorized, Integer.MAX_VALUE);
                ((GitPublication) publication).remotes = c -> remote;
                Files.writeString(publication.repositoryDirectory().resolve("c.txt"), "3");

                assertThatThrownBy(() -> publication.publish(new PublishInfo(List.of(), Optional.empty())))
                        .isInstanceOf(PublishException.class);
                assertThat(remote.pushes.get()).isEqualTo(1);
                assertThat(log.warnings).isEmpty();
                assertThat(bare.branchTip(BRANCH)).isEmpty();
            }
        }
    }

    @Test
    void aTransportErrorIsRetriedWithinTheBudget() throws Exception {
        IOException unavailable = jgitFailure(503);
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            PublishConfig config = config(bare.uri(), tmp.resolve("work"), FAST, RetentionPolicy.defaults(), false);
            try (Publication publication = new GitPublisher(config, PublisherLog.silent()).checkout()) {
                FlakyRemote remote = new FlakyRemote(config, unavailable, 2);
                ((GitPublication) publication).remotes = c -> remote;
                Files.writeString(publication.repositoryDirectory().resolve("c.txt"), "3");

                PublishResult result = publication.publish(new PublishInfo(List.of(), Optional.empty()));

                assertThat(result.attempts()).isEqualTo(3);
                assertThat(remote.pushes.get()).isEqualTo(3);
                assertThat(bare.readTree(BRANCH)).containsOnlyKeys("c.txt");
            }
        }
    }

    @Test
    void aTransportErrorThatNeverGoesAwayExhaustsTheBudget() throws Exception {
        IOException unavailable = jgitFailure(503);
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            PublishConfig config = config(bare.uri(), tmp.resolve("work"), FAST, RetentionPolicy.defaults(), false);
            try (Publication publication = new GitPublisher(config, PublisherLog.silent()).checkout()) {
                FlakyRemote remote = new FlakyRemote(config, unavailable, Integer.MAX_VALUE);
                ((GitPublication) publication).remotes = c -> remote;
                Files.writeString(publication.repositoryDirectory().resolve("c.txt"), "3");

                assertThatThrownBy(() -> publication.publish(new PublishInfo(List.of(), Optional.empty())))
                        .isInstanceOf(PublishException.class)
                        .hasMessageContaining("after 5 attempts");
                assertThat(remote.pushes.get()).isEqualTo(5);
            }
        }
    }
}
