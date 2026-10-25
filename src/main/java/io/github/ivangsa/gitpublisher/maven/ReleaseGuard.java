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
