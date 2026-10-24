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
import java.util.Map;
import java.util.TreeMap;

/**
 * Publishers that prove the {@link ConcurrentPublishScenario} works: one that is correct by brute force (a global
 * lock, so publications run strictly one after another) and one that is broken the way a naive implementation is
 * (read the tip, deploy, force push with no lease). They are test tools, not production code.
 */
public final class ReferencePublishers {

    private ReferencePublishers() {}

    /**
     * Correct reference: everything from "read the branch" to "write the commit" happens under one lock, so it equals
     * a sequential run. Each publication adds a commit on top of the previous tip (history is not the point here).
     */
    public static ConcurrentPublishScenario.Publisher globalLock() {
        Object lock = new Object();
        return publication -> {
            synchronized (lock) {
                Path repo = materialize(publication);
                publication.deploy().deployInto(repo);
                publication.remote()
                        .commitFiles(
                                publication.branch(),
                                readFiles(repo),
                                "Publish " + publication.step().coordinates());
                publication.pushed();
            }
        };
    }

    /**
     * Broken on purpose, more quietly than {@link #forcePushWithoutLease}: it takes the current tip at push time and
     * overlays its own changed files (nothing is overwritten wholesale) but never renumbers a colliding build. Two
     * publications that deployed against the same base in the same session second write the same file names, and the
     * last overlay wins. {@code afterDeploy} lets tests make both deploy against the same base.
     */
    public static ConcurrentPublishScenario.Publisher overlayWithoutRenumbering(Runnable afterDeploy) {
        Object lock = new Object();
        return publication -> {
            Path repo = materialize(publication);
            Map<String, byte[]> base = readFiles(repo);
            publication.deploy().deployInto(repo);
            afterDeploy.run();
            Map<String, byte[]> after = readFiles(repo);
            synchronized (lock) {
                Map<String, byte[]> merged = publication.remote().readTree(publication.branch());
                after.forEach((path, content) -> {
                    if (!base.containsKey(path) || !java.util.Arrays.equals(base.get(path), content)) {
                        merged.put(path, content);
                    }
                });
                publication.remote()
                        .commitFiles(
                                publication.branch(), merged, "Publish " + publication.step().coordinates());
                publication.pushed();
            }
        };
    }

    /**
     * Broken on purpose: reads the tip, deploys, then {@code beforePush} runs (tests use it to make every thread
     * finish its deploy before any of them pushes), then a forced update of the branch with a tree built from the stale
     * read. Whoever pushes last wins; the others' files are gone.
     */
    public static ConcurrentPublishScenario.Publisher forcePushWithoutLease(Runnable beforePush) {
        // Only the physical write is serialized: concurrent writers of identical git objects collide on Windows file
        // renames, which is noise. The race under test (stale read -> overwrite) is untouched.
        Object writeLock = new Object();
        return publication -> {
            Path repo = materialize(publication);
            publication.deploy().deployInto(repo);
            beforePush.run();
            synchronized (writeLock) {
                publication.remote()
                        .commitFiles(
                                publication.branch(),
                                readFiles(repo),
                                "Publish " + publication.step().coordinates());
                publication.pushed();
            }
        };
    }

    /** Writes the current branch tip (if any) into the publication's work directory and returns it. */
    private static Path materialize(ConcurrentPublishScenario.Publication publication) {
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
        return new TreeMap<>(ConcurrentPublishScenario.filesOf(root));
    }
}
