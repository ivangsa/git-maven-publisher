/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Entry point of the core (design section 3): turns a Git branch into a directory the native deploy writes into, and
 * later publishes that directory as one parentless commit.
 */
public final class GitPublisher {

    static final String NOJEKYLL = ".nojekyll";

    private final PublishConfig config;
    private final PublisherLog log;

    public GitPublisher(PublishConfig config, PublisherLog log) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Step 1 of the publication algorithm (design section 4): cleans {@code git/} and {@code repo/} in the work
     * directory, fetches the tip of the publish branch (depth 1), materializes its tree into {@code repo/} and writes
     * an empty {@code .nojekyll} when enabled. A missing branch starts empty.
     */
    public Publication checkout() throws PublishException {
        Path gitDirectory = config.workDirectory().resolve("git");
        Path repoDirectory = config.workDirectory().resolve("repo");
        Repository git = null;
        try {
            FileTrees.deleteRecursively(gitDirectory);
            FileTrees.deleteRecursively(repoDirectory);
            Files.createDirectories(repoDirectory);
            git = Git.init().setBare(true).setDirectory(gitDirectory.toFile()).call().getRepository();

            Optional<ObjectId> tip = new GitRemote(config).fetchTip(git);
            if (tip.isPresent()) {
                TreeMaterializer.materialize(git, tip.get(), repoDirectory);
                log.info("Checked out branch " + config.branch() + " at " + tip.get().name());
            } else {
                log.info("Branch " + config.branch() + " does not exist yet; starting empty");
            }
            if (config.nojekyll()) {
                Files.write(repoDirectory.resolve(NOJEKYLL), new byte[0]);
            }
            return new GitPublication(config, log, git, repoDirectory, tip);
        } catch (PublishException e) {
            closeQuietly(git);
            throw e;
        } catch (IOException | GitAPIException | RuntimeException e) {
            closeQuietly(git);
            throw new PublishException("cannot check out branch '" + config.branch() + "': " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(Repository repository) {
        if (repository != null) {
            repository.close();
        }
    }
}
