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
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
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
    private final List<Needle> needles = new ArrayList<>();

    private record Needle(String form, byte[] bytes) {}

    public LeakScanner(String canary) {
        if (canary == null || canary.isBlank()) {
            throw new IllegalArgumentException("canary must not be blank");
        }
        this.canary = canary;
        byte[] raw = canary.getBytes(UTF_8);
        needles.add(new Needle("canary", raw));
        needles.add(new Needle("URL-encoded canary", URLEncoder.encode(canary, UTF_8).getBytes(UTF_8)));
        // Base64 of the canary inside a longer value (for example HTTP basic auth "user:canary"):
        // the encoding depends on the offset modulo 3, so look for the stable middle of all three alignments.
        for (int offset = 0; offset < 3; offset++) {
            byte[] shifted = new byte[offset + raw.length];
            java.util.Arrays.fill(shifted, 0, offset, (byte) 'x');
            System.arraycopy(raw, 0, shifted, offset, raw.length);
            addBase64(Base64.getEncoder().encodeToString(shifted), offset);
            addBase64(Base64.getUrlEncoder().encodeToString(shifted), offset);
        }
    }

    private void addBase64(String encoded, int offset) {
        String stable = encoded.replace("=", "");
        int from = offset == 0 ? 0 : 4;
        int to = stable.length() - 4;
        if (to > from) {
            needles.add(new Needle("Base64 canary", stable.substring(from, to).getBytes(UTF_8)));
        }
    }

    /** Scans captured text (for example a build log). {@code source} names the text in findings. */
    public List<String> scanText(String source, String text) {
        List<String> findings = new ArrayList<>();
        String form = leakedForm(text.getBytes(UTF_8));
        if (form != null) {
            findings.add(form + " found in " + redact(source));
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
                String nameForm = leakedForm(entry.getFileName().toString().getBytes(UTF_8));
                if (nameForm != null) {
                    findings.add(nameForm + " found in the name of " + redact(relative));
                }
                String contentForm = Files.isRegularFile(entry) ? leakedForm(Files.readAllBytes(entry)) : null;
                if (contentForm != null) {
                    findings.add(contentForm + " found in content of " + redact(relative));
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
                String form = leakedForm(Files.readAllBytes(config));
                if (form != null) {
                    findings.add(form + " found in git config " + redact(relative(root, config)));
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

    /** Returns the label of the first leaked form of the canary in {@code content}, or null. */
    private String leakedForm(byte[] content) {
        for (Needle needle : needles) {
            if (contains(content, needle.bytes())) {
                return needle.form();
            }
        }
        return null;
    }

    private static boolean contains(byte[] content, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= content.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (content[i + j] != needle[j]) {
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
