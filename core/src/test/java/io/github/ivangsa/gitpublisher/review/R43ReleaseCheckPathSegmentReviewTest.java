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
 * Review 2026-10-25-a3707d0, F1. R42 (D-109) closed the query and fragment of the repository URL, but the other half
 * of the URL is still built by string concatenation: {@code ReleaseCheck.exists} appends the artifactId and the base
 * version, taken from directory names of the published tree, without encoding them. A version directory
 * {@code 1.0?rc-SNAPSHOT} (any name Git can hold; this tool does not publish it, but a tree may contain it) yields
 * {@code <repo>/org/acme/lib/1.0?rc/lib-1.0?rc.pom}: the request goes to {@code /maven2/org/acme/lib/1.0} with a
 * query, not to the POM of design section 6. When that resource answers 200 with a non-HTML type, the version is
 * taken for released and deleted.
 *
 * <p>Accepted fixes: percent-encode each path segment, or answer "no" without a request for names that do not form
 * the POM path. Either way the server must never see a request for a path other than the POM. The server is the JDK's
 * in-process {@link HttpServer}: the code under test is our client (D-107).
 */
class R43ReleaseCheckPathSegmentReviewTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 200 {@code application/xml} for {@code /maven2/org/acme/lib/1.0} (not a POM), 404 for everything else. */
    private URI serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            paths.add(path);
            if (path.equals("/maven2/org/acme/lib/1.0")) {
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/maven2/");
    }

    @Test
    void aQuestionMarkInTheVersionDoesNotTurnAnotherResourceIntoTheReleasePom() throws Exception {
        URI repository = serve();

        boolean released = new ReleaseCheck(PublisherLog.silent()).exists(repository, "org/acme", "lib", "1.0?rc");

        assertThat(released)
                .as("release of org.acme:lib:1.0?rc (requests: " + paths + ")")
                .isFalse();
        assertThat(paths)
                .as("the check may only ask the POM path of design section 6")
                .allMatch(p -> p.endsWith(".pom"));
    }
}
