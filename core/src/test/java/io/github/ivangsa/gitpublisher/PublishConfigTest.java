/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;

class PublishConfigTest {

    private static final URI HTTPS = URI.create("https://github.com/acme/snapshots.git");
    private static final Path WORK = Path.of("target", "git-publisher");

    @Test
    void defaults() {
        PublishConfig config = PublishConfig.of(HTTPS, WORK);

        assertThat(config.repository()).isEqualTo(HTTPS);
        assertThat(config.branch()).isEqualTo("gh-pages");
        assertThat(config.credentials()).isEqualTo(Credentials.none());
        assertThat(config.workDirectory()).isEqualTo(WORK);
        assertThat(config.retention()).isEqualTo(new RetentionPolicy(3, -1));
        assertThat(config.retry()).isEqualTo(new RetryPolicy(5, Duration.ofMillis(500), Duration.ofSeconds(8)));
        assertThat(config.author().getName()).isEqualTo("git-maven-publisher");
        assertThat(config.author().getEmailAddress()).isEqualTo("git-maven-publisher@users.noreply.github.com");
        assertThat(config.dryRun()).isFalse();
        assertThat(config.nojekyll()).isFalse();
        assertThat(config.checksumAlgorithms())
                .containsExactlyInAnyOrder(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.MD5);
    }

    @Test
    void checksumAlgorithmsAreValidatedAndCopied() {
        PublishConfig base = PublishConfig.of(HTTPS, WORK);
        assertThatThrownBy(() -> withChecksums(base, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withChecksums(base, null)).isInstanceOf(NullPointerException.class);
        Set<ChecksumAlgorithm> mutable = new HashSet<>(Set.of(ChecksumAlgorithm.SHA512));
        PublishConfig config = withChecksums(base, mutable);
        mutable.add(ChecksumAlgorithm.MD5);
        assertThat(config.checksumAlgorithms()).containsExactly(ChecksumAlgorithm.SHA512);
        assertThatThrownBy(() -> config.checksumAlgorithms().add(ChecksumAlgorithm.MD5))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static PublishConfig withChecksums(PublishConfig c, Set<ChecksumAlgorithm> algorithms) {
        return new PublishConfig(
                c.repository(),
                c.branch(),
                c.credentials(),
                c.workDirectory(),
                c.retention(),
                c.retry(),
                c.author(),
                c.dryRun(),
                c.nojekyll(),
                algorithms);
    }

    @Test
    void acceptsHttpsAndFileSchemes() {
        assertThat(PublishConfig.of(URI.create("file:///tmp/bare.git"), WORK).repository().getScheme())
                .isEqualTo("file");
        assertThat(PublishConfig.of(URI.create("HTTPS://github.com/acme/x.git"), WORK)
                        .repository())
                .isNotNull();
    }

    @Test
    void rejectsOtherSchemes() {
        for (String url : new String[] {
            "http://github.com/acme/x.git",
            "ssh://git@github.com/acme/x.git",
            "git://github.com/acme/x.git",
            "relative/path.git"
        }) {
            assertThatThrownBy(() -> PublishConfig.of(URI.create(url), WORK))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("https")
                    .hasMessageContaining("file");
        }
    }

    @Test
    void rejectsUrlsWithUserinfoWithoutEchoingIt() {
        for (String url : new String[] {
            "https://user:s3cr3t-token@github.com/acme/x.git",
            "https://ghp_s3cr3t-token@github.com/acme/x.git",
            "file://user:s3cr3t-token@localhost/tmp/x.git"
        }) {
            assertThatThrownBy(() -> PublishConfig.of(URI.create(url), WORK))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("credentials")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("s3cr3t").doesNotContain("user"));
        }
    }

    @Test
    void rejectsSchemeErrorsWithoutEchoingUserinfo() {
        assertThatThrownBy(() -> PublishConfig.of(URI.create("ssh://git:s3cr3t@github.com/x.git"), WORK))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("s3cr3t"));
    }

