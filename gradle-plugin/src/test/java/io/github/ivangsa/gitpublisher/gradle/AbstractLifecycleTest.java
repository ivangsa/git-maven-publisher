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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The lifecycle guarantees of design section 2 (Gradle, D-033) as TestKit scenarios against a bare Git repository.
 * Subclasses choose the extra Gradle arguments and how many times each scenario runs the same build.
 */
abstract class AbstractLifecycleTest {

    private static final String PARTICIPANT = "publishMavenPublicationToGitPublisherRepository";

    @TempDir
    Path temp;

    BareRepo remote;
    PublisherBuild build;
    private org.eclipse.jgit.lib.ObjectId lastTip;

    /** Extra Gradle arguments for every invocation. */
    abstract List<String> baseArguments();

    /** How many times each scenario runs the same build. */
    abstract int runs();

    @BeforeEach
    void createRemote() {
        remote = BareRepo.create(temp.resolve("remote.git"));
        build = new PublisherBuild(temp.resolve("project"), remote);
    }

    @AfterEach
    void closeRemote() {
        remote.close();
    }

    static Stream<List<String>> schedules() {
        return Stream.of(List.of(), List.of("--parallel"), List.of("--parallel", "--continue"));
    }

    static Stream<List<String>> failureSchedules() {
        return Stream.of(List.of(), List.of("--continue"), List.of("--parallel"), List.of("--parallel", "--continue"));
    }

    private List<String> arguments(List<String> schedule) {
        return Stream.concat(baseArguments().stream(), schedule.stream()).toList();
    }

    private void assertCacheBehaviour(BuildResult result, int run) {
        if (baseArguments().contains("--configuration-cache")) {
            String output = result.getOutput();
            if (run == 1) {
                assertTrue(output.contains("Configuration cache entry stored."), output);
            } else {
                assertTrue(output.contains("Reusing configuration cache."), output);
            }
            assertFalse(output.contains("problems were found"), output);
        }
    }

    /**
     * One parentless commit (design section 4), and a new one after every successful run: the branch tip moved.
     */
    private void assertValidTree(String... expectedProjects) {
        assertEquals(1, remote.commitCount(PublisherBuild.BRANCH));
        var tip = remote.branchTip(PublisherBuild.BRANCH).orElseThrow();
        assertNotEquals(lastTip, tip, "the run did not push a new commit");
        lastTip = tip;
        Path checkout = remote.checkout(PublisherBuild.BRANCH, temp.resolve("checkout"));
        MetadataAssertions.assertValidRepository(checkout, Set.of("sha1", "md5"));
        for (String project : expectedProjects) {
            Path versionDirectory = checkout.resolve("com/acme/" + project + "/1.0-SNAPSHOT");
            assertTrue(Files.isDirectory(versionDirectory), "missing " + versionDirectory);
            assertTrue(Files.exists(checkout.resolve("com/acme/" + project + "/maven-metadata.xml")));
        }
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void threeProjectsAreOneCommitWithAValidTree(List<String> schedule) {
        build.withProjects("a", "b", "c");
        for (int run = 1; run <= runs(); run++) {
            BuildResult result = build.run(arguments(schedule), "publish");
            assertCacheBehaviour(result, run);
            assertValidTree("a", "b", "c");
        }
    }

    @ParameterizedTest
    @MethodSource("failureSchedules")
    void aFailingParticipantMeansNoPush(List<String> schedule) {
        // a and b finish quickly, c fails last: a push that did not wait for the participant would publish a and b.
        build.withProjects("a", "b", "c")
                .configure(
                        "c",
                        "tasks.named('" + PARTICIPANT
                                + "') { doFirst { Thread.sleep(1500); throw new GradleException('boom in c') } }");
        for (int run = 1; run <= runs(); run++) {
            BuildResult result = build.runAndFail(arguments(schedule), "publish");
            assertCacheBehaviour(result, run);
            assertTrue(result.getOutput().contains("boom in c"), result.getOutput());
            assertTrue(result.getOutput().contains(":c:" + PARTICIPANT), result.getOutput());
            assertTrue(remote.branchTip(PublisherBuild.BRANCH).isEmpty(), "nothing may be pushed");
        }
    }

    @ParameterizedTest
    @MethodSource("failureSchedules")
    void aFailingParticipantMeansNoNewCommitWhenTheBranchExists(List<String> schedule) {
        build.withProjects("a", "b", "c");
        build.run(arguments(List.of()), "publish");
        assertValidTree("a", "b", "c");
        var tip = remote.branchTip(PublisherBuild.BRANCH).orElseThrow();

        build.configure(
                "b", "tasks.named('" + PARTICIPANT + "') { doFirst { throw new GradleException('boom in b') } }");
        BuildResult result = build.runAndFail(arguments(schedule), "publish");
        assertTrue(result.getOutput().contains("boom in b"), result.getOutput());
        assertEquals(tip, remote.branchTip(PublisherBuild.BRANCH).orElseThrow());
        assertEquals(1, remote.commitCount(PublisherBuild.BRANCH));
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void pushNeverStartsBeforeEverySlowParticipantFinished(List<String> schedule) {
        build.withProjects("a", "b", "c")
                .configure("a", "tasks.named('" + PARTICIPANT + "') { doLast { Thread.sleep(300) } }")
                .configure("b", "tasks.named('" + PARTICIPANT + "') { doLast { Thread.sleep(1200) } }")
                .configure("c", "tasks.named('" + PARTICIPANT + "') { doLast { Thread.sleep(2400) } }");
        for (int run = 1; run <= runs(); run++) {
            BuildResult result = build.run(arguments(schedule), "publish", "--console=plain");
            assertCacheBehaviour(result, run);
            assertValidTree("a", "b", "c");
            String output = result.getOutput();
            int push = output.indexOf("gitPublisherPush");
            for (String project : List.of("a", "b", "c")) {
                int participant = output.indexOf(":" + project + ":" + PARTICIPANT);
                assertTrue(participant >= 0 && participant < push, output);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void oneSelectedSubprojectPublishesOnlyItself(List<String> schedule) {
        build.withProjects("a", "b", "c");
        for (int run = 1; run <= runs(); run++) {
            BuildResult result = build.run(arguments(schedule), ":b:publish");
            assertCacheBehaviour(result, run);
            assertValidTree("b");
            Path checkout = temp.resolve("checkout");
            assertFalse(Files.exists(checkout.resolve("com/acme/a")));
            assertFalse(Files.exists(checkout.resolve("com/acme/c")));
        }
    }

    @ParameterizedTest
    @MethodSource("schedules")
    void oneSelectedPublicationTaskPublishesOnlyItself(List<String> schedule) {
        build.withProjects("a", "b", "c");
        for (int run = 1; run <= runs(); run++) {
            BuildResult result = build.run(arguments(schedule), ":c:" + PARTICIPANT);
            assertCacheBehaviour(result, run);
            assertValidTree("c");
        }
    }
}
