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

import io.github.ivangsa.gitpublisher.Credentials;
import io.github.ivangsa.gitpublisher.maven.ReactorTransaction.Entry;
import java.io.File;
import java.util.List;
import java.util.Optional;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.eclipse.aether.RepositorySystem;

/**
 * Publishes the artifacts of the build to a Git branch that serves as a Maven snapshot repository. The goal never
 * generates artifacts or metadata: Maven Resolver does, into a work tree that this plugin turns into one commit.
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY, threadSafe = true)
public class DeployMojo extends AbstractMojo {

    /** Git URL, https or file only; credentials in the URL are rejected. Required unless the project is skipped. */
    @Parameter(property = "git-publisher.repository")
    String repository;

    @Parameter(property = "git-publisher.branch", defaultValue = "gh-pages")
    String branch;

    /** Writes an empty .nojekyll at the root on every publish, for users who serve the branch with GitHub Pages. */
    @Parameter(property = "git-publisher.nojekyll", defaultValue = "false")
    boolean nojekyll;

    /** settings.xml server whose password is the token; the user name defaults to x-access-token. */
    @Parameter(property = "git-publisher.serverId")
    String serverId;

    @Parameter(property = "git-publisher.keepSnapshots", defaultValue = "3")
    int keepSnapshots;

    @Parameter(property = "git-publisher.keepVersions", defaultValue = "-1")
    int keepVersions;

    /** Maven repository asked whether the release of a -SNAPSHOT version exists, so the snapshot is dropped: {@code central} or an https or file URL. Off when unset. */
    @Parameter(property = "git-publisher.releaseRepository")
    String releaseRepository;

    @Parameter(property = "git-publisher.allowReleases", defaultValue = "false")
    boolean allowReleases;

    /** Comma separated, passed to Resolver as aether.checksums.algorithms. */
    @Parameter(property = "git-publisher.checksumAlgorithms", defaultValue = "SHA-1,MD5")
    String checksumAlgorithms;

    @Parameter(property = "git-publisher.maxAttempts", defaultValue = "5")
    int maxAttempts;

    @Parameter(
            property = "git-publisher.workDirectory",
            defaultValue = "${session.executionRootDirectory}/target/git-publisher")
    File workDirectory;

    /** Builds the commit but does not push it. */
    @Parameter(property = "git-publisher.dryRun", defaultValue = "false")
    boolean dryRun;

    @Parameter(property = "git-publisher.skip", defaultValue = "false")
    boolean skip;

    @Parameter(
            property = "git-publisher.commitAuthor",
            defaultValue = "git-maven-publisher <git-maven-publisher@users.noreply.github.com>")
    String commitAuthor;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    @Component
    RepositorySystem repositorySystem;

    @Component
    SettingsDecrypter settingsDecrypter;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    MojoExecution mojoExecution;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    PluginDescriptor pluginDescriptor;

    /**
     * Checks this project in to the reactor transaction (design section 2, D-034). The participant that completes the
     * set publishes everything in one commit; a skipped participant checks in with nothing to publish.
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        String id = ParticipantFinder.id(project);
        String pluginKey = pluginDescriptor.getGroupId() + ":" + pluginDescriptor.getArtifactId();
        String execution = ParticipantFinder.id(project, mojoExecution.getExecutionId(), pluginKey)
                .substring(id.length())
                .replaceFirst("^@", "");
        ReactorTransaction transaction = ReactorTransaction.of(session, pluginKey);
        Entry entry;
        if (skip) {
            getLog().info("Skipping " + id + " (git-publisher.skip)");
            entry = new Entry(id, execution, true, null, List.of());
        } else {
            ReleaseGuard.check(project.getGroupId() + ":" + project.getArtifactId(), project.getVersion(), allowReleases);
            PublisherParameters parameters = parameters();
            parameters.toConfig(Credentials.none()); // fail on invalid settings before checking in
            entry = new Entry(id, execution, false, parameters, ProjectArtifacts.collect(project));
        }
        Optional<List<Entry>> complete = transaction.checkIn(entry);
        if (complete.isPresent()) {
            ReactorPublisher.publish(complete.get(), session, repositorySystem, settingsDecrypter, getLog());
        } else {
            getLog().info(id + " is ready; the last participant of the reactor publishes");
        }
    }

    PublisherParameters parameters() {
        return new PublisherParameters(
                repository,
                branch,
                nojekyll,
                serverId,
                keepSnapshots,
                keepVersions,
                releaseRepository,
                checksumAlgorithms,
                maxAttempts,
                workDirectory.toPath().toAbsolutePath().normalize(),
                dryRun,
                commitAuthor);
    }
}
