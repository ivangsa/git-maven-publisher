/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a publication contains, for the commit message.
 *
 * @param coordinates {@code groupId:artifactId:version} of everything deployed
 * @param sourceCommit commit of the source repository that was built, if known
 */
public record PublishInfo(List<String> coordinates, Optional<String> sourceCommit) {

    public PublishInfo {
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(sourceCommit, "sourceCommit");
        coordinates = List.copyOf(coordinates);
    }
}
