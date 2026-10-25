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
package io.github.ivangsa.gitpublisher.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.maven.ReactorTransaction.Entry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

class ReactorTransactionTest {

    private static final PublisherParameters P = new PublisherParameters(
            "https://github.com/acme/snapshots.git",
            "gh-pages",
            false,
            null,
            3,
            -1,
            null,
            "SHA-1,MD5",
            5,
            Path.of("target", "work").toAbsolutePath(),
            false,
            PublisherParameters.DEFAULT_COMMIT_AUTHOR);

    private static Entry active(String project) {
        return new Entry(project, false, P);
    }

    private static Entry skipped(String project) {
        return new Entry(project, true, null);
    }

    @Test
    void theParticipantThatCompletesTheSetGetsEveryEntry() throws Exception {
        ReactorTransaction tx = new ReactorTransaction(Set.of("a", "b", "c"));

        assertThat(tx.checkIn(active("a"))).isEmpty();
        assertThat(tx.checkIn(active("b"))).isEmpty();
        Optional<List<Entry>> done = tx.checkIn(active("c"));

        assertThat(done).isPresent();
        assertThat(done.get()).extracting(Entry::project).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void everyExecutionOfARepeatedBindingMustCheckInBeforeTheSetCompletes() throws Exception {
        ReactorTransaction tx = new ReactorTransaction(Set.of("a@first", "a@second"));

        assertThat(tx.checkIn(new Entry("a", "first", false, P, List.of()))).isEmpty();
        Optional<List<Entry>> done = tx.checkIn(new Entry("a", "second", false, P, List.of()));

        assertThat(done).isPresent();
    }

    @Test
    void aSkippedParticipantStillCountsAndCanBeTheLastToCheckIn() throws Exception {
        ReactorTransaction tx = new ReactorTransaction(Set.of("a", "b"));

        assertThat(tx.checkIn(active("a"))).isEmpty();
        Optional<List<Entry>> done = tx.checkIn(skipped("b"));

        assertThat(done).isPresent();
        assertThat(done.get()).filteredOn(e -> !e.skipped()).extracting(Entry::project).containsExactly("a");
    }

    @Test
    void aParticipantThatNeverChecksInMeansTheSetNeverCompletes() throws Exception {
        ReactorTransaction tx = new ReactorTransaction(Set.of("a", "failed", "c"));

        assertThat(tx.checkIn(active("a"))).isEmpty();
        assertThat(tx.checkIn(active("c"))).isEmpty();
    }

    @Test
    void checkingInTwiceNeitherCompletesEarlyNorTwice() throws Exception {
        ReactorTransaction tx = new ReactorTransaction(Set.of("a", "b"));

        assertThat(tx.checkIn(active("a"))).isEmpty();
        assertThat(tx.checkIn(active("a"))).isEmpty();
        assertThat(tx.checkIn(active("b"))).isPresent();
        assertThat(tx.checkIn(active("b"))).as("already completed").isEmpty();
    }

    @Test
    void differentSettingsFailTheCheckInBeforeAnythingElse() throws Exception {
        ReactorTransaction tx = new ReactorTransaction(Set.of("a", "b"));
        tx.checkIn(active("a"));

        assertThatThrownBy(() -> tx.checkIn(new Entry("b", false, P.withBranch("other"))))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("b")
                .hasMessageContaining("a")
                .hasMessageContaining("branch");
    }

    @Test
    void skippedParticipantsDoNotContributeSettings() throws Exception {
        ReactorTransaction tx = new ReactorTransaction(Set.of("a", "b", "c"));

        tx.checkIn(skipped("a"));
        tx.checkIn(active("b"));
        assertThat(tx.checkIn(active("c"))).isPresent();
    }

    @Test
    void aProjectOutsideTheParticipantSetIsRejected() {
        ReactorTransaction tx = new ReactorTransaction(Set.of("a"));

        assertThatThrownBy(() -> tx.checkIn(active("stranger")))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("stranger");
    }

    @Test
    void exactlyOneOfManyConcurrentParticipantsCompletesTheSet() throws Exception {
        for (int round = 0; round < 50; round++) {
            int count = 16;
            Set<String> projects = new TreeSet<>();
            for (int i = 0; i < count; i++) {
                projects.add("p" + i);
            }
            ReactorTransaction tx = new ReactorTransaction(projects);
            ExecutorService pool = Executors.newFixedThreadPool(count);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger completions = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (String project : projects) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (tx.checkIn(active(project)).isPresent()) {
                        completions.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            pool.shutdown();

            assertThat(completions).hasValue(1);
        }
    }
}
