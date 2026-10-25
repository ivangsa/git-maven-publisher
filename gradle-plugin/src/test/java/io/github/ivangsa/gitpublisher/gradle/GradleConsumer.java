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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

/**
 * A clean Gradle Kotlin Multiplatform consumer (design section 9, D-043): a new project and a new, empty Gradle user home
 * (TestKit directory), whose only repository for the library is a {@code file://} URL pointing at a working tree checked
 * out from the pushed branch, i.e. exactly what any static file server would serve. It resolves the compile
 * classpath of each target and reports what Gradle selected.
 */
final class GradleConsumer {

    /** What Gradle resolved for one configuration: the component and the file chosen for it. */
    record Resolved(String configuration, String component, String fileName) {}

    private static final Pattern LINE = Pattern.compile("^RESOLVED (\\S+) (\\S+) (\\S+)$", Pattern.MULTILINE);

    private final Path project;
    private final Path gradleUserHome;

    /**
     * @param project empty directory for the consumer build
     * @param gradleUserHome directory used as the Gradle user home; empty on first use, so that no cache is shared
     *     with anything else
     */
    GradleConsumer(Path project, Path gradleUserHome) {
        this.project = project;
        this.gradleUserHome = gradleUserHome;
    }

    /**
     * Deletes a Gradle user home once its daemon has exited (it stops one second after the last build). Gives up quietly
     * after a minute: the directory is temporary.
     */
    static void deleteHome(Path home) {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (Files.exists(home) && System.nanoTime() < deadline) {
            try (java.util.stream.Stream<Path> walk = Files.walk(home)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException | UncheckedIOException e) {
                // still in use; retry
            }
            if (Files.exists(home)) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** The configurations of the three targets whose resolution is reported. */
    static List<String> configurations() {
        return List.of("jvmCompileClasspath", "jsCompileClasspath", KmpSample.nativeTarget() + "CompileKlibraries");
    }

    /** Resolves {@code coordinates} (group:artifact:version) from {@code repository} for every target. */
    List<Resolved> resolve(URI repository, String coordinates) {
        BuildResult result = runner(repository, coordinates).build();
        return parse(result.getOutput());
    }

    /** The output of the same resolution when it is expected to fail. */
    String resolveAndFail(URI repository, String coordinates) {
        return runner(repository, coordinates).buildAndFail().getOutput();
    }

    // The daemon of this home exits one second after the build, so the Gradle user home can be deleted afterwards.
    private GradleRunner runner(URI repository, String coordinates) {
        write("settings.gradle", "rootProject.name = 'consumer'\n");
        StringBuilder names = new StringBuilder();
        for (String configuration : configurations()) {
            names.append("'").append(configuration).append("', ");
        }
        write("build.gradle", """
                plugins { id 'org.jetbrains.kotlin.multiplatform' version '%s' }
                repositories {
                    maven { url = uri('%s') }
                    mavenCentral()
                }
                kotlin {
                    jvm()
                    js { nodejs() }
                    %s()
                    sourceSets {
                        commonMain { dependencies { implementation '%s' } }
                    }
                }
                tasks.register('printResolved') {
                    doLast {
                        [%s].each { name ->
                            configurations.getByName(name).incoming.artifacts.resolvedArtifacts.get().each { artifact ->
                                println "RESOLVED ${name} ${artifact.id.componentIdentifier.displayName.replace(' ', '_')} ${artifact.file.name}"
                            }
                        }
                    }
                }
                """.formatted(KmpSample.KOTLIN_VERSION, repository, KmpSample.nativeTarget(), coordinates, names));
        List<String> arguments = new ArrayList<>(List.of("printResolved", "--stacktrace", "-q"));
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withTestKitDir(gradleUserHome.toFile())
                .withArguments(arguments);
    }

    static List<Resolved> parse(String output) {
        List<Resolved> resolved = new ArrayList<>();
        Matcher matcher = LINE.matcher(output.replace("\r", ""));
        while (matcher.find()) {
            resolved.add(new Resolved(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        return resolved;
    }

    private void write(String relative, String content) {
        try {
            Path file = project.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
