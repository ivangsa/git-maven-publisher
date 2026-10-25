/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-24-f65192f, R02. Design section 9 says the Resolver oracle "produces artifacts and metadata exactly
 * like mvn deploy". Maven 3.10 puts {@code maven.startTime} (the session start) into the Resolver session
 * ({@code DefaultRepositorySystemSessionFactory} in maven-core 3.10.0), and {@code RemoteSnapshotMetadataGenerator}
 * stamps every snapshot of that session with it. So in a real build the timestamp is when the build started, not
 * when it deployed, and an earlier-started build that deploys later gets an older timestamp with a higher build
 * number. A {@link ResolverDeployer} holds one session, so it must behave the same: every snapshot it deploys carries
 * the same timestamp. Today it stamps the wall clock of each deploy, and the harness can never produce the
 * out-of-order timestamps real concurrent CI builds produce.
 */
class R02ResolverDeployerSessionStartTimeTest {

    private static final Pattern TIMESTAMP = Pattern.compile("<timestamp>([0-9.]+)</timestamp>");

    @TempDir
    Path root;

    @Test
    void oneDeployerStampsEverySnapshotWithItsSessionStartTime() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(root)) {
            deployer.deployJavaProject("org.acme", "first", "1.0-SNAPSHOT");
            Thread.sleep(1500); // snapshot timestamps have second resolution
            deployer.deployJavaProject("org.acme", "second", "1.0-SNAPSHOT");
        }
        assertThat(timestamp("second")).isEqualTo(timestamp("first"));
    }

    private String timestamp(String artifactId) throws Exception {
        String xml = Files.readString(root.resolve("org/acme/" + artifactId + "/1.0-SNAPSHOT/maven-metadata.xml"));
        Matcher m = TIMESTAMP.matcher(xml);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
