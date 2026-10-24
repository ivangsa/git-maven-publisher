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
package io.github.ivangsa.gitpublisher.fixtures.review;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.LeakScanner;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Review 2026-10-24-f65192f, R05. The token reaches JGit as HTTP basic credentials ({@code x-access-token:<token>},
 * design section 8). A debug or wire log of that request carries {@code Authorization: Basic <base64>}, from which
 * the token is recovered with one decode. The scanner only looks for the raw canary, so this leak passes T24/T28/T38.
 */
class R05LeakScannerBasicAuthTest {

    private static final String CANARY = "gmp-canary-5e1f2a7b9c-token";

    @Test
    void basicAuthorizationHeaderCarryingTheCanaryIsDetected() {
        String header = "Authorization: Basic "
                + Base64.getEncoder().encodeToString(("x-access-token:" + CANARY).getBytes(UTF_8));
        String log = "> GET /org/repo.git/info/refs?service=git-receive-pack HTTP/1.1\n> " + header + "\n";

        assertThat(new LeakScanner(CANARY).scanText("http wire log", log)).isNotEmpty();
    }
}
