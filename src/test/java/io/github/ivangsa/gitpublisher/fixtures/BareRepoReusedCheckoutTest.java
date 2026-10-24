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
