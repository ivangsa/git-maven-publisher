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

import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * The one native deploy of a reactor (design section 2): Maven's own {@link RepositorySystem} deploys everything
 * collected into the work tree as a {@code file://} repository of type {@code default}. Resolver writes the artifacts,
 * the version and artifact metadata and the checksums, exactly as {@code mvn deploy} would for a real repository.
 */
final class NativeDeploy {

    static final String CHECKSUM_PROPERTY = "aether.checksums.algorithms";
    static final String REPOSITORY_ID = "git-publisher";

    private NativeDeploy() {}

    static void deploy(
            RepositorySystem system,
            RepositorySystemSession mavenSession,
            Path repositoryDirectory,
            List<Artifact> artifacts,
            String checksumAlgorithms)
            throws MojoExecutionException {
        // a copy: Maven's session is read-only and shared by the other projects
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(mavenSession);
        session.setConfigProperty(CHECKSUM_PROPERTY, checksumAlgorithms);

        RemoteRepository target = new RemoteRepository.Builder(
                        REPOSITORY_ID, "default", repositoryDirectory.toAbsolutePath().toUri().toString())
                .build();
        DeployRequest request = new DeployRequest().setRepository(target);
        artifacts.forEach(request::addArtifact);
        try {
            system.deploy(session, request);
        } catch (DeploymentException e) {
            throw new MojoExecutionException("cannot deploy into the work tree: " + e.getMessage(), e);
        }
    }
}
