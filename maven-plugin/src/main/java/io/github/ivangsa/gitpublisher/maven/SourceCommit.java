/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
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
