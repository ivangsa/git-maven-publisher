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
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-24-f9bb6b5, F2. Design section 4 step 3.vi retries a lease failure and a transport
 * {@code IOException}. A push the remote itself refuses for good (here a real JGit receive-pack with
 * {@code receive.denyNonFastForwards}, as a protected branch on GitHub refuses the forced parentless push) is neither:
 * {@link GitRemote#push} turns it into an {@code IOException}, the loop retries it {@code maxAttempts} times and the
 * final message calls it a "transport error".
 */
class R16PermanentPushRejectionTest {

    private static final String BRANCH = "gh-pages";

    @TempDir
    Path tmp;

    private static final class RecordingLog implements PublisherLog {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void debug(String message) {}
    }

    private PublishConfig config(BareRepo bare) {
        return new PublishConfig(
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
    }

    @Test
    void aPushTheRemoteRefusesForGoodIsNotRetriedAsATransportError() throws Exception {
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            try (Publication first = new GitPublisher(config(bare), PublisherLog.silent()).checkout()) {
                Files.writeString(first.repositoryDirectory().resolve("a.txt"), "a");
                assertThat(first.publish(new PublishInfo(List.of(), Optional.empty())).pushed()).isTrue();
            }
            StoredConfig remoteConfig = bare.repository().getConfig();
            remoteConfig.setBoolean("receive", null, "denyNonFastForwards", true);
            remoteConfig.save();

            RecordingLog log = new RecordingLog();
            try (Publication second = new GitPublisher(config(bare), log).checkout()) {
                Files.writeString(second.repositoryDirectory().resolve("b.txt"), "b");

                assertThatThrownBy(() -> second.publish(new PublishInfo(List.of(), Optional.empty())))
                        .isInstanceOf(PublishException.class)
                        .hasMessageContaining("non-fast forward")
                        .hasMessageNotContaining("transport error");
                assertThat(log.warnings).as("retries of a permanent rejection").isEmpty();
            }
            assertThat(bare.readTree(BRANCH)).containsOnlyKeys("a.txt");
        }
    }
}
