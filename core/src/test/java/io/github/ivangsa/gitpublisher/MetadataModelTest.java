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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataModelTest {

    /** Golden files belong to test-fixtures (D-075); core reads them from the sibling module's source tree. */
    private static final Path GOLDEN = Path.of("..", "test-fixtures", "src", "main", "resources", "golden");
    private static final Path A_LEVEL = GOLDEN.resolve("org/apache/commons/commons-lang3/maven-metadata.xml");
    private static final Path KMP_A_LEVEL = GOLDEN.resolve("org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml");
    private static final Path G_LEVEL = GOLDEN.resolve("org/apache/maven/plugins/maven-metadata.xml");
    private static final Path V_LEVEL =
            GOLDEN.resolve("org/apache/maven/resolver/maven-resolver-api/2.0.25-SNAPSHOT/maven-metadata.xml");

    @TempDir
    Path tmp;

    /** The oracle parses our output to the same model as the original file. */
    @Test
    void everyGoldenFileRoundTripsThroughTheOracle() throws Exception {
        for (Path golden : List.of(A_LEVEL, KMP_A_LEVEL, G_LEVEL, V_LEVEL)) {
            Path out = tmp.resolve(golden.getFileName().toString() + "." + golden.hashCode());
            MetadataModel.read(golden).write(out);

            assertThat(oracle(out)).as(golden.toString()).isEqualTo(oracle(golden));
        }
    }

    @Test
    void namespacedMetadataRoundTripsThroughTheOracle() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<metadata xmlns=\"http://maven.apache.org/METADATA/1.1.0\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                + " xsi:schemaLocation=\"http://maven.apache.org/METADATA/1.1.0"
                + " https://maven.apache.org/xsd/repository-metadata-1.1.0.xsd\" modelVersion=\"1.1.0\">"
                + "<groupId>g</groupId><artifactId>a</artifactId><versioning><versions><version>1</version>"
                + "</versions></versioning></metadata>";
        Path in = tmp.resolve("in.xml");
        Path out = tmp.resolve("out.xml");
        Files.writeString(in, xml, UTF_8);

        MetadataModel.read(in).write(out);

        assertThat(oracle(out)).isEqualTo(oracle(in));
    }

    @Test
    void writingIsStableAfterTheFirstWrite() throws Exception {
        for (Path golden : List.of(A_LEVEL, KMP_A_LEVEL, G_LEVEL, V_LEVEL)) {
            byte[] first = MetadataModel.read(golden).toBytes();
            byte[] second = MetadataModel.read(first).toBytes();
            assertThat(new String(second, UTF_8)).as(golden.toString()).isEqualTo(new String(first, UTF_8));
        }
    }

    @Test
    void readsArtifactLevelMetadata() throws Exception {
        MetadataModel model = MetadataModel.read(A_LEVEL);
        Metadata oracle = read(A_LEVEL);

        assertThat(model.level()).isEqualTo(MetadataModel.Level.ARTIFACT);
        assertThat(model.groupId()).contains("org.apache.commons");
        assertThat(model.artifactId()).contains("commons-lang3");
        assertThat(model.version()).isEmpty();
        assertThat(model.versions()).isNotEmpty().isEqualTo(oracle.getVersioning().getVersions());
        assertThat(model.latest()).contains(oracle.getVersioning().getLatest());
        assertThat(model.release()).contains(oracle.getVersioning().getRelease());
        assertThat(model.lastUpdated()).contains(oracle.getVersioning().getLastUpdated());
    }

    @Test
    void readsGroupLevelMetadata() throws Exception {
        MetadataModel model = MetadataModel.read(G_LEVEL);
        Metadata oracle = read(G_LEVEL);

        assertThat(model.level()).isEqualTo(MetadataModel.Level.GROUP);
        assertThat(model.plugins()).hasSameSizeAs(oracle.getPlugins());
        assertThat(model.plugins())
                .extracting(MetadataModel.Plugin::prefix)
                .containsExactlyElementsOf(oracle.getPlugins().stream()
                        .map(org.apache.maven.artifact.repository.metadata.Plugin::getPrefix)
                        .toList());
        assertThat(model.plugins()).extracting(MetadataModel.Plugin::artifactId).doesNotContainNull();
    }

    @Test
    void readsVersionLevelMetadata() throws Exception {
        MetadataModel model = MetadataModel.read(V_LEVEL);
        Metadata oracle = read(V_LEVEL);

        assertThat(model.level()).isEqualTo(MetadataModel.Level.VERSION);
        assertThat(model.version()).contains("2.0.25-SNAPSHOT");
        var snapshot = model.snapshot().orElseThrow();
        assertThat(snapshot.timestamp()).isEqualTo(oracle.getVersioning().getSnapshot().getTimestamp());
        assertThat(snapshot.buildNumber()).isEqualTo(oracle.getVersioning().getSnapshot().getBuildNumber());
        assertThat(model.snapshotVersions())
                .extracting(
                        MetadataModel.SnapshotVersion::classifier,
                        MetadataModel.SnapshotVersion::extension,
                        MetadataModel.SnapshotVersion::value)
                .containsExactlyElementsOf(oracle.getVersioning().getSnapshotVersions().stream()
                        .map(sv -> org.assertj.core.groups.Tuple.tuple(
                                sv.getClassifier() == null ? "" : sv.getClassifier(), sv.getExtension(), sv.getVersion()))
                        .toList());
    }

    @Test
    void unknownElementsAndAttributesArePreserved() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata modelVersion="1.1.0" custom="yes">
                  <groupId>org.acme</groupId>
                  <artifactId>lib</artifactId>
                  <extra><nested a="1">value &amp; more</nested></extra>
                  <versioning>
                    <future>kept</future>
                    <versions><version>1.0</version></versions>
                  </versioning>
                </metadata>
                """;
        MetadataModel model = MetadataModel.read(xml.getBytes(UTF_8));
        model.setLastUpdated("20261024090000");
        String out = model.toString();

        assertThat(out).contains("<metadata modelVersion=\"1.1.0\" custom=\"yes\">");
        assertThat(out).contains("<nested a=\"1\">value &amp; more</nested>");
        assertThat(out).contains("<future>kept</future>");
        assertThat(out).contains("<lastUpdated>20261024090000</lastUpdated>");
        assertThat(out).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(MetadataModel.read(out.getBytes(UTF_8)).toString()).isEqualTo(out);
    }

    @Test
    void removesSnapshotVersionsAndVersions() throws Exception {
        MetadataModel v = MetadataModel.read(V_LEVEL);
        int total = v.snapshotVersions().size();
        String firstExtension = v.snapshotVersions().get(0).extension();

        int removed = v.removeSnapshotVersionsIf(sv -> sv.extension().equals(firstExtension));

        assertThat(removed).isPositive();
        assertThat(v.snapshotVersions()).hasSize(total - removed);
        assertThat(v.snapshotVersions()).noneMatch(sv -> sv.extension().equals(firstExtension));
        Path out = tmp.resolve("v.xml");
        v.write(out);
        assertThat(read(out).getVersioning().getSnapshotVersions()).hasSize(total - removed);

        MetadataModel a = MetadataModel.read(A_LEVEL);
        String first = a.versions().get(0);
        assertThat(a.removeVersions(Set.of(first))).isEqualTo(1);
        assertThat(a.versions()).doesNotContain(first);
        a.setLatest("9.9");
        a.setRelease(null);
        assertThat(a.latest()).contains("9.9");
        assertThat(a.release()).isEmpty();
    }

    @Test
    void malformedXmlIsAnIoException() {
        assertThatThrownBy(() -> MetadataModel.read("<metadata><groupId>x</groupId>".getBytes(UTF_8)))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> MetadataModel.read("<other/>".getBytes(UTF_8))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> MetadataModel.read(new byte[0])).isInstanceOf(IOException.class);
    }

    @Test
    void externalEntitiesAreNotResolved() throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-CONTENT");
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE metadata [<!ENTITY xxe SYSTEM \"" + secret.toUri()
                + "\">]><metadata><groupId>&xxe;</groupId></metadata>";
        try {
            MetadataModel model = MetadataModel.read(xml.getBytes(UTF_8));
            assertThat(model.toString()).doesNotContain("TOP-SECRET-CONTENT");
        } catch (IOException expected) {
            assertThat(expected.getMessage()).doesNotContain("TOP-SECRET-CONTENT");
        }
    }

    private static Metadata read(Path file) throws Exception {
        try (var in = Files.newInputStream(file)) {
            return new MetadataXpp3Reader().read(in, true);
        }
    }

    private static String oracle(Path file) throws Exception {
        StringWriter out = new StringWriter();
        new MetadataXpp3Writer().write(out, read(file));
        return out.toString();
    }
}
