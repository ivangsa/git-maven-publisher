/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven;

import io.github.ivangsa.gitpublisher.Credentials;
import io.github.ivangsa.gitpublisher.GitPublisher;
import io.github.ivangsa.gitpublisher.PublishConfig;
import io.github.ivangsa.gitpublisher.PublishException;
import io.github.ivangsa.gitpublisher.PublishInfo;
import io.github.ivangsa.gitpublisher.PublishResult;
import io.github.ivangsa.gitpublisher.Publication;
import io.github.ivangsa.gitpublisher.maven.ReactorTransaction.Entry;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;

/** The one publication of a reactor: checkout, one native deploy of everything collected, publish (design section 2). */
final class ReactorPublisher {

    private ReactorPublisher() {}

    static void publish(
            List<Entry> entries,
            MavenSession session,
            RepositorySystem repositorySystem,
            SettingsDecrypter settingsDecrypter,
            Log log)
            throws MojoExecutionException, MojoFailureException {
        List<Entry> active = entries.stream().filter(e -> !e.skipped()).toList();
        if (active.isEmpty()) {
            log.info("Every participant of the reactor is skipped; nothing is checked out or pushed");
            return;
        }
        PublisherParameters parameters = active.get(0).parameters();
        Credentials credentials = CredentialsResolver.resolve(
                parameters.serverId(), session.getRequest().getServers(), settingsDecrypter, System::getenv, log::warn);
        PublishConfig config = parameters.toConfig(credentials);
        List<String> coordinates = active.stream().map(Entry::project).distinct().toList();
        Path root = Path.of(session.getExecutionRootDirectory());
        PublishInfo info = new PublishInfo(coordinates, SourceCommit.find(root, System::getenv));

        try (Publication publication = new GitPublisher(config, new MavenPublisherLog(log)).checkout()) {
            List<Artifact> artifacts = active.stream().flatMap(e -> e.artifacts().stream()).distinct().toList();
            if (!artifacts.isEmpty()) {
                NativeDeploy.deploy(
                        repositorySystem,
                        session.getRepositorySession(),
                        publication.repositoryDirectory(),
                        artifacts,
                        parameters.resolverChecksumList());
            }
            PublishResult result = publication.publish(info);
            if (result.pushed()) {
                log.info("Published " + coordinates.size() + " project(s) in one commit " + result.commit().name()
                        + " (" + result.attempts() + " attempt(s))");
            } else {
                log.info("Dry run: built commit " + result.commit().name() + ", nothing was pushed");
            }
        } catch (PublishException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
