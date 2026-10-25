/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.review;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.PublishConfig;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Review 2026-10-24-9150059 F3: {@code PublishConfig} checks {@code URI.getRawUserInfo()}, which is null when the
 * authority is registry-based (a host name {@code java.net.URI} cannot parse, for example with an underscore). A
 * {@code file} URL with credentials then passes; design section 8 says URLs with userinfo are rejected.
 */
class R13RegistryAuthorityUserInfoTest {

    @ParameterizedTest
    @ValueSource(strings = {"file://user:secret@my_host/repo.git", "file://secret@my_host/repo.git"})
    void fileUrlWithCredentialsInARegistryBasedAuthorityIsRejected(String url) {
        assertThatThrownBy(() -> PublishConfig.of(URI.create(url), Path.of("work")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain credentials")
                .hasMessageNotContaining("secret");
    }

    @Test
    void httpsVariantIsAlreadyRejectedWithoutEchoingTheSecret() {
        assertThatThrownBy(() -> PublishConfig.of(URI.create("https://user:secret@my_host/x.git"), Path.of("work")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
    }
}
