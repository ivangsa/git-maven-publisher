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
package io.github.ivangsa.gitpublisher;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Change recorder: diff of the served tree against the tree of the expected commit (design section 4, step 3.i). */
class ChangeRecorderTest {

    @TempDir
    Path tmp;

    private static byte[] b(String s) {
        return s.getBytes(UTF_8);
    }

    private void write(Path dir, String path, String content) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void emptyBaseReportsEveryFileAsAdded() throws Exception {
        try (BareRepo repo = BareRepo.create(tmp.resolve("bare.git"))) {
            Path dir = tmp.resolve("repo");
            write(dir, "a.txt", "a");
            write(dir, "org/acme/lib/1.0/lib-1.0.jar", "jar");

            ChangeRecorder.Changes changes = ChangeRecorder.record(repo.repository(), Optional.empty(), dir);

            assertThat(changes.added()).containsOnlyKeys("a.txt", "org/acme/lib/1.0/lib-1.0.jar");
            assertThat(changes.added().get("a.txt")).isEqualTo(b("a"));
            assertThat(changes.modified()).isEmpty();
        }
    }

    @Test
    void emptyBaseAndEmptyTreeIsNoChange() throws Exception {
        try (BareRepo repo = BareRepo.create(tmp.resolve("bare.git"))) {
            Path dir = Files.createDirectories(tmp.resolve("repo"));
            ChangeRecorder.Changes changes = ChangeRecorder.record(repo.repository(), Optional.empty(), dir);
            assertThat(changes.paths()).isEmpty();
        }
    }

    @Test
    void unchangedFilesAreNotReported() throws Exception {
        try (BareRepo repo = BareRepo.create(tmp.resolve("bare.git"))) {
            ObjectId base = repo.commitFiles("gh-pages", Map.of("a.txt", b("a"), "x/y/z.txt", b("z")), "base");
            Path dir = tmp.resolve("repo");
            write(dir, "a.txt", "a");
            write(dir, "x/y/z.txt", "z");

            ChangeRecorder.Changes changes = ChangeRecorder.record(repo.repository(), Optional.of(base), dir);

            assertThat(changes.paths()).isEmpty();
        }
    }

    @Test
    void addedAndModifiedAreDistinguishedIncludingNestedPaths() throws Exception {
        try (BareRepo repo = BareRepo.create(tmp.resolve("bare.git"))) {
            ObjectId base = repo.commitFiles(
                    "gh-pages", Map.of("keep.txt", b("k"), "x/y/z.txt", b("old"), "x/y/gone.txt", b("g")), "base");
            Path dir = tmp.resolve("repo");
            write(dir, "keep.txt", "k");
            write(dir, "x/y/z.txt", "new");
            write(dir, "x/y/deeper/new.txt", "n");
            write(dir, "top.txt", "t");

            ChangeRecorder.Changes changes = ChangeRecorder.record(repo.repository(), Optional.of(base), dir);

            assertThat(changes.modified()).containsOnlyKeys("x/y/z.txt");
            assertThat(changes.modified().get("x/y/z.txt")).isEqualTo(b("new"));
            assertThat(changes.added()).containsOnlyKeys("x/y/deeper/new.txt", "top.txt");
            // a file the deploy removed is not an "ours" change: the recorder reports added and modified only
            assertThat(changes.paths()).doesNotContain("x/y/gone.txt");
        }
    }

    @Test
    void sameLengthDifferentBytesIsModified() throws Exception {
        try (BareRepo repo = BareRepo.create(tmp.resolve("bare.git"))) {
            ObjectId base = repo.commitFiles("gh-pages", Map.of("f", b("aaaa")), "base");
            Path dir = tmp.resolve("repo");
            write(dir, "f", "aaab");
            assertThat(ChangeRecorder.record(repo.repository(), Optional.of(base), dir).modified())
                    .containsOnlyKeys("f");
        }
    }
}
