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

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Review 2026-10-25-20d7325, F1. Design section 6 asks {@code <repo>/<groupPath>/<artifactId>/X/<artifactId>-X.pom}.
 * {@code ReleaseCheck.exists} builds that URL by appending the relative path to the whole repository string, so a
 * repository URL with a query or a fragment (for example a signed {@code ?sig=...} URL) puts the POM path into the
 * query or fragment, and the request goes to the repository root. A root that answers 200 with a non-HTML type (an
 * S3-style XML listing, a JSON API) then makes every untouched {@code -SNAPSHOT} version look released, and they are
 * all deleted. {@code RetentionPolicy} accepts such URLs.
 *
 * <p>Either fix is accepted: {@code RetentionPolicy} rejects a release repository with a query or fragment, or the
 * check asks the POM path itself. The server is the JDK's in-process {@link HttpServer}: the code under test is our
 * client (D-107).
 */
class R42ReleaseRepositoryQueryReviewTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 200 {@code application/xml} for the repository root only (a bucket listing), 404 for everything else. */
    private String serveListingAtRoot() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            paths.add(path);
            if (path.equals("/maven2/") || path.equals("/maven2")) {
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/maven2/";
    }

    private static boolean policyRejects(String suffix) {
        try {
            new RetentionPolicy(3, RetentionPolicy.UNLIMITED, URI.create("https://repo.example.org/maven2/" + suffix));
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private void assertTheRootIsNeverTakenForARelease(String suffix) throws Exception {
        if (policyRejects(suffix)) {
            return; // the configuration fails before any check runs
        }
        URI repository = URI.create(serveListingAtRoot() + suffix);

        boolean released = new ReleaseCheck(PublisherLog.silent()).exists(repository, "org/acme", "lib", "1.0");

        assertThat(released)
                .as("release of org.acme:lib:1.0 behind " + suffix + " (requests: " + paths + ")")
                .isFalse();
        assertThat(paths).as("the check must ask the POM, not the repository root").doesNotContain("/maven2/");
    }

    @Test
    void aQueryInTheRepositoryUrlDoesNotTurnTheRootIntoARelease() throws Exception {
        assertTheRootIsNeverTakenForARelease("?sig=abc");
    }

    @Test
    void aFragmentInTheRepositoryUrlDoesNotTurnTheRootIntoARelease() throws Exception {
        assertTheRootIsNeverTakenForARelease("#releases");
    }
}
