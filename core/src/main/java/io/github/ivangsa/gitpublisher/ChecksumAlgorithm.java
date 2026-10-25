/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Checksum algorithms of a Maven repository: file extension and JCA name. */
public enum ChecksumAlgorithm {
    MD5("md5", "MD5"),
    SHA1("sha1", "SHA-1"),
    SHA256("sha256", "SHA-256"),
    SHA512("sha512", "SHA-512");

    private final String extension;
    private final String jcaName;

    ChecksumAlgorithm(String extension, String jcaName) {
        this.extension = extension;
        this.jcaName = jcaName;
    }

    /** File extension without the dot, for example {@code sha1}. */
    public String extension() {
        return extension;
    }

    /** Name for {@link java.security.MessageDigest}, for example {@code SHA-1}. */
    public String jcaName() {
        return jcaName;
    }

    /** Accepts Resolver names ({@code SHA-1}), extensions ({@code sha1}) and any letter case. */
    public static Optional<ChecksumAlgorithm> parse(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT).replace("-", "");
        return fromExtension(normalized);
    }

    static Optional<ChecksumAlgorithm> fromExtension(String extension) {
        return Arrays.stream(values()).filter(a -> a.extension.equals(extension)).findFirst();
    }
}
