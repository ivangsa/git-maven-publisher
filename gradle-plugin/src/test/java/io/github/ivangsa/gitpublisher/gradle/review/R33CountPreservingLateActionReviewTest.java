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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Review of BR11 (R32, D-103), finding F1 (R33). Design section 2 (Gradle, D-033): "A failed publication prevents push."
 *
 * <p>D-103 makes the success record fail closed by comparing {@code task.getActions().size()} with the count it stored
 * when it was added, and states that the check "holds for any callback order". The count is a proxy for "the record is
 * the last action": a later {@code whenReady} that changes the action list without changing its size still puts a
 * failing action after the record. Two cases: (1) one earlier action is removed (replaced by a later plugin) and a
 * failing {@code doLast} is added; (2) the last action (the record) is wrapped through {@code setActions} by a wrapper
 * that fails after delegating. In both, the participant is FAILED but recorded, and the finalizer pushes.
 */
class R33CountPreservingLateActionReviewTest {

    private static final String BRANCH = "gh-pages";
    private static final String PARTICIPANT = "publishMavenPublicationToGitPublisherRepository";

    private static final String REMOVE_AND_ADD = "def participant = tasks.named('" + PARTICIPANT + "')\n"
            + "participant.configure { doLast { println 'audit action in b' } }\n"
            + "gradle.taskGraph.whenReady {\n"
            + "    def t = participant.get()\n"
            + "    // a later plugin replaces the audit action (index 1, after the publish action) by its own check\n"
            + "    t.actions.remove(1)\n"
            + "    t.doLast { throw new GradleException('late boom in b') }\n"
            + "}\n";

    private static final String WRAP_LAST = "def participant = tasks.named('" + PARTICIPANT + "')\n"
            + "gradle.taskGraph.whenReady {\n"
            + "    def t = participant.get()\n"
            + "    def actions = new ArrayList(t.actions)\n"
            + "    def last = actions.remove(actions.size() - 1)\n"
            + "    actions.add({ task -> last.execute(task); throw new GradleException('late boom in b') } as Action)\n"
            + "    t.actions = actions\n"
            + "}\n";

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

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("remove-and-add", REMOVE_AND_ADD, List.of()),
                Arguments.of("remove-and-add", REMOVE_AND_ADD, List.of("--continue")),
                Arguments.of("remove-and-add", REMOVE_AND_ADD, List.of("--configuration-cache")),
                Arguments.of("wrap-last", WRAP_LAST, List.of()),
                Arguments.of("wrap-last", WRAP_LAST, List.of("--continue")));
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("cases")
    void aCountPreservingChangeThatPutsAFailingActionAfterTheRecordMeansNoPush(
            String name, String bScript, List<String> schedule) throws IOException {
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
        Files.writeString(root.resolve("b/build.gradle"), bScript);

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
