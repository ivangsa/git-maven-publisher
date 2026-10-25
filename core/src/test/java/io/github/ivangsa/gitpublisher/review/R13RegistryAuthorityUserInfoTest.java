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
