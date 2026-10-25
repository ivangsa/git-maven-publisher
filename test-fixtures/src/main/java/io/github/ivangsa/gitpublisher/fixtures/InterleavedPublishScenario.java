/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A deterministic interleaving of two publications to one bare repository (D-044): publisher A checks out, publisher B
 * publishes in full (checkout, deploy, push), then A deploys and pushes. Everything runs on one thread, so the race
 * that matters (A's base is stale when A pushes) happens every time, not sometimes.
 *
 * <p>The publisher under test is pluggable. It tells the scenario where the interleaving point is by calling
 * {@link Publication#checkedOut()} right after it read the branch and before it deploys. A publisher that serializes
 * publications (a lock) never reaches that point; the scenario then runs B after A finished.
 *
 * <p>The scenario records every file the native deploy of each publication wrote (everything except metadata, whose
 * names are unique through timestamp and build number) and checks that each of them is on the branch with the same
 * content, that the tree passes {@link MetadataAssertions}, and that it equals the {@link SequentialReference}.
 */
public final class InterleavedPublishScenario {

    /** The native deploy of one publication: writes into the given directory (the publisher's work tree). */
    @FunctionalInterface
    public interface DeployAction {
        void deployInto(Path repositoryDirectory);
    }

    /** What a publisher receives for one publication. */
    public record Publication(
            SequentialReference.Step step,
            BareRepo remote,
            String branch,
            Path workDirectory,
            DeployAction deploy,
            Runnable onCheckedOut) {

        /**
         * The publisher calls this after it read the branch and before it deploys. For publisher A, publisher B
         * publishes completely inside this call.
         */
        public void checkedOut() {
            onCheckedOut.run();
        }
    }

    /** The publisher under test. It must run {@code deploy} exactly once and make the result appear on the branch. */
    @FunctionalInterface
    public interface Publisher {
        void publish(Publication publication) throws Exception;
    }

    private final BareRepo remote;
    private final String branch;
    private final Path workRoot;
    private final SequentialReference.Step stepA;
    private final SequentialReference.Step stepB;
    private final Supplier<Instant> sessionClock;

    /** Every file a deploy wrote, with its content. The same path can appear twice when two publications collide. */
    private final List<Map.Entry<String, byte[]>> deployedFiles = new ArrayList<>();

    public InterleavedPublishScenario(
            BareRepo remote,
            String branch,
            Path workRoot,
            SequentialReference.Step stepA,
            SequentialReference.Step stepB) {
        this(remote, branch, workRoot, stepA, stepB, Instant::now);
    }

    /**
     * {@code sessionClock} gives each publication its Maven session start, taken when the publication starts (tests fix
     * it to force identical timestamps). Steps without explicit parts get content that is unique per publication, so
     * two publications of equal coordinates never produce byte-identical files (review F4).
     */
    public InterleavedPublishScenario(
            BareRepo remote,
            String branch,
            Path workRoot,
            SequentialReference.Step stepA,
            SequentialReference.Step stepB,
            Supplier<Instant> sessionClock) {
        this.remote = remote;
        this.branch = branch;
        this.workRoot = workRoot;
        this.stepA = uniqueContent(stepA, "A");
        this.stepB = uniqueContent(stepB, "B");
        this.sessionClock = sessionClock;
    }

    private static SequentialReference.Step uniqueContent(SequentialReference.Step step, String publisher) {
        if (!step.parts().isEmpty()) {
            return step;
        }
        String id = step.coordinates() + " published by " + publisher;
        return SequentialReference.Step.of(
                step.groupId(),
                step.artifactId(),
                step.version(),
                ResolverDeployer.Part.of("", "jar", "jar of " + id),
                ResolverDeployer.Part.of(
                        "",
                        "pom",
                        ResolverDeployer.pom(step.groupId(), step.artifactId(), step.version()) + "<!-- " + id
                                + " -->"),
                ResolverDeployer.Part.of("sources", "jar", "sources of " + id));
    }

    /** Runs A with the interleaving point and B inside it. Failures of publishers are collected, not thrown. */
    public Outcome run(Publisher publisher) {
        AtomicReference<Throwable> errorB = new AtomicReference<>();
        AtomicReference<Map<String, byte[]>> afterB = new AtomicReference<>();
        boolean[] interleaved = {false};

        Throwable errorA = runOne(stepA, "publisher-a", publisher, () -> {
            if (interleaved[0]) {
                throw new IllegalStateException("Publication.checkedOut() called twice");
            }
            interleaved[0] = true;
            errorB.set(runOne(stepB, "publisher-b", publisher, () -> {}));
            afterB.set(remote.readTree(branch));
        });
        if (!interleaved[0]) {
            errorB.set(runOne(stepB, "publisher-b", publisher, () -> {}));
            afterB.set(remote.readTree(branch));
        }
        return new Outcome(errorA, errorB.get(), interleaved[0], afterB.get());
    }

    private Throwable runOne(SequentialReference.Step step, String name, Publisher publisher, Runnable onCheckedOut) {
        // Like Maven: the session start time is taken before checkout and stamps the snapshots later.
        Instant sessionStart = sessionClock.get();
        try {
            publisher.publish(new Publication(
                    step,
                    remote,
                    branch,
                    workRoot.resolve(name),
                    dir -> deploy(step, dir, sessionStart),
                    onCheckedOut));
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private void deploy(SequentialReference.Step step, Path directory, Instant sessionStart) {
        Map<String, byte[]> before = ReferencePublishers.readFiles(directory);
        try (ResolverDeployer deployer = new ResolverDeployer(directory, sessionStart)) {
            SequentialReference.apply(deployer, step);
        }
        Map<String, byte[]> after = ReferencePublishers.readFiles(directory);
        after.forEach((path, content) -> {
            boolean metadata = path.substring(path.lastIndexOf('/') + 1).startsWith("maven-metadata.xml");
            if (!metadata && !(before.containsKey(path) && Arrays.equals(before.get(path), content))) {
                deployedFiles.add(Map.entry(path, content));
            }
        });
    }

    /** Result of one run; the assertions read the branch of the bare repository. */
    public final class Outcome {

        private final Throwable errorA;
        private final Throwable errorB;
        private final boolean interleaved;
        private final Map<String, byte[]> treeAfterB;

        private Outcome(Throwable errorA, Throwable errorB, boolean interleaved, Map<String, byte[]> treeAfterB) {
            this.errorA = errorA;
            this.errorB = errorB;
            this.interleaved = interleaved;
            this.treeAfterB = treeAfterB;
        }

        /** The failure of publisher A (the one whose base became stale), or null. */
        public Throwable errorA() {
            return errorA;
        }

        /** The failure of publisher B (the one that published inside A's checkout-to-push window), or null. */
        public Throwable errorB() {
            return errorB;
        }

        /** True when the publisher reached the interleaving point, so B really published between A's checkout and push. */
        public boolean interleaved() {
            return interleaved;
        }

        /** Deployed files (path with timestamped name) that are missing from the branch or have other content. */
        public List<String> lostFiles() {
            Map<String, byte[]> tree = remote.readTree(branch);
            List<String> lost = new ArrayList<>();
            deployedFiles.forEach(file -> {
                if (!tree.containsKey(file.getKey()) || !Arrays.equals(tree.get(file.getKey()), file.getValue())) {
                    lost.add(file.getKey());
                }
            });
            return lost;
        }

        public int deployedFileCount() {
            return deployedFiles.size();
        }

        public Outcome assertNoErrors() {
            if (errorA != null || errorB != null) {
                AssertionError error = new AssertionError("publisher failed (A=" + errorA + ", B=" + errorB + ")");
                error.initCause(errorA != null ? errorA : errorB);
                if (errorA != null && errorB != null) {
                    error.addSuppressed(errorB);
                }
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

        /** Checks out the branch (exactly) into {@code directory} and runs {@link MetadataAssertions} on it. */
        public Path assertValidTree(Path directory) {
            Path tree = remote.checkout(branch, directory);
            MetadataAssertions.assertValidRepository(tree);
            return tree;
        }

        /**
         * Checks out the branch and compares it with the sequential replay of A then B or B then A: for disjoint
         * coordinates either order gives the same tree.
         */
        public Outcome assertMatchesSequentialReference(Path checkoutDirectory, Path referenceDirectory) {
            Path actual = remote.checkout(branch, checkoutDirectory);
            TreeDiff ab = TreeDiff.compare(
                    SequentialReference.replay(referenceDirectory.resolve("a-then-b"), List.of(stepA, stepB)), actual);
            if (ab.isEmpty()) {
                return this;
            }
            TreeDiff ba = TreeDiff.compare(
                    SequentialReference.replay(referenceDirectory.resolve("b-then-a"), List.of(stepB, stepA)), actual);
            if (ba.isEmpty()) {
                return this;
            }
            throw new AssertionError("trees differ from the sequential reference in both orders.\nA then B ("
                    + ab.differences().size() + "):\n" + ab.report() + "\nB then A (" + ba.differences().size()
                    + "):\n" + ba.report());
        }

        /** The contract for disjoint coordinates: no errors, nothing lost, valid tree, equal to the reference. */
        public Outcome assertBothPublished(Path workDirectory) {
            assertNoErrors();
            assertAllDeployedFilesPresent();
            assertValidTree(workDirectory.resolve("checkout"));
            assertMatchesSequentialReference(workDirectory.resolve("checkout-2"), workDirectory.resolve("reference"));
            return this;
        }

        /**
         * The contract for disjoint coordinates under a real race (T21, T23): {@link #assertBothPublished} plus the
         * proof that B published between A's checkout and push, so a publisher that never called
         * {@link Publication#checkedOut()} cannot pass without a lease failure ever happening.
         */
        public Outcome assertInterleavedAndBothPublished(Path workDirectory) {
            if (!interleaved) {
                throw new AssertionError(
                        "the publisher never reached the interleaving point (Publication.checkedOut()), so no lease"
                                + " failure was forced");
            }
            return assertBothPublished(workDirectory);
        }

        /**
         * The contract for a shared path (outside the merge contract): A fails and the branch is exactly what B
         * published, so nothing of B was overwritten and nothing of A reached the remote.
         */
        public Outcome assertSharedPathFailsSafely() {
            if (!interleaved) {
                throw new AssertionError(
                        "the publisher never reached the interleaving point (Publication.checkedOut())");
            }
            if (errorB != null) {
                throw new AssertionError("publisher B must succeed, but failed: " + errorB);
            }
            if (errorA == null) {
                throw new AssertionError("publisher A should report a conflict on the shared path but succeeded");
            }
            Map<String, byte[]> now = remote.readTree(branch);
            List<String> changed = new ArrayList<>();
            new TreeMap<>(treeAfterB).forEach((path, content) -> {
                if (!now.containsKey(path)) {
                    changed.add("removed: " + path);
                } else if (!Arrays.equals(now.get(path), content)) {
                    changed.add("overwritten: " + path);
                }
            });
            now.keySet().stream()
                    .filter(path -> !treeAfterB.containsKey(path))
                    .sorted()
                    .forEach(path -> changed.add("added: " + path));
            if (!changed.isEmpty()) {
                throw new AssertionError("branch '" + branch + "' differs from what publisher B pushed:\n  "
                        + String.join("\n  ", changed));
            }
            return this;
        }
    }
}
