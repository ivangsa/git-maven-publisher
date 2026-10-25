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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Gradle snippets of README.md (T44). The setup snippet must equal {@code readme/build.gradle}, the build that the
 * test runs through TestKit with the repository placeholder replaced by a bare repository. The command snippet must be
 * the task that the test runs. Nothing else in the README is copied from this build.
 */
class ReadmeGradleSnippetsTest {

    /** The README sits in the root of the repository; Gradle tests run with the module directory as working directory. */
    private static final Path README = Path.of("..", "README.md");
    private static final Path SETUP = Path.of("src/test/resources/readme/build.gradle");
    private static final String REPOSITORY_PLACEHOLDER = "https://github.com/<owner>/<snapshots-repo>.git";
    private static final String BRANCH = "snapshots";

    @TempDir
    Path dir;

    @Test
    void theGroovySnippetIsTheSetupBuildThatTheTestRuns() throws IOException {
        List<String> snippets = fencedBlocks("groovy");
        assertEquals(1, snippets.size(), "one groovy block in README.md");
        assertEquals(lineFeeds(Files.readString(SETUP, UTF_8)).strip(), snippets.get(0).strip());
    }

    @Test
    void theGradleCommandSnippetIsTheTaskThatTheTestRuns() throws IOException {
        List<String> commands = gradleCommands();
        assertEquals(List.of("./gradlew publishAllPublicationsToGitPublisherRepository"), commands);
    }

    @Test
    void theSetupSnippetPublishesThroughTestKitWithTheDocumentedCommand() throws IOException {
        String command = gradleCommands().get(0);
        String task = command.substring("./gradlew ".length());
        Path project = Files.createDirectories(dir.resolve("project"));
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'readme-lib'\n");
        try (BareRepo remote = BareRepo.create(dir.resolve("remote"))) {
            String build = lineFeeds(Files.readString(SETUP, UTF_8)).replace(REPOSITORY_PLACEHOLDER, remote.uri().toString());
            Files.writeString(project.resolve("build.gradle"), build);

            BuildResult result = GradleRunner.create()
                    .withProjectDir(project.toFile())
                    .withPluginClasspath()
                    .withArguments(task, "--stacktrace")
                    .build();

            assertEquals(TaskOutcome.SUCCESS, result.task(":" + task).getOutcome(), result.getOutput());
            assertEquals(1, remote.commitCount(BRANCH), "one commit for the build");
            Path checkout = remote.checkout(BRANCH, dir.resolve("checkout"));
            MetadataAssertions.assertValidRepository(checkout);
            assertTrue(Files.exists(checkout.resolve("com/acme/readme-lib/1.0-SNAPSHOT/maven-metadata.xml")));
        }
    }

    private static List<String> gradleCommands() throws IOException {
        List<String> commands = new ArrayList<>();
        for (String block : fencedBlocks("sh")) {
            if (block.strip().startsWith("./gradlew ")) {
                commands.add(block.strip());
            }
        }
        return commands;
    }

    /**
     * Line endings do not depend on the checkout: Git for Windows with {@code core.autocrlf=true} checks text files out
     * with CRLF, and the comparison must hold there too (R28, D-099).
     */
    static String lineFeeds(String text) {
        return text.replace("\r\n", "\n");
    }

    /** Text of every fenced code block with the given info string, without the fences. */
    static List<String> fencedBlocks(String language) throws IOException {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;
        for (String line : lineFeeds(Files.readString(README, UTF_8)).split("\n", -1)) {
            if (current == null && line.equals("```" + language)) {
                current = new StringBuilder();
            } else if (current != null && line.equals("```")) {
                blocks.add(current.toString());
                current = null;
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        return blocks;
    }
}
