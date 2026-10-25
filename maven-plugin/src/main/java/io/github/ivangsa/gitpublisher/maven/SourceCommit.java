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
package io.github.ivangsa.gitpublisher.maven;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/** The commit of the source project that produced a publication, for the {@code source:} line of the commit message. */
final class SourceCommit {

    private SourceCommit() {}

    /** {@code GITHUB_SHA} when set, otherwise the HEAD of the repository enclosing {@code directory}, if any. */
    static Optional<String> find(Path directory, Function<String, String> environment) {
        String fromCi = environment.apply("GITHUB_SHA");
        if (fromCi != null && !fromCi.isBlank()) {
            return Optional.of(fromCi.trim());
        }
        try (Repository repository = new FileRepositoryBuilder()
                .findGitDir(directory.toFile())
                .build()) {
            if (repository.getDirectory() == null) {
                return Optional.empty();
            }
            ObjectId head = repository.resolve("HEAD");
            return Optional.ofNullable(head).map(ObjectId::name);
        } catch (Exception | LinkageError e) {
            return Optional.empty();
        }
    }
}
