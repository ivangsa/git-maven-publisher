/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.junit.jupiter.api.Test;

/**
 * Parses every golden file with an oracle that we do not control:
 * Maven's own metadata reader for {@code maven-metadata.xml}, Jackson for Gradle module metadata,
 * and the JDK XML parser for POMs. See docs/design.md section 9.
 */
class GoldenFixturesTest {

    private static final String ROOT = "golden/";
    private static final String KOTLIN_VERSION = "0.8.0-0.6.x-compat";

    private static final String G_LEVEL = "org/apache/maven/plugins/maven-metadata.xml";

    private static final List<String> METADATA = List.of(
            "org/apache/commons/commons-lang3/maven-metadata.xml",
            G_LEVEL,
            "org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml",
            "org/apache/maven/resolver/maven-resolver-api/2.0.25-SNAPSHOT/maven-metadata.xml");

    private static final List<String> KMP_MODULES = List.of(
            "org/jetbrains/kotlinx/kotlinx-datetime/" + KOTLIN_VERSION + "/kotlinx-datetime-" + KOTLIN_VERSION + ".module",
            "org/jetbrains/kotlinx/kotlinx-datetime-jvm/" + KOTLIN_VERSION + "/kotlinx-datetime-jvm-" + KOTLIN_VERSION + ".module",
            "org/jetbrains/kotlinx/kotlinx-datetime-js/" + KOTLIN_VERSION + "/kotlinx-datetime-js-" + KOTLIN_VERSION + ".module",
            "org/jetbrains/kotlinx/kotlinx-datetime-linuxx64/" + KOTLIN_VERSION + "/kotlinx-datetime-linuxx64-" + KOTLIN_VERSION + ".module");

    private static final List<String> KMP_POMS = KMP_MODULES.stream()
            .map(path -> path.substring(0, path.length() - ".module".length()) + ".pom")
            .toList();

    @Test
    void everyGoldenFileIsListedAndNothingElseIsThere() throws IOException, URISyntaxException {
        Path root = Paths.get(getClass().getClassLoader().getResource(ROOT.substring(0, ROOT.length() - 1)).toURI());
        try (Stream<Path> files = Files.walk(root)) {
            Set<String> actual = files
                    .filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.equals("README.md"))
                    .collect(Collectors.toSet());
            Set<String> expected = Stream.of(METADATA, KMP_MODULES, KMP_POMS)
                    .flatMap(List::stream)
                    .collect(Collectors.toSet());
            assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    void metadataFilesParseWithMavenReader() throws Exception {
        MetadataXpp3Reader reader = new MetadataXpp3Reader();
        for (String path : METADATA) {
            try (Reader in = reader(path)) {
                Metadata metadata = reader.read(in, true);
                if (path.equals(G_LEVEL)) {
                    // G-level metadata lists plugins and has no coordinates.
                    assertThat(metadata.getPlugins()).as(path).isNotEmpty();
                } else {
                    assertThat(metadata.getGroupId()).as(path).isNotBlank();
                    assertThat(metadata.getArtifactId()).as(path).isNotBlank();
                }
            }
        }
    }

    @Test
    void commonsLangMetadataHasExpectedCoordinates() throws Exception {
        Metadata metadata;
        try (Reader in = reader("org/apache/commons/commons-lang3/maven-metadata.xml")) {
            metadata = new MetadataXpp3Reader().read(in, true);
        }
        assertThat(metadata.getGroupId()).isEqualTo("org.apache.commons");
        assertThat(metadata.getArtifactId()).isEqualTo("commons-lang3");
        assertThat(metadata.getVersioning().getVersions()).contains("3.17.0");
    }

    @Test
    void kmpModuleFilesParseAsJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String path : KMP_MODULES) {
            try (InputStream in = open(path)) {
                JsonNode module = mapper.readTree(in);
                assertThat(module.path("formatVersion").asText()).as(path).isNotBlank();
                assertThat(module.path("component").path("version").asText()).as(path).isEqualTo(KOTLIN_VERSION);
                assertThat(module.path("variants").isArray()).as(path).isTrue();
            }
        }
    }

    @Test
    void kmpPomFilesAreWellFormedXml() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        for (String path : KMP_POMS) {
            try (InputStream in = open(path)) {
                assertThat(factory.newDocumentBuilder().parse(in).getDocumentElement().getTagName())
                        .as(path)
                        .isEqualTo("project");
            }
        }
    }

    private static Reader reader(String path) {
        return new InputStreamReader(open(path), StandardCharsets.UTF_8);
    }

    private static InputStream open(String path) {
        InputStream in = GoldenFixturesTest.class.getClassLoader().getResourceAsStream(ROOT + path);
        assertThat(in).as("missing golden file %s", path).isNotNull();
        return in;
    }
}
