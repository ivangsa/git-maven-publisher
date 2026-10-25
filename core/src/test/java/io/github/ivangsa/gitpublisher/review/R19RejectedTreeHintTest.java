/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-25-0701e5d, F1. Design section 4 step 3.vi, D-027 and the T20 "Done when" say a rejected
 * {@code refs/publisher/tree} never fails or retries a publish: the tree ref is a transfer hint and only the branch
 * status decides. R18 (D-060) made the push atomic, so a remote that refuses the hint ref now refuses the branch update
 * with it, and {@code GitRemote.push} reports that as a permanent rejection ("the branch must allow force pushes"):
 * every later publication to that remote fails.
 *
 * <p>The refusal here is a real JGit receive-pack that cannot lock {@code refs/publisher/tree} (a stale
 * {@code refs/publisher/tree.lock} on the server); without the atomic flag the same push moves the branch and only the
 * hint is rejected (sanity test below).
 */
class R19RejectedTreeHintTest {

    private static final String BRANCH = "gh-pages";
    private static final String STALE_LOCK = "stale lock";

    @TempDir
    Path tmp;

    private PublishConfig config(BareRepo bare, String work) {
        return new PublishConfig(
                bare.uri(),
                BRANCH,
                Credentials.none(),
                tmp.resolve(work),
                RetentionPolicy.defaults(),
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(2)),
                PublishConfig.defaultAuthor(),
                false,
                false,
                PublishConfig.DEFAULT_CHECKSUMS);
    }

    @Test
    void aRemoteThatRefusesTheTreeHintStillGetsThePublication() throws Exception {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            try (Publication first = new GitPublisher(config(bare, "work1"), PublisherLog.silent()).checkout()) {
                Files.writeString(first.repositoryDirectory().resolve("a.txt"), "a");
                assertThat(first.publish(new PublishInfo(List.of(), Optional.empty())).pushed()).isTrue();
            }
            assertThat(bare.repository().exactRef("refs/publisher/tree"))
                    .as("hint after the first publication")
                    .isNotNull();
            Files.createDirectories(bare.directory().resolve("refs/publisher"));
            Files.writeString(bare.directory().resolve("refs/publisher/tree.lock"), STALE_LOCK);

            try (Publication second = new GitPublisher(config(bare, "work2"), PublisherLog.silent()).checkout()) {
                Files.writeString(second.repositoryDirectory().resolve("b.txt"), "b");

                PublishResult result = second.publish(new PublishInfo(List.of(), Optional.empty()));

                assertThat(result.pushed()).isTrue();
                assertThat(result.attempts()).as("a rejected hint never retries (D-027)").isEqualTo(1);
            }
            assertThat(bare.readTree(BRANCH)).containsOnlyKeys("a.txt", "b.txt");
            assertThat(bare.commitCount(BRANCH)).isEqualTo(1);
        }
    }

    /** Sanity of the setup: the same refusal in a non-atomic push leaves the branch update OK (D-027's premise). */
    @Test
    void sanityANonAtomicPushMovesTheBranchAndOnlyTheHintIsRejected() throws Exception {
        try (BareRepo source = BareRepo.create(tmp.resolve("source.git"));
                BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            ObjectId commit = source.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "a");
            Repository git = source.repository();
            ObjectId tree;
            try (RevWalk walk = new RevWalk(git)) {
                tree = walk.parseCommit(commit).getTree().getId();
            }
            Files.createDirectories(bare.directory().resolve("refs/publisher"));
            Files.writeString(bare.directory().resolve("refs/publisher/tree.lock"), STALE_LOCK);

            PushResult result;
            try (Transport transport = Transport.open(git, new URIish(bare.uri().toString()))) {
                transport.setPushAtomic(false);
                result = transport.push(
                        NullProgressMonitor.INSTANCE,
                        List.of(
                                new RemoteRefUpdate(
                                        git, commit.name(), "refs/heads/" + BRANCH, true, null, ObjectId.zeroId()),
                                new RemoteRefUpdate(git, tree.name(), "refs/publisher/tree", true, null, null)));
            }
            assertThat(result.getRemoteUpdate("refs/heads/" + BRANCH).getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
            assertThat(result.getRemoteUpdate("refs/publisher/tree").getStatus())
                    .isNotEqualTo(RemoteRefUpdate.Status.OK);
        }
    }
}
