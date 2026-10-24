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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Compares the served tree with the tree of the commit it was materialized from and records what the native deploy
 * added or modified (design section 4, step 3.i). The recorded content is the "ours" side of an overlay replay, so it
 * is read before retention touches the tree.
 */
final class ChangeRecorder {

    private ChangeRecorder() {}

    /**
     * Added and modified files, keyed by slash-separated path relative to the served tree, with their bytes.
     * Deleted files are not recorded.
     */
    record Changes(SortedMap<String, byte[]> added, SortedMap<String, byte[]> modified) {

        /** Added and modified paths together. */
        SortedSet<String> paths() {
            SortedSet<String> paths = new TreeSet<>(added.keySet());
            paths.addAll(modified.keySet());
            return paths;
        }

        /** Added and modified content together. */
        SortedMap<String, byte[]> ours() {
            SortedMap<String, byte[]> all = new TreeMap<>(added);
            all.putAll(modified);
            return all;
        }
    }

    /**
     * @param repository object store that holds {@code base}
     * @param base the commit the served tree was materialized from; empty when the branch did not exist
     * @param directory the served tree
     */
    static Changes record(Repository repository, Optional<ObjectId> base, Path directory) throws IOException {
        Map<String, ObjectId> baseBlobs = baseBlobs(repository, base);
        SortedMap<String, byte[]> added = new TreeMap<>();
        SortedMap<String, byte[]> modified = new TreeMap<>();
        Path root = directory.toAbsolutePath().normalize();
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter();
                Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                    .toList()) {
                String path = root.relativize(file).toString().replace('\\', '/');
                ObjectId existing = baseBlobs.get(path);
                ObjectId current;
                try (InputStream in = Files.newInputStream(file)) {
                    current = formatter.idFor(Constants.OBJ_BLOB, Files.size(file), in);
                }
                if (existing == null) {
                    added.put(path, Files.readAllBytes(file));
                } else if (!existing.equals(current)) {
                    modified.put(path, Files.readAllBytes(file));
                }
            }
        }
        return new Changes(added, modified);
    }

    private static Map<String, ObjectId> baseBlobs(Repository repository, Optional<ObjectId> base) throws IOException {
        Map<String, ObjectId> blobs = new HashMap<>();
        if (base.isEmpty()) {
            return blobs;
        }
        try (RevWalk walk = new RevWalk(repository);
                TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(base.get()).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                blobs.put(tree.getPathString(), tree.getObjectId(0));
            }
        }
        return blobs;
    }
}
