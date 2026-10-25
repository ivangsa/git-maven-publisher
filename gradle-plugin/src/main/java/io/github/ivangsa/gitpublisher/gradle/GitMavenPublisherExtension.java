/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
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
