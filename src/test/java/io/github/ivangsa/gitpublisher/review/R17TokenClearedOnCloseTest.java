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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.Credentials;
import io.github.ivangsa.gitpublisher.GitPublisher;
import io.github.ivangsa.gitpublisher.Publication;
import io.github.ivangsa.gitpublisher.PublishConfig;
import io.github.ivangsa.gitpublisher.PublisherLog;
import io.github.ivangsa.gitpublisher.RetentionPolicy;
import io.github.ivangsa.gitpublisher.RetryPolicy;
import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-24-f9bb6b5, F3. Design section 8: "Token held as char[], wrapped in
 * UsernamePasswordCredentialsProvider per operation, cleared on close()". {@code GitPublication.close()} only closes
 * the object store; the token survives the publication.
 */
class R17TokenClearedOnCloseTest {

    @TempDir
    Path tmp;

    @Test
    void closingThePublicationClearsTheToken() throws Exception {
        Credentials.Token token = Credentials.token("s3cr3t".toCharArray());
        try (BareRepo bare = BareRepo.create(tmp.resolve("remote.git"))) {
            PublishConfig config = new PublishConfig(
                    bare.uri(),
                    "gh-pages",
                    token,
                    tmp.resolve("work"),
                    RetentionPolicy.defaults(),
                    RetryPolicy.defaults(),
                    PublishConfig.defaultAuthor(),
                    false,
                    false,
                    PublishConfig.DEFAULT_CHECKSUMS);
            Publication publication = new GitPublisher(config, PublisherLog.silent()).checkout();
            publication.close();
        }

        assertThat(new String(token.token())).as("token after Publication.close()").isEqualTo("\0".repeat(6));
    }
}
