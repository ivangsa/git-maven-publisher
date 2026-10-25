/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A checkout used by Pages consumers must contain exactly the files of the current branch. */
class R09ExactBranchCheckoutTest {
    @TempDir
    Path tmp;

    @Test
    void checkoutDoesNotKeepFilesDeletedByTheBranch() {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles("gh-pages", Map.of("old.jar", new byte[] {1}), "old build");
            Path checkout = remote.checkout("gh-pages", tmp.resolve("checkout"));
            assertThat(checkout.resolve("old.jar")).exists();
            remote.commitFiles("gh-pages", Map.of("new.jar", new byte[] {2}), "retained build");
            assertThat(remote.readTree("gh-pages")).containsOnlyKeys("new.jar");
            remote.checkout("gh-pages", checkout);
            assertThat(checkout.resolve("new.jar")).exists();
            assertThat(checkout.resolve("old.jar")).as("the branch no longer contains this file").doesNotExist();
        }
    }
}
