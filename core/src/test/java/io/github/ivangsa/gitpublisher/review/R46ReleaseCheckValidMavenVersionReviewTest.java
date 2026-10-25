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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Review 2026-10-25-03ebe01, F1. R43 rejects every path segment outside a narrow ASCII allow-list. Maven only
 * rejects filesystem-reserved characters from a version, so {@code 1.0@rc} is a valid version; {@code @} is also a
 * URI path character. Returning doubt without asking its release POM means release-aware cleanup never removes this
 * otherwise valid old snapshot, contrary to design section 6.
 */
class R46ReleaseCheckValidMavenVersionReviewTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aValidMavenVersionWhosePomExistsIsRecognizedAsReleased() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            paths.add(path);
            if (path.equals("/maven2/org/acme/lib/1.0@rc/lib-1.0@rc.pom")) {
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        URI repository = URI.create("http://localhost:" + server.getAddress().getPort() + "/maven2/");

        boolean released = new ReleaseCheck(PublisherLog.silent()).exists(repository, "org/acme", "lib", "1.0@rc");

        assertThat(released).as("the Maven-valid release version is checked at its POM").isTrue();
        assertThat(paths).containsExactly("/maven2/org/acme/lib/1.0@rc/lib-1.0@rc.pom");
    }
}