    @Test
    void rejectsInvalidBranchNamesAndMissingValues() {
        PublishConfig base = PublishConfig.of(HTTPS, WORK);
        for (String branch : new String[] {"", " ", "a b", "x..y", "-lead", "end.lock", "refs/heads/"}) {
            assertThatThrownBy(() -> withBranch(base, branch)).as(branch).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> withBranch(base, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PublishConfig.of(null, WORK)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PublishConfig.of(HTTPS, null)).isInstanceOf(NullPointerException.class);
        assertThat(withBranch(base, "snapshots/main").branch()).isEqualTo("snapshots/main");
    }

    private static PublishConfig withBranch(PublishConfig c, String branch) {
        return new PublishConfig(
                c.repository(),
                branch,
                c.credentials(),
                c.workDirectory(),
                c.retention(),
                c.retry(),
                c.author(),
                c.dryRun(),
                c.nojekyll(),
                c.checksumAlgorithms());
    }

    @Test
    void nojekyllAndDryRunAreCarried() {
        PublishConfig base = PublishConfig.of(HTTPS, WORK);
        PublishConfig config = new PublishConfig(
                base.repository(),
                base.branch(),
                base.credentials(),
                base.workDirectory(),
                base.retention(),
                base.retry(),
                new PersonIdent("Bot", "bot@example.org"),
                true,
                true,
                Set.of(ChecksumAlgorithm.SHA256));
        assertThat(config.nojekyll()).isTrue();
        assertThat(config.dryRun()).isTrue();
        assertThat(config.author().getName()).isEqualTo("Bot");
    }

    @Test
    void retentionDefaultsAndValidation() {
        assertThat(RetentionPolicy.defaults()).isEqualTo(new RetentionPolicy(3, -1));
        assertThat(new RetentionPolicy(1, 5).keepVersions()).isEqualTo(5);
        assertThatThrownBy(() -> new RetentionPolicy(0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(-3, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(3, -2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryDefaultsAndValidation() {
        RetryPolicy defaults = RetryPolicy.defaults();
        assertThat(defaults.maxAttempts()).isEqualTo(5);
        assertThat(defaults.baseDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(defaults.maxDelay()).isEqualTo(Duration.ofSeconds(8));
        assertThat(new RetryPolicy(1, Duration.ZERO, Duration.ZERO).maxAttempts()).isEqualTo(1);
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMillis(-1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void tokenToStringDoesNotContainTheToken() {
        Credentials.Token token = new Credentials.Token("x-access-token", "ghp_s3cr3t".toCharArray());

        assertThat(token.toString()).doesNotContain("s3cr3t").doesNotContain("ghp_").contains("x-access-token");
        assertThat(Credentials.token("ghp_s3cr3t".toCharArray()).toString()).doesNotContain("s3cr3t");
        PublishConfig withToken = new PublishConfig(
                HTTPS,
                "gh-pages",
                token,
                WORK,
                RetentionPolicy.defaults(),
                RetryPolicy.defaults(),
                PublishConfig.defaultAuthor(),
                false,
                false,
                PublishConfig.DEFAULT_CHECKSUMS);
        assertThat(withToken.toString()).doesNotContain("s3cr3t");
    }

    @Test
    void tokenDefaultUsernameValidationAndCopies() {
        char[] chars = "abc".toCharArray();
        Credentials.Token token = Credentials.token(chars);
        assertThat(token.username()).isEqualTo("x-access-token");

        chars[0] = 'z';
        assertThat(token.token()).containsExactly('a', 'b', 'c');
        token.token()[0] = 'z';
        assertThat(token.token()).containsExactly('a', 'b', 'c');

        assertThat(token).isEqualTo(Credentials.token("abc".toCharArray()));
        assertThat(token.hashCode()).isEqualTo(Credentials.token("abc".toCharArray()).hashCode());
        assertThat(token).isNotEqualTo(Credentials.token("abd".toCharArray()));

        assertThatThrownBy(() -> new Credentials.Token("", "abc".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Credentials.Token("u", new char[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Credentials.Token("u", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void tokenCanBeCleared() {
        Credentials.Token token = Credentials.token("abc".toCharArray());
        token.clear();
        assertThat(token.token()).containsExactly('\0', '\0', '\0');
    }
}
