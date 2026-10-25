/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BareRepoTest {

    @TempDir
    Path tmp;

    @Test
    void commitWithJGitThenCheckoutTipAsPlainTree() throws Exception {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            assertThat(bare.branchTip("gh-pages")).isEmpty();
            assertThat(bare.commitCount("gh-pages")).isZero();

            // a plain JGit client commits and pushes, like the publisher will
            Path clone = tmp.resolve("clone");
            try (Git git = Git.init().setDirectory(clone.toFile()).setInitialBranch("gh-pages").call()) {
                Path file = clone.resolve("org/acme/hello.txt");
                Files.createDirectories(file.getParent());
                Files.writeString(file, "hello pages", UTF_8);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("first").setSign(false).call();
                git.push().setRemote(bare.uri().toString()).add("gh-pages").call();
            }

            assertThat(bare.branchTip("gh-pages")).isPresent();
            assertThat(bare.commitCount("gh-pages")).isEqualTo(1);
            assertThat(bare.readTree("gh-pages")).containsOnlyKeys("org/acme/hello.txt");

            Path served = bare.checkout("gh-pages", tmp.resolve("served"));
            assertThat(served.resolve(".git")).doesNotExist();
            assertThat(served.resolve("org/acme/hello.txt")).hasContent("hello pages");
            assertThat(served.resolve("org/acme/missing.txt")).doesNotExist();
        }
    }

    @Test
    void commitFilesBuildsHistoryAndCountsCommits() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            bare.commitFiles("gh-pages", Map.of("a.txt", "1".getBytes(UTF_8)), "one");
            bare.commitFiles("gh-pages", Map.of("a.txt", "2".getBytes(UTF_8), "b/c.txt", "3".getBytes(UTF_8)), "two");
            assertThat(bare.commitCount("gh-pages")).isEqualTo(2);
            assertThat(bare.readTree("gh-pages")).containsOnlyKeys("a.txt", "b/c.txt");
            assertThat(new String(bare.readTree("gh-pages").get("a.txt"), UTF_8)).isEqualTo("2");
        }
    }
}
