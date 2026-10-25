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
package io.github.ivangsa.gitpublisher.maven.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.fixtures.BareRepo;
import io.github.ivangsa.gitpublisher.fixtures.MavenConsumer;
import io.github.ivangsa.gitpublisher.fixtures.MetadataAssertions;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Helpers for the {@code maven-invoker-plugin} ITs in {@code src/it}. The Groovy hook scripts only call these methods,
 * so the logic is compiled and type checked with the rest of the tests.
 *
 * <p>Every IT is a user project: {@link #prebuild(File)} creates its bare "remote" next to the cloned project, points
 * the plugin at it with {@code .mvn/maven.config} (as a user would on the command line) and fills in the modules the
 * project lists but does not spell out.
 */
public final class ItSupport {

    public static final String BRANCH = "gh-pages";
    public static final String GROUP = "it.reactor";
    public static final String VERSION = "1.0-SNAPSHOT";

    private static final Pattern MODULE = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");

    private ItSupport() {}

    /** The bare repository of this IT, next to the cloned project directory. */
    public static Path remote(File basedir) {
        return basedir.toPath().toAbsolutePath().resolveSibling(basedir.getName() + "-remote.git");
    }

    /** Opens the IT's bare repository (created by {@link #prebuild(File)}). */
    public static BareRepo bare(File basedir) {
        return BareRepo.create(remote(basedir));
    }

    /** Maven's output of the IT build. */
    public static String log(File basedir) throws IOException {
        return Files.readString(basedir.toPath().resolve("build.log"), StandardCharsets.UTF_8);
    }

    /** Creates the remote, writes {@code .mvn/maven.config} and generates the modules of the root POM that have no directory. */
    public static void prebuild(File basedir) throws IOException {
        prebuild(basedir, remote(basedir).toUri().toString(), List.of());
    }

    /** As {@link #prebuild(File)} with the repository URL given and extra lines for {@code .mvn/maven.config}. */
    public static void prebuild(File basedir, String repositoryUrl, List<String> extraConfig) throws IOException {
        BareRepo.create(remote(basedir)).close();
        Path root = basedir.toPath();
        List<String> config = new ArrayList<>();
        config.add("-Dgit-publisher.repository=" + repositoryUrl);
        config.add("-Dgit-publisher.nojekyll=true");
        config.addAll(extraConfig);
        Files.createDirectories(root.resolve(".mvn"));
        Files.write(root.resolve(".mvn").resolve("maven.config"), config, StandardCharsets.UTF_8);
        generateMissingModules(root);
    }

    private static void generateMissingModules(Path root) throws IOException {
        String pom = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8);
        Matcher matcher = MODULE.matcher(pom);
        while (matcher.find()) {
            String name = matcher.group(1);
            Path module = root.resolve(name);
            if (Files.exists(module.resolve("pom.xml"))) {
                continue;
            }
            Path source = module.resolve("src/main/java/it");
            Files.createDirectories(source);
            Files.writeString(module.resolve("pom.xml"), modulePom(name), StandardCharsets.UTF_8);
            Files.writeString(
                    source.resolve("Main.java"),
                    "package it;\n\npublic class Main {\n    public static String name() {\n        return \"" + name
                            + "\";\n    }\n}\n",
                    StandardCharsets.UTF_8);
        }
    }

    private static String modulePom(String name) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>%s</groupId>
                    <artifactId>reactor</artifactId>
                    <version>%s</version>
                  </parent>
                  <artifactId>%s</artifactId>
                </project>
                """
                .formatted(GROUP, VERSION, name);
    }

    /** {@code it.reactor:<artifactId>:1.0-SNAPSHOT} */
    public static String id(String artifactId) {
        return GROUP + ":" + artifactId + ":" + VERSION;
    }

    /** Subject line of the commit at the branch tip. */
    public static String commitMessage(BareRepo bare) {
        ObjectId tip = bare.branchTip(BRANCH).orElseThrow(() -> new AssertionError("branch " + BRANCH + " does not exist"));
        try (RevWalk walk = new RevWalk(bare.repository())) {
            return walk.parseCommit(tip).getFullMessage();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The coordinates named by the commit message {@code Publish a, b, c}. */
    public static Set<String> publishedProjects(BareRepo bare) {
        String subject = commitMessage(bare).lines().findFirst().orElse("");
        assertThat(subject).startsWith("Publish ");
        return new LinkedHashSet<>(Arrays.asList(subject.substring("Publish ".length()).split(", ")));
    }

    /** Exactly one commit on the branch whose message names exactly these artifactIds (group {@code it.reactor}). */
    public static void assertOnePublicationOf(File basedir, String... artifactIds) {
        try (BareRepo bare = bare(basedir)) {
            assertThat(bare.commitCount(BRANCH)).as("commits on " + BRANCH).isEqualTo(1);
            assertThat(publishedProjects(bare))
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(artifactIds).map(ItSupport::id).toList());
            assertThat(bare.readTree(BRANCH)).containsKey(".nojekyll");
        }
    }

    /**
     * Property-only publication (T30): the project has no plugin {@code <configuration>}; branch and checksum algorithms
     * came from {@code -Dgit-publisher.*} on the command line. One commit on {@code branch} names the coordinate, the
     * default branch was not written, and only the configured checksum files exist.
     */
    public static void assertPropertyOnlyPublication(File basedir, String branch, String checksum, String artifactId) {
        try (BareRepo bare = bare(basedir)) {
            assertThat(bare.branchTip(BRANCH)).as("default branch " + BRANCH).isEmpty();
            ObjectId tip = bare.branchTip(branch).orElseThrow(() -> new AssertionError("branch " + branch + " does not exist"));
            assertThat(bare.commitCount(branch)).as("commits on " + branch).isEqualTo(1);
            try (RevWalk walk = new RevWalk(bare.repository())) {
                String subject = walk.parseCommit(tip).getFullMessage().lines().findFirst().orElse("");
                assertThat(subject).isEqualTo("Publish " + id(artifactId));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Set<String> files = bare.readTree(branch).keySet();
            String extension = "." + checksum.toLowerCase(java.util.Locale.ROOT).replace("-", "");
            assertThat(files)
                    .as("files of " + artifactId + " with " + checksum)
                    .anyMatch(p -> p.startsWith("it/reactor/" + artifactId + "/") && p.endsWith(".jar" + extension))
                    .anyMatch(p -> p.startsWith("it/reactor/" + artifactId + "/") && p.endsWith(".pom" + extension));
            assertThat(files)
                    .as("checksum files other than " + checksum)
                    .noneMatch(p -> p.endsWith(".md5") || p.endsWith(".sha1"));
        }
    }

    /** Nothing was pushed: the branch does not exist. */
    public static void assertNothingPushed(File basedir) {
        try (BareRepo bare = bare(basedir)) {
            assertThat(bare.branchTip(BRANCH)).as("branch " + BRANCH).isEmpty();
        }
    }

    /**
     * The tree published by {@code deploy-sources}: valid by the metadata oracle, signature and parent POM present, and
     * a clean Maven consumer resolves main jars and sources from an exact checkout of the branch.
     */
    public static void verifyDeployedArtifacts(File basedir) {
        String name = basedir.getName();
        try (BareRepo bare = bare(basedir)) {
            assertThat(bare.commitCount(BRANCH)).as("commits on " + BRANCH).isEqualTo(1);
            Path checkout = bare.checkout(BRANCH, basedir.toPath().resolveSibling(name + "-checkout"));
            MetadataAssertions.assertValidRepository(checkout);

            Set<String> files = bare.readTree(BRANCH).keySet();
            assertThat(files)
                    .anyMatch(p -> p.matches("it/reactor/lib/1[.]0-SNAPSHOT/lib-1[.]0-[0-9]{8}[.][0-9]{6}-1[.]jar[.]asc"))
                    .anyMatch(p -> p.matches("it/reactor/reactor/1[.]0-SNAPSHOT/reactor-1[.]0-[0-9]{8}[.][0-9]{6}-1[.]pom"))
                    .anyMatch(p -> p.matches("it/reactor/app/1[.]0-SNAPSHOT/app-1[.]0-[0-9]{8}[.][0-9]{6}-1-sources[.]jar"));

            MavenConsumer.Result result = new MavenConsumer(
                            basedir.toPath().resolveSibling(name + "-consumer"), checkout.toUri())
                    .resolve(
                            "it.reactor:lib:1.0-SNAPSHOT",
                            "it.reactor:lib:1.0-SNAPSHOT:sources",
                            "it.reactor:app:1.0-SNAPSHOT",
                            "it.reactor:app:1.0-SNAPSHOT:sources");
            assertThat(result.success()).as(result.log()).isTrue();
            for (String artifact : List.of("lib", "app")) {
                assertThat(result.resolved(GROUP, artifact, VERSION, null)).as(artifact + " jar").isTrue();
                assertThat(result.resolved(GROUP, artifact, VERSION, "sources")).as(artifact + " sources").isTrue();
            }
        }
    }

    /** Token that the invoker configuration puts into {@code GIT_PUBLISHER_TOKEN} for every IT. */
    public static final String ENV_TOKEN = "it-env-token-8c1f2b";

    /** Token stored (encrypted) in the {@code settings.xml} server of the serverId IT. */
    public static final String SERVER_TOKEN = "it-server-token-41d7e9";

    static final String SERVER_ID = "snapshots-server";
    private static final String SERVER_REGISTRY = "gitpublisher.it.server.";

    /**
     * Prebuild of the HTTPS ITs: starts a TLS Git server for the IT's bare repository that accepts exactly one token,
     * makes the Maven of the IT trust its certificate ({@code .mvn/jvm.config}), and points the plugin at it. With
     * {@code tokenFromSettings} the server accepts {@link #SERVER_TOKEN}, which only the encrypted {@code src/it/settings.xml}
     * server holds ({@code GIT_PUBLISHER_TOKEN} is {@link #ENV_TOKEN} and would be refused, so the server must win);
     * otherwise it accepts {@link #ENV_TOKEN}, which only the environment holds.
     */
    public static void prebuildHttps(File basedir, boolean tokenFromSettings) throws Exception {
        Path root = basedir.toPath();
        BareRepo.create(remote(basedir)).close();
        ItServer server = ItServer.start(
                remote(basedir),
                root.toAbsolutePath().resolveSibling(basedir.getName() + "-tls"),
                tokenFromSettings ? SERVER_TOKEN : ENV_TOKEN);
        // the Groovy hooks run in different class loaders: keep only the JDK interface in the shared registry
        System.getProperties().put(SERVER_REGISTRY + basedir.getName(), server);

        List<String> extra = new ArrayList<>();
        if (tokenFromSettings) {
            // src/it/settings.xml (the invoker settingsFile) has the encrypted server; src/it/settings-security.xml the master
            Path security = root.toAbsolutePath().getParent().getParent().getParent().resolve("src/it/settings-security.xml");
            assertThat(security).as("master password file next to the ITs").exists();
            extra.add("-Dsettings.security=" + slashes(security));
            extra.add("-Dgit-publisher.serverId=" + SERVER_ID);
        }
        prebuild(basedir, server.url().toString(), extra);
        Files.write(
                root.resolve(".mvn").resolve("jvm.config"),
                List.of(
                        "-Djavax.net.ssl.trustStore=" + slashes(server.truststore().toAbsolutePath()),
                        "-Djavax.net.ssl.trustStorePassword=" + ItServer.STORE_PASSWORD,
                        "-Djavax.net.ssl.trustStoreType=PKCS12"),
                StandardCharsets.UTF_8);
    }

    private static String slashes(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    /**
     * Postbuild of the HTTPS ITs: the publication reached the server that demands the token (so the right token was
     * used), and no token is in Maven's output, the work directory's Git config, the remote's config or the tree.
     */
    public static void verifyHttps(File basedir, String... artifactIds) throws IOException {
        Object server = System.getProperties().remove(SERVER_REGISTRY + basedir.getName());
        if (server instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        assertOnePublicationOf(basedir, artifactIds);

        List<String> secrets = List.of(ENV_TOKEN, SERVER_TOKEN);
        String log = log(basedir);
        Path gitConfig = basedir.toPath().resolve("target/git-publisher/git/config");
        assertThat(gitConfig).exists();
        String config = Files.readString(gitConfig, StandardCharsets.UTF_8);
        String remoteConfig = Files.readString(remote(basedir).resolve("config"), StandardCharsets.UTF_8);
        for (String secret : secrets) {
            assertThat(log).as("build log").doesNotContain(secret);
            assertThat(config).as("Git config of the work directory").doesNotContain(secret);
            assertThat(remoteConfig).as("config of the remote").doesNotContain(secret);
            assertThat(config).doesNotContain("https://");
        }
        try (BareRepo bare = bare(basedir)) {
            bare.readTree(BRANCH).forEach((path, content) -> {
                String text = new String(content, StandardCharsets.ISO_8859_1);
                for (String secret : secrets) {
                    assertThat(text).as(path).doesNotContain(secret);
                }
            });
        }
    }
}
