/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeSet;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Replays the paths a publication changed ("ours") onto a newer tip of the branch (design section 7). A path is written
 * when the remote did not touch it since the base, kept when the remote changed it to the very same bytes, and a
 * conflict otherwise. A remote change is never overwritten.
 */
final class OverlayReplay {

    private OverlayReplay() {}

    /**
     * Materializes {@code remoteTip} into the empty {@code target} and lays {@code ours} over it.
     *
     * @param base the commit the publication was based on; empty when the branch did not exist
     * @param remoteTip the tip just fetched; empty when the branch does not exist (any more)
     * @throws PublishException on a conflict, before anything is written
     */
    static void replay(
            Repository repository,
            Optional<ObjectId> base,
            Optional<ObjectId> remoteTip,
            SortedMap<String, byte[]> ours,
            Path target)
            throws IOException, PublishException {
        Map<String, ObjectId> baseBlobs = blobs(repository, base);
        Map<String, ObjectId> remoteBlobs = blobs(repository, remoteTip);

        TreeSet<String> conflicts = new TreeSet<>();
        TreeSet<String> write = new TreeSet<>();
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
            for (Map.Entry<String, byte[]> entry : ours.entrySet()) {
                String path = entry.getKey();
                ObjectId remote = remoteBlobs.get(path);
                ObjectId ourId = formatter.idFor(Constants.OBJ_BLOB, entry.getValue());
                if (java.util.Objects.equals(remote, baseBlobs.get(path))) {
                    write.add(path);
                } else if (!ourId.equals(remote)) {
                    conflicts.add(path);
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw conflict(conflicts);
        }

        if (remoteTip.isPresent()) {
            TreeMaterializer.materialize(repository, remoteTip.get(), target);
        }
        for (String path : write) {
            Path out = target.resolve(path);
            Files.createDirectories(out.getParent());
            Files.write(out, ours.get(path));
        }
    }

    static PublishException conflict(java.util.Collection<String> paths) {
        StringBuilder message = new StringBuilder(
                "the publication conflicts with changes pushed to the branch since it was checked out; nothing was pushed."
                        + " Conflicting paths:");
        for (String path : paths) {
            message.append("\n  ").append(path);
        }
        message.append("\nPublications that share these paths (same groupId:artifactId, or shared G-level metadata)"
                + " must be serialized externally, for example with a CI concurrency group (design section 7).");
        return new PublishException(message.toString());
    }

    private static Map<String, ObjectId> blobs(Repository repository, Optional<ObjectId> commit) throws IOException {
        Map<String, ObjectId> blobs = new HashMap<>();
        if (commit.isEmpty()) {
            return blobs;
        }
        try (RevWalk walk = new RevWalk(repository);
                TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(commit.get()).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                blobs.put(tree.getPathString(), tree.getObjectId(0));
            }
        }
        return blobs;
    }
}
