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

import io.github.ivangsa.gitpublisher.fixtures.ResolverDeployer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The oracle is Maven Resolver: our checksum files must be byte-identical to the ones it wrote. */
class ChecksumWriterTest {

    @TempDir
    Path repo;

    @Test
    void matchesResolverForDefaultAlgorithms() throws IOException {
        assertMatchesResolver(EnumSet.of(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.MD5), "SHA-1", "MD5");
    }

    @Test
    void matchesResolverForAllAlgorithms() throws IOException {
        assertMatchesResolver(EnumSet.allOf(ChecksumAlgorithm.class), "SHA-1", "MD5", "SHA-256", "SHA-512");
    }

    private void assertMatchesResolver(Set<ChecksumAlgorithm> configured, String... resolverNames) throws IOException {
        try (ResolverDeployer deployer = new ResolverDeployer(repo, resolverNames)) {
            for (int i = 0; i < 2; i++) {
                deployer.deployJavaProject("org.acme", "lib", "1.0-SNAPSHOT");
            }
            deployer.deploy(
                    "org.acme",
                    "lib",
                    "2.0-SNAPSHOT",
                    ResolverDeployer.Part.of("", "jar", "jar"),
                    ResolverDeployer.Part.of("", "module", "{\"formatVersion\":\"1.1\"}"));
        }
        List<Path> files = realFiles();
        assertThat(files).hasSizeGreaterThan(8);
        assertThat(files).anyMatch(f -> f.getFileName().toString().equals("maven-metadata.xml"));
        for (Path file : files) {
            for (ChecksumAlgorithm algorithm : configured) {
                assertThat(ChecksumWriter.checksumFile(file, algorithm)).as(file.toString()).exists();
            }
        }

        // Remember what Resolver wrote, delete it, and write it again from scratch.
        for (Path file : files) {
            for (ChecksumAlgorithm algorithm : configured) {
                Path checksum = ChecksumWriter.checksumFile(file, algorithm);
                byte[] expected = Files.readAllBytes(checksum);
                Files.delete(checksum);

                Set<ChecksumAlgorithm> written = ChecksumWriter.write(file, Set.of(algorithm));

                assertThat(written).contains(algorithm);
                assertThat(Files.readAllBytes(checksum)).as(checksum.toString()).isEqualTo(expected);
            }
        }
    }

    @Test
    void regeneratesPresentAlgorithmsAfterTheFileChanged() throws IOException {
        Path file = repo.resolve("a/maven-metadata.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "old");
        ChecksumWriter.write(file, Set.of(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.SHA512));

        Files.writeString(file, "new content");
        Set<ChecksumAlgorithm> written = ChecksumWriter.write(file, Set.of());

        assertThat(written).containsExactlyInAnyOrder(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.SHA512);
        assertThat(Files.readString(ChecksumWriter.checksumFile(file, ChecksumAlgorithm.SHA1)))
                .isEqualTo(ChecksumWriter.hex(ChecksumAlgorithm.SHA1, "new content".getBytes(StandardCharsets.UTF_8)));
        assertThat(ChecksumWriter.checksumFile(file, ChecksumAlgorithm.MD5)).doesNotExist();
    }

    @Test
    void writesPresentPlusConfigured() throws IOException {
        Path file = repo.resolve("x.jar");
        Files.writeString(file, "bytes");
        ChecksumWriter.write(file, Set.of(ChecksumAlgorithm.MD5));

        Set<ChecksumAlgorithm> written = ChecksumWriter.write(file, Set.of(ChecksumAlgorithm.SHA256));

        assertThat(written).containsExactlyInAnyOrder(ChecksumAlgorithm.MD5, ChecksumAlgorithm.SHA256);
        assertThat(ChecksumWriter.checksumFile(file, ChecksumAlgorithm.SHA1)).doesNotExist();
    }

    @Test
    void writesNothingWhenNoAlgorithmApplies() throws IOException {
        Path file = repo.resolve("x.jar");
        Files.writeString(file, "bytes");

        assertThat(ChecksumWriter.write(file, Set.of())).isEmpty();
        try (Stream<Path> list = Files.list(repo)) {
            assertThat(list.toList()).containsExactly(file);
        }
    }

    @Test
    void parsesResolverNamesAndExtensions() {
        assertThat(ChecksumAlgorithm.parse("SHA-1")).contains(ChecksumAlgorithm.SHA1);
        assertThat(ChecksumAlgorithm.parse("sha256")).contains(ChecksumAlgorithm.SHA256);
        assertThat(ChecksumAlgorithm.parse(" MD5 ")).contains(ChecksumAlgorithm.MD5);
        assertThat(ChecksumAlgorithm.parse("SHA-512")).contains(ChecksumAlgorithm.SHA512);
        assertThat(ChecksumAlgorithm.parse("crc32")).isEmpty();
    }

    private List<Path> realFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(repo)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> ChecksumAlgorithm.fromExtension(extension(p)).isEmpty())
                    .toList();
        }
    }

    private static String extension(Path p) {
        String name = p.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
