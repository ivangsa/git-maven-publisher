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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * N threads publish to one bare repository at the same moment (barrier start). Each thread publishes one
 * {@link SequentialReference.Step}; steps mix shared and distinct coordinates. The publisher under test is pluggable.
 *
 * <p>The scenario records every file the native deploy of each step wrote (everything except metadata, whose names are
 * unique through timestamp and build number) and, after all threads finished, checks that each of them is in the
 * branch with the same content, that the tree passes {@link MetadataAssertions}, and optionally that it equals the
 * {@link SequentialReference} of the order in which the deploys ran.
 */
public final class ConcurrentPublishScenario {

    /** The native deploy of one publication: writes into the given directory (the publisher's work tree). */
    @FunctionalInterface
    public interface DeployAction {
        void deployInto(Path repositoryDirectory);
    }

    /** What a publisher receives for one publication. */
    public record Publication(
            SequentialReference.Step step, BareRepo remote, String branch, Path workDirectory, DeployAction deploy) {}

    /** The publisher under test. It must run {@code deploy} exactly once and make the result appear on the branch. */
    @FunctionalInterface
    public interface Publisher {
        void publish(Publication publication) throws Exception;
    }

    private final BareRepo remote;
    private final String branch;
    private final Path workRoot;
    private final List<SequentialReference.Step> steps;

    private final Map<String, byte[]> deployedFiles = Collections.synchronizedMap(new TreeMap<>());
    private final List<SequentialReference.Step> deployOrder = Collections.synchronizedList(new ArrayList<>());

    /** One thread per step. */
    public ConcurrentPublishScenario(
            BareRepo remote, String branch, Path workRoot, List<SequentialReference.Step> steps) {
        this.remote = remote;
        this.branch = branch;
        this.workRoot = workRoot;
        this.steps = List.copyOf(steps);
    }

    /**
     * {@code threads} steps over shared and distinct coordinates: even threads publish the same
     * {@code shared:1.0-SNAPSHOT}, threads 1 mod 4 other versions of {@code shared}, threads 3 mod 4 their own artifact.
     */
    public static List<SequentialReference.Step> mixedSteps(int threads) {
        List<SequentialReference.Step> steps = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            steps.add(
                    switch (i % 4) {
                        case 0, 2 -> SequentialReference.Step.javaProject("org.acme", "shared", "1.0-SNAPSHOT");
                        case 1 -> SequentialReference.Step.javaProject("org.acme", "shared", (i + 1) + ".0-SNAPSHOT");
                        default -> SequentialReference.Step.javaProject("org.acme", "artifact-" + i, "1.0-SNAPSHOT");
                    });
        }
        return steps;
    }

    /** Runs all threads and waits for them. Failures of publishers are collected in the outcome, not thrown. */
    public Outcome run(Publisher publisher) {
        int n = steps.size();
        CyclicBarrier start = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            SequentialReference.Step step = steps.get(i);
            Path work = workRoot.resolve("publisher-" + i);
            futures.add(pool.submit(() -> {
                try {
                    start.await(60, TimeUnit.SECONDS);
                    publisher.publish(new Publication(step, remote, branch, work, dir -> deploy(step, dir)));
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        try {
            for (Future<?> f : futures) {
                f.get(180, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            errors.add(e);
        } finally {
            pool.shutdownNow();
        }
        return new Outcome(List.copyOf(errors));
    }

    private void deploy(SequentialReference.Step step, Path directory) {
        Map<String, byte[]> before = ReferencePublishers.readFiles(directory);
        try (ResolverDeployer deployer = new ResolverDeployer(directory)) {
            SequentialReference.apply(deployer, step);
        }
        Map<String, byte[]> after = ReferencePublishers.readFiles(directory);
        after.forEach((path, content) -> {
            boolean metadata = path.substring(path.lastIndexOf('/') + 1).startsWith("maven-metadata.xml");
            if (!metadata && !(before.containsKey(path) && Arrays.equals(before.get(path), content))) {
                deployedFiles.put(path, content);
            }
        });
        deployOrder.add(step);
    }

    /** Result of one run; the assertions read the branch of the bare repository. */
    public final class Outcome {

        private final List<Throwable> errors;

        private Outcome(List<Throwable> errors) {
            this.errors = errors;
        }

        public List<Throwable> errors() {
            return errors;
        }

        /** Steps in the order their native deploys completed (the order a sequential run must replay). */
        public List<SequentialReference.Step> deployOrder() {
            synchronized (deployOrder) {
                return List.copyOf(deployOrder);
            }
        }

        /** Deployed files (path with timestamped name) that are missing from the branch or have other content. */
        public List<String> lostFiles() {
            Map<String, byte[]> tree = remote.readTree(branch);
            List<String> lost = new ArrayList<>();
            synchronized (deployedFiles) {
                deployedFiles.forEach((path, content) -> {
                    if (!tree.containsKey(path) || !Arrays.equals(tree.get(path), content)) {
                        lost.add(path);
                    }
                });
            }
            return lost;
        }

        public int deployedFileCount() {
            return deployedFiles.size();
        }

        public Outcome assertNoErrors() {
            if (!errors.isEmpty()) {
                AssertionError error = new AssertionError(errors.size() + " publisher(s) failed, first: " + errors.get(0));
                errors.forEach(error::addSuppressed);
                throw error;
            }
            return this;
        }

        public Outcome assertAllDeployedFilesPresent() {
            List<String> lost = lostFiles();
            if (!lost.isEmpty()) {
                throw new AssertionError(lost.size() + " of " + deployedFiles.size()
                        + " deployed files are missing from branch '" + branch + "':\n  " + String.join("\n  ", lost));
            }
            return this;
        }

        /** Checks out the branch into {@code directory} and runs {@link MetadataAssertions} on it. */
        public Path assertValidTree(Path directory) {
            Path tree = remote.checkout(branch, directory);
            MetadataAssertions.assertValidRepository(tree);
            return tree;
        }

        /** Checks out the branch and compares it with the sequential replay of {@link #deployOrder()}. */
        public Outcome assertMatchesSequentialReference(Path checkoutDirectory, Path referenceDirectory) {
            Path actual = remote.checkout(branch, checkoutDirectory);
            Path expected = SequentialReference.replay(referenceDirectory, deployOrder());
            TreeDiff.compare(expected, actual).assertNoDifference();
            return this;
        }

        /** The full contract: no errors, nothing lost, valid tree. */
        public Outcome assertConsistent(Path checkoutDirectory) {
            assertNoErrors();
            assertAllDeployedFilesPresent();
            assertValidTree(checkoutDirectory);
            return this;
        }
    }

    static Map<String, byte[]> filesOf(Path root) {
        Map<String, byte[]> files = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }
}
