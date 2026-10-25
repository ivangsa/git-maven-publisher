/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reusing a checkout directory must follow file/directory transitions of the branch (R09). */
class BareRepoReusedCheckoutTest {
    @TempDir
    Path tmp;

    @Test
    void fileBecomesDirectoryAndDirectoryBecomesFile() {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles("gh-pages", Map.of("a", new byte[] {1}, "d/x.jar", new byte[] {2}), "first");
            Path checkout = remote.checkout("gh-pages", tmp.resolve("checkout"));
            remote.commitFiles("gh-pages", Map.of("a/b.jar", new byte[] {3}, "d", new byte[] {4}), "second");
            remote.checkout("gh-pages", checkout);
            assertThat(Files.isDirectory(checkout.resolve("a"))).isTrue();
            assertThat(checkout.resolve("a/b.jar")).hasBinaryContent(new byte[] {3});
            assertThat(Files.isRegularFile(checkout.resolve("d"))).isTrue();
            assertThat(checkout.resolve("d")).hasBinaryContent(new byte[] {4});
        }
    }
}
