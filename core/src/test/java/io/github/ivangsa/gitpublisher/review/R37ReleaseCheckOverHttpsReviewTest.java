/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-25-250f684, F3. The {@code https} branch of the release check (the one the Central use case needs)
 * has no test. Design section 6 says "any doubt keeps the version" and drops it only when
 * {@code <repo>/.../X/<artifactId>-X.pom} exists. The client follows redirects and accepts any final 200, so a
 * repository that redirects unknown paths to an HTML login or landing page (common for repository managers behind SSO,
 * and for captive proxies) makes every old {@code -SNAPSHOT} version look released, and they are all deleted.
 *
 * <p>The server is the JDK's in-process {@link HttpsServer} with a throwaway self-signed key made by the JDK's
 * {@code keytool}; the code under test is our HTTP client (D-043 removed a server that tested nothing of ours).
 */
class R37ReleaseCheckOverHttpsReviewTest {

    private static final Set<ChecksumAlgorithm> ALGORITHMS = Set.of(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.MD5);
    private static final Path ARTIFACT = Path.of("org/acme/lib");

    @TempDir
    Path root;

    @TempDir
    Path keys;

    private HttpsServer server;
    private SSLContext previousDefault;

    @BeforeEach
    void startServer() throws Exception {
        Path keystore = keys.resolve("server.p12");
        Process keytool = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                        "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                        "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                        "-validity", "2", "-storetype", "PKCS12",
                        "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true)
                .start();
        try (InputStream out = keytool.getInputStream()) {
            out.readAllBytes();
        }
        assertThat(keytool.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(keytool.exitValue()).as("keytool").isZero();

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status;
            if (path.equals("/maven2/org/acme/lib/1.5/lib-1.5.pom")) {
                status = 200; // a real release
            } else if (path.equals("/maven2/org/acme/lib/1.6/lib-1.6.pom")) {
                status = 500;
            } else if (path.startsWith("/maven2/")) {
                exchange.getResponseHeaders().add("Location", "/login");
                status = 302; // unknown path: redirect to the login page
            } else if (path.equals("/login")) {
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                status = 200;
            } else {
                status = 404;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();

        previousDefault = SSLContext.getDefault();
        SSLContext.setDefault(context); // trust the throwaway key for this test only
    }

    @AfterEach
    void stopServer() {
        if (previousDefault != null) {
            SSLContext.setDefault(previousDefault);
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private URI repository() {
        return URI.create("https://localhost:" + server.getAddress().getPort() + "/maven2/");
    }

    private void deploy(String version, String start) {
        try (ResolverDeployer deployer = new ResolverDeployer(root, Instant.parse(start), "SHA-1", "MD5")) {
            deployer.deployJavaProject("org.acme", "lib", version);
        }
    }

    private Set<String> versionDirectories() throws Exception {
        try (Stream<Path> list = Files.list(root.resolve(ARTIFACT))) {
            return list.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void onlyAReleasePomCountsARedirectToAnHtmlPageOrAnErrorKeepsTheVersion() throws Exception {
        deploy("1.0-SNAPSHOT", "2026-10-24T10:00:00Z"); // no release: the server redirects to /login
        deploy("1.5-SNAPSHOT", "2026-10-24T10:10:00Z"); // released
        deploy("1.6-SNAPSHOT", "2026-10-24T10:20:00Z"); // server error
        deploy("2.0-SNAPSHOT", "2026-10-24T10:30:00Z"); // published now

        Retention.apply(
                root,
                List.of(ARTIFACT.resolve("2.0-SNAPSHOT")),
                new RetentionPolicy(3, -1).withReleaseRepository(repository()),
                ALGORITHMS);

        Set<String> remaining = versionDirectories();
        assertThat(remaining).as("released version dropped over https").doesNotContain("1.5-SNAPSHOT");
        assertThat(remaining).as("an HTTP 500 is doubt").contains("1.6-SNAPSHOT", "2.0-SNAPSHOT");
        assertThat(remaining)
                .as("a redirect that ends at an HTML login page is not a release POM; any doubt keeps")
                .contains("1.0-SNAPSHOT");
        MetadataAssertions.assertValidRepository(root, Set.of("sha1", "md5"));
    }
}
