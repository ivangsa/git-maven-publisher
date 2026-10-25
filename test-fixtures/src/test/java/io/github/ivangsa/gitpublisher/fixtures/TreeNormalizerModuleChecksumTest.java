/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
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
