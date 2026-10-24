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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * Talks to the remote. The URL is passed per call and never stored in the object store's configuration; the token goes
 * only to JGit's credentials provider (design section 8).
 */
class GitRemote {

    private final PublishConfig config;

    GitRemote(PublishConfig config) {
        this.config = config;
    }

    /**
     * Fetches the tip of the publish branch (depth 1) into {@code git}, as {@code refs/heads/<branch>}.
     *
     * @return the fetched tip, or empty when the remote has no such branch
     */
    Optional<ObjectId> fetchTip(Repository git) throws GitAPIException, java.io.IOException {
        String ref = Constants.R_HEADS + config.branch();
        String url = config.repository().toString();
        Collection<Ref> advertised = authenticate(Git.lsRemoteRepository().setRemote(url).setHeads(true).setTags(false))
                .call();
        if (advertised.stream().noneMatch(r -> r.getName().equals(ref))) {
            return Optional.empty();
        }
        authenticate(Git.wrap(git).fetch())
                .setRemote(url)
                .setRefSpecs("+" + ref + ":" + ref)
                .setDepth(1)
                .setTagOpt(TagOpt.NO_TAGS)
                .call();
        return Optional.ofNullable(git.resolve(ref));
    }

    /** Hint ref that points at the root tree of the published commit, so later parentless pushes send only new objects (D-020). */
    static final String TREE_REF = "refs/publisher/tree";

    /** Result of one push attempt. */
    sealed interface PushOutcome {
        /** The branch was updated. */
        record Pushed() implements PushOutcome {}

        /** The branch moved since {@code expectedOldId} was read (or was created meanwhile): re-fetch and replay. */
        record LeaseFailed(String reason) implements PushOutcome {}
    }

    /**
     * One forced push of {@code commit} to the publish branch with the lease {@code expectedOld} (zero id when the
     * branch is expected to be missing), together with a forced, lease-free move of {@link #TREE_REF} to {@code tree}.
     * The status of the tree ref is ignored: only the branch update decides (D-027).
     *
     * @throws IOException on transport errors and on a push the remote refuses for a reason other than the lease
     */
    PushOutcome push(Repository git, ObjectId commit, ObjectId tree, Optional<ObjectId> expectedOld)
            throws IOException {
        String branchRef = Constants.R_HEADS + config.branch();
        RemoteRefUpdate branch = new RemoteRefUpdate(
                git, commit.name(), branchRef, true, null, expectedOld.orElse(ObjectId.zeroId()));
        RemoteRefUpdate treeUpdate = new RemoteRefUpdate(git, tree.name(), TREE_REF, true, null, null);
        PushResult result;
        try (Transport transport = Transport.open(git, new URIish(config.repository().toString()))) {
            CredentialsProvider provider = credentialsProvider();
            if (provider != null) {
                transport.setCredentialsProvider(provider);
            }
            result = transport.push(NullProgressMonitor.INSTANCE, List.of(branch, treeUpdate));
        } catch (java.net.URISyntaxException e) {
            throw new IOException("invalid repository URL", e);
        }
        RemoteRefUpdate status = result.getRemoteUpdate(branchRef);
        if (status == null) {
            throw new IOException("the remote did not report a status for " + branchRef);
        }
        return switch (status.getStatus()) {
            case OK, UP_TO_DATE -> new PushOutcome.Pushed();
            case REJECTED_REMOTE_CHANGED -> new PushOutcome.LeaseFailed(
                    "the branch " + config.branch() + " changed on the remote since it was fetched");
            default -> throw new IOException("the remote rejected the push to " + branchRef + ": "
                    + status.getStatus() + (status.getMessage() == null ? "" : " (" + status.getMessage() + ")"));
        };
    }

    private <C extends TransportCommand<C, ?>> C authenticate(C command) {
        CredentialsProvider provider = credentialsProvider();
        if (provider != null) {
            command.setCredentialsProvider(provider);
        }
        return command;
    }

    private CredentialsProvider credentialsProvider() {
        if (config.credentials() instanceof Credentials.Token token) {
            return new UsernamePasswordCredentialsProvider(token.username(), token.token());
        }
        return null;
    }
}
