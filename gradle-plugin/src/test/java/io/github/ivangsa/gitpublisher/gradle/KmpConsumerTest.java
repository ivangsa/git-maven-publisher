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
package io.github.ivangsa.gitpublisher.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.gradle.GradleConsumer.Resolved;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T40: the KMP sample is published through Git (T39), the pushed branch is checked out into a plain directory, and a
 * clean Gradle KMP consumer with empty caches resolves it from that {@code file://} URL. The consumer, not our code,
 * decides which variant each target gets.
 */
class KmpConsumerTest {

    private static final String COORDINATES = KmpSample.GROUP + ":" + KmpSample.NAME + ":" + KmpSample.VERSION;

    @TempDir
    static Path directory;

    private static Path checkout;
    private static Path home;

    @BeforeAll
    static void publishAndCheckOut() throws IOException {
        home = Files.createTempDirectory("kmp-consumer-home");
        try (BareRepo remote = BareRepo.create(directory.resolve("remote.git"))) {
            new KmpSample(directory.resolve("sample"), remote.uri()).publish();
            checkout = remote.checkout(KmpSample.BRANCH, directory.resolve("checkout"));
        }
    }

    @AfterAll
    static void cleanUp() {
        GradleConsumer.deleteHome(home);
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static Resolved only(List<Resolved> resolved, String configuration, String componentPart) {
        List<Resolved> matches = resolved.stream()
                .filter(r -> r.configuration().equals(configuration) && r.component().contains(componentPart))
                .toList();
        assertEquals(1, matches.size(), configuration + " " + componentPart + " in " + resolved);
        return matches.get(0);
    }

    @Test
    void eachTargetGetsItsOwnVariantFromTheCheckout() {
        GradleConsumer consumer = new GradleConsumer(directory.resolve("consumer"), home);
        List<Resolved> resolved = consumer.resolve(checkout.toUri(), COORDINATES);

        String nativeTarget = KmpSample.nativeTarget();
        String nativeId = KmpSample.NAME + "-" + nativeTarget.toLowerCase(Locale.ROOT);
        Resolved jvm = only(resolved, "jvmCompileClasspath", "com.acme:" + KmpSample.NAME + "-jvm:");
        Resolved js = only(resolved, "jsCompileClasspath", "com.acme:" + KmpSample.NAME + "-js:");
        Resolved nativeKlib = only(resolved, nativeTarget + "CompileKlibraries", "com.acme:" + nativeId + ":");

        assertTrue(jvm.fileName().endsWith(".jar"), jvm.toString());
        assertTrue(js.fileName().endsWith(".klib"), js.toString());
        assertTrue(nativeKlib.fileName().endsWith(".klib"), nativeKlib.toString());
        // Gradle resolved the unique snapshot (timestamp and build number) that was published.
        for (Resolved r : List.of(jvm, js, nativeKlib)) {
            assertTrue(r.component().matches(".*:1\\.0-SNAPSHOT:\\d{8}\\.\\d{6}-\\d+"), r.toString());
        }
        // No target is served another target's variant.
        for (Resolved r : resolved) {
            String component = r.component();
            if (r.configuration().equals("jvmCompileClasspath")) {
                assertFalse(component.contains(KmpSample.NAME + "-js") || component.contains(nativeId), r.toString());
            } else if (r.configuration().equals("jsCompileClasspath")) {
                assertFalse(component.contains(KmpSample.NAME + "-jvm") || component.contains(nativeId), r.toString());
            } else {
                assertFalse(component.contains(KmpSample.NAME + "-jvm") || component.contains(KmpSample.NAME + "-js"), r.toString());
            }
        }
    }

    /** The harness must notice a broken tree: without the JS module the JS target cannot resolve. */
    @Test
    void theConsumerFailsWhenATargetModuleIsMissingFromTheCheckout() throws IOException {
        Path broken = directory.resolve("broken-checkout");
        try (Stream<Path> walk = Files.walk(checkout)) {
            for (Path source : walk.toList()) {
                Path target = broken.resolve(checkout.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        delete(broken.resolve("com/acme/" + KmpSample.NAME + "-js"));
        GradleConsumer consumer =
                new GradleConsumer(directory.resolve("consumer-broken"), home);
        String failure = consumer.resolveAndFail(broken.toUri(), COORDINATES);
        assertTrue(failure.contains(KmpSample.NAME + "-js"), failure);
    }
}
