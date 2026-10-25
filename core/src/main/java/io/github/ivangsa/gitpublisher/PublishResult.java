/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.util.Objects;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Outcome of {@link Publication#publish(PublishInfo)}.
 *
 * @param commit the parentless commit that was built
 * @param attempts push attempts used
 * @param changedPaths added or modified paths, relative to the served tree
 * @param deletedPaths paths removed by retention
 * @param pushed false for a dry run
 */
public record PublishResult(
        ObjectId commit, int attempts, Set<String> changedPaths, Set<String> deletedPaths, boolean pushed) {

    public PublishResult {
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(changedPaths, "changedPaths");
        Objects.requireNonNull(deletedPaths, "deletedPaths");
        changedPaths = Set.copyOf(changedPaths);
        deletedPaths = Set.copyOf(deletedPaths);
    }
}
