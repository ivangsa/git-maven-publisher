/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

/** Turns the served tree into a Git tree and a commit without parents (design section 4, step 3.iv). */
final class TreeCommitter {

    private TreeCommitter() {}

    /** The new commit and its root tree. */
    record Commit(ObjectId commit, ObjectId tree) {}

    /**
     * Inserts every regular file below {@code directory} as a blob, builds the tree and a commit with no parents.
     * Nothing is written to a ref.
     */
    static Commit commit(Repository repository, Path directory, PersonIdent author, String message)
            throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        try (ObjectInserter inserter = repository.newObjectInserter();
                Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                    .toList();
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            for (Path file : files) {
                DirCacheEntry entry = new DirCacheEntry(root.relativize(file).toString().replace('\\', '/'));
                entry.setFileMode(FileMode.REGULAR_FILE);
                try (InputStream in = Files.newInputStream(file)) {
                    entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, Files.size(file), in));
                }
                builder.add(entry);
            }
            builder.finish();
            ObjectId tree = cache.writeTree(inserter);

            PersonIdent now = new PersonIdent(author, new Date());
            CommitBuilder commit = new CommitBuilder();
            commit.setAuthor(now);
            commit.setCommitter(now);
            commit.setMessage(message);
            commit.setTreeId(tree);
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return new Commit(id, tree);
        }
    }
}
