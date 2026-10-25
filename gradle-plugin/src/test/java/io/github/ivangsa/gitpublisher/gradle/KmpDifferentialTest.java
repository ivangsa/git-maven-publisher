/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import io.github.ivangsa.gitpublisher.fixtures.TreeDiff;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T41, the primary oracle of design section 9 for Gradle. Expected: the KMP publications written by Gradle's own
 * {@code maven-publish} into a clean {@code file://} repository. Actual: the very same publications of the very same
 * build transported through Git by the plugin, while a concurrent publisher of other coordinates moves the branch
 * between the plugin's checkout and its push (a real lease failure and replay). After removing the other
 * publisher's coordinates the two trees must not differ.
 */
class KmpDifferentialTest {

    private static final String OTHER_GROUP = "org.other";

    @TempDir
    Path temp;

    private BareRepo remote;
    private KmpSample sample;
    private Path reference;
    private Path ready;
    private Path go;

    @BeforeEach
    void create() {
        remote = BareRepo.create(temp.resolve("remote.git"));
        sample = new KmpSample(temp.resolve("sample"), remote.uri());
        reference = temp.resolve("reference");
        ready = temp.resolve("ready.marker");
        go = temp.resolve("go.marker");
    }

    @AfterEach
    void close() {
        remote.close();
    }

    /** Gradle's own {@code maven-publish} also publishes to a clean file repository, in the same build. */
    private void addReferenceRepository() {
        sample.appendToBuild("""
                publishing {
                    repositories {
                        maven {
                            name = 'reference'
                            url = uri('%s')
                        }
                    }
                }
                """.formatted(reference.toUri()));
    }

    @Test
    void theSamePublicationsThroughGitEqualGradlesOwnPublishAfterALeaseFailure() throws Exception {
        addReferenceRepository();
        sample.pauseBetweenCheckoutAndPush(ready, go);

        CompletableFuture<BuildResult> build = CompletableFuture.supplyAsync(() -> sample.publish());
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(25);
        while (!Files.exists(ready)) {
            if (build.isDone()) {
                // Surfaces the build failure, or fails because the pause task never ran.
                build.get();
                throw new AssertionError("the build finished without reaching the concurrent publisher");
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the build never reached the concurrent publisher");
            }
            Thread.sleep(100);
        }

        // The concurrent publisher: a real Maven Resolver deploy of other coordinates, pushed onto the branch tip.
        Path other = temp.resolve("other");
        try (ResolverDeployer deployer = new ResolverDeployer(other, "SHA-1", "MD5")) {
            deployer.deployJavaProject(OTHER_GROUP, "thing", "2.0-SNAPSHOT");
        }
        Map<String, byte[]> files = new HashMap<>(remote.readTree(KmpSample.BRANCH));
        files.putAll(read(other));
        remote.commitFiles(KmpSample.BRANCH, files, "concurrent publisher");
        Files.writeString(go, "go");

        BuildResult result = build.get(25, TimeUnit.MINUTES);
        assertTrue(
                result.getOutput().contains("moved; replayed"),
                "the push did not hit a lease failure:\n" + result.getOutput());

        Path checkout = remote.checkout(KmpSample.BRANCH, temp.resolve("checkout"));
        assertTrue(Files.isDirectory(checkout.resolve("org/other/thing")), "the concurrent publisher's files were lost");
        MetadataAssertions.assertValidRepository(checkout, Set.of("sha1", "md5"));
        delete(checkout.resolve("org"));

        assertTrue(Files.isDirectory(reference.resolve("com/acme/kmplib-jvm")), "the reference repository is empty");
        TreeDiff.compare(reference, checkout).assertNoDifference();
    }

    @Test
    void theComparisonSeesAFileMissingFromTheGitTree() throws Exception {
        // Harness self-test: the same comparison fails when the Git side lacks a published file.
        addReferenceRepository();
        sample.publish();
        Path checkout = remote.checkout(KmpSample.BRANCH, temp.resolve("checkout"));
        TreeDiff.compare(reference, checkout).assertNoDifference();
        try (Stream<Path> files = Files.walk(checkout.resolve("com/acme/kmplib-js"))) {
            Path module = files.filter(f -> f.getFileName().toString().endsWith(".module")).findFirst().orElseThrow();
            Files.delete(module);
        }
        assertFalse(TreeDiff.compare(reference, checkout).isEmpty());
        assertEquals(1, remote.commitCount(KmpSample.BRANCH));
    }

    private static Map<String, byte[]> read(Path root) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return result;
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
}
