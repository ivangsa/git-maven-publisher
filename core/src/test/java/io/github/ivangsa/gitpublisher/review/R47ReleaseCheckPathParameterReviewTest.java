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
 * Review 2026-10-25-4de4033, F1. R46 (D-113) encodes each segment with the multi-argument {@code java.net.URI}
 * constructor, which leaves {@code ;} unquoted: it is a legal path character (RFC 3986 sub-delim). Servlet containers
 * (Tomcat, Jetty; the containers of Nexus and Artifactory, typical release repositories) treat {@code ;...} up to the
 * next {@code /} as a path parameter and drop it before mapping the path (Servlet specification, "path parameters").
 * A group directory {@code acme;rc} (any name Git can hold, the R43 premise) yields the request
 * {@code /maven2/org/acme;rc/lib/1.0/lib-1.0.pom}, which such a server answers with the POM of {@code org.acme:lib:1.0}.
 * That release then deletes {@code org.acme;rc:lib:1.0-SNAPSHOT}, which was never released.
 *
 * <p>The stub below applies that container rule (strip {@code ;[^/]*} from the raw path, then percent-decode). A
 * percent-encoded {@code %3B} is a literal character for such a server. Accepted fixes: quote {@code ;} in every
 * segment, or answer "no" without a request for a segment containing it.
 */
class R47ReleaseCheckPathParameterReviewTest {

    private HttpServer server;
    private final List<String> mapped = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aSemicolonInAGroupDirectoryDoesNotTurnAnotherReleaseIntoThisOne() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String raw = exchange.getRequestURI().getRawPath();
            String path = URLDecoder.decode(raw.replaceAll(";[^/]*", "").replace("+", "%2B"), StandardCharsets.UTF_8);
            mapped.add(path);
            if (path.equals("/maven2/org/acme/lib/1.0/lib-1.0.pom")) {
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        URI repository = URI.create("http://localhost:" + server.getAddress().getPort() + "/maven2/");

        boolean released = new ReleaseCheck(PublisherLog.silent()).exists(repository, "org/acme;rc", "lib", "1.0");

        assertThat(released)
                .as("release of org.acme;rc:lib:1.0 (paths as the server mapped them: " + mapped + ")")
                .isFalse();
        assertThat(mapped)
                .as("the server may only see the POM path of design section 6, or no request at all")
                .allMatch(p -> p.equals("/maven2/org/acme;rc/lib/1.0/lib-1.0.pom"));
    }
}
