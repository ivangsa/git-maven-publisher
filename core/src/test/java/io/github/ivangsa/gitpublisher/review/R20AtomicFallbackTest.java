/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-25-0701e5d, F2. Design section 4 step 3.v (D-060): the push is atomic, and only "if the server does
 * not support atomic pushes the hint is skipped". {@code GitRemote.push} decides that by looking for the word "atomic"
 * anywhere in the JGit {@code TransportException} message, which also holds the repository URL (the same mistake R15
 * removed from the auth classification). A transient failure of the push on a repository whose name contains "atomic"
 * is taken for "server without atomic pushes": the publication is pushed again at once, non-atomically and without the
 * hint, instead of being retried as a transport error. The real fallback (a server that does not advertise
 * {@code atomic}) had no test (D-060); it is covered here against a JGit HTTPS server too.
 */
class R20AtomicFallbackTest {

    private static final String BRANCH = "gh-pages";
    private static final String TREE_REF = "refs/publisher/tree";

    @TempDir
    Path tmp;

    private static SSLContext originalContext;
    private static javax.net.ssl.SSLSocketFactory originalFactory;
    private static Path tlsDirectory;
    private static Path keystore;

    private Server server;

    @BeforeAll
    static void trustALoopbackCertificate() throws Exception {
        tlsDirectory = Files.createTempDirectory("tls");
        keystore = tlsDirectory.resolve("tls.p12");
        Process keytool = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                        "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                        "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1", "-validity", "2",
                        "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "changeit")
                .redirectErrorStream(true)
                .start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(keytool.waitFor()).as(output).isZero();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("test", store.getCertificate("test"));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        originalContext = SSLContext.getDefault();
        originalFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory();
        SSLContext.setDefault(context);
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
    }

    @AfterAll
    static void restoreTrust() throws Exception {
        SSLContext.setDefault(originalContext);
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory);
        Files.deleteIfExists(keystore);
        Files.deleteIfExists(tlsDirectory);
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    /** Fails the first {@code failures} receive-pack POSTs with 503, counts every receive-pack POST. */
    private static final class FlakyReceivePack implements Filter {
        final AtomicInteger posts = new AtomicInteger();
        final int failures;

        FlakyReceivePack(int failures) {
            this.failures = failures;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, jakarta.servlet.ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            if ("POST".equals(req.getMethod()) && req.getRequestURI().endsWith("/git-receive-pack")) {
                if (posts.incrementAndGet() <= failures) {
                    ((HttpServletResponse) response).sendError(503);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }

    private URI serve(String path, Repository repository, Filter filter) throws Exception {
        GitServlet servlet = new GitServlet();
        servlet.setRepositoryResolver((req, name) -> {
            repository.incrementOpen();
            return repository;
        });
        servlet.setReceivePackFactory((req, db) -> new ReceivePack(db));
        SslContextFactory.Server ssl = new SslContextFactory.Server();
        ssl.setKeyStorePath(keystore.toString());
        ssl.setKeyStorePassword("changeit");
        server = new Server();
        ServerConnector connector = new ServerConnector(server, ssl);
        connector.setHost("127.0.0.1");
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/");
        context.addFilter(new FilterHolder(filter), "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(servlet), "/*");
        server.setHandler(context);
        server.start();
        return URI.create("https://127.0.0.1:" + connector.getLocalPort() + path);
    }

    private PublishConfig config(URI url) {
        return new PublishConfig(
                url,
                BRANCH,
                Credentials.none(),
                tmp.resolve("work"),
                RetentionPolicy.defaults(),
                new RetryPolicy(5, Duration.ofMillis(1), Duration.ofMillis(5)),
                PublishConfig.defaultAuthor(),
                false,
                false,
                PublishConfig.DEFAULT_CHECKSUMS);
    }

    @Test
    void aServerWithoutAtomicPushGetsTheBranchWithoutTheHint() throws Exception {
        InMemoryRepository remote = new InMemoryRepository(new DfsRepositoryDescription("remote"));
        remote.setPerformsAtomicTransactions(false); // receive-pack then does not advertise "atomic"
        URI url = serve("/remote.git", remote, new FlakyReceivePack(0));

        try (Publication publication = new GitPublisher(config(url), PublisherLog.silent()).checkout()) {
            Files.writeString(publication.repositoryDirectory().resolve("a.txt"), "a");

            PublishResult result = publication.publish(new PublishInfo(List.of(), Optional.empty()));

            assertThat(result.pushed()).isTrue();
            assertThat(remote.exactRef("refs/heads/" + BRANCH).getObjectId()).isEqualTo(result.commit());
            assertThat(remote.exactRef(TREE_REF)).as("hint on a server without atomic pushes").isNull();
        }
    }

    @Test
    void aTransientPushFailureIsRetriedWhateverTheRepositoryIsCalled() throws Exception {
        InMemoryRepository remote = new InMemoryRepository(new DfsRepositoryDescription("remote"));
        // atomic pushes supported (InMemoryRepository default)
        FlakyReceivePack flaky = new FlakyReceivePack(1);
        URI url = serve("/acme/atomic-snapshots.git", remote, flaky);

        try (Publication publication = new GitPublisher(config(url), PublisherLog.silent()).checkout()) {
            Files.writeString(publication.repositoryDirectory().resolve("a.txt"), "a");

            PublishResult result = publication.publish(new PublishInfo(List.of(), Optional.empty()));

            assertThat(result.pushed()).isTrue();
            assertThat(result.attempts())
                    .as("the 503 is a transport error of attempt 1; attempt 2 pushes (design section 4 step 3.vi)")
                    .isEqualTo(2);
            assertThat(remote.exactRef(TREE_REF))
                    .as("the server supports atomic pushes, so the hint is not skipped")
                    .isNotNull();
        }
    }
}
