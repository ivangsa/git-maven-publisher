/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-24-f9bb6b5, F1. Design section 4 step 3.vi: an authentication failure fails fast. JGit reports an
 * HTTP 403 on push (the usual answer of GitHub to a token without {@code Contents: write}) as
 * {@code "<url>: git-receive-pack not permitted on '<url>'"}, which the message-based check in
 * {@link GitPublication#isFatal} does not recognise, so the publication is retried {@code maxAttempts} times. The
 * same check matches words in the repository URL, so a transport error on a repository whose name contains
 * "authentication" is not retried.
 */
class R15AuthFailureClassificationTest {

    private static final String BRANCH = "gh-pages";

    @TempDir
    Path tmp;

    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void stop() throws Exception {
        for (Server server : servers) {
            server.stop();
        }
    }

    private String serve(String path, jakarta.servlet.Servlet servlet) throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/");
        context.addServlet(new ServletHolder(servlet), "/*");
        server.setHandler(context);
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + connector.getLocalPort() + path;
    }

    /** The exception JGit really throws when a real Git HTTP server refuses receive-pack with 403 Forbidden. */
    private Exception forbiddenPush(BareRepo remote) throws Exception {
        StoredConfig config = remote.repository().getConfig();
        config.setBoolean("http", null, "receivepack", false); // GitServlet answers 403 (ServiceNotEnabled)
        config.save();
        GitServlet servlet = new GitServlet();
        servlet.setRepositoryResolver((req, name) -> {
            remote.repository().incrementOpen();
            return remote.repository();
        });
        String url = serve("/remote.git", servlet);

        try (Git local = Git.init().setDirectory(tmp.resolve("local").toFile()).call()) {
            Files.writeString(tmp.resolve("local/a.txt"), "a");
            local.add().addFilepattern("a.txt").call();
            local.commit().setMessage("a").setSign(false).call();
            local.push()
                    .setRemote(new URIish(url).toString())
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/" + BRANCH))
                    .call();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            return e;
        }
        throw new AssertionError("the server should have refused the push");
    }

    @Test
    void aForbiddenPushFromARealGitServerIsAnAuthenticationFailure() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            Exception forbidden = forbiddenPush(remote);

            assertThat(GitPublication.isFatal(forbidden))
                    .as("HTTP 403 on push must fail fast; JGit message was: %s", forbidden.getMessage())
                    .isTrue();
        }
    }

    @Test
    void aForbiddenPushMakesExactlyOneAttempt() throws Exception {
        try (BareRepo forbiddenRemote = BareRepo.create(tmp.resolve("remote.git"));
                BareRepo bare = BareRepo.create(tmp.resolve("target.git"))) {
            Exception forbidden = forbiddenPush(forbiddenRemote);
            IOException cause = forbidden.getCause() instanceof IOException io ? io : new IOException(forbidden);

            PublishConfig config = new PublishConfig(
                    bare.uri(),
                    BRANCH,
                    Credentials.none(),
                    tmp.resolve("work"),
                    RetentionPolicy.defaults(),
                    new RetryPolicy(5, Duration.ofMillis(1), Duration.ofMillis(2)),
                    PublishConfig.defaultAuthor(),
                    false,
                    false,
                    PublishConfig.DEFAULT_CHECKSUMS);
            AtomicInteger pushes = new AtomicInteger();
            try (Publication publication = new GitPublisher(config, PublisherLog.silent()).checkout()) {
                ((GitPublication) publication).remotes = c -> new GitRemote(c) {
                    @Override
                    PushOutcome push(Repository git, ObjectId commit, ObjectId tree, Optional<ObjectId> expectedOld)
                            throws IOException {
                        pushes.incrementAndGet();
                        throw cause;
                    }
                };
                Files.writeString(publication.repositoryDirectory().resolve("c.txt"), "3");

                assertThatThrownBy(() -> publication.publish(new PublishInfo(List.of(), Optional.empty())))
                        .isInstanceOf(PublishException.class);
                assertThat(pushes.get()).as("push attempts for a 403").isEqualTo(1);
            }
        }
    }

    @Test
    void aTransportErrorIsRetriedWhateverTheRepositoryIsCalled() throws Exception {
        String url = serve("/acme/authentication-snapshots.git", new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.sendError(503);
            }
        });
        Exception unavailable;
        try {
            Git.lsRemoteRepository().setRemote(url).setHeads(true).call();
            throw new AssertionError("the server should have failed the request");
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            unavailable = e;
        }

        assertThat(GitPublication.isFatal(unavailable))
                .as("a 503 must be retried; JGit message was: %s", unavailable.getMessage())
                .isFalse();
    }

    @Test
    void sanityTheBareRepoIsUntouched() throws Exception {
        // guards the setup above: the forbidden server really refused the push
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            forbiddenPush(remote);
            assertThat(remote.branchTip(BRANCH)).isEmpty();
        }
    }
}
