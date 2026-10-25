/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Writes the tree of a commit into a plain directory (no {@code .git}), as a static server would serve it. */
final class TreeMaterializer {

    private TreeMaterializer() {}

    /**
     * Writes every file of the tree of {@code commit} below {@code target} (which must exist). Symbolic links and
     * submodules are refused: a tree that cannot be reproduced would silently lose them on the next publication.
     */
    static void materialize(Repository repository, ObjectId commit, Path target) throws IOException, PublishException {
        Path root = target.toAbsolutePath().normalize();
        try (RevWalk walk = new RevWalk(repository);
                TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(commit).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                FileMode mode = tree.getFileMode(0);
                if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
                    throw new PublishException("the publish branch contains an entry that is not a regular file ("
                            + path + "); symbolic links and submodules are not supported");
                }
                Path out = root.resolve(path).normalize();
                if (!out.startsWith(root) || out.equals(root)) {
                    throw new PublishException("the publish branch contains an unsafe path: " + path);
                }
                Files.createDirectories(out.getParent());
                ObjectLoader loader = repository.open(tree.getObjectId(0));
                try (InputStream in = loader.openStream()) {
                    Files.copy(in, out);
                }
            }
        }
    }
}
