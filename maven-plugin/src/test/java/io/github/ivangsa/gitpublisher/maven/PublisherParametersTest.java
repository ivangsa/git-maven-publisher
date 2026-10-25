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

import io.github.ivangsa.gitpublisher.ChecksumAlgorithm;
import io.github.ivangsa.gitpublisher.Credentials;
import io.github.ivangsa.gitpublisher.PublishConfig;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

class PublisherParametersTest {

    private static final Path WORK = Path.of("target", "work").toAbsolutePath();

    private static PublisherParameters params(String repository) {
        return new PublisherParameters(
                repository,
                "gh-pages",
                false,
                null,
                3,
                -1,
                "SHA-1,MD5",
                5,
                WORK,
                false,
                PublisherParameters.DEFAULT_COMMIT_AUTHOR);
    }

    @Test
    void buildsAPublishConfigFromTheParameters() throws Exception {
        PublisherParameters p = new PublisherParameters(
                "https://github.com/acme/snapshots.git",
                "snapshots",
                true,
                "my-server",
                7,
                -1,
                "SHA-256, sha-1",
                2,
                WORK,
                true,
                "Jane Doe <jane@example.org>");

        PublishConfig config = p.toConfig(Credentials.none());

        assertThat(config.repository().toString()).isEqualTo("https://github.com/acme/snapshots.git");
        assertThat(config.branch()).isEqualTo("snapshots");
        assertThat(config.nojekyll()).isTrue();
        assertThat(config.dryRun()).isTrue();
        assertThat(config.retention().keepSnapshots()).isEqualTo(7);
        assertThat(config.retry().maxAttempts()).isEqualTo(2);
        assertThat(config.checksumAlgorithms()).containsExactlyInAnyOrder(ChecksumAlgorithm.SHA256, ChecksumAlgorithm.SHA1);
        assertThat(config.author().getName()).isEqualTo("Jane Doe");
        assertThat(config.author().getEmailAddress()).isEqualTo("jane@example.org");
        assertThat(config.workDirectory()).isEqualTo(WORK);
    }

    @Test
    void theDefaultCommitAuthorIsTheDocumentedOne() throws Exception {
        PublishConfig config = params("file:///tmp/remote.git").toConfig(Credentials.none());

        // Name and email are the documented identity. PersonIdent.equals also compares the creation instant, so
        // comparing whole objects built at two different moments is flaky (D-077).
        assertThat(config.author().getName()).isEqualTo(PublishConfig.defaultAuthor().getName());
        assertThat(config.author().getEmailAddress()).isEqualTo(PublishConfig.defaultAuthor().getEmailAddress());
    }

    @Test
    void aMissingRepositoryIsAConfigurationError() {
        assertThatThrownBy(() -> params(null).toConfig(Credentials.none()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("git-publisher.repository");
        assertThatThrownBy(() -> params("  ").toConfig(Credentials.none())).isInstanceOf(MojoFailureException.class);
    }

    @Test
    void invalidValuesAreConfigurationErrorsThatDoNotEchoTheUrl() {
        assertThatThrownBy(() -> params("ssh://git@github.com/acme/r.git").toConfig(Credentials.none()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("https or file");
        assertThatThrownBy(() -> params("https://user:s3cr3t@github.com/acme/r.git").toConfig(Credentials.none()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageNotContaining("s3cr3t");
        assertThatThrownBy(() -> params("https://exa mple:s3cr3t@host/r.git").toConfig(Credentials.none()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageNotContaining("s3cr3t");
    }

    @Test
    void invalidChecksumAlgorithmsAndAuthorsAreRejected() {
        PublisherParameters base = params("file:///tmp/remote.git");
        assertThatThrownBy(() -> base.withChecksumAlgorithms("SHA-1,CRC32").toConfig(Credentials.none()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("CRC32");
        assertThatThrownBy(() -> base.withChecksumAlgorithms(" , ").toConfig(Credentials.none()))
                .isInstanceOf(MojoFailureException.class);
        assertThatThrownBy(() -> base.withCommitAuthor("no email here").toConfig(Credentials.none()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("commitAuthor");
    }

    @Test
    void outOfRangeNumbersAreRejected() {
        PublisherParameters base = params("file:///tmp/remote.git");
        assertThatThrownBy(() -> base.withKeepSnapshots(0).toConfig(Credentials.none()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("keepSnapshots");
        assertThatThrownBy(() -> base.withMaxAttempts(0).toConfig(Credentials.none()))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void comparesTransactionSettingsByNameIgnoringSpelling() {
        PublisherParameters a = params("https://github.com/acme/r.git");

        assertThat(a.differencesFrom(params("https://github.com/acme/r.git").withChecksumAlgorithms("MD5, SHA-1")))
                .isEmpty();
        assertThat(a.differencesFrom(params("https://github.com/acme/other.git")))
                .isEqualTo(List.of("repository"));
        assertThat(a.differencesFrom(a.withBranch("x").withServerId("s")))
                .containsExactlyInAnyOrder("branch", "serverId");
    }
}
