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
package io.github.ivangsa.gitpublisher.fixtures.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.ConcurrentPublishScenario;
import io.github.ivangsa.gitpublisher.fixtures.SequentialReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Design section 7 requires a colliding build to be renamed; file-loss checks must accept that rename. */
class R08RenumberedPublicationTest {
    @TempDir
    Path tmp;

    @Test
    void validRenumberedPublicationIsNotReportedLost() {
        var step = SequentialReference.Step.javaProject("org.acme", "lib", "1.0-SNAPSHOT");
        CyclicBarrier deployed = new CyclicBarrier(2);
        Object pushLock = new Object();
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            var scenario = new ConcurrentPublishScenario(remote, "gh-pages", tmp.resolve("work"),
                    List.of(step, step), () -> Instant.parse("2026-10-24T09:00:00Z"));
            var outcome = scenario.run(publication -> {
                Path repo = Files.createDirectories(publication.workDirectory().resolve("repo"));
                publication.deploy().deployInto(repo);
                deployed.await();
                synchronized (pushLock) {
                    Map<String, byte[]> own = files(repo);
                    Map<String, byte[]> merged = new TreeMap<>(remote.readTree("gh-pages"));
                    if (merged.isEmpty()) {
                        merged.putAll(own);
                    } else {
                        // Minimal, deterministic instance of the mandated collision replay: preserve remote build 1,
                        // rename only our build to 2, and fix V-level metadata plus its checksums.
                        for (var entry : own.entrySet()) {
                            String path = entry.getKey();
                            byte[] content = entry.getValue();
                            if (path.endsWith("1.0-SNAPSHOT/maven-metadata.xml")) {
                                content = new String(content, StandardCharsets.UTF_8)
                                        .replace("20261024.090000-1", "20261024.090000-2")
                                        .replace("<buildNumber>1</buildNumber>", "<buildNumber>2</buildNumber>")
                                        .getBytes(StandardCharsets.UTF_8);
                            }
                            merged.put(path.replace("20261024.090000-1", "20261024.090000-2"), content);
                        }
                        String metadata = "org/acme/lib/1.0-SNAPSHOT/maven-metadata.xml";
                        for (String algorithm : List.of("SHA-1", "MD5")) {
                            String suffix = algorithm.equals("SHA-1") ? ".sha1" : ".md5";
                            merged.put(metadata + suffix, HexFormat.of()
                                    .formatHex(MessageDigest.getInstance(algorithm).digest(merged.get(metadata)))
                                    .getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    remote.commitFiles("gh-pages", merged, "publish");
                    publication.pushed();
                }
            });
            outcome.assertNoErrors();
            outcome.assertValidTree(tmp.resolve("checkout"));
            // Independent Resolver replay proves the committed tree is precisely the sequential oracle.
            outcome.assertMatchesSequentialReference(tmp.resolve("actual"), tmp.resolve("expected"));
            assertThat(outcome.lostFiles()).as("both unique publications survive as builds 1 and 2").isEmpty();
        }
    }

    private static Map<String, byte[]> files(Path root) throws Exception {
        Map<String, byte[]> result = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return result;
    }
}
