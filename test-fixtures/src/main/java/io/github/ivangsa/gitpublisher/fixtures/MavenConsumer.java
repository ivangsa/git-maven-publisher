/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.shared.verifier.VerificationException;
import org.apache.maven.shared.verifier.Verifier;

/**
 * A clean Maven consumer: a generated project that depends on the given artifacts, an empty local repository, and
 * the repository under test (a {@code file://} URL of an exact checkout of the branch, D-043) as its only extra repository. It runs the real Maven
 * (the one executing this build, {@code maven.home}) through Maven Verifier, so resolution is Maven's, not ours.
 *
 * <p>Needs network access to Maven Central for the {@code maven-dependency-plugin} (the local repository is empty).
 */
public final class MavenConsumer {

    static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.8.1";

    /** Outcome of one consumer build. */
    public record Result(boolean success, String log, Path localRepository) {
        /** True when {@code groupId:artifactId:version[:classifier]} (extension {@code jar}) is in the local repository. */
        public boolean resolved(String groupId, String artifactId, String version, String classifier) {
            return Files.isRegularFile(artifactFile(groupId, artifactId, version, classifier, "jar"));
        }

        public Path artifactFile(
                String groupId, String artifactId, String version, String classifier, String extension) {
            String suffix = classifier == null || classifier.isEmpty() ? "" : "-" + classifier;
            return localRepository
                    .resolve(groupId.replace('.', '/'))
                    .resolve(artifactId)
                    .resolve(version)
                    .resolve(artifactId + "-" + version + suffix + "." + extension);
        }
    }

    private final Path directory;
    private final URI repositoryUrl;

    /**
     * @param directory empty directory the project and the local repository are created in
     * @param repositoryUrl base URL of the Maven repository to resolve from
     */
    public MavenConsumer(Path directory, URI repositoryUrl) {
        this.directory = directory;
        this.repositoryUrl = repositoryUrl;
    }

    /**
     * Resolves the coordinates {@code groupId:artifactId:version[:classifier]} (type {@code jar}) into a fresh local
     * repository. A failed resolution returns {@code success == false} with Maven's log.
     */
    public Result resolve(String... coordinates) {
        Path project = directory.resolve("consumer");
        Path localRepo = directory.resolve("local-repo");
        try {
            Files.createDirectories(project);
            Files.createDirectories(localRepo);
            Files.writeString(project.resolve("pom.xml"), pom(coordinates));
            Path settings = directory.resolve("settings.xml");
            Files.writeString(
                    settings,
                    "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"><localRepository>"
                            + localRepo.toAbsolutePath() + "</localRepository></settings>");

            Verifier verifier = new Verifier(project.toAbsolutePath().toString(), settings.toAbsolutePath().toString());
            verifier.setLocalRepo(localRepo.toAbsolutePath().toString());
            verifier.setCliOptions(new ArrayList<>(List.of("-B", "-U")));
            boolean success = true;
            try {
                verifier.executeGoal(DEPENDENCY_PLUGIN + ":resolve");
            } catch (VerificationException e) {
                success = false;
            }
            String log = Files.exists(project.resolve("log.txt")) ? Files.readString(project.resolve("log.txt")) : "";
            return new Result(success, log, localRepo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (VerificationException e) {
            throw new IllegalStateException("cannot start Maven: " + e.getMessage(), e);
        }
    }

    private String pom(String... coordinates) {
        StringBuilder dependencies = new StringBuilder();
        for (String coordinate : coordinates) {
            String[] part = coordinate.split(":");
            if (part.length < 3 || part.length > 4) {
                throw new IllegalArgumentException("expected groupId:artifactId:version[:classifier], got " + coordinate);
            }
            dependencies.append("<dependency><groupId>").append(part[0]).append("</groupId><artifactId>")
                    .append(part[1]).append("</artifactId><version>").append(part[2]).append("</version>");
            if (part.length == 4) {
                dependencies.append("<classifier>").append(part[3]).append("</classifier>");
            }
            dependencies.append("</dependency>");
        }
        String repo = "<id>pages</id><url>" + repositoryUrl + "</url>"
                + "<releases><enabled>true</enabled></releases>"
                + "<snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots>";
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>consumer</groupId><artifactId>clean-consumer</artifactId><version>1</version>"
                + "<dependencies>" + dependencies + "</dependencies>"
                + "<repositories><repository>" + repo + "</repository></repositories>"
                + "<pluginRepositories><pluginRepository><id>central</id>"
                + "<url>https://repo.maven.apache.org/maven2</url></pluginRepository></pluginRepositories>"
                + "</project>";
    }
}
