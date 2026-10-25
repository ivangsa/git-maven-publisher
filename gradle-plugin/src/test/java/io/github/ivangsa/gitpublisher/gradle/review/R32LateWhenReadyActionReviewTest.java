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
package io.github.ivangsa.gitpublisher.gradle.review;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Review 2026-10-25-53fd0c7, finding F1 (R32). Design section 2 (Gradle, D-033): "A failed publication prevents push."
 *
 * <p>D-097 adds the success record in the plugin's own {@code taskGraph.whenReady} callback and claims that every action
 * added while configuring comes before it. A {@code whenReady} callback registered after the plugin was applied (by a
 * subproject build script, or by any plugin applied later) is still configuration time and runs after the plugin's
 * callback, so its {@code doLast} follows the record. When that action fails, the participant is FAILED but already
 * recorded, and the finalizer {@code gitPublisherPush} pushes.
 */
class R32LateWhenReadyActionReviewTest {

    private static final String BRANCH = "gh-pages";
    private static final String PARTICIPANT = "publishMavenPublicationToGitPublisherRepository";

    @TempDir
    Path temp;

    private BareRepo remote;

    @BeforeEach
    void createRemote() {
        remote = BareRepo.create(temp.resolve("remote.git"));
    }

    @AfterEach
    void closeRemote() {
        remote.close();
    }

    static Stream<List<String>> schedules() {
        return Stream.of(List.of(), List.of("--continue"), List.of("--configuration-cache"));
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void anActionAddedInALaterWhenReadyThatFailsMeansNoPush(List<String> schedule) throws IOException {
        Path root = temp.resolve("project");
        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'multi'\ninclude 'a'\ninclude 'b'\n");
        Files.writeString(root.resolve("build.gradle"), """
                plugins { id 'io.github.ivangsa.git-maven-publisher' apply false }
                subprojects {
                    apply plugin: 'java-library'
                    apply plugin: 'io.github.ivangsa.git-maven-publisher'
                    group = 'com.acme'
                    version = '1.0-SNAPSHOT'
                    gitMavenPublisher {
                        repository = '%s'
                        branch = '%s'
                    }
                    publishing {
                        publications {
                            maven(MavenPublication) { from components.java }
                        }
                    }
                }
                """.formatted(remote.uri(), BRANCH));
        Files.writeString(root.resolve("a/build.gradle"), "// project a\n");
        // b's build script adds a failing action when the task graph is ready: still configuration time, but after the
        // plugin's own whenReady callback, which was registered when the plugin was applied.
        Files.writeString(root.resolve("b/build.gradle"), "def participant = tasks.named('" + PARTICIPANT + "')\n"
                + "gradle.taskGraph.whenReady { participant.get().doLast { throw new GradleException('late boom in b') } }\n");

        List<String> arguments = new ArrayList<>(List.of("publish", "--stacktrace"));
        arguments.addAll(schedule);
        BuildResult result = GradleRunner.create()
                .withProjectDir(root.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .buildAndFail();

        assertTrue(result.getOutput().contains("late boom in b"), result.getOutput());
        assertTrue(
                remote.branchTip(BRANCH).isEmpty(),
                "nothing may be pushed when a participant failed (design section 2, D-033):\n" + result.getOutput());
    }
}
