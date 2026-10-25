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
// Lives in review/ but in the core package: Retention, MetadataModel and IntegrityCheck are package-private.
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer.Part;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review 2026-10-24-9150059 F1: {@code XmlNode} reads attributes with {@code getAttributeLocalName}, so a rewrite
 * turns {@code xmlns:xsi} into {@code xsi} and {@code xsi:schemaLocation} into {@code schemaLocation}. Maven's strict
 * reader (the oracle of design section 5) accepts the input and rejects the rewritten file, while
 * {@link IntegrityCheck} still passes it.
 */
class R11NamespacedMetadataRewriteTest {

    private static final Path V_DIR = Path.of("org/acme/lib/1.0-SNAPSHOT");
    private static final String NAMESPACED_ROOT = "<metadata xmlns=\"http://maven.apache.org/METADATA/1.1.0\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " xsi:schemaLocation=\"http://maven.apache.org/METADATA/1.1.0"
            + " https://maven.apache.org/xsd/repository-metadata-1.1.0.xsd\" modelVersion=\"1.1.0\">";

    @TempDir
    Path root;

    @Test
    void retentionRewriteOfNamespacedMetadataStillParsesWithMavensReader() throws Exception {
        try (ResolverDeployer deployer = new ResolverDeployer(root, "SHA-1", "MD5")) {
            deployer.deploy("org.acme", "lib", "1.0-SNAPSHOT", Part.of("", "jar", "jar"), Part.of("javadoc", "jar", "javadoc"));
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
            deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
        }
        Path metadata = root.resolve(V_DIR).resolve("maven-metadata.xml");
        String xml = Files.readString(metadata, StandardCharsets.UTF_8).replaceFirst("<metadata[^>]*>", NAMESPACED_ROOT);
        Files.writeString(metadata, xml, StandardCharsets.UTF_8);
        ChecksumWriter.write(metadata, Set.of());
        Set<String> checksums = Set.of("sha1", "md5");
        Set<ChecksumAlgorithm> algorithms = Set.of(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.MD5);
        assertThat(MetadataAssertions.violations(root, checksums)).as("namespaced input is valid").isEmpty();

        Retention.apply(root, List.of(V_DIR), new RetentionPolicy(1, -1), algorithms);

        assertThat(MetadataModel.read(metadata).snapshotVersions())
                .as("the javadoc entry of the deleted build was dropped, so the file was rewritten")
                .noneMatch(sv -> sv.classifier().equals("javadoc"));
        assertThat(MetadataAssertions.violations(root, checksums))
                .as("rewritten metadata must still parse with Maven's strict reader")
                .isEmpty();
    }

    @Test
    void rewriteKeepsPrefixedAttributeNames() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + NAMESPACED_ROOT
                + "<groupId>g</groupId><artifactId>a</artifactId><versioning><versions><version>1</version>"
                + "</versions></versioning></metadata>";

        String out = new String(MetadataModel.read(xml.getBytes(StandardCharsets.UTF_8)).toBytes(), StandardCharsets.UTF_8);

        assertThat(out).contains("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
                .contains("xsi:schemaLocation=\"");
    }
}
