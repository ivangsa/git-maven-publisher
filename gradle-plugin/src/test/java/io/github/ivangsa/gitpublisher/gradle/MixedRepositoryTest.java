/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.MavenConsumer;
import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.gradle.GradleConsumer.Resolved;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.maven.shared.verifier.VerificationException;
import org.apache.maven.shared.verifier.Verifier;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T42: a Maven project (the real Maven running the installed Maven plugin) and a Gradle KMP project (the Gradle
 * plugin) with distinct coordinates publish into one bare repository while their runs overlap: Gradle has checked out
 * and is paused before its push, Maven publishes completely, then Gradle pushes against a moved branch (lease
 * failure and replay). Both the Maven consumer and the Gradle KMP consumer resolve their artifacts from a checkout
 * of the branch.
 */
class MixedRepositoryTest {

    private static final String MAVEN_GROUP = "org.mvn";
    private static final String MAVEN_ARTIFACT = "mavenlib";
    private static final String MAVEN_VERSION = "1.0-SNAPSHOT";

    @TempDir
    Path temp;

    private BareRepo remote;

    @BeforeEach
    void create() {
        remote = BareRepo.create(temp.resolve("remote.git"));
    }

    @AfterEach
    void close() {
        remote.close();
    }

    @Test
    void mavenAndGradleKmpPublishConcurrentlyIntoOneRepositoryAndBothConsumersResolve() throws Exception {
        Path ready = temp.resolve("ready.marker");
        Path go = temp.resolve("go.marker");
        KmpSample kmp = new KmpSample(temp.resolve("kmp"), remote.uri()).pauseBetweenCheckoutAndPush(ready, go);

        CompletableFuture<BuildResult> gradle = CompletableFuture.supplyAsync(kmp::publish);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(25);
        while (!Files.exists(ready)) {
            if (gradle.isDone()) {
                gradle.get();
                throw new AssertionError("the Gradle build finished without reaching its pause");
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the Gradle build never reached its pause");
            }
            Thread.sleep(100);
        }

        // Maven publishes now, completely, while Gradle holds a checkout of the branch it saw (empty).
        String mavenLog = publishWithMaven(temp.resolve("maven-project"), remote.uri());
        assertTrue(mavenLog.contains("Pushed "), mavenLog);
        assertEquals(1, remote.commitCount(KmpSample.BRANCH));
        Files.writeString(go, "go");

        BuildResult result = gradle.get(25, TimeUnit.MINUTES);
        assertTrue(
                result.getOutput().contains("moved; replayed"),
                "the Gradle push did not hit a lease failure:\n" + result.getOutput());

        // Both publications are on the branch, in valid, consistent metadata.
        Path checkout = remote.checkout(KmpSample.BRANCH, temp.resolve("checkout"));
        MetadataAssertions.assertValidRepository(checkout, Set.of("sha1", "md5"));
        for (String artifactId : KmpSample.artifactIds()) {
            assertTrue(Files.isDirectory(checkout.resolve("com/acme/" + artifactId)), artifactId);
        }
        assertTrue(Files.isDirectory(checkout.resolve("org/mvn/" + MAVEN_ARTIFACT + "/" + MAVEN_VERSION)));

        // Maven consumer: the Maven library.
        MavenConsumer.Result maven = new MavenConsumer(temp.resolve("maven-consumer"), checkout.toUri())
                .resolve(MAVEN_GROUP + ":" + MAVEN_ARTIFACT + ":" + MAVEN_VERSION);
        assertTrue(maven.success(), maven.log());
        assertTrue(maven.resolved(MAVEN_GROUP, MAVEN_ARTIFACT, MAVEN_VERSION, null), maven.log());

        // Gradle KMP consumer: every target of the KMP library.
        Path home = Files.createTempDirectory("mixed-consumer-home");
        try {
            URI repository = checkout.toUri();
            List<Resolved> resolved = new GradleConsumer(temp.resolve("gradle-consumer"), home)
                    .resolve(repository, KmpSample.GROUP + ":" + KmpSample.NAME + ":" + KmpSample.VERSION);
            for (String configuration : GradleConsumer.configurations()) {
                assertEquals(
                        1,
                        resolved.stream()
                                .filter(r -> r.configuration().equals(configuration)
                                        && r.component().startsWith("com.acme:" + KmpSample.NAME + "-"))
                                .count(),
                        configuration + " in " + resolved);
            }
        } finally {
            GradleConsumer.deleteHome(home);
        }
    }

    /** A plain Java project published by the installed Maven plugin through the real Maven; returns Maven's log. */
    private static String publishWithMaven(Path project, URI repository) throws IOException, VerificationException {
        String version = System.getProperty("gitMavenPublisher.version");
        assertTrue(version != null && !version.isBlank(), "system property gitMavenPublisher.version is not set");
        Files.createDirectories(project.resolve("src/main/java/org/mvn"));
        Files.writeString(
                project.resolve("src/main/java/org/mvn/Lib.java"),
                "package org.mvn;\n\npublic final class Lib {\n    public static String hello() { return \"hello\"; }\n}\n");
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <!-- the standard deploy plugin must not run next to git-maven-publisher -->
                    <maven.deploy.skip>true</maven.deploy.skip>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>io.github.ivangsa</groupId>
                        <artifactId>git-maven-publisher-maven-plugin</artifactId>
                        <version>%s</version>
                        <executions>
                          <execution>
                            <goals><goal>deploy</goal></goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(MAVEN_GROUP, MAVEN_ARTIFACT, MAVEN_VERSION, version));
        Verifier verifier = new Verifier(project.toAbsolutePath().toString());
        List<String> options = new ArrayList<>(List.of(
                "-B",
                "-Dgit-publisher.repository=" + repository,
                "-Dgit-publisher.branch=" + KmpSample.BRANCH));
        verifier.setCliOptions(options);
        try {
            verifier.executeGoal("deploy");
        } finally {
            verifier.resetStreams();
        }
        return Files.readString(project.resolve("log.txt"));
    }
}
