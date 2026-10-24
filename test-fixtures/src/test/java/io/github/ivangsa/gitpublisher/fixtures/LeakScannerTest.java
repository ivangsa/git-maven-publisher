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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Self-test: the scanner must detect a planted canary in every location it claims to check,
 * and pass on a clean run. Its own messages must never echo the canary.
 */
class LeakScannerTest {

    private static final String CANARY = "gmp-canary-5e1f2a7b9c-token";
    private final LeakScanner scanner = new LeakScanner(CANARY);

    @Test
    void rejectsBlankCanary() {
        assertThatThrownBy(() -> new LeakScanner(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cleanLogPasses() {
        assertThat(scanner.scanText("build log", "Publishing org.example:lib:1.0-SNAPSHOT\n")).isEmpty();
    }

    @Test
    void canaryInCapturedLogIsDetectedWithoutEchoingIt() {
        List<String> findings = scanner.scanText("build log", "push to https://x-access-token:" + CANARY + "@host\n");

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0)).contains("build log").doesNotContain(CANARY);
    }

    @Test
    void cleanTreePasses(@TempDir Path root) throws IOException {
        write(root.resolve("repo/maven-metadata.xml"), "<metadata/>");
        write(root.resolve("git/HEAD"), "ref: refs/heads/gh-pages\n");

        assertThat(scanner.scanTree(root)).isEmpty();
    }

    @Test
    void canaryInFileContentIsDetected(@TempDir Path root) throws IOException {
        write(root.resolve("repo/notes.txt"), "token=" + CANARY);

        List<String> findings = scanner.scanTree(root);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0)).contains("notes.txt").doesNotContain(CANARY);
    }

    @Test
    void canaryInBinaryContentIsDetected(@TempDir Path root) throws IOException {
        byte[] prefix = {0, (byte) 0xFF, 0x10, 0x00};
        byte[] canary = CANARY.getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[prefix.length + canary.length];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        System.arraycopy(canary, 0, content, prefix.length, canary.length);
        Files.createDirectories(root.resolve("repo"));
        Files.write(root.resolve("repo/artifact.jar"), content);

        assertThat(scanner.scanTree(root)).hasSize(1);
    }

    @Test
    void canaryInFileNameIsDetected(@TempDir Path root) throws IOException {
        write(root.resolve("repo/" + CANARY + ".txt"), "clean");

        assertThat(scanner.scanTree(root)).hasSize(1);
    }

    @Test
    void canaryInDirectoryNameIsDetected(@TempDir Path root) throws IOException {
        write(root.resolve("repo/" + CANARY + "/file.txt"), "clean");

        assertThat(scanner.scanTree(root)).hasSize(1);
    }

    @Test
    void canaryInWorkTreeGitConfigIsDetected(@TempDir Path root) throws IOException {
        write(root.resolve("work/.git/config"), "[remote \"origin\"]\n\turl = https://u:" + CANARY + "@host/repo.git\n");

        assertThat(scanner.scanGitConfig(root)).hasSize(1);
        assertThat(scanner.scanTree(root)).isNotEmpty();
    }

    @Test
    void canaryInBareRepoConfigIsDetected(@TempDir Path root) throws IOException {
        Path bare = root.resolve("git");
        write(bare.resolve("HEAD"), "ref: refs/heads/gh-pages\n");
        Files.createDirectories(bare.resolve("objects"));
        write(bare.resolve("config"), "[http]\n\textraHeader = Authorization: " + CANARY + "\n");

        assertThat(scanner.scanGitConfig(root)).hasSize(1);
    }

    @Test
    void cleanGitConfigPasses(@TempDir Path root) throws IOException {
        write(root.resolve("work/.git/config"), "[core]\n\trepositoryformatversion = 0\n");

        assertThat(scanner.scanGitConfig(root)).isEmpty();
    }

    @Test
    void configFileOutsideGitDirectoriesIsNotGitConfig(@TempDir Path root) throws IOException {
        write(root.resolve("repo/config"), "token=" + CANARY);

        assertThat(scanner.scanGitConfig(root)).isEmpty();
        assertThat(scanner.scanTree(root)).hasSize(1);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
