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
package io.github.ivangsa.gitpublisher.fixtures.review;

import io.github.ivangsa.gitpublisher.fixtures.TreeDiff;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-24-f65192f, R06. Gradle writes the timestamped file names of a snapshot build into the
 * {@code .module} file ({@code "url": "lib-1.0-<timestamp>-<build>.jar"}). TreeNormalizer normalizes timestamps in
 * file names and in maven-metadata.xml only, so two runs of the same publication always differ in every
 * {@code .module}: the Gradle differential oracle (T41) can never pass.
 */
class R06TreeNormalizerModuleFileTest {

    @TempDir
    Path tmp;

    @Test
    void timestampsInsideModuleFilesAreNormalized() throws Exception {
        Path first = run(tmp.resolve("first"), "20261024.091500");
        Path second = run(tmp.resolve("second"), "20261024.101733");
        TreeDiff.compare(first, second).assertNoDifference();
    }

    private static Path run(Path root, String timestamp) throws Exception {
        Path dir = Files.createDirectories(root.resolve("org/acme/lib/1.0-SNAPSHOT"));
        String jar = "lib-1.0-" + timestamp + "-1.jar";
        Files.writeString(dir.resolve(jar), "jar");
        Files.writeString(
                dir.resolve("lib-1.0-" + timestamp + "-1.module"),
                "{\n  \"formatVersion\": \"1.1\",\n  \"variants\": [ { \"files\": [ { \"name\": \"" + jar
                        + "\", \"url\": \"" + jar + "\" } ] } ]\n}\n");
        return root;
    }
}
