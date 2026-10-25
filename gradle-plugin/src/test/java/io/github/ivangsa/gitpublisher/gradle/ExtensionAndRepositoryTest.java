/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtensionAndRepositoryTest {

    @TempDir
    Path project;

    private static final String JAVA_LIBRARY = """
            plugins {
                id 'java-library'
                id 'io.github.ivangsa.git-maven-publisher'
            }
            group = 'com.acme'
            version = '1.0-SNAPSHOT'
            publishing {
                publications {
                    maven(MavenPublication) { from components.java }
                }
            }
            """;

    private BuildResult run(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(project.resolve(name), content);
    }

    @Test
    void registersGitPublisherRepositoryAndPublishTasksForAJavaLibrary() throws IOException {
        write("settings.gradle", "rootProject.name = 'lib'\n");
        write("build.gradle", JAVA_LIBRARY + """
                gitMavenPublisher { repository = 'https://github.com/acme/maven-snapshots.git' }
                tasks.register('showRepository') {
                    def repository = publishing.repositories.getByName('gitPublisher')
                    def url = repository.url
                    doLast { println "REPOSITORY=" + url }
                }
                """);

        BuildResult tasks = run("tasks", "--all");
        assertTrue(tasks.getOutput().contains("publishMavenPublicationToGitPublisherRepository"), tasks.getOutput());
        assertTrue(tasks.getOutput().contains("publishAllPublicationsToGitPublisherRepository"), tasks.getOutput());

        BuildResult shown = run("showRepository", "-q");
        Path expected = project.resolve("build").resolve("git-publisher").resolve("repo");
        String line = shown.getOutput().lines().filter(l -> l.startsWith("REPOSITORY=")).findFirst().orElseThrow();
        Path actual = Path.of(java.net.URI.create(line.substring("REPOSITORY=".length()).trim()));
        assertEquals(expected.toAbsolutePath().normalize(), actual.toAbsolutePath().normalize(), shown.getOutput());
    }

    @Test
    void extensionHasTheDocumentedDefaults() throws IOException {
        write("settings.gradle", "rootProject.name = 'lib'\n");
        write("build.gradle", JAVA_LIBRARY + """
                tasks.register('showDefaults') {
                    def e = gitMavenPublisher
                    doLast {
                        println "branch=" + e.branch.get()
                        println "nojekyll=" + e.nojekyll.get()
                        println "username=" + e.username.get()
                        println "keepSnapshots=" + e.keepSnapshots.get()
                        println "keepVersions=" + e.keepVersions.get()
                        println "releaseRepositoryPresent=" + e.releaseRepository.isPresent()
                        println "allowReleases=" + e.allowReleases.get()
                        println "maxAttempts=" + e.maxAttempts.get()
                        println "dryRun=" + e.dryRun.get()
                        println "repositoryPresent=" + e.repository.isPresent()
                        println "tokenPresent=" + e.token.isPresent()
                    }
                }
                """);

        String output = run("showDefaults", "-q").getOutput();
        for (String expected : new String[] {
            "branch=gh-pages", "nojekyll=false", "username=x-access-token", "keepSnapshots=3", "keepVersions=-1",
            "allowReleases=false", "maxAttempts=5", "dryRun=false", "repositoryPresent=false", "releaseRepositoryPresent=false"
        }) {
            assertTrue(output.contains(expected), expected + " missing in " + output);
        }
    }

    @Test
    void tokenIsNotResolvedAtConfigurationTime() throws IOException {
        write("settings.gradle", "rootProject.name = 'lib'\n");
        write("build.gradle", JAVA_LIBRARY + """
                tasks.register('showToken') {
                    def e = gitMavenPublisher
                    doLast { println "token=" + e.token.orNull }
                }
                """);

        Map<String, String> withoutEnvironmentToken = new HashMap<>(System.getenv());
        withoutEnvironmentToken.remove("GIT_PUBLISHER_TOKEN");
        Map<String, String> withEnvironmentToken = new HashMap<>(withoutEnvironmentToken);
        withEnvironmentToken.put("GIT_PUBLISHER_TOKEN", "from-environment");

        // D-087: the environment variable and the Gradle property are read when the publication runs (so that no
        // configuration cache entry holds them); GradleCredentialsTest covers the sources and their order.
        assertEquals("token=null", token(withoutEnvironmentToken));
        assertEquals("token=null", token(withoutEnvironmentToken, "-PgitPublisherToken=from-property"));
        assertEquals("token=null", token(withEnvironmentToken, "-PgitPublisherToken=from-property"));
    }

    private String token(Map<String, String> environment, String... extra) {
        String[] arguments = new String[2 + extra.length];
        arguments[0] = "showToken";
        arguments[1] = "-q";
        System.arraycopy(extra, 0, arguments, 2, extra.length);
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withEnvironment(environment)
                .withArguments(arguments)
                .build()
                .getOutput()
                .trim();
    }
}
