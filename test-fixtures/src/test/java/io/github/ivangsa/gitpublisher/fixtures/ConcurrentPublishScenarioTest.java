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

import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConcurrentPublishScenarioTest {

    private static final int THREADS = 8;

    @TempDir
    Path tmp;

    @Test
    void brokenForcePushPublisherIsDetectedAsLosingFiles() throws Exception {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            CyclicBarrier allDeployed = new CyclicBarrier(THREADS);
            var scenario = new ConcurrentPublishScenario(
                    bare, "gh-pages", tmp.resolve("work"), ConcurrentPublishScenario.mixedSteps(THREADS));
            var outcome = scenario.run(ReferencePublishers.forcePushWithoutLease(() -> {
                try {
                    allDeployed.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));

            assertThat(outcome.errors()).isEmpty();
            assertThat(outcome.lostFiles()).isNotEmpty();
            assertThatThrownBy(outcome::assertAllDeployedFilesPresent)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("deployed files are missing from branch 'gh-pages'");
        }
    }

    @Test
    void globalLockPublisherLosesNothingAndMatchesSequentialReference() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var scenario = new ConcurrentPublishScenario(
                    bare, "gh-pages", tmp.resolve("work"), ConcurrentPublishScenario.mixedSteps(THREADS));
            var outcome = scenario.run(ReferencePublishers.globalLock());

            outcome.assertConsistent(tmp.resolve("checkout"));
            assertThat(outcome.lostFiles()).isEmpty();
            assertThat(outcome.deployedFileCount()).isGreaterThan(THREADS);
            assertThat(outcome.deployOrder()).hasSize(THREADS);
            assertThat(bare.commitCount("gh-pages")).isEqualTo(THREADS);
            outcome.assertMatchesSequentialReference(tmp.resolve("checkout-2"), tmp.resolve("reference"));
        }
    }

    @Test
    void publisherFailureIsReportedNotSwallowed() {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            var scenario = new ConcurrentPublishScenario(
                    bare, "gh-pages", tmp.resolve("work"), ConcurrentPublishScenario.mixedSteps(2));
            var outcome = scenario.run(publication -> {
                throw new IllegalStateException("boom");
            });
            assertThat(outcome.errors()).hasSize(2);
            assertThatThrownBy(outcome::assertNoErrors).isInstanceOf(AssertionError.class).hasMessageContaining("boom");
        }
    }

    @Test
    void mixedStepsContainSharedAndDistinctCoordinates() {
        var steps = ConcurrentPublishScenario.mixedSteps(THREADS);
        assertThat(steps).extracting(SequentialReference.Step::coordinates)
                .contains("org.acme:shared:1.0-SNAPSHOT", "org.acme:shared:2.0-SNAPSHOT", "org.acme:artifact-3:1.0-SNAPSHOT")
                .hasSize(THREADS);
        assertThat(steps.stream().map(SequentialReference.Step::coordinates).distinct().count())
                .isLessThan(THREADS);
    }
}
