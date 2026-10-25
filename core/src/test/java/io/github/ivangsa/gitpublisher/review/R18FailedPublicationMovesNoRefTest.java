/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.Credentials;
import io.github.ivangsa.gitpublisher.GitPublisher;
import io.github.ivangsa.gitpublisher.Publication;
import io.github.ivangsa.gitpublisher.PublishConfig;
import io.github.ivangsa.gitpublisher.PublishException;
import io.github.ivangsa.gitpublisher.PublishInfo;
import io.github.ivangsa.gitpublisher.PublisherLog;
import io.github.ivangsa.gitpublisher.RetentionPolicy;
import io.github.ivangsa.gitpublisher.RetryPolicy;
import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-24-f9bb6b5, F4. Design section 7: a conflict fails with "nothing pushed", and "nothing partial is
 * ever pushed". The branch and the lease-free {@code refs/publisher/tree} hint go in one non-atomic push, so a push
 * whose branch update is rejected by the lease still moves the hint to the tree of the failed publication (and uploads
 * its objects) before the replay finds the conflict.
 */
class R18FailedPublicationMovesNoRefTest {

    private static final String BRANCH = "gh-pages";

    @TempDir
    Path tmp;

    @Test
    void aConflictingPublicationLeavesTheTreeHintOnThePublishedTree() throws Exception {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            bare.commitFiles(BRANCH, Map.of("a.txt", new byte[] {1}), "first");
            PublishConfig config = new PublishConfig(
                    bare.uri(),
                    BRANCH,
                    Credentials.none(),
                    tmp.resolve("work"),
                    RetentionPolicy.defaults(),
                    new RetryPolicy(5, Duration.ofMillis(1), Duration.ofMillis(2)),
                    PublishConfig.defaultAuthor(),
                    false,
                    false,
                    PublishConfig.DEFAULT_CHECKSUMS);
            try (Publication publication = new GitPublisher(config, PublisherLog.silent()).checkout()) {
                ObjectId competing = bare.commitFiles(BRANCH, Map.of("a.txt", new byte[] {2}), "competing");
                Files.writeString(publication.repositoryDirectory().resolve("a.txt"), "ours");

                assertThatThrownBy(() -> publication.publish(new PublishInfo(List.of(), Optional.empty())))
                        .isInstanceOf(PublishException.class)
                        .hasMessageContaining("nothing was pushed");
                assertThat(bare.branchTip(BRANCH)).contains(competing);

                Ref hint = bare.repository().exactRef("refs/publisher/tree");
                if (hint != null) {
                    ObjectId publishedTree;
                    try (RevWalk walk = new RevWalk(bare.repository())) {
                        publishedTree = walk.parseCommit(competing).getTree().getId();
                    }
                    assertThat(hint.getObjectId())
                            .as("refs/publisher/tree after a failed publication")
                            .isEqualTo(publishedTree);
                }
            }
        }
    }
}
