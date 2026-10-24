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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.LeakScanner;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Encoded credentials in a path must not be copied into the scanner's own diagnostics. */
class R10EncodedFindingRedactionTest {
    @TempDir
    Path tmp;

    @Test
    void findingsDoNotEchoUrlEncodedTokens() throws Exception {
        String token = "review-canary+long/secret==";
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve(encoded + ".txt"), "clean");
        List<String> findings = new LeakScanner(token).scanTree(tmp);
        assertThat(findings).hasSize(1);
        assertThat(String.join("\n", findings)).doesNotContain(encoded);
    }

    @Test
    void findingsDoNotEchoBasicAuthInSourceNames() {
        String token = "review-canary-long-secret";
        String encoded = Base64.getEncoder()
                .encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
        List<String> findings = new LeakScanner(token).scanText("wire-" + encoded + ".log", token);
        assertThat(findings).hasSize(1);
        assertThat(String.join("\n", findings)).doesNotContain(encoded);
    }
}
