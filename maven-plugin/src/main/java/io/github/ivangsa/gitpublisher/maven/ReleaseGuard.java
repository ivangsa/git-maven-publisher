/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven;

import org.apache.maven.plugin.MojoFailureException;

/** Snapshot repository only: a release version fails fast unless {@code git-publisher.allowReleases} is set. */
final class ReleaseGuard {

    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    private ReleaseGuard() {}

    static void check(String project, String version, boolean allowReleases) throws MojoFailureException {
        if (allowReleases || version.endsWith(SNAPSHOT_SUFFIX)) {
            return;
        }
        throw new MojoFailureException(project + " has the release version " + version
                + ", but this plugin publishes snapshots. Set git-publisher.allowReleases=true to publish releases too.");
    }
}
