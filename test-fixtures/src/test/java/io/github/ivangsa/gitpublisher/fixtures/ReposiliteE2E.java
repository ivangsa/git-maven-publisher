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
package io.github.ivangsa.gitpublisher.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Interop smoke test with a real repository manager (profile {@code e2e}, needs Docker): Maven Resolver deploys over
 * HTTP to Reposilite and a clean Maven consumer resolves from it. Reposilite is not a metadata oracle (D-014): the
 * client computes the metadata, the server stores files. This only shows our harness speaks to a real server.
 */
class ReposiliteE2E {

    private static final String TOKEN_NAME = "admin";
    private static final String TOKEN_SECRET = "e2e-throwaway-secret";

    @TempDir
    Path tmp;

    @Test
    void deployOverHttpAndResolveWithCleanMaven() throws Exception {
        try (GenericContainer<?> reposilite = new GenericContainer<>(DockerImageName.parse("dzikoysk/reposilite:3.6.4"))
                .withExposedPorts(8080)
                .withEnv("REPOSILITE_OPTS", "--token " + TOKEN_NAME + ":" + TOKEN_SECRET)
                .waitingFor(Wait.forHttp("/").forStatusCode(200))) {
            reposilite.start();
            URI snapshots = URI.create("http://" + reposilite.getHost() + ":" + reposilite.getMappedPort(8080) + "/snapshots/");

            try (ResolverDeployer deployer = new ResolverDeployer(snapshots, TOKEN_NAME, TOKEN_SECRET)) {
                for (int i = 0; i < 3; i++) {
                    deployer.deployJavaProject("org.acme", "e2e-lib", "1.0-SNAPSHOT");
                }
            }

            HttpResponse<String> metadata = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(snapshots.resolve("org/acme/e2e-lib/1.0-SNAPSHOT/maven-metadata.xml"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(metadata.statusCode()).isEqualTo(200);
            assertThat(metadata.body()).contains("<buildNumber>3</buildNumber>");

            MavenConsumer.Result result = new MavenConsumer(tmp.resolve("consumer"), snapshots)
                    .resolve("org.acme:e2e-lib:1.0-SNAPSHOT", "org.acme:e2e-lib:1.0-SNAPSHOT:sources");
            assertThat(result.success()).as(result.log()).isTrue();
            assertThat(result.resolved("org.acme", "e2e-lib", "1.0-SNAPSHOT", "")).isTrue();
            assertThat(result.resolved("org.acme", "e2e-lib", "1.0-SNAPSHOT", "sources")).isTrue();
        }
    }
}
