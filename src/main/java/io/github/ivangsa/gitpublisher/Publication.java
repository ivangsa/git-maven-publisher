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
