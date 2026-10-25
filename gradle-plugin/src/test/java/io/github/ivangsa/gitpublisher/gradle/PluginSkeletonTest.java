/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginSkeletonTest {

    @TempDir
    Path project;

    @Test
    void pluginIdApplies() throws IOException {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'skeleton'\n");
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'io.github.ivangsa.git-maven-publisher' }\n");

        BuildResult result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("help", "--stacktrace")
                .build();

        assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, result.task(":help").getOutcome());
    }
}
