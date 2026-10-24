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
package io.github.ivangsa.gitpublisher.fixtures;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Looks for a canary string (a fake token) in captured logs, in a directory tree (file names,
 * directory names and file contents) and in Git configuration files. Findings never contain the canary.
 * Used by credential tests, see docs/design.md section 8.
 */
public final class LeakScanner {

    private static final String REDACTED = "<redacted>";

    private final String canary;
    private final byte[] canaryBytes;

    public LeakScanner(String canary) {
        if (canary == null || canary.isBlank()) {
            throw new IllegalArgumentException("canary must not be blank");
        }
        this.canary = canary;
        this.canaryBytes = canary.getBytes(UTF_8);
    }

    /** Scans captured text (for example a build log). {@code source} names the text in findings. */
    public List<String> scanText(String source, String text) {
        List<String> findings = new ArrayList<>();
        if (text.contains(canary)) {
            findings.add("canary found in " + redact(source));
        }
        return findings;
    }

    /** Scans every entry under {@code root}: entry names, and the bytes of regular files. */
    public List<String> scanTree(Path root) throws IOException {
        List<String> findings = new ArrayList<>();
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path entry : entries.sorted().toList()) {
                if (entry.equals(root)) {
                    continue;
                }
                String relative = relative(root, entry);
                if (entry.getFileName().toString().contains(canary)) {
                    findings.add("canary found in the name of " + redact(relative));
                }
                if (Files.isRegularFile(entry) && containsCanary(Files.readAllBytes(entry))) {
                    findings.add("canary found in content of " + redact(relative));
                }
            }
        }
        return findings;
    }

    /**
     * Scans Git configuration under {@code root}: any {@code config} file inside a {@code .git} directory
     * or next to {@code HEAD} and {@code objects/} (bare repository).
     */
    public List<String> scanGitConfig(Path root) throws IOException {
        List<String> findings = new ArrayList<>();
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path config : entries.filter(Files::isRegularFile).filter(LeakScanner::isGitConfig).sorted().toList()) {
                if (containsCanary(Files.readAllBytes(config))) {
                    findings.add("canary found in git config " + redact(relative(root, config)));
                }
            }
        }
        return findings;
    }

    private static boolean isGitConfig(Path file) {
        if (!"config".equals(file.getFileName().toString())) {
            return false;
        }
        Path directory = file.getParent();
        if (directory == null) {
            return false;
        }
        if (".git".equals(String.valueOf(directory.getFileName()))) {
            return true;
        }
        return Files.isRegularFile(directory.resolve("HEAD")) && Files.isDirectory(directory.resolve("objects"));
    }

    private boolean containsCanary(byte[] content) {
        outer:
        for (int i = 0; i + canaryBytes.length <= content.length; i++) {
            for (int j = 0; j < canaryBytes.length; j++) {
                if (content[i + j] != canaryBytes[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private String redact(String text) {
        return text.replace(canary, REDACTED);
    }

    private static String relative(Path root, Path entry) {
        return root.relativize(entry).toString().replace('\\', '/');
    }
}
