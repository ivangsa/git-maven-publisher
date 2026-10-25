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
 * R33, D-104: the success record counts only when Gradle itself runs it as the last action of the task. Count-preserving
 * changes beyond the review test: an earlier action that calls the record and then fails (the record is still the last
 * element of the list), and a wrapper that copies the record's display name and fails after delegating to it.
 */
class RecordRunsLastAndUnwrappedTest {

    private static final String BRANCH = "gh-pages";
    private static final String PARTICIPANT = "publishMavenPublicationToGitPublisherRepository";

    /** Actions [publish, audit, record]: the audit action is replaced by one that calls the record, then fails. */
    private static final String EARLY_CALL = "def participant = tasks.named('" + PARTICIPANT + "')\n"
            + "participant.configure { doLast { println 'audit action in b' } }\n"
            + "gradle.taskGraph.whenReady {\n"
            + "    def t = participant.get()\n"
            + "    def actions = new ArrayList(t.actions)\n"
            + "    def record = actions.get(actions.size() - 1)\n"
            + "    actions.set(actions.size() - 2, { task -> record.execute(task); throw new GradleException('late boom in b') } as Action)\n"
            + "    t.actions = actions\n"
            + "}\n";

    /** The record is replaced by a wrapper that reports the record's display name and fails after delegating. */
    private static final String NAME_PRESERVING_WRAPPER = "class NamedWrapper implements Action<Task>, org.gradle.api.Describable {\n"
            + "    final Action<? super Task> inner\n"
            + "    NamedWrapper(Action<? super Task> inner) { this.inner = inner }\n"
            + "    void execute(Task task) { inner.execute(task); throw new GradleException('late boom in b') }\n"
            + "    String getDisplayName() { ((org.gradle.api.Describable) inner).displayName }\n"
            + "}\n"
            + "def participant = tasks.named('" + PARTICIPANT + "')\n"
            + "gradle.taskGraph.whenReady {\n"
            + "    def t = participant.get()\n"
            + "    def actions = new ArrayList(t.actions)\n"
            + "    def last = actions.remove(actions.size() - 1)\n"
            + "    actions.add(new NamedWrapper(last))\n"
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
                Arguments.of("early-call", EARLY_CALL, List.of()),
                Arguments.of("early-call", EARLY_CALL, List.of("--continue")),
                Arguments.of("name-preserving-wrapper", NAME_PRESERVING_WRAPPER, List.of()),
                Arguments.of("name-preserving-wrapper", NAME_PRESERVING_WRAPPER, List.of("--continue")));
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("cases")
    void aRecordThatGradleDoesNotRunAsTheLastActionMeansNoPush(String name, String bScript, List<String> schedule)
            throws IOException {
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
        assertTrue(result.getOutput().contains("is not recorded as successful"), result.getOutput());
        assertTrue(
                remote.branchTip(BRANCH).isEmpty(),
                "nothing may be pushed when a participant failed (design section 2, D-033):\n" + result.getOutput());
    }
}
