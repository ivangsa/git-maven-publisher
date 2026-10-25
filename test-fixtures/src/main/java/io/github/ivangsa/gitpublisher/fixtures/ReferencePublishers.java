/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Publishers that prove the {@link InterleavedPublishScenario} works: one that is correct because it serializes
 * (a global lock), one that is correct for disjoint paths and fails safely on shared ones, and one that is broken the
 * way a naive implementation is (read the tip, deploy, force push with no lease). They are test tools, not production
 * code.
 */
public final class ReferencePublishers {

    private ReferencePublishers() {}

    /**
     * Correct reference: everything from "read the branch" to "write the commit" happens under one lock, so it equals
     * a sequential run. It never reaches the interleaving point, so the scenario runs B after A. Each publication adds
     * a commit on top of the previous tip (history is not the point here).
     */
    public static InterleavedPublishScenario.Publisher globalLock() {
        Object lock = new Object();
        return publication -> {
            synchronized (lock) {
                Path repo = materialize(publication);
                publication.deploy().deployInto(repo);
                publication
                        .remote()
                        .commitFiles(
                                publication.branch(),
                                readFiles(repo),
                                "Publish " + publication.step().coordinates());
            }
        };
    }

    /**
     * Correct and interleaving-aware: when the tip moved since its checkout it overlays its own changed files onto the
     * new tip, and when a path it changed was also changed (or deleted) on the remote with other content it throws
     * and pushes nothing. This is the contract of design section 7 in a few lines, only so the harness can show a
     * passing and a failing interleaving.
     */
    public static InterleavedPublishScenario.Publisher replayOrFail() {
        return publication -> {
            Path repo = materialize(publication);
            Map<String, byte[]> base = readFiles(repo);
            publication.checkedOut();
            publication.deploy().deployInto(repo);
            Map<String, byte[]> ours = readFiles(repo);
            Map<String, byte[]> remote = publication.remote().readTree(publication.branch());
            TreeSet<String> conflicts = new TreeSet<>();
            Map<String, byte[]> result = new TreeMap<>(remote);
            ours.forEach((path, content) -> {
                if (base.containsKey(path) && Arrays.equals(base.get(path), content)) {
                    return; // not ours
                }
                boolean remoteChanged = base.containsKey(path)
                        ? !Arrays.equals(base.get(path), remote.get(path))
                        : remote.containsKey(path);
                if (remoteChanged && !Arrays.equals(content, remote.get(path))) {
                    conflicts.add(path);
                }
                result.put(path, content);
            });
            if (!conflicts.isEmpty()) {
                throw new IllegalStateException("conflict on " + conflicts);
            }
            publication
                    .remote()
                    .commitFiles(
                            publication.branch(), result, "Publish " + publication.step().coordinates());
        };
    }

    /**
     * Broken on purpose: reads the tip, lets the other publisher run, deploys, then force-updates the branch with a
     * tree built from the stale read. Whatever the other publisher pushed in between is gone.
     */
    public static InterleavedPublishScenario.Publisher forcePushWithoutLease() {
        return publication -> {
            Path repo = materialize(publication);
            publication.checkedOut();
            publication.deploy().deployInto(repo);
            publication
                    .remote()
                    .commitFiles(
                            publication.branch(),
                            readFiles(repo),
                            "Publish " + publication.step().coordinates());
        };
    }

    /** Writes the current branch tip (if any) into the publication's work directory and returns it. */
    private static Path materialize(InterleavedPublishScenario.Publication publication) {
        Path repo = publication.workDirectory().resolve("repo");
        try {
            Files.createDirectories(repo);
            if (publication.remote().branchTip(publication.branch()).isPresent()) {
                publication.remote().checkout(publication.branch(), repo);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return repo;
    }

    /** All regular files below {@code root}, keyed by slash-separated relative path. Empty if {@code root} is absent. */
    public static Map<String, byte[]> readFiles(Path root) {
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
