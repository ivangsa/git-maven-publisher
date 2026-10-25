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
package io.github.ivangsa.gitpublisher.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T39: a real Kotlin Multiplatform library (JVM, JS, one native target) publishes every publication in one commit.
 * Each artifactId has its Gradle module metadata, POM and Maven metadata, and the files named inside the
 * {@code .module} files exist under exactly those (timestamped) names.
 */
class KmpPublishTest {

    private static final Pattern URL = Pattern.compile("\"url\"\s*:\s*\"([^\"]+)\"");
    private static final Pattern AVAILABLE_AT_MODULE = Pattern.compile("\"module\"\s*:\s*\"([^\"]+)\"");

    @TempDir
    Path temp;

    private BareRepo remote;
    private KmpSample sample;

    @BeforeEach
    void create() {
        remote = BareRepo.create(temp.resolve("remote.git"));
        sample = new KmpSample(temp.resolve("sample"), remote.uri());
    }

    @AfterEach
    void close() {
        remote.close();
    }

    @Test
    void everyPublicationIsPublishedInOneCommitWithModulePomAndMetadata() throws IOException {
        BuildResult result = sample.publish();

        for (String artifactId : KmpSample.artifactIds()) {
            assertTrue(
                    result.getOutput().contains("publish" + publicationOf(artifactId) + "PublicationToGitPublisherRepository"),
                    "no publish task for " + artifactId + ":\n" + result.getOutput());
        }
        assertEquals(1, remote.commitCount(KmpSample.BRANCH));
        Path checkout = remote.checkout(KmpSample.BRANCH, temp.resolve("checkout"));
        MetadataAssertions.assertValidRepository(checkout, Set.of("sha1", "md5"));

        for (String artifactId : KmpSample.artifactIds()) {
            Path artifactDirectory = checkout.resolve("com/acme/" + artifactId);
            Path versionDirectory = artifactDirectory.resolve(KmpSample.VERSION);
            assertTrue(Files.exists(artifactDirectory.resolve("maven-metadata.xml")), artifactId + " A-level metadata");
            assertTrue(Files.exists(versionDirectory.resolve("maven-metadata.xml")), artifactId + " V-level metadata");
            Path module = single(versionDirectory, artifactId, ".module");
            Path pom = single(versionDirectory, artifactId, ".pom");
            assertTrue(Files.size(module) > 0 && Files.size(pom) > 0);
            assertReferencedFilesExist(module, versionDirectory);
        }
    }

    @Test
    void theRootModuleNamesTheTargetModulesThatWerePublished() throws IOException {
        sample.publish();
        Path checkout = remote.checkout(KmpSample.BRANCH, temp.resolve("checkout"));
        Path root = single(checkout.resolve("com/acme/" + KmpSample.NAME + "/" + KmpSample.VERSION), KmpSample.NAME, ".module");
        String content = Files.readString(root);
        Matcher matcher = AVAILABLE_AT_MODULE.matcher(content);
        int found = 0;
        while (matcher.find()) {
            String target = matcher.group(1);
            assertTrue(KmpSample.artifactIds().contains(target) || target.equals("kotlin-stdlib"), "unexpected " + target);
            if (!target.equals("kotlin-stdlib")) {
                assertTrue(Files.isDirectory(checkout.resolve("com/acme/" + target)), "root module points at " + target);
                found++;
            }
        }
        assertTrue(found >= 3, "the root module should redirect to the target modules, found " + found + ":\n" + content);
    }

    private static String publicationOf(String artifactId) {
        // kotlinMultiplatform (root), jvm, js, mingwX64 ...
        if (artifactId.equals(KmpSample.NAME)) {
            return "KotlinMultiplatform";
        }
        String target = artifactId.substring(KmpSample.NAME.length() + 1);
        return target.equals("jvm") || target.equals("js")
                ? Character.toUpperCase(target.charAt(0)) + target.substring(1)
                : Character.toUpperCase(KmpSample.nativeTarget().charAt(0)) + KmpSample.nativeTarget().substring(1);
    }

    /** The one file {@code <artifactId>-<version-with-timestamp><suffix>} (timestamped snapshot name). */
    private static Path single(Path directory, String artifactId, String suffix) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> matches = files.filter(file -> {
                        String name = file.getFileName().toString();
                        return name.startsWith(artifactId + "-1.0-") && name.endsWith(suffix);
                    })
                    .toList();
            assertEquals(1, matches.size(), artifactId + " " + suffix + " in " + directory + ": " + matches);
            return matches.get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Gradle writes {@code -SNAPSHOT} names in a {@code .module} and the consumer maps them to the timestamp of the
     * module it read, so every named file must exist under the module's own timestamped version, next to it. A target
     * module in another artifactId (an {@code available-at} reference) must exist under the same timestamp.
     */
    private static void assertReferencedFilesExist(Path module, Path directory) {
        String content;
        try {
            content = Files.readString(module);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String fileName = module.getFileName().toString();
        Matcher stamp = Pattern.compile("-(1\\.0-\\d{8}\\.\\d{6}-\\d+)\\.module$").matcher(fileName);
        assertTrue(stamp.find(), "not a timestamped module name: " + fileName);
        String timestamped = stamp.group(1);
        Matcher matcher = URL.matcher(content);
        int urls = 0;
        while (matcher.find()) {
            urls++;
            String url = matcher.group(1);
            int slash = url.lastIndexOf('/') + 1;
            // Only the file name is timestamped; the version directory stays 1.0-SNAPSHOT.
            String resolved = url.substring(0, slash) + url.substring(slash).replace("1.0-SNAPSHOT", timestamped);
            if (resolved.startsWith("../")) {
                // ../../<artifactId>/1.0-SNAPSHOT/<artifactId>-1.0-SNAPSHOT.module
                Path target = directory.resolve(resolved).normalize();
                assertTrue(Files.exists(target), fileName + " refers to a module that was not published: " + resolved);
            } else {
                assertTrue(Files.exists(directory.resolve(resolved)), fileName + " names a missing file " + resolved);
            }
        }
        assertTrue(urls > 0, fileName + " references no files");
    }
}
