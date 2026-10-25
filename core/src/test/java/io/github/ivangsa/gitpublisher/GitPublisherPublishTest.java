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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.ObjectDirectory;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code Publication.publish()}: parentless commit and one push with a lease (design section 4, steps 3.i to 3.vi). */
class GitPublisherPublishTest {

    private static final String BRANCH = "gh-pages";
    private static final PublisherLog LOG = PublisherLog.silent();
    private static final PublishInfo INFO =
            new PublishInfo(List.of("org.acme:lib:1.0-SNAPSHOT"), Optional.of("abc123"));

    @TempDir
    Path tmp;

    private PublishConfig config(URI repository, RetentionPolicy retention, boolean dryRun) {
        return config(repository, retention, dryRun, RetryPolicy.defaults());
    }

    private PublishConfig config(URI repository, RetentionPolicy retention, boolean dryRun, RetryPolicy retry) {
        return new PublishConfig(
                repository,
                BRANCH,
                Credentials.none(),
                tmp.resolve("work"),
                retention,
                retry,
                PublishConfig.defaultAuthor(),
                dryRun,
                false,
                PublishConfig.DEFAULT_CHECKSUMS);
    }

    private PublishConfig singleAttempt(URI repository) {
        return config(
                repository,
                RetentionPolicy.defaults(),
                false,
                new RetryPolicy(1, java.time.Duration.ZERO, java.time.Duration.ZERO));
    }

    private PublishConfig config(URI repository) {
        return config(repository, RetentionPolicy.defaults(), false);
    }

