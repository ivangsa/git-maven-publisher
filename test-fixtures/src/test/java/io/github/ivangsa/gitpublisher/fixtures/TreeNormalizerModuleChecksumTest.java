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
package io.github.ivangsa.gitpublisher.fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checksums of {@code .module} files follow from their (normalized) content and are not compared raw. */
class TreeNormalizerModuleChecksumTest {

    @TempDir
    Path tmp;

    @Test
    void moduleChecksumsDifferingBetweenRunsAreNotADifference() throws Exception {
        TreeDiff.compare(run("first", "20261024.091500", "aaa"), run("second", "20261024.101733", "bbb"))
                .assertNoDifference();
    }

    @Test
    void realModuleContentDifferenceIsStillReported() throws Exception {
        Path a = run("a", "20261024.091500", "aaa");
        Path b = run("b", "20261024.091500", "aaa");
        Files.writeString(b.resolve("org/acme/lib/1.0-SNAPSHOT/lib-1.0-20261024.091500-1.module"), "{\"other\": true}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TreeDiff.compare(a, b).assertNoDifference())
                .isInstanceOf(AssertionError.class);
    }

    private Path run(String name, String timestamp, String checksum) throws Exception {
        Path root = tmp.resolve(name);
        Path dir = Files.createDirectories(root.resolve("org/acme/lib/1.0-SNAPSHOT"));
        String base = "lib-1.0-" + timestamp + "-1.module";
        Files.writeString(dir.resolve(base), "{\"url\": \"lib-1.0-" + timestamp + "-1.jar\"}");
        Files.writeString(dir.resolve(base + ".sha1"), checksum);
        Files.writeString(dir.resolve("lib-1.0-" + timestamp + "-1.jar"), "jar");
        return root;
    }
}
