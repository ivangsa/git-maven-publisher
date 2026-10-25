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
package io.github.ivangsa.gitpublisher;

import java.net.URI;

/**
 * Retention limits (design section 6).
 *
 * @param keepSnapshots timestamped builds kept per {@code -SNAPSHOT} version, at least 1
 * @param keepVersions {@code -SNAPSHOT} versions kept per {@code groupId:artifactId}; {@code -1} means unlimited
 * @param releaseRepository Maven repository (directory layout, {@code https} or {@code file}) asked whether the
 *     release of a {@code -SNAPSHOT} version exists; such versions are dropped (design section 6, D-106). {@code null}
 *     switches the check off, which is the default; {@link #CENTRAL} is Maven Central
 */
public record RetentionPolicy(int keepSnapshots, int keepVersions, URI releaseRepository) {

    public static final int UNLIMITED = -1;
    public static final URI CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");

    public RetentionPolicy(int keepSnapshots, int keepVersions) {
        this(keepSnapshots, keepVersions, null);
    }

    public RetentionPolicy {
        if (keepSnapshots < 1) {
            throw new IllegalArgumentException("keepSnapshots must be at least 1, got " + keepSnapshots);
        }
        if (keepVersions != UNLIMITED && keepVersions < 1) {
            throw new IllegalArgumentException("keepVersions must be -1 (unlimited) or at least 1, got " + keepVersions);
        }
        if (releaseRepository != null) {
            String scheme = releaseRepository.getScheme() == null ? "" : releaseRepository.getScheme().toLowerCase();
            if (!scheme.equals("https") && !scheme.equals("file")) {
                throw new IllegalArgumentException("releaseRepository must use https or file");
            }
            if (releaseRepository.getRawUserInfo() != null) {
                throw new IllegalArgumentException("releaseRepository must not contain credentials");
            }
        }
    }

    public RetentionPolicy withReleaseRepository(URI repository) {
        return new RetentionPolicy(keepSnapshots, keepVersions, repository);
    }

    public static RetentionPolicy defaults() {
        return new RetentionPolicy(3, UNLIMITED);
    }
}
