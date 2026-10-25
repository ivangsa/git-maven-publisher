/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceCommitTest {

    @TempDir
    Path tmp;

    @Test
    void theCiEnvironmentWins() {
        assertThat(SourceCommit.find(tmp, Map.of("GITHUB_SHA", "abc123")::get)).contains("abc123");
    }

    @Test
    void otherwiseTheHeadOfTheEnclosingRepository() throws Exception {
        String sha;
        try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
            Files.writeString(tmp.resolve("a.txt"), "a");
            git.add().addFilepattern("a.txt").call();
            sha = git.commit()
                    .setMessage("first")
                    .setAuthor("t", "t@example.invalid")
                    .setCommitter("t", "t@example.invalid")
                    .setSign(false)
                    .call()
                    .name();
        }
        Path nested = Files.createDirectories(tmp.resolve("module"));

        assertThat(SourceCommit.find(nested, name -> null)).contains(sha);
    }

    @Test
    void anUnreadableLocationNeverThrows() {
        assertThat(SourceCommit.find(tmp.resolve("does-not-exist"), name -> null)).isNotNull();
    }
}
