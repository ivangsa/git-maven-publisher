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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
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
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTPS token authentication (T24) against a real JGit HTTP server that enforces basic authentication. */
class GitPublisherTokenAuthTest {

    private static final String BRANCH = "gh-pages";
    private static final String RIGHT = "right-token-7f3a9c1d";
    private static final String WRONG = "wrong-token-52be80aa";
    private static final RetryPolicy FAST = new RetryPolicy(5, Duration.ofMillis(1), Duration.ofMillis(5));

    @TempDir
    Path tmp;

    private static SSLContext originalContext;
    private static javax.net.ssl.SSLSocketFactory originalFactory;
    private static Path tlsDirectory;
    private static Path keystore;

    private BareRepo bare;
    private Server server;
    private URI url;
    private final AtomicReference<String> acceptedToken = new AtomicReference<>(RIGHT);
    /** Authorization headers received by receive-pack discovery requests (one per push attempt). */
    private final List<String> pushDiscoveries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        bare = BareRepo.create(tmp.resolve("remote.git"));
        StoredConfig config = bare.repository().getConfig();
        config.setBoolean("http", null, "receivepack", true);
        config.save();

        GitServlet servlet = new GitServlet();
        servlet.setRepositoryResolver((req, name) -> {
            bare.repository().incrementOpen();
            return bare.repository();
        });
        SslContextFactory.Server ssl = new SslContextFactory.Server();
        ssl.setKeyStorePath(keystore.toString());
        ssl.setKeyStorePassword("changeit");
        server = new Server();
        ServerConnector connector = new ServerConnector(server, ssl);
        connector.setHost("127.0.0.1");
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/");
        context.addFilter(new FilterHolder(new BasicAuth()), "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(servlet), "/*");
        server.setHandler(context);
        server.start();
        url = URI.create("https://127.0.0.1:" + connector.getLocalPort() + "/remote.git");
    }

    /**
     * A real TLS endpoint: a self-signed certificate for 127.0.0.1 made by the JDK's keytool, trusted by the JVM-wide
     * default SSL context for the duration of this class (once: the JDK caches the default socket factory).
     */
    @BeforeAll
    static void trustALoopbackCertificate() throws Exception {
        tlsDirectory = Files.createTempDirectory("tls");
        keystore = tlsDirectory.resolve("tls.p12");
        char[] password = "changeit".toCharArray();
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
            store.load(in, password);
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
        server.stop();
        bare.close();
    }

    /** Accepts exactly one token (any user name), answers 401 with a challenge otherwise. */
    private final class BasicAuth implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, jakarta.servlet.ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            String header = req.getHeader("Authorization");
            if ("git-receive-pack".equals(req.getParameter("service")) && header != null) {
                pushDiscoveries.add(header);
            }
            if (header != null && header.startsWith("Basic ")) {
                String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
                if (decoded.substring(decoded.indexOf(':') + 1).equals(acceptedToken.get())) {
                    chain.doFilter(request, response);
                    return;
                }
            }
            resp.setHeader("WWW-Authenticate", "Basic realm=\"git\"");
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    private PublishConfig config(String token, Path work) {
        return new PublishConfig(
                url,
                BRANCH,
                Credentials.token(token.toCharArray()),
                work,
                RetentionPolicy.defaults(),
                FAST,
                PublishConfig.defaultAuthor(),
                false,
                false,
                PublishConfig.DEFAULT_CHECKSUMS);
    }

    private static String textOfAllFiles(Path directory) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().equals("config")) {
                    all.append(Files.readString(file, StandardCharsets.ISO_8859_1));
                }
            }
        }
        return all.toString();
    }

    @Test
    void thePublicationSucceedsWithTheRightToken() throws Exception {
        Path work = tmp.resolve("work");
        try (Publication publication = new GitPublisher(config(RIGHT, work), PublisherLog.silent()).checkout()) {
            Files.writeString(publication.repositoryDirectory().resolve("a.txt"), "a");

            PublishResult result = publication.publish(new PublishInfo(List.of("g:a:1"), Optional.empty()));

            assertThat(result.pushed()).isTrue();
            assertThat(result.attempts()).isEqualTo(1);
        }
        assertThat(bare.readTree(BRANCH)).containsOnlyKeys("a.txt");
        assertThat(bare.commitCount(BRANCH)).isEqualTo(1);
        // the token is in no Git config of the work directory, and not in the bare repository's config either
        assertThat(textOfAllFiles(work)).doesNotContain(RIGHT);
        assertThat(textOfAllFiles(bare.directory())).doesNotContain(RIGHT);
        assertThat(url.toString()).doesNotContain(RIGHT);
    }

    @Test
    void aWrongTokenFailsAtCheckoutWithoutLeakingIt() {
        assertThatThrownBy(() -> new GitPublisher(config(WRONG, tmp.resolve("work")), PublisherLog.silent()).checkout())
                .isNotInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(WRONG);
                    }
                });
        assertThat(bare.branchTip(BRANCH)).isEmpty();
    }

    @Test
    void aTokenRejectedAtPushFailsAfterOneAttemptWithoutLeakingIt() throws Exception {
        Path work = tmp.resolve("work");
        RecordingLog log = new RecordingLog();
        try (Publication publication = new GitPublisher(config(RIGHT, work), log).checkout()) {
            Files.writeString(publication.repositoryDirectory().resolve("a.txt"), "a");
            acceptedToken.set(WRONG); // the token is revoked between checkout and push
            pushDiscoveries.clear();

            assertThatThrownBy(() -> publication.publish(new PublishInfo(List.of("g:a:1"), Optional.empty())))
                    .isInstanceOf(PublishException.class)
                    .satisfies(e -> {
                        for (Throwable t = e; t != null; t = t.getCause()) {
                            assertThat(String.valueOf(t.getMessage())).doesNotContain(RIGHT);
                        }
                    });
        }
        // JGit itself repeats a rejected basic-auth request up to 3 times inside one connection; a publisher retry
        // loop (5 attempts) would show 15 requests and "Attempt n of 5" warnings.
        assertThat(pushDiscoveries).as("authenticated push requests").hasSizeLessThanOrEqualTo(3);
        assertThat(log.lines).noneMatch(line -> line.contains("Attempt"));
        assertThat(bare.branchTip(BRANCH)).isEmpty();
        assertThat(textOfAllFiles(work)).doesNotContain(RIGHT);
        assertThat(log.lines).noneMatch(line -> line.contains(RIGHT));
    }

    @Test
    void aWrongTokenAgainstAnExistingBranchAlsoFailsAtCheckout() throws Exception {
        bare.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "first");
        pushDiscoveries.clear();

        assertThatThrownBy(() -> new GitPublisher(config(WRONG, tmp.resolve("work")), PublisherLog.silent()).checkout())
                .isNotInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(GitPublication.isFatal(e)).isTrue());
        assertThat(bare.readTree(BRANCH)).containsOnlyKeys("a.txt");
    }

    /** Collects every log line. */
    private static final class RecordingLog implements PublisherLog {
        final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public void info(String message) {
            lines.add(message);
        }

        @Override
        public void warn(String message) {
            lines.add(message);
        }

        @Override
        public void debug(String message) {
            lines.add(message);
        }
    }
}
