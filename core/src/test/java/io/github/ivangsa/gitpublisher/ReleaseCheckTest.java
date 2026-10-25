/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The release check (R37, R39): answers are asked once per publication, failures are logged once, HTML is doubt. */
class ReleaseCheckTest {

    @TempDir
    Path releases;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> warnings = new ArrayList<>();

    private final PublisherLog log = new PublisherLog() {
        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void debug(String message) {}
    };

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI serve(int status, String contentType) throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/maven2/");
    }

    @Test
    void anAnswerIsAskedOncePerPublicationEvenAcrossAttempts() throws Exception {
        Path dir = releases.resolve("org/acme/lib/1.0");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("lib-1.0.pom"), "<project/>");
        ReleaseCheck check = new ReleaseCheck(log);

        assertThat(check.exists(releases.toUri(), "org/acme", "lib", "1.0")).isTrue();
        Files.delete(dir.resolve("lib-1.0.pom")); // a second attempt must not ask again
        assertThat(check.exists(releases.toUri(), "org/acme", "lib", "1.0")).isTrue();
        assertThat(new ReleaseCheck(log).exists(releases.toUri(), "org/acme", "lib", "1.0")).isFalse();
    }

    @Test
    void aFailureIsAskedAndLoggedOnce() throws Exception {
        URI withQuery = serve(500, null);
        ReleaseCheck check = new ReleaseCheck(log);

        assertThat(check.exists(withQuery, "org/acme", "lib", "1.0")).isFalse();
        assertThat(check.exists(withQuery, "org/acme", "lib", "1.0")).isFalse();

        assertThat(requests.get()).isEqualTo(1);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("HTTP 500").doesNotContain("secret").doesNotContain("token");
    }

    @Test
    void aPlain404IsNotWarnedAboutAndAnHtmlAnswerIs() throws Exception {
        ReleaseCheck missing = new ReleaseCheck(log);
        assertThat(missing.exists(serve(404, null), "org/acme", "lib", "1.0")).isFalse();
        assertThat(warnings).isEmpty();
        server.stop(0);

        ReleaseCheck html = new ReleaseCheck(log);
        assertThat(html.exists(serve(200, "text/html; charset=utf-8"), "org/acme", "lib", "1.0")).isFalse();
        assertThat(warnings).hasSize(1).allSatisfy(w -> assertThat(w).contains("HTML"));
    }

    @Test
    void theServerSeesTheExactPomPath() throws Exception {
        List<String> paths = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();

        assertThat(new ReleaseCheck(log).exists(URI.create(base + "/maven2/"), "org/acme", "lib", "1.0")).isTrue();
        assertThat(new ReleaseCheck(log).exists(URI.create(base + "/maven2"), "org/acme", "lib", "1.0")).isTrue();

        assertThat(paths)
                .containsExactly(
                        "HEAD /maven2/org/acme/lib/1.0/lib-1.0.pom", "HEAD /maven2/org/acme/lib/1.0/lib-1.0.pom");
    }

    @Test
    void aQueryOrFragmentIsNeverAskedAndPolicyRejectsItWithoutEchoingIt() throws Exception {
        URI repository = serve(200, "application/xml");
        ReleaseCheck check = new ReleaseCheck(log);

        assertThat(check.exists(URI.create(repository + "?token=secret"), "org/acme", "lib", "1.0"))
                .isFalse();
        assertThat(check.exists(URI.create(repository + "#frag"), "org/acme", "lib", "1.0"))
                .isFalse();
        assertThat(requests.get()).isZero();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RetentionPolicy(
                        3, RetentionPolicy.UNLIMITED, URI.create("https://repo.example.org/m2/?token=secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query")
                .hasMessageNotContaining("secret");
    }

    @Test
    void aPomAnswerCountsAsARelease() throws Exception {
        assertThat(new ReleaseCheck(log).exists(serve(200, "application/xml"), "org/acme", "lib", "1.0")).isTrue();
        assertThat(warnings).isEmpty();
    }
}
