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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/** The checked-out state behind {@link Publication}. */
final class GitPublication implements Publication {

    private final PublishConfig config;
    private final PublisherLog log;
    private final Repository git;
    private final Path repositoryDirectory;
    private Optional<ObjectId> expectedSha;

    /** Test seam: the remote used for fetching and pushing. */
    java.util.function.Function<PublishConfig, GitRemote> remotes = GitRemote::new;

    GitPublication(
            PublishConfig config,
            PublisherLog log,
            Repository git,
            Path repositoryDirectory,
            Optional<ObjectId> expectedSha) {
        this.config = config;
        this.log = log;
        this.git = git;
        this.repositoryDirectory = repositoryDirectory;
        this.expectedSha = expectedSha;
    }

    @Override
    public Path repositoryDirectory() {
        return repositoryDirectory;
    }

    @Override
    public Optional<ObjectId> expectedSha() {
        return expectedSha;
    }

    @Override
    public PublishResult publish(PublishInfo info) throws PublishException {
        ChangeRecorder.Changes ours;
        try {
            ours = ChangeRecorder.record(git, expectedSha, repositoryDirectory);
        } catch (IOException e) {
            throw new PublishException("cannot publish to branch '" + config.branch() + "': " + e.getMessage(), e);
        }
        SortedMap<String, byte[]> oursContent = ours.ours();

        Set<Path> touched = new LinkedHashSet<>();
        for (String path : ours.paths()) {
            int slash = path.lastIndexOf('/');
            if (slash > 0) {
                String directory = path.substring(0, slash);
                if (directory.endsWith("-SNAPSHOT")) {
                    touched.add(Path.of(directory));
                }
            }
        }

        int maxAttempts = config.retry().maxAttempts();
        String lastFailure = "";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (attempt > 1) {
                    replayOntoRemote(oursContent);
                }
                Set<String> deletedPaths = new TreeSet<>();
                for (Path path : Retention.apply(
                        repositoryDirectory, touched, config.retention(), config.checksumAlgorithms())) {
                    deletedPaths.add(path.toString().replace('\\', '/'));
                }

                IntegrityCheck.verify(repositoryDirectory, config.checksumAlgorithms());

                TreeCommitter.Commit commit =
                        TreeCommitter.commit(git, repositoryDirectory, config.author(), message(info));
                if (config.dryRun()) {
                    log.info("Dry run: built commit " + commit.commit().name() + ", nothing pushed");
                    return new PublishResult(commit.commit(), 0, ours.paths(), deletedPaths, false);
                }

                GitRemote.PushOutcome outcome =
                        remotes.apply(config).push(git, commit.commit(), commit.tree(), expectedSha);
                if (outcome instanceof GitRemote.PushOutcome.Pushed) {
                    log.info("Pushed " + commit.commit().name() + " to branch " + config.branch());
                    return new PublishResult(commit.commit(), attempt, ours.paths(), deletedPaths, true);
                }
                lastFailure = ((GitRemote.PushOutcome.LeaseFailed) outcome).reason();
            } catch (IOException | GitAPIException e) {
                if (isFatal(e)) {
                    throw new PublishException("cannot publish to branch '" + config.branch() + "': " + e.getMessage(), e);
                }
                lastFailure = "transport error: " + e.getMessage();
            }
            if (attempt < maxAttempts) {
                long delay = backoffMillis(attempt);
                log.warn("Attempt " + attempt + " of " + maxAttempts + " failed (" + lastFailure + "); retrying in "
                        + delay + " ms");
                sleep(delay);
            }
        }
        throw new PublishException("cannot publish to branch '" + config.branch() + "' after " + maxAttempts
                + (maxAttempts == 1 ? " attempt: " : " attempts: ") + lastFailure + "; nothing was pushed");
    }

    /** Fetches the current tip, rebuilds the served tree from it and lays this publication's own changes over it. */
    private void replayOntoRemote(SortedMap<String, byte[]> ours) throws IOException, GitAPIException, PublishException {
        Optional<ObjectId> tip = remotes.apply(config).fetchTip(git);
        FileTrees.deleteRecursively(repositoryDirectory);
        Files.createDirectories(repositoryDirectory);
        OverlayReplay.replay(git, expectedSha, tip, ours, repositoryDirectory);
        expectedSha = tip;
        log.info("The branch " + config.branch() + " moved; replayed " + ours.size() + " changed files onto "
                + tip.map(ObjectId::name).orElse("an empty branch"));
    }

    /** Authentication failures and a missing repository do not get better by waiting. */
    static boolean isFatal(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof NoRemoteRepositoryException) {
                return true;
            }
            if (t instanceof TransportException || t instanceof org.eclipse.jgit.api.errors.TransportException) {
                String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
                if (message.contains("not authorized")
                        || message.contains("unauthorized")
                        || message.contains("authentication")
                        || message.contains("forbidden")
                        || message.contains("invalid credentials")
                        || message.contains("auth fail")
                        || message.contains(" 401")
                        || message.contains(" 403")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Full jitter: {@code random(0, min(maxDelay, baseDelay * 2^(attempt-1)))} (design section 7). */
    private long backoffMillis(int attempt) {
        RetryPolicy retry = config.retry();
        long base = retry.baseDelay().toMillis();
        long cap = retry.maxDelay().toMillis();
        long ceiling = attempt > 40 ? cap : Math.min(cap, base << (attempt - 1));
        return ceiling <= 0 ? 0 : ThreadLocalRandom.current().nextLong(ceiling + 1);
    }

    private static void sleep(long millis) throws PublishException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("interrupted while waiting to retry the publication", e);
        }
    }

    /** {@code Publish <coords>}, a blank line, and {@code source: <sha>} when known (design section 4, step 3.iv). */
    static String message(PublishInfo info) {
        StringBuilder message = new StringBuilder("Publish");
        if (info.coordinates().isEmpty()) {
            message.append(" changes");
        } else {
            message.append(' ').append(String.join(", ", info.coordinates()));
        }
        info.sourceCommit().ifPresent(sha -> message.append("\n\nsource: ").append(sha));
        return message.toString();
    }

    @Override
    public void close() {
        git.close();
    }
}
