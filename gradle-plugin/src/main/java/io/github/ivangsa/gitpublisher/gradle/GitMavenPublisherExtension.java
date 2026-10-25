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

import org.gradle.api.provider.Property;

/** The {@code gitMavenPublisher { }} block (design section 2, Gradle). */
public abstract class GitMavenPublisherExtension {

    /** HTTPS (or file) URL of the Git repository that serves the snapshots. Required. */
    public abstract Property<String> getRepository();

    /** Branch that holds the served tree. */
    public abstract Property<String> getBranch();

    /** Writes {@code .nojekyll} at the root of the tree. */
    public abstract Property<Boolean> getNojekyll();

    /**
     * Access token. Unset by default: the build then uses environment variable {@code GIT_PUBLISHER_TOKEN}, then Gradle
     * property {@code gitPublisherToken}, both read when the publication runs so that the configuration cache never
     * stores them (D-087). A value set here is part of the build configuration and is stored with it.
     */
    public abstract Property<String> getToken();

    public abstract Property<String> getUsername();

    public abstract Property<Integer> getKeepSnapshots();

    /** Phase 3; {@code -1} keeps every version. */
    public abstract Property<Integer> getKeepVersions();

    /**
     * Phase 3, off when unset: {@code central} or an {@code https}/{@code file} URL of a Maven repository. A
     * {@code -SNAPSHOT} version whose release POM exists there is deleted (D-106, D-107).
     */
    public abstract Property<String> getReleaseRepository();

    public abstract Property<Boolean> getAllowReleases();

    public abstract Property<Integer> getMaxAttempts();

    public abstract Property<Boolean> getDryRun();
}
