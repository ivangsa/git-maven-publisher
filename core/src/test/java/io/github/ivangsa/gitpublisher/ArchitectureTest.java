/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Architecture rule of docs/design.md section 1 (D-037): the publication logic in {@code io.github.ivangsa.gitpublisher}
 * imports no Maven or Gradle API. The Maven adapter lives in {@code ...gitpublisher.maven} and is the only exception.
 *
 * <p>The check reads source text, so it sees imports and fully qualified names alike. Comment lines are skipped.
 */
class ArchitectureTest {

    private static final Path CORE_SOURCES = Path.of("src", "main", "java", "io", "github", "ivangsa", "gitpublisher");
    private static final Path ADAPTER_PACKAGE = Path.of("maven");
    private static final Pattern FORBIDDEN = Pattern.compile("\\borg\\.(apache\\.maven|gradle)\\.");

    @Test
    void corePackagesDoNotUseMavenOrGradleApis() throws IOException {
        assertThat(violations(CORE_SOURCES)).isEmpty();
    }

    @Test
    void plantedViolationIsReportedAndAdapterIsExempt(@TempDir Path root) throws IOException {
        Path core = root.resolve("io/github/ivangsa/gitpublisher");
        write(core.resolve("Planted.java"), "package io.github.ivangsa.gitpublisher;\nimport org.apache.maven.plugin.Mojo;\n");
        write(core.resolve("Qualified.java"), "package io.github.ivangsa.gitpublisher;\nclass Qualified { org.gradle.api.Project p; }\n");
        write(core.resolve("Commented.java"), "package io.github.ivangsa.gitpublisher;\n// org.apache.maven.plugin.Mojo is fine in a comment\n");
        write(core.resolve("maven/Adapter.java"), "package io.github.ivangsa.gitpublisher.maven;\nimport org.apache.maven.plugin.Mojo;\n");

        List<String> found = violations(core);

        assertThat(found).hasSize(2);
        assertThat(found).anyMatch(v -> v.startsWith("Planted.java:2:"));
        assertThat(found).anyMatch(v -> v.startsWith("Qualified.java:2:"));
    }

    /** Returns one entry per offending code line, as {@code relative/File.java:line: text}. Adapter sources are skipped. */
    static List<String> violations(Path packageRoot) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(packageRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                Path relative = packageRoot.relativize(file);
                if (relative.startsWith(ADAPTER_PACKAGE)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String code = lines.get(i).trim();
                    if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")) {
                        continue;
                    }
                    if (FORBIDDEN.matcher(code).find()) {
                        found.add(relative.toString().replace('\\', '/') + ":" + (i + 1) + ": " + code);
                    }
                }
            }
        }
        return found;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
