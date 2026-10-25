/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T38: the token sources against a real Git HTTPS server that accepts exactly one token, run through TestKit.
 * The server is the oracle: a push that reaches the branch proves the right token was sent.
 */
class GradleCredentialsTest {

    private static final String RIGHT = "right-token-7f3a9c1d4e";
    private static final String WRONG = "wrong-token-52be80aa17";
    private static final String PARTICIPANT = ":a:publishMavenPublicationToGitPublisherRepository";

    /**
     * Entries are encrypted by default, which would hide a leaked token from the scan; the internal Gradle option below
     * stores them in the clear (Gradle 8.x and 9.x name the option differently, so both
     * names are passed; a build ignores the one it does not know, D-096). The positive control in {@link #aTokenSetInTheBuildScriptIsStoredInTheEntry()} shows the
     * scan finds a credential that is there.
     */
    private static final String NO_ENCRYPTION_8 = "-Dorg.gradle.configuration-cache.internal.encryption=false";
    private static final String NO_ENCRYPTION_9 = "-Dorg.gradle.internal.configuration-cache.encryption=false";
    private static final List<String> CACHE = List.of("--configuration-cache", NO_ENCRYPTION_8, NO_ENCRYPTION_9);
    /** A value of a property that nothing reads: the control for what Gradle itself stores of the command line (D-096). */
    private static final String PROBE = "probe-value-3c8e51b0d2";
    private static final List<String> CACHE_WITH_PROPERTY = List.of(
            "--configuration-cache",
            NO_ENCRYPTION_8,
            NO_ENCRYPTION_9,
            "-PgitPublisherToken=" + RIGHT,
            "-PunreadProbeProperty=" + PROBE);

    private static Path tlsDirectory;

    @TempDir
    Path temp;

    private BareRepo remote;
    private HttpsGitServer server;
    private PublisherBuild build;

    @BeforeAll
    static void createTls() throws Exception {
        tlsDirectory = Files.createTempDirectory("gradle-tls");
    }

    @BeforeEach
    void startServer() throws Exception {
        remote = BareRepo.create(temp.resolve("remote.git"));
        server = HttpsGitServer.start(remote.directory(), tlsDirectory, RIGHT);
        build = new PublisherBuild(temp.resolve("project"), remote, server.url()).withProjects("a");
        // The build runs in a Gradle daemon: trust the certificate of the test server there.
        build.gradleProperties("org.gradle.jvmargs=-Xmx768m -Djavax.net.ssl.trustStore="
                + server.truststore().toString().replace('\\', '/')
                + " -Djavax.net.ssl.trustStorePassword=" + HttpsGitServer.STORE_PASSWORD + "\n");
    }

    @AfterEach
    void stopServer() {
        server.close();
        remote.close();
    }

    private void assertPublished() {
        assertEquals(1, remote.commitCount(PublisherBuild.BRANCH));
        Path checkout = remote.checkout(PublisherBuild.BRANCH, temp.resolve("checkout"));
        MetadataAssertions.assertValidRepository(checkout, Set.of("sha1", "md5"));
        assertTrue(Files.isDirectory(checkout.resolve("com/acme/a/1.0-SNAPSHOT")));
    }

    private void assertRejectedBeforeAnythingIsWritten(BuildResult result, String secret) {
        String output = result.getOutput();
        assertNotNull(result.task(":a:gitPublisherCheckout"), output);
        assertEquals(TaskOutcome.FAILED, result.task(":a:gitPublisherCheckout").getOutcome(), output);
        assertNull(result.task(PARTICIPANT), "the publication must not start with a rejected token:\n" + output);
        assertTrue(remote.branchTip(PublisherBuild.BRANCH).isEmpty(), "nothing may be pushed");
        assertTrue(output.contains("not authorized") || output.contains("Authentication is required"), output);
        assertFalse(output.contains(secret), "the token must never be printed");
    }

    @Test
    void theEnvironmentVariableIsUsed() {
        build.environment("GIT_PUBLISHER_TOKEN", RIGHT);
        build.run(List.of(), "publish");
        assertPublished();
    }

    @Test
    void theGradlePropertyIsUsed() {
        build.run(List.of("-PgitPublisherToken=" + RIGHT), "publish");
        assertPublished();
    }

    @Test
    void theGradlePropertyFromGradlePropertiesIsUsed() {
        build.gradleProperties(text(build.root().resolve("gradle.properties")) + "gitPublisherToken=" + RIGHT + "\n");
        build.run(List.of(), "publish");
        assertPublished();
    }

    @Test
    void theExtensionTokenIsUsed() {
        build.publisherSettings("token = '" + RIGHT + "'").withProjects("a");
        build.run(List.of(), "publish");
        assertPublished();
    }

    @Test
    void theExtensionTokenWinsOverTheEnvironment() {
        build.publisherSettings("token = '" + RIGHT + "'").withProjects("a");
        build.environment("GIT_PUBLISHER_TOKEN", WRONG);
        build.run(List.of(), "publish");
        assertPublished();
    }

    @Test
    void theEnvironmentVariableWinsOverTheGradleProperty() {
        build.environment("GIT_PUBLISHER_TOKEN", RIGHT);
        build.run(List.of("-PgitPublisherToken=" + WRONG), "publish");
        assertPublished();
    }

