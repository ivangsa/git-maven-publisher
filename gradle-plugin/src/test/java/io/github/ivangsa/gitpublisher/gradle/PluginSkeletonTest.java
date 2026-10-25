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
