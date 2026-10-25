/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * Writes checksum files next to a file, in the format Maven Resolver uses: the lower-case hex digest, no newline and
 * no file name (design section 5).
 */
final class ChecksumWriter {

    private ChecksumWriter() {}

    /**
     * Regenerates the checksum files of {@code file} for every algorithm that already has a checksum file next to it,
     * plus the {@code configured} ones.
     *
     * @return the algorithms written
     */
    static Set<ChecksumAlgorithm> write(Path file, Set<ChecksumAlgorithm> configured) throws IOException {
        Set<ChecksumAlgorithm> algorithms = EnumSet.noneOf(ChecksumAlgorithm.class);
        algorithms.addAll(configured);
        for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
            if (Files.isRegularFile(checksumFile(file, algorithm))) {
                algorithms.add(algorithm);
            }
        }
        if (algorithms.isEmpty()) {
            return algorithms;
        }
        byte[] content = Files.readAllBytes(file);
        for (ChecksumAlgorithm algorithm : algorithms) {
            Files.writeString(checksumFile(file, algorithm), hex(algorithm, content), StandardCharsets.US_ASCII);
        }
        return algorithms;
    }

    static Path checksumFile(Path file, ChecksumAlgorithm algorithm) {
        return file.resolveSibling(file.getFileName() + "." + algorithm.extension());
    }

    static String hex(ChecksumAlgorithm algorithm, byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm.jcaName()).digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK lacks " + algorithm.jcaName(), e);
        }
    }
}
