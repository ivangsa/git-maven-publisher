/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
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
