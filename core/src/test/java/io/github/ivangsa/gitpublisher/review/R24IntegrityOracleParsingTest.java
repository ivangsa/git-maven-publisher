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

import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Design section 5 requires metadata that the Maven oracle parser accepts, not merely well-formed XML. */
class R24IntegrityOracleParsingTest {
    @TempDir
    Path root;

    @Test
    void duplicateKnownElementsRejectedByMavenMustPreventPublication() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(root)) {
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
        }
        Path metadata = root.resolve("org/acme/lib/maven-metadata.xml");
        String original = Files.readString(metadata);
        Files.writeString(metadata, original.replace("<artifactId>lib</artifactId>",
                "<artifactId>lib</artifactId><artifactId>other</artifactId>"));
        ChecksumWriter.write(metadata, PublishConfig.DEFAULT_CHECKSUMS);

        assertThat(MetadataAssertions.violations(root, java.util.Set.of())).anyMatch(v -> v.contains("does not parse"));
        assertThatThrownBy(() -> IntegrityCheck.verify(root, PublishConfig.DEFAULT_CHECKSUMS))
                .isInstanceOf(PublishException.class)
                .hasMessageContaining("maven-metadata.xml");
    }
}
