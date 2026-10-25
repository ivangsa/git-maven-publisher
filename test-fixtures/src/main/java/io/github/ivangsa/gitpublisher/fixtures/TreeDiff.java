/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Compares two repository trees after {@link TreeNormalizer}: file set and normalized contents. The report names
 * every missing file, extra file and differing line, so a failure says what is wrong, not only that something is.
 */
public final class TreeDiff {

    private final List<String> differences;

    private TreeDiff(List<String> differences) {
        this.differences = List.copyOf(differences);
    }

    public static TreeDiff compare(Path expected, Path actual) {
        return compare(TreeNormalizer.normalize(expected), TreeNormalizer.normalize(actual));
    }

    public static TreeDiff compare(Map<String, String> expected, Map<String, String> actual) {
        List<String> found = new ArrayList<>();
        for (String path : new TreeSet<>(expected.keySet())) {
            if (!actual.containsKey(path)) {
                found.add("missing in actual: " + path);
            }
        }
        for (String path : new TreeSet<>(actual.keySet())) {
            if (!expected.containsKey(path)) {
                found.add("unexpected in actual: " + path);
            }
        }
        for (String path : new TreeSet<>(expected.keySet())) {
            String e = expected.get(path);
            String a = actual.get(path);
            if (a != null && !e.equals(a)) {
                found.add("content differs: " + path + "\n" + lineDiff(e, a));
            }
        }
        return new TreeDiff(found);
    }

    private static String lineDiff(String expected, String actual) {
        List<String> e = Arrays.asList(expected.split("\n", -1));
        List<String> a = Arrays.asList(actual.split("\n", -1));
        StringBuilder out = new StringBuilder();
        for (String line : e) {
            if (!a.contains(line)) {
                out.append("    - expected: ").append(line.strip()).append('\n');
            }
        }
        for (String line : a) {
            if (!e.contains(line)) {
                out.append("    + actual:   ").append(line.strip()).append('\n');
            }
        }
        if (out.length() == 0) {
            out.append("    same lines in a different order or multiplicity\n");
        }
        return out.toString().stripTrailing();
    }

    public boolean isEmpty() {
        return differences.isEmpty();
    }

    public List<String> differences() {
        return differences;
    }

    public String report() {
        return differences.isEmpty() ? "no differences" : String.join("\n", differences);
    }

    /** Throws an {@link AssertionError} carrying the report when the trees differ. */
    public void assertNoDifference() {
        if (!differences.isEmpty()) {
            throw new AssertionError("trees differ (" + differences.size() + "):\n" + report());
        }
    }
}
