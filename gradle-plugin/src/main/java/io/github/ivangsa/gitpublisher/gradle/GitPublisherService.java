/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import io.github.ivangsa.gitpublisher.Credentials;
import io.github.ivangsa.gitpublisher.GitPublisher;
import io.github.ivangsa.gitpublisher.PublishConfig;
import io.github.ivangsa.gitpublisher.PublishException;
import io.github.ivangsa.gitpublisher.PublishInfo;
import io.github.ivangsa.gitpublisher.PublishResult;
import io.github.ivangsa.gitpublisher.Publication;
import io.github.ivangsa.gitpublisher.PublisherLog;
import io.github.ivangsa.gitpublisher.RetentionPolicy;
import io.github.ivangsa.gitpublisher.RetryPolicy;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * Holds the core {@link Publication} and the outcome of every participant for one build (design section 2, Gradle).
 *
 * <p>Sharing this service coordinates state and resources (writers are limited to one, see the plugin); it does not
 * order tasks. {@link #close()} only releases resources, it never publishes (D-033).
 */
public abstract class GitPublisherService implements BuildService<GitPublisherService.Parameters>, AutoCloseable {

    /** Settings of the publication, wired lazily from the {@code gitMavenPublisher} extension. */
    public interface Parameters extends BuildServiceParameters {
        Property<String> getRepository();

        Property<String> getBranch();

        Property<Boolean> getNojekyll();

        /**
         * Only an explicit {@code gitMavenPublisher.token} reaches this parameter (and so a configuration cache entry);
         * the environment variable and the Gradle property are read when the service runs (D-087).
         */
        Property<String> getToken();

        Property<String> getUsername();

        Property<Integer> getKeepSnapshots();

        Property<Integer> getKeepVersions();

        Property<String> getReleaseRepository();

        Property<Integer> getMaxAttempts();

        Property<Boolean> getDryRun();

        /** {@code <rootBuildDir>/git-publisher}. */
        DirectoryProperty getWorkDirectory();

        /** Directory searched for the source commit recorded in the commit message. */
        DirectoryProperty getSourceDirectory();
    }

    /** Read-only access to environment variables and Gradle properties, resolved at execution time (D-087). */
    @Inject
    protected abstract ProviderFactory getProviders();

    private static final Logger LOGGER = Logging.getLogger(GitPublisherService.class);

    private static final PublisherLog LOG = new PublisherLog() {
        @Override
        public void info(String message) {
            LOGGER.lifecycle(message);
        }

        @Override
        public void warn(String message) {
            LOGGER.warn(message);
        }

        @Override
        public void debug(String message) {
            LOGGER.debug(message);
        }
    };

    private final Set<String> succeeded = ConcurrentHashMap.newKeySet();
    private Publication publication;
    private boolean published;

    /** Step 1 of the publication algorithm. Runs once per build; later calls return at once. */
    public synchronized void checkout() {
        if (publication != null) {
            return;
        }
        try {
            publication = new GitPublisher(config(), LOG).checkout();
        } catch (PublishException e) {
            throw new GradleException("git-maven-publisher checkout failed: " + e.getMessage(), e);
        }
    }

    /** Called by a participant after its publish action completed. A participant that failed never calls it. */
    public void recordSuccess(String taskPath) {
        succeeded.add(taskPath);
    }

    /**
     * Publishes once, and only if every participant recorded success. Otherwise nothing is pushed and the build fails
     * naming the participants that did not complete.
     */
    public synchronized void publishIfComplete(Collection<String> participants, List<String> coordinates) {
        if (published) {
            return;
        }
        if (participants.isEmpty()) {
            LOGGER.info("git-maven-publisher: no publication participates in this build, nothing to push");
            return;
        }
        List<String> incomplete =
                participants.stream().filter(p -> !succeeded.contains(p)).sorted().toList();
        if (!incomplete.isEmpty()) {
            // A refused push is not a success: a participant that Gradle skipped (onlyIf, enabled = false) or that did
            // not run leaves nothing published, so the build fails and names it (R29, D-098).
            throw new GradleException("git-maven-publisher: nothing is pushed because these publication tasks did not"
                    + " complete successfully (failed, skipped or not run): " + String.join(", ", incomplete));
        }
        if (publication == null) {
            throw new GradleException("git-maven-publisher: the checkout did not run before the push");
        }
        try {
            PublishResult result = publication.publish(new PublishInfo(coordinates, sourceCommit()));
            published = true;
            if (result.pushed()) {
                LOGGER.lifecycle("git-maven-publisher: pushed " + result.commit().name() + " after " + result.attempts()
                        + " attempt(s)");
            } else {
                LOGGER.lifecycle("git-maven-publisher: nothing pushed (dry run or unchanged tree)");
            }
        } catch (PublishException e) {
            throw new GradleException("git-maven-publisher publication failed: " + e.getMessage(), e);
        }
    }

    /** Cleanup: releases the checkout. Runs whatever the outcome; never publishes. */
    public synchronized void release() {
        if (publication != null) {
            publication.close();
            publication = null;
        }
    }

    @Override
    public void close() {
        release();
    }

    private PublishConfig config() {
        Parameters p = getParameters();
        if (!p.getRepository().isPresent() || p.getRepository().get().isBlank()) {
            throw new GradleException(
                    "gitMavenPublisher.repository is not set; configure the URL of the snapshots repository");
        }
        String token = resolveToken();
        Credentials credentials = token != null
                ? new Credentials.Token(p.getUsername().get(), token.toCharArray())
                : Credentials.none();
        Path workDirectory = p.getWorkDirectory().get().getAsFile().toPath();
        try {
            return new PublishConfig(
                    URI.create(p.getRepository().get()),
                    p.getBranch().get(),
                    credentials,
                    workDirectory,
                    new RetentionPolicy(p.getKeepSnapshots().get(), p.getKeepVersions().get())
                            .withReleaseRepository(
                                    RetentionPolicy.parseReleaseRepository(p.getReleaseRepository().getOrNull())),
                    new RetryPolicy(
                            p.getMaxAttempts().get(),
                            RetryPolicy.defaults().baseDelay(),
                            RetryPolicy.defaults().maxDelay()),
                    PublishConfig.defaultAuthor(),
                    p.getDryRun().get(),
                    p.getNojekyll().get(),
                    PublishConfig.DEFAULT_CHECKSUMS);
        } catch (IllegalArgumentException e) {
            throw new GradleException("invalid gitMavenPublisher configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Token order (design section 2): the explicit {@code gitMavenPublisher.token}, then environment variable
     * {@code GIT_PUBLISHER_TOKEN}, then Gradle property {@code gitPublisherToken}. The last two are read here, at
     * execution time, so no configuration cache entry holds them (D-087).
     */
    private String resolveToken() {
        Property<String> explicit = getParameters().getToken();
        if (explicit.isPresent() && !explicit.get().isEmpty()) {
            return explicit.get();
        }
        String fromEnvironment = getProviders()
                .environmentVariable(GitMavenPublisherPlugin.TOKEN_ENVIRONMENT_VARIABLE)
                .getOrNull();
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return fromEnvironment;
        }
        String fromProperty =
                getProviders().gradleProperty(GitMavenPublisherPlugin.TOKEN_PROPERTY).getOrNull();
        return fromProperty == null || fromProperty.isEmpty() ? null : fromProperty;
    }

    /** {@code GITHUB_SHA} when set, otherwise the HEAD of the repository enclosing the build, if any. */
    private Optional<String> sourceCommit() {
        String fromCi = System.getenv("GITHUB_SHA");
        if (fromCi != null && !fromCi.isBlank()) {
            return Optional.of(fromCi.trim());
        }
        try (Repository repository = new FileRepositoryBuilder()
                .findGitDir(getParameters().getSourceDirectory().get().getAsFile())
                .build()) {
            if (repository.getDirectory() == null) {
                return Optional.empty();
            }
            ObjectId head = repository.resolve("HEAD");
            return Optional.ofNullable(head).map(ObjectId::name);
        } catch (Exception | LinkageError e) {
            return Optional.empty();
        }
    }
}
