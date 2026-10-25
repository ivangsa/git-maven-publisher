/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code GitPublisher.checkout()} against a real bare repository (design section 4, step 1). */
class GitPublisherCheckoutTest {

    private static final String BRANCH = "gh-pages";
    private static final PublisherLog LOG = PublisherLog.silent();

    @TempDir
    Path tmp;

    private PublishConfig config(java.net.URI repository, boolean nojekyll) {
        return new PublishConfig(
                repository,
                BRANCH,
                Credentials.none(),
                tmp.resolve("work"),
                RetentionPolicy.defaults(),
                RetryPolicy.defaults(),
                PublishConfig.defaultAuthor(),
                false,
                nojekyll,
                PublishConfig.DEFAULT_CHECKSUMS);
    }

    private static Map<String, byte[]> tree() {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("README.md", "hello".getBytes(UTF_8));
        files.put("org/acme/lib/maven-metadata.xml", "<metadata/>".getBytes(UTF_8));
        files.put("org/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar", new byte[] {0, 1, 2, (byte) 255});
        return files;
    }

    private static Map<String, byte[]> read(Path root) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return files;
    }

    @Test
    void existingBranchIsMaterializedWithItsTipAsExpectedSha() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles(BRANCH, Map.of("old.txt", new byte[] {1}), "first");
            Map<String, byte[]> files = tree();
            var tip = remote.commitFiles(BRANCH, files, "second");

            try (Publication publication = new GitPublisher(config(remote.uri(), false), LOG).checkout()) {
                assertThat(publication.expectedSha()).contains(tip);
                assertThat(publication.repositoryDirectory()).isEqualTo(tmp.resolve("work/repo"));
                Map<String, byte[]> actual = read(publication.repositoryDirectory());
                assertThat(actual).containsOnlyKeys(files.keySet());
                for (Map.Entry<String, byte[]> file : files.entrySet()) {
                    assertThat(actual.get(file.getKey())).isEqualTo(file.getValue());
                }
                assertThat(publication.repositoryDirectory().resolve(".git")).doesNotExist();
            }
        }
    }

    @Test
    void checkoutIsShallow() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles(BRANCH, Map.of("a", new byte[] {1}), "first");
            remote.commitFiles(BRANCH, Map.of("a", new byte[] {2}), "second");
            remote.commitFiles(BRANCH, Map.of("a", new byte[] {3}), "third");

            try (Publication ignored = new GitPublisher(config(remote.uri(), false), LOG).checkout()) {
                assertThat(tmp.resolve("work/git/shallow")).exists();
            }
        }
    }

    @Test
    void missingBranchStartsEmpty() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles("other", Map.of("x", new byte[] {1}), "other branch");

            try (Publication publication = new GitPublisher(config(remote.uri(), false), LOG).checkout()) {
                assertThat(publication.expectedSha()).isEmpty();
                assertThat(publication.repositoryDirectory()).isDirectory();
                assertThat(read(publication.repositoryDirectory())).isEmpty();
            }
        }
    }

    @Test
    void emptyRemoteStartsEmpty() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            try (Publication publication = new GitPublisher(config(remote.uri(), false), LOG).checkout()) {
                assertThat(publication.expectedSha()).isEmpty();
                assertThat(read(publication.repositoryDirectory())).isEmpty();
            }
        }
    }

    @Test
    void nojekyllEnabledWritesAnEmptyFileOnAMissingBranch() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            try (Publication publication = new GitPublisher(config(remote.uri(), true), LOG).checkout()) {
                Path file = publication.repositoryDirectory().resolve(".nojekyll");
                assertThat(file).isRegularFile();
                assertThat(Files.size(file)).isZero();
            }
        }
    }

    @Test
    void nojekyllEnabledKeepsExistingFilesAndAddsTheMarker() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles(BRANCH, tree(), "first");
            try (Publication publication = new GitPublisher(config(remote.uri(), true), LOG).checkout()) {
                assertThat(read(publication.repositoryDirectory()))
                        .containsKeys(".nojekyll", "README.md", "org/acme/lib/maven-metadata.xml");
            }
        }
    }

    @Test
    void nojekyllDisabledWritesNoMarker() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles(BRANCH, tree(), "first");
            try (Publication publication = new GitPublisher(config(remote.uri(), false), LOG).checkout()) {
                assertThat(publication.repositoryDirectory().resolve(".nojekyll")).doesNotExist();
            }
        }
    }

    @Test
    void remoteUrlIsNeverWrittenToTheObjectStore() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles(BRANCH, tree(), "first");
            String marker = remote.directory().getFileName().toString();
            try (Publication ignored = new GitPublisher(config(remote.uri(), false), LOG).checkout()) {
                String config = Files.readString(tmp.resolve("work/git/config"));
                assertThat(config).doesNotContain("[remote").doesNotContain(marker);
                assertThat(config).doesNotContain(remote.uri().toString());
            }
        }
    }

    @Test
    void secondCheckoutCleansTheWorkDirectory() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "first");
            GitPublisher publisher = new GitPublisher(config(remote.uri(), false), LOG);
            try (Publication first = publisher.checkout()) {
                Files.writeString(first.repositoryDirectory().resolve("leftover.txt"), "x");
            }
            remote.commitFiles(BRANCH, Map.of("b.txt", new byte[] {2}), "second");

            try (Publication second = publisher.checkout()) {
                assertThat(read(second.repositoryDirectory())).containsOnlyKeys("b.txt");
                assertThat(second.expectedSha()).isEqualTo(remote.branchTip(BRANCH));
            }
        }
    }

    @Test
    void unreachableRemoteFailsWithAPublishException() {
        PublishConfig config = config(tmp.resolve("does-not-exist.git").toUri(), false);
        assertThatThrownBy(() -> new GitPublisher(config, LOG).checkout()).isInstanceOf(PublishException.class);
    }
}
