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

import io.github.ivangsa.gitpublisher.ChecksumAlgorithm;
import io.github.ivangsa.gitpublisher.Credentials;
import io.github.ivangsa.gitpublisher.PublishConfig;
import io.github.ivangsa.gitpublisher.RetentionPolicy;
import io.github.ivangsa.gitpublisher.RetryPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.jgit.lib.PersonIdent;

/**
 * The settings of one {@code deploy} execution that must agree across the participants of a reactor (design section 2,
 * D-034), as the user wrote them. {@link #toConfig(Credentials)} validates them and builds the core configuration.
 */
record PublisherParameters(
        String repository,
        String branch,
        boolean nojekyll,
        String serverId,
        int keepSnapshots,
        int keepVersions,
        String checksumAlgorithms,
        int maxAttempts,
        Path workDirectory,
        boolean dryRun,
        String commitAuthor) {

    static final String DEFAULT_COMMIT_AUTHOR =
            PublishConfig.DEFAULT_AUTHOR_NAME + " <" + PublishConfig.DEFAULT_AUTHOR_EMAIL + ">";

    private static final Pattern AUTHOR = Pattern.compile("\\s*([^<>]+?)\\s*<\\s*([^<>\\s]+)\\s*>\\s*");

    PublisherParameters withBranch(String value) {
        return new PublisherParameters(repository, value, nojekyll, serverId, keepSnapshots, keepVersions,
                checksumAlgorithms, maxAttempts, workDirectory, dryRun, commitAuthor);
    }

    PublisherParameters withServerId(String value) {
        return new PublisherParameters(repository, branch, nojekyll, value, keepSnapshots, keepVersions,
                checksumAlgorithms, maxAttempts, workDirectory, dryRun, commitAuthor);
    }

    PublisherParameters withKeepSnapshots(int value) {
        return new PublisherParameters(repository, branch, nojekyll, serverId, value, keepVersions,
                checksumAlgorithms, maxAttempts, workDirectory, dryRun, commitAuthor);
    }

    PublisherParameters withChecksumAlgorithms(String value) {
        return new PublisherParameters(repository, branch, nojekyll, serverId, keepSnapshots, keepVersions,
                value, maxAttempts, workDirectory, dryRun, commitAuthor);
    }

    PublisherParameters withMaxAttempts(int value) {
        return new PublisherParameters(repository, branch, nojekyll, serverId, keepSnapshots, keepVersions,
                checksumAlgorithms, value, workDirectory, dryRun, commitAuthor);
    }

    PublisherParameters withCommitAuthor(String value) {
        return new PublisherParameters(repository, branch, nojekyll, serverId, keepSnapshots, keepVersions,
                checksumAlgorithms, maxAttempts, workDirectory, dryRun, value);
    }

    /** Validates the parameters and builds the core configuration. Messages never repeat the repository URL. */
    PublishConfig toConfig(Credentials credentials) throws MojoFailureException {
        if (repository == null || repository.isBlank()) {
            throw new MojoFailureException(
                    "git-publisher.repository is required (the https or file URL of the Git repository)");
        }
        URI uri;
        try {
            uri = new URI(repository.trim());
        } catch (URISyntaxException e) {
            throw new MojoFailureException("git-publisher.repository is not a valid URI");
        }
        try {
            RetryPolicy defaults = RetryPolicy.defaults();
            return new PublishConfig(
                    uri,
                    branch,
                    credentials,
                    workDirectory,
                    new RetentionPolicy(keepSnapshots, keepVersions),
                    new RetryPolicy(maxAttempts, defaults.baseDelay(), defaults.maxDelay()),
                    author(),
                    dryRun,
                    nojekyll,
                    algorithms());
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException("invalid git-publisher configuration: " + e.getMessage());
        }
    }

    /** Names of the settings that differ from {@code other}, in declaration order. */
    List<String> differencesFrom(PublisherParameters other) {
        List<String> differing = new ArrayList<>();
        if (!Objects.equals(blankToNull(repository), blankToNull(other.repository))) {
            differing.add("repository");
        }
        if (!Objects.equals(branch, other.branch)) {
            differing.add("branch");
        }
        if (nojekyll != other.nojekyll) {
            differing.add("nojekyll");
        }
        if (!Objects.equals(blankToNull(serverId), blankToNull(other.serverId))) {
            differing.add("serverId");
        }
        if (keepSnapshots != other.keepSnapshots) {
            differing.add("keepSnapshots");
        }
        if (keepVersions != other.keepVersions) {
            differing.add("keepVersions");
        }
        if (!sameAlgorithms(other)) {
            differing.add("checksumAlgorithms");
        }
        if (maxAttempts != other.maxAttempts) {
            differing.add("maxAttempts");
        }
        if (!workDirectory.toAbsolutePath().normalize().equals(other.workDirectory.toAbsolutePath().normalize())) {
            differing.add("workDirectory");
        }
        if (dryRun != other.dryRun) {
            differing.add("dryRun");
        }
        if (!Objects.equals(blankToNull(commitAuthor), blankToNull(other.commitAuthor))) {
            differing.add("commitAuthor");
        }
        return differing;
    }

    /** The algorithms in the form Resolver reads from {@code aether.checksums.algorithms}, for example {@code SHA-1,MD5}. */
    String resolverChecksumList() throws MojoFailureException {
        return algorithms().stream().map(ChecksumAlgorithm::jcaName).collect(Collectors.joining(","));
    }

    Set<ChecksumAlgorithm> algorithms() throws MojoFailureException {
        Set<ChecksumAlgorithm> result = EnumSet.noneOf(ChecksumAlgorithm.class);
        for (String name : checksumAlgorithms == null ? new String[0] : checksumAlgorithms.split(",")) {
            if (name.isBlank()) {
                continue;
            }
            result.add(ChecksumAlgorithm.parse(name)
                    .orElseThrow(() -> new MojoFailureException("unknown checksum algorithm '" + name.trim()
                            + "' in git-publisher.checksumAlgorithms (use MD5, SHA-1, SHA-256, SHA-512)")));
        }
        if (result.isEmpty()) {
            throw new MojoFailureException("git-publisher.checksumAlgorithms must name at least one algorithm");
        }
        return result;
    }

    private PersonIdent author() throws MojoFailureException {
        Matcher matcher = AUTHOR.matcher(commitAuthor == null ? "" : commitAuthor);
        if (!matcher.matches()) {
            throw new MojoFailureException("git-publisher.commitAuthor must look like 'Name <email>'");
        }
        return new PersonIdent(matcher.group(1), matcher.group(2));
    }

    private boolean sameAlgorithms(PublisherParameters other) {
        try {
            return algorithms().equals(other.algorithms());
        } catch (MojoFailureException e) {
            return Objects.equals(checksumAlgorithms, other.checksumAlgorithms);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
