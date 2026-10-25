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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

/**
 * A real Kotlin Multiplatform library (JVM, JS and one native target) that publishes
 * {@code com.acme:kmplib:1.0-SNAPSHOT} through the plugin. The native target is the one the host can compile.
 */
final class KmpSample {

    static final String KOTLIN_VERSION = "2.2.21";
    static final String GROUP = "com.acme";
    static final String NAME = "kmplib";
    static final String VERSION = "1.0-SNAPSHOT";
    static final String BRANCH = "gh-pages";

    private final Path root;
    private final URI repository;

    KmpSample(Path root, URI repository) {
        this.root = root;
        this.repository = repository;
        write("settings.gradle", "rootProject.name = '" + NAME + "'\n");
        write("build.gradle", """
                plugins {
                    id 'org.jetbrains.kotlin.multiplatform' version '%s'
                    id 'io.github.ivangsa.git-maven-publisher'
                }
                group = '%s'
                version = '%s'
                repositories { mavenCentral() }
                kotlin {
                    jvm()
                    js { nodejs() }
                    %s()
                }
                gitMavenPublisher {
                    repository = '%s'
                    branch = '%s'
                }
                """.formatted(KOTLIN_VERSION, GROUP, VERSION, nativeTarget(), repository, BRANCH));
        write("src/commonMain/kotlin/com/acme/Platform.kt", """
                package com.acme

                expect fun platform(): String

                fun greeting(): String = "hello from " + platform()
                """);
        write("src/jvmMain/kotlin/com/acme/PlatformJvm.kt", """
                package com.acme

                actual fun platform(): String = "jvm"
                """);
        write("src/jsMain/kotlin/com/acme/PlatformJs.kt", """
                package com.acme

                actual fun platform(): String = "js"
                """);
        write("src/nativeMain/kotlin/com/acme/PlatformNative.kt", """
                package com.acme

                actual fun platform(): String = "native"
                """);
    }

    /** The Kotlin/Native target the host builds: Kotlin publishes no klib for a target it cannot compile here. */
    static String nativeTarget() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "mingwX64";
        }
        if (os.contains("mac")) {
            return arch.contains("aarch64") || arch.contains("arm") ? "macosArm64" : "macosX64";
        }
        return "linuxX64";
    }

    /** Every artifactId the sample publishes: the root module and one per target. */
    static List<String> artifactIds() {
        return List.of(NAME, NAME + "-jvm", NAME + "-js", NAME + "-" + nativeTarget().toLowerCase(Locale.ROOT));
    }

    Path root() {
        return root;
    }

    /**
     * Adds a task between the plugin's checkout and its push that hands control to the test and waits for it. */
    KmpSample pauseBetweenCheckoutAndPush(Path ready, Path go) {
        return appendToBuild("""
                tasks.register('concurrentPublisher') {
                    dependsOn 'gitPublisherCheckout'
                    def ready = new File('%s')
                    def go = new File('%s')
                    doLast {
                        ready.text = 'ready'
                        long deadline = System.currentTimeMillis() + 180000L
                        while (!go.exists()) {
                            if (System.currentTimeMillis() > deadline) {
                                throw new GradleException('the test never released the concurrent publisher')
                            }
                            Thread.sleep(50)
                        }
                    }
                }
                tasks.named('gitPublisherPush') { mustRunAfter 'concurrentPublisher' }
                tasks.named('publish') { dependsOn 'concurrentPublisher' }
                """.formatted(ready.toString().replace('\\', '/'), go.toString().replace('\\', '/')));
    }

    /** Appends Groovy to the sample's {@code build.gradle}. */
    KmpSample appendToBuild(String groovy) {
        try {
            Path file = root.resolve("build.gradle");
            Files.writeString(file, Files.readString(file) + "\n" + groovy + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    BuildResult publish(String... extraArguments) {
        return runner("publish", extraArguments).build();
    }

    BuildResult publishAndFail(String... extraArguments) {
        return runner("publish", extraArguments).buildAndFail();
    }

    private GradleRunner runner(String task, String... extraArguments) {
        List<String> arguments = new java.util.ArrayList<>(List.of(task, "--stacktrace"));
        arguments.addAll(List.of(extraArguments));
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments(arguments);
    }

    private void write(String relative, String content) {
        try {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