    @Test
    void aWrongTokenFailsFastBeforeAnyPublicationRuns() {
        build.environment("GIT_PUBLISHER_TOKEN", WRONG);
        assertRejectedBeforeAnythingIsWritten(build.runAndFail(List.of(), "publish"), WRONG);
    }

    @Test
    void aWrongGradlePropertyFailsFast() {
        assertRejectedBeforeAnythingIsWritten(
                build.runAndFail(List.of("-PgitPublisherToken=" + WRONG), "publish"), WRONG);
    }

    @Test
    void noTokenFailsFast() {
        assertRejectedBeforeAnythingIsWritten(build.runAndFail(List.of(), "publish"), WRONG);
    }

    /**
     * Carry-over from T37: the configuration cache entry must not hold the token (D-087). The entry is scanned for the
     * token; a marker that the entry certainly holds proves the scan can see inside it.
     */
    @Test
    void theConfigurationCacheEntryHoldsNoEnvironmentToken() {
        build.environment("GIT_PUBLISHER_TOKEN", RIGHT);
        BuildResult first = build.run(CACHE, "publish");
        assertTrue(first.getOutput().contains("Configuration cache entry stored."), first.getOutput());
        assertEntryHoldsNoToken(RIGHT);
        // The second run reuses the entry and still authenticates: the token is read when the service runs.
        BuildResult second = build.run(CACHE, "publish");
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
        assertPublished();
    }

    @Test
    void theConfigurationCacheEntryHoldsNoGradlePropertyToken() {
        build.gradleProperties(text(build.root().resolve("gradle.properties")) + "gitPublisherToken=" + RIGHT + "\n");
        BuildResult first = build.run(CACHE, "publish");
        assertTrue(first.getOutput().contains("Configuration cache entry stored."), first.getOutput());
        assertEntryHoldsNoToken(RIGHT);
        BuildResult second = build.run(CACHE, "publish");
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
        assertPublished();
    }

    @Test
    void theCommandLineGradlePropertyIsNotInTheConfigurationCacheEntryEither() {
        BuildResult first = build.run(CACHE_WITH_PROPERTY, "publish");
        assertTrue(first.getOutput().contains("Configuration cache entry stored."), first.getOutput());
        assertEntryHoldsNoMoreOfTheTokenThanOfTheProbe();
    }

    /** Positive control and documented limit (D-087): a literal in the build script is configuration, so it is stored. */
    @Test
    void aTokenSetInTheBuildScriptIsStoredInTheEntry() {
        build.publisherSettings("token = '" + RIGHT + "'").withProjects("a");
        build.run(CACHE, "publish");
        Path cache = build.root().resolve(".gradle/configuration-cache");
        try (Stream<Path> walk = Files.walk(cache)) {
            assertTrue(walk.filter(Files::isRegularFile).anyMatch(file -> contains(file, RIGHT)),
                    "the scan must find a token that is stored");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void assertEntryHoldsNoToken(String token) {
        Path cache = build.root().resolve(".gradle/configuration-cache");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(cache)) {
            files = walk.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertFalse(files.isEmpty(), "no configuration cache entry under " + cache);
        // Control: the repository URL is configuration the entry certainly holds, so a scan that cannot find it is blind.
        String marker = server.url().toString();
        boolean markerFound = false;
        for (Path file : files) {
            markerFound |= contains(file, marker);
            assertFalse(contains(file, token), "the token is stored in " + file);
        }
        assertTrue(markerFound, "the scan cannot see inside the cache entry (encrypted or compressed?): " + files);
    }

    /**
     * Some Gradle versions (8.x) store every command-line {@code -P} value in the entry whether or not anything reads it
     * (D-096). The token of the plugin must then appear exactly as often, per file, as the value of a property that
     * nothing reads: whatever is stored of the token is Gradle's own copy of the command line, never one the plugin
     * adds. On Gradle versions that store none, both counts are zero, which is the plain "no token" assertion.
     */
    private void assertEntryHoldsNoMoreOfTheTokenThanOfTheProbe() {
        Path cache = build.root().resolve(".gradle/configuration-cache");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(cache)) {
            files = walk.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertFalse(files.isEmpty(), "no configuration cache entry under " + cache);
        String marker = server.url().toString();
        boolean markerFound = false;
        for (Path file : files) {
            markerFound |= contains(file, marker);
            assertEquals(occurrences(file, PROBE), occurrences(file, RIGHT),
                    "the plugin stored the token in " + file + " beyond what Gradle stores of the command line");
        }
        assertTrue(markerFound, "the scan cannot see inside the cache entry (encrypted or compressed?): " + files);
    }

    private static int occurrences(Path file, String text) {
        String stored = text.substring(0, text.length() - 1);
        byte[] bytes = bytes(file);
        int count = 0;
        for (var charset : List.of(StandardCharsets.ISO_8859_1, StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE)) {
            String all = new String(bytes, charset);
            for (int at = all.indexOf(stored); at >= 0; at = all.indexOf(stored, at + 1)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Gradle writes a string with the high bit set on its last character, so the stored form is the text without
     * its last character; searching for that prefix finds the string (and a credential that was stored).
     */
    private static boolean contains(Path file, String text) {
        String stored = text.substring(0, text.length() - 1);
        byte[] bytes = bytes(file);
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(stored)
                || new String(bytes, StandardCharsets.UTF_16BE).contains(stored)
                || new String(bytes, StandardCharsets.UTF_16LE).contains(stored);
    }

    private static String text(Path file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
