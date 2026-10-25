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

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

/** A multi-project Gradle build under test that publishes to a bare Git repository, run through TestKit. */
final class PublisherBuild {

    static final String BRANCH = "gh-pages";

    private final Path root;
    private final BareRepo remote;
    private final URI repositoryUri;
    private String publisherSettings = "";
    private final Map<String, String> environment = new LinkedHashMap<>();

    PublisherBuild(Path root, BareRepo remote) {
        this(root, remote, remote.uri());
    }

    /** The repository to publish to is {@code repositoryUri}; {@code remote} is the bare repository behind it. */
    PublisherBuild(Path root, BareRepo remote, URI repositoryUri) {
        this.root = root;
        this.remote = remote;
        this.repositoryUri = repositoryUri;
    }

    /** Extra lines inside {@code gitMavenPublisher { }} of every subproject. */
    PublisherBuild publisherSettings(String groovy) {
        this.publisherSettings = groovy;
        return this;
    }

    /** An environment variable of the build (added to the inherited environment). */
    PublisherBuild environment(String name, String value) {
        environment.put(name, value);
        return this;
    }

    /** Writes {@code gradle.properties} of the project. */
    PublisherBuild gradleProperties(String content) {
        write(root.resolve("gradle.properties"), content);
        return this;
    }

    BareRepo remote() {
        return remote;
    }

    Path root() {
        return root;
    }

    /** Subprojects apply java-library and the plugin; every subproject publishes {@code com.acme:<name>:1.0-SNAPSHOT}. */
    PublisherBuild withProjects(String... names) {
        StringBuilder settings = new StringBuilder("rootProject.name = 'multi'\n");
        for (String name : names) {
            settings.append("include '").append(name).append("'\n");
        }
        write(root.resolve("settings.gradle"), settings.toString());
        write(root.resolve("build.gradle"), """
                plugins { id 'io.github.ivangsa.git-maven-publisher' apply false }
                subprojects {
                    apply plugin: 'java-library'
                    apply plugin: 'io.github.ivangsa.git-maven-publisher'
                    group = 'com.acme'
                    version = '1.0-SNAPSHOT'
                    gitMavenPublisher {
                        repository = '%s'
                        branch = '%s'
                        %s
                    }
                    publishing {
                        publications {
                            maven(MavenPublication) { from components.java }
                        }
                    }
                }
                """.formatted(repositoryUri, BRANCH, publisherSettings));
        for (String name : names) {
            write(root.resolve(name).resolve("build.gradle"), "// project " + name + "\n");
        }
        return this;
    }

    /** Appends Groovy to the build file of one subproject. */
    PublisherBuild configure(String project, String groovy) {
        Path file = root.resolve(project).resolve("build.gradle");
        try {
            Files.writeString(file, Files.readString(file) + groovy + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    BuildResult run(List<String> extraArguments, String... tasks) {
        return runner(extraArguments, tasks).build();
    }

    BuildResult runAndFail(List<String> extraArguments, String... tasks) {
        return runner(extraArguments, tasks).buildAndFail();
    }

    private GradleRunner runner(List<String> extraArguments, String... tasks) {
        List<String> arguments = new ArrayList<>(List.of(tasks));
        arguments.addAll(extraArguments);
        arguments.add("--stacktrace");
        GradleRunner runner =
                GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments(arguments);
        if (!environment.isEmpty()) {
            Map<String, String> merged = new HashMap<>(System.getenv());
            merged.putAll(environment);
            runner.withEnvironment(merged);
        }
        return runner;
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
