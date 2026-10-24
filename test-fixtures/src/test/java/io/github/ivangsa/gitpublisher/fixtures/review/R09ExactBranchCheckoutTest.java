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
