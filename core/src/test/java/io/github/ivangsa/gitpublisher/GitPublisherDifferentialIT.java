/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.InterleavedPublishScenario;
import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import io.github.ivangsa.gitpublisher.fixtures.SequentialReference;
import io.github.ivangsa.gitpublisher.fixtures.SequentialReference.Step;
import io.github.ivangsa.gitpublisher.fixtures.TreeDiff;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Differential test (T23): a script of publications on distinct coordinates goes through {@link SequentialReference}
 * (expected: the real Resolver, one deploy after another) and through interleaved {@link GitPublisher} checkouts
 * (actual: every round forces a lease failure and an overlay replay). {@link TreeDiff} must report no difference.
 */
class GitPublisherDifferentialIT {

    private static final String BRANCH = "gh-pages";
    private static final RetryPolicy FAST = new RetryPolicy(5, Duration.ofMillis(1), Duration.ofMillis(5));

    /** One round: publisher A checks out, publisher B publishes in full, then A pushes. Coordinates are disjoint. */
    record Round(String a, String b) {}

    /** A step with content unique per round and publisher, so no two publications are byte-identical. */
    private static Step step(String coordinates, int round, String publisher) {
        String[] gav = coordinates.split(":");
        String id = coordinates + " round " + round + " by " + publisher;
        return Step.of(
                gav[0],
                gav[1],
                gav[2],
                ResolverDeployer.Part.of("", "jar", "jar of " + id),
                ResolverDeployer.Part.of(
                        "", "pom", ResolverDeployer.pom(gav[0], gav[1], gav[2]) + "<!-- " + id + " -->"),
                ResolverDeployer.Part.of("sources", "jar", "sources of " + id));
    }

    static Stream<Arguments> scripts() {
        List<Round> repeated = List.of(
                new Round("org.acme:alpha:1.0-SNAPSHOT", "org.acme:beta:1.0-SNAPSHOT"),
                new Round("org.acme:alpha:1.0-SNAPSHOT", "org.acme:gamma:1.0-SNAPSHOT"),
                new Round("org.acme:alpha:1.0-SNAPSHOT", "org.acme:beta:1.0-SNAPSHOT"),
                new Round("org.acme:alpha:1.0-SNAPSHOT", "org.acme:beta:1.0-SNAPSHOT"),
                new Round("org.acme:alpha:1.0-SNAPSHOT", "org.acme:gamma:1.0-SNAPSHOT"));
        List<Round> groupsAndVersions = List.of(
                new Round("org.acme:alpha:1.0-SNAPSHOT", "com.other:omega:2.0-SNAPSHOT"),
                new Round("org.acme:alpha:1.1-SNAPSHOT", "com.other:omega:2.0-SNAPSHOT"),
                new Round("com.other:omega:2.1-SNAPSHOT", "org.acme:alpha:1.0-SNAPSHOT"),
                new Round("org.acme:alpha:1.1-SNAPSHOT", "com.other:omega:2.1-SNAPSHOT"));
        List<RetentionPolicy> policies = List.of(
                new RetentionPolicy(Integer.MAX_VALUE, RetentionPolicy.UNLIMITED), new RetentionPolicy(2, -1));
        List<Arguments> all = new ArrayList<>();
        for (RetentionPolicy policy : policies) {
            all.add(Arguments.of("repeated builds", repeated, policy));
            all.add(Arguments.of("groups and versions", groupsAndVersions, policy));
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0}, retention {2}")
    @MethodSource("scripts")
    void interleavedGitPublicationsEqualTheSequentialReference(
            String name, List<Round> script, RetentionPolicy retention, @TempDir Path tmp) {
        List<Step> published = new ArrayList<>();
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            int roundNumber = 0;
            for (Round round : script) {
                roundNumber++;
                Step a = step(round.a(), roundNumber, "A");
                Step b = step(round.b(), roundNumber, "B");
                InterleavedPublishScenario scenario =
                        new InterleavedPublishScenario(bare, BRANCH, tmp.resolve("work-" + roundNumber), a, b);

                var outcome = scenario.run(publication -> {
                    PublishConfig config = new PublishConfig(
                            publication.remote().uri(),
                            BRANCH,
                            Credentials.none(),
                            publication.workDirectory(),
                            retention,
                            FAST,
                            PublishConfig.defaultAuthor(),
                            false,
                            false,
                            PublishConfig.DEFAULT_CHECKSUMS);
                    try (Publication git = new GitPublisher(config, PublisherLog.silent()).checkout()) {
                        publication.checkedOut();
                        publication.deploy().deployInto(git.repositoryDirectory());
                        PublishResult result = git.publish(
                                new PublishInfo(List.of(publication.step().coordinates()), Optional.empty()));
                        assertThat(result.pushed()).isTrue();
                    }
                });

                outcome.assertNoErrors();
                assertThat(outcome.interleaved())
                        .as("round %d forces a lease failure", roundNumber)
                        .isTrue();
                outcome.assertAllDeployedFilesPresent();
                published.add(a);
                published.add(b);
            }

            Path actual = bare.checkout(BRANCH, tmp.resolve("actual"));
            MetadataAssertions.assertValidRepository(actual);
            assertThat(bare.commitCount(BRANCH)).isEqualTo(1);

            Path expected = SequentialReference.replay(
                    tmp.resolve("expected"), survivors(published, retention.keepSnapshots()));
            TreeDiff diff = TreeDiff.compare(expected, actual);
            assertThat(diff.isEmpty()).as(diff.report()).isTrue();
        }
    }

    /**
     * Retention keeps the newest N builds of each snapshot version, so the sequential reference only needs those
     * publications (the Resolver itself never deletes anything). Computed here, independent of the production code.
     */
    private static List<Step> survivors(List<Step> script, int keep) {
        Map<String, Integer> seen = new HashMap<>();
        List<Step> kept = new ArrayList<>();
        for (int i = script.size() - 1; i >= 0; i--) {
            Step step = script.get(i);
            if (seen.merge(step.coordinates(), 1, Integer::sum) <= keep) {
                kept.add(0, step);
            }
        }
        return kept;
    }
}
