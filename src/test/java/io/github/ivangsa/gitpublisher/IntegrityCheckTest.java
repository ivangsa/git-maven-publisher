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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production {@link IntegrityCheck} must agree with the harness {@code MetadataAssertions} (which parses with
 * Maven's own reader) on a valid Resolver tree and on one crafted broken tree per invariant.
 */
class IntegrityCheckTest {

    private static final String LIB = "org/acme/lib";
    private static final String A_META = LIB + "/maven-metadata.xml";
    private static final String V_META = LIB + "/1.0-SNAPSHOT/maven-metadata.xml";
    private static final Pattern PARSE_FAILURE = Pattern.compile("does not parse[^\\n]*");

    private static final List<Set<String>> REQUIRED_SETS = List.of(Set.of(), Set.of("sha1", "md5"), Set.of("sha256"));

    @TempDir
    Path root;

    @FunctionalInterface
    interface Break {
        void apply(Path root) throws Exception;
    }

    private static final Map<String, Break> BROKEN = new LinkedHashMap<>();

    static {
        BROKEN.put("valid", r -> {});
        BROKEN.put("snapshotVersionPointsAtMissingFile", r -> {
            for (String ext : List.of("", ".sha1", ".md5")) {
                try (Stream<Path> files = Files.list(r.resolve(LIB + "/1.0-SNAPSHOT"))) {
                    for (Path p : files.filter(p -> p.getFileName().toString().endsWith("-sources.jar" + ext))
                            .toList()) {
                        Files.delete(p);
                    }
                }
            }
        });
        BROKEN.put("versionWithoutDirectory", r -> deleteTree(r.resolve(LIB + "/2.0-SNAPSHOT")));
        BROKEN.put("versionWithEmptyDirectory", r -> {
            try (Stream<Path> files = Files.walk(r.resolve(LIB + "/2.0-SNAPSHOT"))) {
                for (Path p : files.filter(Files::isRegularFile).toList()) {
                    Files.delete(p);
                }
            }
        });
        BROKEN.put("latestNotInVersions", r -> edit(r, A_META, "<versions>", "<latest>9.9</latest><versions>"));
        BROKEN.put("releaseNotInVersions", r -> edit(r, A_META, "<versions>", "<release>2.0</release><versions>"));
        BROKEN.put("malformedXml", r -> edit(r, A_META, "</metadata>", ""));
        BROKEN.put("checksumMismatch", r -> Files.writeString(r.resolve(V_META + ".sha1"), "00"));
        BROKEN.put("metadataWithoutChecksum", r -> {
            Files.delete(r.resolve(A_META + ".sha1"));
            Files.delete(r.resolve(A_META + ".md5"));
        });
        BROKEN.put("checksumOfMissingFile", r -> {
            try (Stream<Path> files = Files.list(r.resolve(LIB + "/1.0-SNAPSHOT"))) {
                Path jar = files.filter(p -> p.getFileName().toString().endsWith("-3.jar"))
                        .findFirst()
                        .orElseThrow();
                Files.delete(jar);
            }
        });
        BROKEN.put("versionDirectoryNameMismatch", r -> edit(r, V_META, "<version>1.0-SNAPSHOT</version>", "<version>1.1-SNAPSHOT</version>"));
        BROKEN.put("artifactDirectoryNameMismatch", r -> edit(r, A_META, "<artifactId>lib</artifactId>", "<artifactId>other</artifactId>"));
        BROKEN.put("snapshotNamesDeletedBuild", r -> {
            try (Stream<Path> files = Files.list(r.resolve(LIB + "/1.0-SNAPSHOT"))) {
                for (Path p : files.filter(p -> p.getFileName().toString().contains("-3.")
                                || p.getFileName().toString().contains("-3-"))
                        .toList()) {
                    Files.delete(p);
                }
            }
            Path metadata = r.resolve(V_META);
            String xml = Files.readString(metadata)
                    .replaceAll("(?s)\\s*<snapshotVersion>(?:(?!</snapshotVersion>).)*</snapshotVersion>", "");
            Files.writeString(metadata, xml);
            ChecksumWriter.write(metadata, Set.of());
        });
    }

    @Test
    void agreesWithMetadataAssertionsOnEveryTree() throws Exception {
        for (Map.Entry<String, Break> scenario : BROKEN.entrySet()) {
            Path tree = Files.createTempDirectory(root, scenario.getKey());
            validResolverTree(tree);
            scenario.getValue().apply(tree);

            for (Set<String> required : REQUIRED_SETS) {
                List<String> expected = normalize(MetadataAssertions.violations(tree, required));
                List<String> actual = normalize(IntegrityCheck.violations(tree, algorithms(required)));

                assertThat(actual)
                        .as("%s with required checksums %s", scenario.getKey(), required)
                        .isEqualTo(expected);
                boolean expectedValid = scenario.getKey().equals("valid") && !required.contains("sha256");
                assertThat(actual.isEmpty()).as("%s %s", scenario.getKey(), required).isEqualTo(expectedValid);
            }
        }
    }

    @Test
    void verifyPassesOnAValidTreeAndListsEveryViolationOtherwise() throws Exception {
        validResolverTree(root);
        assertThatCode(() -> IntegrityCheck.verify(root, algorithms(Set.of("sha1", "md5"))))
                .doesNotThrowAnyException();

        Files.writeString(root.resolve(V_META + ".sha1"), "00");
        edit(root, A_META, "<versions>", "<latest>9.9</latest><versions>");

        assertThatThrownBy(() -> IntegrityCheck.verify(root, algorithms(Set.of("sha1", "md5"))))
                .isInstanceOf(PublishException.class)
                .hasMessageContaining("2 violation(s)")
                .hasMessageContaining(V_META + ".sha1: checksum mismatch")
                .hasMessageContaining(A_META + ": <latest> 9.9 is not in <versions>")
                .hasMessageContaining("nothing was pushed");
    }

    @Test
    void namesTheBrokenInvariantPrecisely() throws Exception {
        validResolverTree(root);
        BROKEN.get("snapshotNamesDeletedBuild").apply(root);

        assertThat(IntegrityCheck.violations(root, algorithms(Set.of("sha1"))))
                .singleElement()
                .asString()
                .startsWith(V_META + ": <snapshot> ")
                .endsWith("names a build that has no files in the directory");
    }

    /** Two versions, version 1.0 with three builds (jar, pom, sources) and 2.0 with one build and a Gradle module. */
    private static void validResolverTree(Path tree) {
        try (ResolverDeployer deployer = new ResolverDeployer(tree, "SHA-1", "MD5")) {
            for (int i = 0; i < 3; i++) {
                deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
            }
            deployer.deploy(
                    "org.acme",
                    "lib",
                    "2.0-SNAPSHOT",
                    ResolverDeployer.Part.of("", "jar", "jar"),
                    ResolverDeployer.Part.of("", "pom", ResolverDeployer.pom("org.acme", "lib", "2.0-SNAPSHOT")),
                    ResolverDeployer.Part.of("", "module", "{\"formatVersion\":\"1.1\"}"));
        }
    }

    private static Set<ChecksumAlgorithm> algorithms(Set<String> extensions) {
        return extensions.stream()
                .map(e -> ChecksumAlgorithm.fromExtension(e).orElseThrow())
                .collect(Collectors.toSet());
    }

    /** The parse failure text differs by parser; everything else must be identical. */
    private static List<String> normalize(List<String> violations) {
        return violations.stream()
                .map(v -> PARSE_FAILURE.matcher(v).replaceAll("does not parse"))
                .map(v -> v.replace("does not parse with maven-repository-metadata", "does not parse"))
                .sorted()
                .toList();
    }

    private static void edit(Path tree, String rel, String from, String to) throws IOException {
        Path file = tree.resolve(rel);
        String xml = Files.readString(file);
        assertThat(xml).as("%s contains %s", rel, from).contains(from);
        Files.writeString(file, xml.replace(from, to));
        ChecksumWriter.write(file, Set.of());
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
