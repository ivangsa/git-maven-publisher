/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

/**
 * Everything the core needs to publish (design section 3).
 *
 * <p>The repository URL must use {@code https} or {@code file} and must not carry credentials (userinfo). Validation
 * errors never echo the URL, because it may contain a secret that was put there by mistake.
 *
 * @param repository Git remote, {@code https} or {@code file}, without userinfo
 * @param branch branch to publish to
 * @param credentials how to authenticate
 * @param workDirectory contains {@code repo/} (served tree) and {@code git/} (object store)
 * @param author commit author and committer
 * @param dryRun build the commit but do not push
 * @param nojekyll write an empty {@code .nojekyll} at the root of every published tree (D-043)
 * @param checksumAlgorithms algorithms of the checksum files; never empty, default SHA-1 and MD5 (D-053)
 */
public record PublishConfig(
        URI repository,
        String branch,
        Credentials credentials,
        Path workDirectory,
        RetentionPolicy retention,
        RetryPolicy retry,
        PersonIdent author,
        boolean dryRun,
        boolean nojekyll,
        Set<ChecksumAlgorithm> checksumAlgorithms) {

    public static final Set<ChecksumAlgorithm> DEFAULT_CHECKSUMS =
            Collections.unmodifiableSet(EnumSet.of(ChecksumAlgorithm.MD5, ChecksumAlgorithm.SHA1));
    public static final String DEFAULT_BRANCH = "gh-pages";
    public static final String DEFAULT_AUTHOR_NAME = "git-maven-publisher";
    public static final String DEFAULT_AUTHOR_EMAIL = "git-maven-publisher@users.noreply.github.com";

    public PublishConfig {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(workDirectory, "workDirectory");
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(checksumAlgorithms, "checksumAlgorithms");
        if (checksumAlgorithms.isEmpty()) {
            throw new IllegalArgumentException("checksumAlgorithms must not be empty");
        }
        checksumAlgorithms = Collections.unmodifiableSet(EnumSet.copyOf(checksumAlgorithms));
        validateRepository(repository);
        if (branch.isBlank()
                || branch.startsWith("-")
                || branch.chars().anyMatch(Character::isWhitespace)
                || !Repository.isValidRefName(Constants.R_HEADS + branch)) {
            throw new IllegalArgumentException("invalid branch name: '" + branch + "'");
        }
    }

    /** Defaults for everything but the repository and the work directory. */
    public static PublishConfig of(URI repository, Path workDirectory) {
        return new PublishConfig(
                repository,
                DEFAULT_BRANCH,
                Credentials.none(),
                workDirectory,
                RetentionPolicy.defaults(),
                RetryPolicy.defaults(),
                defaultAuthor(),
                false,
                false,
                DEFAULT_CHECKSUMS);
    }

    public static PersonIdent defaultAuthor() {
        return new PersonIdent(DEFAULT_AUTHOR_NAME, DEFAULT_AUTHOR_EMAIL);
    }

    private static void validateRepository(URI repository) {
        String scheme = repository.getScheme();
        String normalized = scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
        if (!"https".equals(normalized) && !"file".equals(normalized)) {
            throw new IllegalArgumentException("repository URL scheme must be https or file, got "
                    + (scheme == null ? "none" : "'" + scheme + "'"));
        }
        String authority = repository.getRawAuthority();
        String opaquePart = repository.getRawSchemeSpecificPart();
        if (repository.getRawUserInfo() != null
                || (authority != null && authority.indexOf('@') >= 0)
                || (repository.isOpaque() && opaquePart != null && opaquePart.indexOf('@') >= 0)) {
            throw new IllegalArgumentException(
                    "repository URL must not contain credentials; configure a token instead (serverId, GIT_PUBLISHER_TOKEN)");
        }
        if ("https".equals(normalized) && (repository.getHost() == null || repository.getHost().isEmpty())) {
            throw new IllegalArgumentException("https repository URL must have a host");
        }
    }
}
