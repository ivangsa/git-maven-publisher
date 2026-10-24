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
