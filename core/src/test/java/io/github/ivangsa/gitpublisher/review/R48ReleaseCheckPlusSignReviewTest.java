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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Review 2026-10-25-5dc5581, F1. D-114 leaves {@code +} literal in every POM-path segment and states that no common
 * server gives the remaining sub-delims a meaning in a path. Amazon S3 (REST and website endpoints, also behind
 * CloudFront, a common host for Maven release repositories) decodes a literal {@code +} in the request path as a
 * space, so the object key {@code .../1.0+build/lib-1.0+build.pom} is only found through {@code %2B}. A literal
 * {@code +} asks for the key {@code .../1.0 build/lib-1.0 build.pom}, gets 404, and release-aware cleanup keeps the
 * released {@code 1.0+build-SNAPSHOT} forever. {@code +} is common in versions (semver build metadata, sbt-dynver).
 *
 * <p>The stub applies the S3 rule: the raw path is form-decoded ({@code +} is a space, {@code %2B} is {@code +}).
 * Every other server decodes {@code %2B} in a path to {@code +}, so quoting {@code +} names the same file everywhere.
 */
class R48ReleaseCheckPlusSignReviewTest {

    private HttpServer server;
    private final List<String> mapped = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aPlusInTheVersionNamesThePomKeyOnAServerThatReadsPlusAsSpace() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String key = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
            mapped.add(key);
            if (key.equals("/maven2/org/acme/lib/1.0+build/lib-1.0+build.pom")) {
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        URI repository = URI.create("http://localhost:" + server.getAddress().getPort() + "/maven2/");

        boolean released = new ReleaseCheck(PublisherLog.silent()).exists(repository, "org/acme", "lib", "1.0+build");

        assertThat(released)
                .as("release of org.acme:lib:1.0+build (keys as the server read them: " + mapped + ")")
                .isTrue();
    }
}
