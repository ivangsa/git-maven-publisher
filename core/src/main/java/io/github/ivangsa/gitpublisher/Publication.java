/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;

/** A checked-out publish branch the native deploy writes into (design section 3). Closing releases the object store. */
public interface Publication extends AutoCloseable {

    /** Directory the native deploy writes to (the target of a {@code file://} repository). */
    Path repositoryDirectory();

    /** Tip of the publish branch when it was fetched; empty when the branch did not exist. */
    Optional<ObjectId> expectedSha();

    /** Steps 3 to 6 of the publication algorithm (design section 4), including retries. */
    PublishResult publish(PublishInfo info) throws PublishException;

    @Override
    void close();
}
