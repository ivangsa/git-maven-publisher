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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;

/**
 * Deploys artifacts with the real Maven Resolver ({@code maven-resolver-supplier-mvn3}, the same engine as
 * {@code mvn deploy}) to a {@code file://} directory. The resulting tree and metadata are what native tools produce,
 * which makes this the "native deploy" oracle of docs/design.md section 9.
 *
 * <p>One deployer is one Maven session: every snapshot it deploys carries the same timestamp (the session start).
 */
public final class ResolverDeployer implements AutoCloseable {

    /** One file to deploy for a coordinate. {@code classifier} may be empty. */
    public record Part(String classifier, String extension, byte[] content) {
        public static Part of(String classifier, String extension, String content) {
            return new Part(classifier, extension, content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final RepositorySystem system;
    private final RepositorySystemSession.CloseableSession session;
    private final RemoteRepository target;
    private final Path scratch;

    /**
     * @param repositoryDirectory directory that receives the deployed tree
     * @param checksumAlgorithms Resolver names, for example {@code SHA-1}, {@code MD5}, {@code SHA-256};
     *     none means the Resolver default
     */
    public ResolverDeployer(Path repositoryDirectory, String... checksumAlgorithms) {
        this(repositoryDirectory, Instant.now(), checksumAlgorithms);
    }

    /**
     * Like {@link #ResolverDeployer(Path, String...)} with an explicit session start time. Maven stamps every
     * snapshot of a build with the session start ({@code maven.startTime}), not with the deploy time, so a build that
     * started earlier and deploys later gets an older timestamp and a higher build number.
     */
    public ResolverDeployer(Path repositoryDirectory, Instant sessionStartTime, String... checksumAlgorithms) {
        Path directory = prepare(repositoryDirectory);
        try {
            this.scratch = Files.createTempDirectory("resolver-deployer");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.system = new RepositorySystemSupplier().get();
        var builder = new SessionBuilderSupplier(system).get().withLocalRepositoryBaseDirectories(scratch.resolve("local"));
        builder.setConfigProperty("maven.startTime", Date.from(sessionStartTime));
        if (checksumAlgorithms.length > 0) {
            builder.setConfigProperty("aether.checksums.algorithms", String.join(",", checksumAlgorithms));
        }
        this.session = builder.build();
        this.target = new RemoteRepository.Builder("target", "default", directory.toUri().toString()).build();
    }

    private static Path prepare(Path repositoryDirectory) {
        try {
            Files.createDirectories(repositoryDirectory);
            return repositoryDirectory.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deploys {@code jar}, {@code pom} and {@code sources} jar of the coordinate, like a plain Java project. */
    public void deployJavaProject(String groupId, String artifactId, String version) {
        deploy(
                groupId,
                artifactId,
                version,
                Part.of("", "jar", "jar of " + groupId + ":" + artifactId + ":" + version),
                Part.of("", "pom", pom(groupId, artifactId, version)),
                Part.of("sources", "jar", "sources of " + groupId + ":" + artifactId + ":" + version));
    }

    /** Deploys the given parts in a single Resolver deploy request (one metadata update, one build number). */
    public void deploy(String groupId, String artifactId, String version, Part... parts) {
        DeployRequest request = new DeployRequest().setRepository(target);
        List<Artifact> artifacts = new ArrayList<>();
        try {
            for (Part part : parts) {
                Path file = Files.createTempFile(scratch, "part", "." + part.extension());
                Files.write(file, part.content());
                artifacts.add(new DefaultArtifact(groupId, artifactId, part.classifier(), part.extension(), version)
                        .setFile(file.toFile()));
            }
            artifacts.forEach(request::addArtifact);
            system.deploy(session, request);
        } catch (DeploymentException e) {
            throw new IllegalStateException("Resolver deploy failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deploys the group-level {@code maven-metadata.xml} a Maven plugin build writes (plugin prefix list), through the
     * Resolver's metadata upload. The file lists only this plugin: the group-level path is shared by every plugin of
     * the group, which is exactly what the conflict tests need.
     */
    public void deployPluginGroupMetadata(String groupId, String prefix, String pluginArtifactId) {
        try {
            Path file = Files.createTempFile(scratch, "group-metadata", ".xml");
            Files.writeString(
                    file,
                    "<metadata><plugins><plugin><name>" + pluginArtifactId + "</name><prefix>" + prefix
                            + "</prefix><artifactId>" + pluginArtifactId + "</artifactId></plugin></plugins></metadata>");
            Metadata metadata = new DefaultMetadata(groupId, "", "", "maven-metadata.xml", Metadata.Nature.RELEASE_OR_SNAPSHOT, file.toFile());
            system.deploy(session, new DeployRequest().setRepository(target).addMetadata(metadata));
        } catch (DeploymentException e) {
            throw new IllegalStateException("Resolver deploy failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String pom(String groupId, String artifactId, String version) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>" + groupId + "</groupId>"
                + "<artifactId>" + artifactId + "</artifactId>"
                + "<version>" + version + "</version>"
                + "</project>";
    }

    @Override
    public void close() {
        session.close();
        system.shutdown();
        try (var walk = Files.walk(scratch)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // scratch directory is in the system temp dir; leaving it behind is harmless
        }
    }
}
