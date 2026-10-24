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
