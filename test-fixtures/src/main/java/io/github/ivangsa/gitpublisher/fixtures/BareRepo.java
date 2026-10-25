/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * A bare Git repository on disk, standing in for the remote (GitHub). Reads the tree and history of a branch and
 * checks the branch tip out into a plain directory, which is what a static server would serve.
 */
public final class BareRepo implements AutoCloseable {

    private final Path directory;
    private final Repository repository;

    private BareRepo(Path directory, Repository repository) {
        this.directory = directory;
        this.repository = repository;
    }

    /** Creates an empty bare repository in {@code directory} (created if needed). */
    public static BareRepo create(Path directory) {
        try {
            Files.createDirectories(directory);
            Git git = Git.init().setBare(true).setDirectory(directory.toFile()).call();
            return new BareRepo(directory, git.getRepository());
        } catch (Exception e) {
            throw new IllegalStateException("cannot create bare repository in " + directory, e);
        }
    }

    public Path directory() {
        return directory;
    }

    /** {@code file://} URL usable as a Git remote. */
    public URI uri() {
        return directory.toAbsolutePath().toUri();
    }

    public Repository repository() {
        return repository;
    }

    public Optional<ObjectId> branchTip(String branch) {
        try {
            return Optional.ofNullable(repository.resolve("refs/heads/" + branch));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Number of commits reachable from the branch tip (0 when the branch does not exist). */
    public int commitCount(String branch) {
        Optional<ObjectId> tip = branchTip(branch);
        if (tip.isEmpty()) {
            return 0;
        }
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(tip.get()));
            int count = 0;
            for (RevCommit ignored : walk) {
                count++;
            }
            return count;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All files of the branch tip, keyed by slash-separated path. Empty when the branch does not exist. */
    public Map<String, byte[]> readTree(String branch) {
        Map<String, byte[]> files = new TreeMap<>();
        Optional<ObjectId> tip = branchTip(branch);
        if (tip.isEmpty()) {
            return files;
        }
        try (RevWalk walk = new RevWalk(repository);
                TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(tip.get()).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                ObjectLoader loader = repository.open(tree.getObjectId(0));
                files.put(tree.getPathString(), loader.getBytes());
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Makes {@code target} (created if needed) contain exactly the files of the branch tip: previous content is removed. No {@code .git} directory is
     * created, so the result is a plain tree, as a static server serves it.
     */
    public Path checkout(String branch, Path target) {
        if (branchTip(branch).isEmpty()) {
            throw new IllegalStateException("branch does not exist: " + branch);
        }
        try {
            Files.createDirectories(target);
            clear(target);
            for (Map.Entry<String, byte[]> file : readTree(branch).entrySet()) {
                Path out = target.resolve(file.getKey());
                Files.createDirectories(out.getParent());
                Files.write(out, file.getValue());
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes everything inside {@code directory} (files, directories, links), keeping the directory itself. */
    private static void clear(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                if (Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    clear(entry);
                }
                Files.delete(entry);
            }
        }
    }

    /**
     * Commits {@code files} as the whole tree of the branch, with the previous tip as parent (or as a root commit if
     * the branch does not exist). For tests that need history without a clone.
     */
    public ObjectId commitFiles(String branch, Map<String, byte[]> files, String message) {
        try (var inserter = repository.newObjectInserter()) {
            var builder = org.eclipse.jgit.dircache.DirCache.newInCore().builder();
            for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
                var entry = new org.eclipse.jgit.dircache.DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, file.getValue()));
                builder.add(entry);
            }
            builder.finish();
            ObjectId tree = builder.getDirCache().writeTree(inserter);

            var commit = new org.eclipse.jgit.lib.CommitBuilder();
            var ident = new org.eclipse.jgit.lib.PersonIdent("test", "test@example.invalid");
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(message);
            commit.setTreeId(tree);
            branchTip(branch).ifPresent(commit::setParentId);
            ObjectId id = inserter.insert(commit);
            inserter.flush();

            var update = repository.updateRef("refs/heads/" + branch);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            update.update();
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        repository.close();
    }
}
