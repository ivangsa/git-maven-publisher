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