    private static void write(Publication publication, String path, String content) throws Exception {
        Path file = publication.repositoryDirectory().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Map<String, byte[]> bigTree() {
        Map<String, byte[]> files = new TreeMap<>();
        for (int d = 0; d < 5; d++) {
            for (int f = 0; f < 8; f++) {
                files.put("d" + d + "/f" + f + ".txt", ("content " + d + "/" + f).getBytes(UTF_8));
            }
        }
        return files;
    }

    @Test
    void firstPublishCreatesTheBranchWithOneParentlessCommit() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            try (Publication publication = new GitPublisher(config(remote.uri()), LOG).checkout()) {
                write(publication, "org/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar", "jar");
                write(publication, "README.md", "hello");

                PublishResult result = publication.publish(INFO);

                assertThat(result.pushed()).isTrue();
                assertThat(result.attempts()).isEqualTo(1);
                assertThat(result.changedPaths())
                        .containsExactlyInAnyOrder("README.md", "org/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar");
                assertThat(result.deletedPaths()).isEmpty();
                assertThat(remote.branchTip(BRANCH)).contains(result.commit());
                assertThat(remote.commitCount(BRANCH)).isEqualTo(1);
                assertThat(remote.readTree(BRANCH))
                        .containsOnlyKeys("README.md", "org/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar");
                assertThat(new String(remote.readTree(BRANCH).get("README.md"), UTF_8))
                        .isEqualTo("hello");

                try (RevWalk walk = new RevWalk(remote.repository())) {
                    RevCommit commit = walk.parseCommit(result.commit());
                    assertThat(commit.getParentCount()).isZero();
                    assertThat(commit.getFullMessage())
                            .isEqualTo("Publish org.acme:lib:1.0-SNAPSHOT\n\nsource: abc123");
                    assertThat(commit.getAuthorIdent().getName()).isEqualTo(PublishConfig.DEFAULT_AUTHOR_NAME);
                }
            }
        }
    }

    @Test
    void messageListsEveryCoordinateAndOmitsAnUnknownSource() {
        PublishInfo info = new PublishInfo(List.of("g:a:1-SNAPSHOT", "g:b:1-SNAPSHOT"), Optional.empty());
        assertThat(GitPublication.message(info)).isEqualTo("Publish g:a:1-SNAPSHOT, g:b:1-SNAPSHOT");
    }

    @Test
    void afterManyPublishesTheBranchHasExactlyOneCommit() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            GitPublisher publisher = new GitPublisher(config(remote.uri()), LOG);
            ObjectId last = null;
            for (int i = 1; i <= 4; i++) {
                try (Publication publication = publisher.checkout()) {
                    write(publication, "v" + i + "/file.txt", "round " + i);
                    PublishResult result = publication.publish(INFO);
                    assertThat(result.commit()).isNotEqualTo(last);
                    last = result.commit();
                }
            }
            assertThat(remote.commitCount(BRANCH)).isEqualTo(1);
            assertThat(remote.readTree(BRANCH))
                    .containsOnlyKeys("v1/file.txt", "v2/file.txt", "v3/file.txt", "v4/file.txt");
            assertThat(remote.branchTip(BRANCH)).contains(last);
        }
    }

    @Test
    void secondAndLaterPushesSendOnlyTheNewObjects() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            GitPublisher publisher = new GitPublisher(config(remote.uri()), LOG);
            try (Publication publication = publisher.checkout()) {
                for (Map.Entry<String, byte[]> file : bigTree().entrySet()) {
                    write(publication, file.getKey(), new String(file.getValue(), UTF_8));
                }
                publication.publish(INFO);
            }
            assertThat(objectCount(remote)).isEqualTo(1 + 1 + 5 + 40); // commit, root tree, 5 trees, 40 blobs

            for (int round = 1; round <= 3; round++) {
                int before = objectCount(remote);
                try (Publication publication = publisher.checkout()) {
                    write(publication, "d3/f2.txt", "changed " + round);
                    publication.publish(INFO);
                }
                // blob, tree d3, root tree, commit
                assertThat(objectCount(remote) - before).isEqualTo(4);
            }
            assertThat(remote.commitCount(BRANCH)).isEqualTo(1);
        }
    }

    @Test
    void theTreeRefFollowsTheBranch() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            try (Publication publication = new GitPublisher(config(remote.uri()), LOG).checkout()) {
                write(publication, "a.txt", "1");
                PublishResult result = publication.publish(INFO);
                try (RevWalk walk = new RevWalk(remote.repository())) {
                    assertThat(remote.repository().resolve(GitRemote.TREE_REF))
                            .isEqualTo(walk.parseCommit(result.commit()).getTree());
                }
            }
        }
    }

    @Test
    void aBranchWithoutTheTreeRefPublishesInOneAttemptAndCreatesIt() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles(BRANCH, Map.of("old.txt", new byte[] {1}), "created by hand");
            assertThat(remote.repository().resolve(GitRemote.TREE_REF)).isNull();

            try (Publication publication = new GitPublisher(config(remote.uri()), LOG).checkout()) {
                write(publication, "new.txt", "2");
                PublishResult result = publication.publish(INFO);

                assertThat(result.attempts()).isEqualTo(1);
                assertThat(result.pushed()).isTrue();
                assertThat(remote.branchTip(BRANCH)).contains(result.commit());
                assertThat(remote.commitCount(BRANCH)).isEqualTo(1);
                assertThat(remote.readTree(BRANCH)).containsOnlyKeys("old.txt", "new.txt");
                assertThat(remote.repository().resolve(GitRemote.TREE_REF)).isNotNull();
            }
        }
    }

    @Test
    void aStaleLeaseWithASingleAttemptBudgetFailsAndPushesNothing() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            remote.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "first");
            try (Publication publication = new GitPublisher(singleAttempt(remote.uri()), LOG).checkout()) {
                ObjectId competing = remote.commitFiles(BRANCH, Map.of("b.txt", new byte[] {2}), "competing");
                write(publication, "c.txt", "3");

                assertThatThrownBy(() -> publication.publish(INFO))
                        .isInstanceOf(PublishException.class)
                        .hasMessageContaining("changed on the remote");
                assertThat(remote.branchTip(BRANCH)).contains(competing);
                assertThat(remote.readTree(BRANCH)).containsOnlyKeys("b.txt");
            }
        }
    }

    @Test
    void remoteLeaseOutcomeIsAResultNotAnException() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            ObjectId first = remote.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "first");
            ObjectId competing = remote.commitFiles(BRANCH, Map.of("b.txt", new byte[] {2}), "competing");
            Path directory = Files.createDirectories(tmp.resolve("tree"));
            Files.writeString(directory.resolve("c.txt"), "3");
            try (Repository local = Git.init()
                    .setBare(true)
                    .setDirectory(tmp.resolve("local.git").toFile())
                    .call()
                    .getRepository()) {
                TreeCommitter.Commit commit =
                        TreeCommitter.commit(local, directory, PublishConfig.defaultAuthor(), "ours");
                GitRemote git = new GitRemote(config(remote.uri()));

                assertThat(git.push(local, commit.commit(), commit.tree(), Optional.of(first)))
                        .isInstanceOf(GitRemote.PushOutcome.LeaseFailed.class);
                assertThat(remote.branchTip(BRANCH)).contains(competing);

                assertThat(git.push(local, commit.commit(), commit.tree(), Optional.of(competing)))
                        .isInstanceOf(GitRemote.PushOutcome.Pushed.class);
                assertThat(remote.branchTip(BRANCH)).contains(commit.commit());

                // create-only lease: the branch exists now
                TreeCommitter.Commit another =
                        TreeCommitter.commit(local, directory, PublishConfig.defaultAuthor(), "another");
                assertThat(git.push(local, another.commit(), another.tree(), Optional.empty()))
                        .isInstanceOf(GitRemote.PushOutcome.LeaseFailed.class);
            }
        }
    }

    @Test
    void aBranchCreatedByAnotherPublisherMeanwhileFailsTheCreateOnlyLease() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            try (Publication publication = new GitPublisher(singleAttempt(remote.uri()), LOG).checkout()) {
                ObjectId competing = remote.commitFiles(BRANCH, Map.of("b.txt", new byte[] {2}), "competing");
                write(publication, "c.txt", "3");

                assertThatThrownBy(() -> publication.publish(INFO)).isInstanceOf(PublishException.class);
                assertThat(remote.branchTip(BRANCH)).contains(competing);
            }
        }
    }

    @Test
    void dryRunBuildsTheCommitAndPushesNothing() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            ObjectId tip = remote.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "first");
            try (Publication publication = new GitPublisher(config(remote.uri(), RetentionPolicy.defaults(), true), LOG)
                    .checkout()) {
                write(publication, "b.txt", "2");
                PublishResult result = publication.publish(INFO);

                assertThat(result.pushed()).isFalse();
                assertThat(result.commit()).isNotNull();
                assertThat(remote.branchTip(BRANCH)).contains(tip);
                assertThat(remote.repository().resolve(GitRemote.TREE_REF)).isNull();
            }
        }
    }

    @Test
    void retentionRunsBeforeTheCommit() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            try (Publication publication = new GitPublisher(
                            config(remote.uri(), new RetentionPolicy(2, RetentionPolicy.UNLIMITED), false), LOG)
                    .checkout()) {
                String dir = "org/acme/lib/1.0-SNAPSHOT/";
                for (int build = 1; build <= 4; build++) {
                    write(publication, dir + "lib-1.0-20260101.00000" + build + "-" + build + ".jar", "b" + build);
                }
                write(publication, dir + "lib-1.0-SNAPSHOT.jar", "plain");

                PublishResult result = publication.publish(INFO);

                assertThat(result.deletedPaths())
                        .containsExactlyInAnyOrder(
                                dir + "lib-1.0-20260101.000001-1.jar", dir + "lib-1.0-20260101.000002-2.jar");
                assertThat(remote.readTree(BRANCH))
                        .containsOnlyKeys(
                                dir + "lib-1.0-20260101.000003-3.jar",
                                dir + "lib-1.0-20260101.000004-4.jar",
                                dir + "lib-1.0-SNAPSHOT.jar");
            }
        }
    }

    @Test
    void keepVersionsAndReleaseCleanupRunBeforeTheCommit() throws Exception {
        Path releases = Files.createDirectories(tmp.resolve("releases/org/acme/lib/1.0"));
        Files.writeString(releases.resolve("lib-1.0.pom"), "<project/>");
        RetentionPolicy policy = new RetentionPolicy(3, 2).withReleaseRepository(tmp.resolve("releases").toUri());
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            for (String version : List.of("0.9", "1.0", "1.1", "1.2")) {
                try (Publication publication =
                        new GitPublisher(config(remote.uri(), policy, false), LOG).checkout()) {
                    try (var deployer = new io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer(
                            publication.repositoryDirectory(),
                            java.time.Instant.parse("2026-10-24T10:00:00Z").plusSeconds(60L * List.of("0.9", "1.0", "1.1", "1.2").indexOf(version)),
                            "SHA-1",
                            "MD5")) {
                        deployer.deployJavaProject("org.acme", "lib", version + "-SNAPSHOT");
                    }
                    PublishResult result = publication.publish(INFO);
                    assertThat(result.pushed()).isTrue();
                }
            }
            Path checkout = tmp.resolve("check");
            Map<String, byte[]> tree = remote.readTree(BRANCH);
            for (Map.Entry<String, byte[]> e : tree.entrySet()) {
                Path file = checkout.resolve(e.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue());
            }
            assertThat(tree.keySet())
                    .noneMatch(k -> k.startsWith("org/acme/lib/1.0-SNAPSHOT/"))
                    .noneMatch(k -> k.startsWith("org/acme/lib/0.9-SNAPSHOT/"))
                    .anyMatch(k -> k.startsWith("org/acme/lib/1.1-SNAPSHOT/"))
                    .anyMatch(k -> k.startsWith("org/acme/lib/1.2-SNAPSHOT/"));
            io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions.assertValidRepository(
                    checkout, java.util.Set.of("sha1", "md5"));
        }
    }

    @Test
    void aTreeThatBreaksAnInvariantIsNeverPushed() throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            ObjectId tip = remote.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "first");
            try (Publication publication = new GitPublisher(config(remote.uri()), LOG).checkout()) {
                write(
                        publication,
                        "org/acme/lib/maven-metadata.xml",
                        "<metadata><groupId>org.acme</groupId><artifactId>lib</artifactId><versioning><versions>"
                                + "<version>1.0</version></versions></versioning></metadata>");

                assertThatThrownBy(() -> publication.publish(INFO))
                        .isInstanceOf(PublishException.class)
                        .hasMessageContaining("integrity check failed");
                assertThat(remote.branchTip(BRANCH)).contains(tip);
            }
        }
    }

    /** Loose objects plus objects stored in packs: the receiving side may keep a pushed pack as it is. */
    private static int objectCount(BareRepo remote) throws Exception {
        ObjectDirectory objects = (ObjectDirectory) remote.repository().getObjectDatabase();
        objects.close();
        int packed = objects.getPacks().stream()
                .mapToInt(p -> (int) java.util.stream.StreamSupport.stream(p.spliterator(), false)
                        .count())
                .sum();
        try (Stream<Path> walk = Files.walk(remote.directory().resolve("objects"))) {
            int loose = (int) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getParent().getFileName().toString().length() == 2)
                    .count();
            return loose + packed;
        }
    }
}
