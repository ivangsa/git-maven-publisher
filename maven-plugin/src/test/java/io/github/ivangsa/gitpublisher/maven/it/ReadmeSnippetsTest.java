/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every code block of README.md is copied from a file under {@code src/it}, where the invoker ITs run it (T31): the
 * block, or a leading {@code mvn } removed from it, is a contiguous part of one IT file. Shell commands are therefore
 * checked against the goals of the ITs, YAML and XML against the project files that the ITs build.
 */
class ReadmeSnippetsTest {

    /** The README sits in the root of the reactor; surefire runs with the module directory as working directory. */
    private static final Path README = Path.of("..", "README.md");
    private static final Path IT_DIR = Path.of("src/it");

    @Test
    void everyReadmeSnippetIsCopiedFromAnIt() throws IOException {
        List<String> snippets = snippets(Files.readString(README, StandardCharsets.UTF_8));
        assertThat(snippets).as("code blocks in README.md").hasSizeGreaterThanOrEqualTo(4);
        List<String> corpus = itFiles();
        for (String snippet : snippets) {
            assertThat(isCopied(snippet, corpus))
                    .as("README snippet not found in any file under src/it:%n%s", snippet)
                    .isTrue();
        }
    }

    @Test
    void aSnippetThatNoItContainsIsRejected() throws IOException {
        List<String> corpus = itFiles();
        assertThat(isCopied("mvn deploy -Dgit-publisher.invented=true", corpus)).isFalse();
        assertThat(isCopied("<project>\n  <invented/>\n</project>", corpus)).isFalse();
    }

    /**
     * Text of every fenced code block, without the fences, except the Gradle ones ({@code groovy} and {@code groovy settings.gradle}
     * blocks and {@code ./gradlew} commands): those are checked by {@code ReadmeGradleSnippetsTest} (T44, D-094, D-111).
     */
    static List<String> snippets(String markdown) {
        List<String> snippets = new ArrayList<>();
        StringBuilder current = null;
        boolean gradle = false;
        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("```")) {
                if (current == null) {
                    current = new StringBuilder();
                    gradle = line.equals("```groovy") || line.equals("```groovy settings.gradle");
                } else {
                    String text = current.toString().stripTrailing();
                    if (!gradle && !text.startsWith("./gradlew ")) {
                        snippets.add(text);
                    }
                    current = null;
                }
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        assertThat(current).as("unclosed code block in README.md").isNull();
        return snippets;
    }

    static boolean isCopied(String snippet, List<String> itFiles) {
        String text = snippet.strip();
        if (text.startsWith("mvn ")) {
            text = text.substring("mvn ".length());
        }
        for (String file : itFiles) {
            if (file.contains(text)) {
                return true;
            }
        }
        return false;
    }

    /** Contents of every regular file under src/it, line endings normalized. */
    static List<String> itFiles() throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(IT_DIR)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.add(Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return files;
    }
}
