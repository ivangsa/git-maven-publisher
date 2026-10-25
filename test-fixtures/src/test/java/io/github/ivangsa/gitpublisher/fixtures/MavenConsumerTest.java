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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenConsumerTest {

    @TempDir
    Path tmp;

    /** Reads a directory into a path -> bytes map, the shape {@link BareRepo#commitFiles} takes. */
    static Map<String, byte[]> readFiles(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return files;
    }

    @Test
    void resolvesDeployedSnapshotFromBranchCheckoutAndFailsOnMissingArtifact() throws Exception {
        Path deployed = SequentialReference.replay(
                tmp.resolve("deployed"),
                java.util.List.of(
                        SequentialReference.Step.javaProject("org.acme", "alpha", "1.0-SNAPSHOT"),
                        SequentialReference.Step.javaProject("org.acme", "alpha", "1.0-SNAPSHOT")));
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            bare.commitFiles("gh-pages", readFiles(deployed), "publish");
            Path served = bare.checkout("gh-pages", tmp.resolve("served"));
            MavenConsumer.Result ok =
                    new MavenConsumer(tmp.resolve("ok"), served.toUri()).resolve(
                            "org.acme:alpha:1.0-SNAPSHOT", "org.acme:alpha:1.0-SNAPSHOT:sources");
            assertThat(ok.success()).as(ok.log()).isTrue();
            assertThat(ok.resolved("org.acme", "alpha", "1.0-SNAPSHOT", "")).isTrue();
            assertThat(ok.resolved("org.acme", "alpha", "1.0-SNAPSHOT", "sources")).isTrue();
            assertThat(Files.readString(ok.artifactFile("org.acme", "alpha", "1.0-SNAPSHOT", "", "jar")))
                    .isEqualTo("jar of org.acme:alpha:1.0-SNAPSHOT");

            MavenConsumer.Result missing =
                    new MavenConsumer(tmp.resolve("missing"), served.toUri()).resolve("org.acme:nope:1.0-SNAPSHOT");
            assertThat(missing.success()).isFalse();
            assertThat(missing.log()).contains("org.acme:nope:jar:1.0-SNAPSHOT");
            assertThat(missing.resolved("org.acme", "nope", "1.0-SNAPSHOT", "")).isFalse();
        }
    }
}
