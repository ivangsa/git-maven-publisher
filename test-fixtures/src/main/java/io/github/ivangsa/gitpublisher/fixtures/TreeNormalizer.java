/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns a repository tree into a comparable map {@code relative path -> content}, removing what legitimately differs
 * between two runs of the same publications: snapshot timestamps, build numbers (renumbered by order per snapshot
 * directory), and {@code lastUpdated} / {@code updated}. Checksums of metadata files are not compared by content
 * (they follow from the content, and {@link MetadataAssertions} checks them separately); their names are kept.
 */
public final class TreeNormalizer {

    static final String TIMESTAMP = "TS";

    private static final Pattern BUILD_IN_NAME = Pattern.compile("-(\\d{8}\\.\\d{6})-(\\d+)(?=[-.<])");
    private static final Pattern SNAPSHOT_ELEMENT =
            Pattern.compile("<timestamp>(\\d{8}\\.\\d{6})</timestamp>(\\s*)<buildNumber>(\\d+)</buildNumber>");
    private static final Pattern LAST_UPDATED = Pattern.compile("<(lastUpdated|updated)>\\d+</\\1>");
    private static final String CHECKSUM_PLACEHOLDER = "<checksum of normalized metadata>";

    private TreeNormalizer() {}

    public static Map<String, String> normalize(Path root) {
        Map<String, String> result = new TreeMap<>();
        for (Path dir : directories(root)) {
            Map<String, Integer> builds = buildsByOrder(dir);
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    String relative = relative(root, dir) + normalizeText(name, builds);
                    result.put(relative, content(file, name, builds));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return result;
    }

    private static String content(Path file, String name, Map<String, Integer> builds) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (name.startsWith("maven-metadata.xml.") || name.matches(".*\\.module\\.(md5|sha1|sha256|sha512)")) {
            return CHECKSUM_PLACEHOLDER;
        }
        if (name.equals("maven-metadata.xml")) {
            return normalizeMetadata(new String(bytes, StandardCharsets.UTF_8), builds);
        }
        if (name.endsWith(".module")) {
            return normalizeText(new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n"), builds);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    static String normalizeMetadata(String xml, Map<String, Integer> builds) {
        String text = xml.replace("\r\n", "\n");
        text = LAST_UPDATED.matcher(text).replaceAll("<$1>0</$1>");
        text = SNAPSHOT_ELEMENT.matcher(text).replaceAll(m -> {
            Integer index = builds.get(m.group(1) + "-" + m.group(3));
            String number = index == null ? m.group(3) : String.valueOf(index);
            String ts = index == null ? m.group(1) : TIMESTAMP;
            return Matcher.quoteReplacement(
                    "<timestamp>" + ts + "</timestamp>" + m.group(2) + "<buildNumber>" + number + "</buildNumber>");
        });
        return normalizeText(text, builds);
    }

    private static String normalizeText(String text, Map<String, Integer> builds) {
        return BUILD_IN_NAME.matcher(text).replaceAll(m -> {
            Integer index = builds.get(m.group(1) + "-" + m.group(2));
            return index == null
                    ? Matcher.quoteReplacement(m.group())
                    : Matcher.quoteReplacement("-" + TIMESTAMP + "-" + index);
        });
    }

    /** Builds found in the file names of one directory, numbered 1.. by (timestamp, build number). */
    private static Map<String, Integer> buildsByOrder(Path dir) {
        record Build(String timestamp, int number) {}
        List<Build> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Matcher m = BUILD_IN_NAME.matcher(file.getFileName().toString());
                while (m.find()) {
                    Build b = new Build(m.group(1), Integer.parseInt(m.group(2)));
                    if (!found.contains(b)) {
                        found.add(b);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        found.sort(Comparator.comparingInt(Build::number).thenComparing(Build::timestamp));
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < found.size(); i++) {
            result.put(found.get(i).timestamp() + "-" + found.get(i).number(), i + 1);
        }
        return result;
    }

    private static List<Path> directories(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path root, Path dir) {
        String rel = root.relativize(dir).toString().replace('\\', '/');
        return rel.isEmpty() ? "" : rel + "/";
    }
}
