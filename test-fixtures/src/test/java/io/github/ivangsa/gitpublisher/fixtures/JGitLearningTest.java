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
package io.github.ivangsa.gitpublisher.fixtures;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.internal.storage.file.ObjectDirectory;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Learning tests: what real JGit does for the operations the publisher relies on (docs/design.md sections 4 and 7),
 * over {@code file://} and over JGit's own HTTP server. Surprises are recorded in docs/decisions.md (D-020).
 */
class JGitLearningTest {

    private static final String BRANCH = "gh-pages";
    private static final String REF = "refs/heads/" + BRANCH;

    @TempDir
    Path tmp;

    private Server server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "http"})
    void depthOneFetchBringsOnlyTheTip(String transport) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            seedHistory(remote, 3);
            URI uri = uri(transport, remote);

            try (Repository local = Git.init().setBare(true).setDirectory(tmp.resolve("local.git").toFile()).call()
                    .getRepository()) {
                Git.wrap(local)
                        .fetch()
                        .setRemote(uri.toString())
                        .setRefSpecs("+" + REF + ":" + REF)
                        .setDepth(1)
                        .call();

                assertThat(local.resolve(REF)).isEqualTo(remote.branchTip(BRANCH).orElseThrow());
                assertThat(commitsReachable(local, REF)).isEqualTo(1);
                assertThat(remote.commitCount(BRANCH)).isEqualTo(3);
                // the shallow boundary is recorded, the URL is not: no remote section in the Git config
                assertThat(tmp.resolve("local.git/shallow")).exists();
                assertThat(local.getConfig().getSubsections("remote")).isEmpty();
                assertThat(Files.readString(tmp.resolve("local.git/config"))).doesNotContain(uri.toString());
            }
        }
    }

    /**
     * Surprise (D-020): a push of a commit WITHOUT parents sends the complete tree again. JGit's pack writer only
     * excludes the objects of remote tips it reaches as boundary commits of the walk, and a parentless commit has no
     * boundary. The remote ends with duplicates of every object.
     */
    @ParameterizedTest
    @ValueSource(strings = {"file", "http"})
    void plainParentlessPushSendsTheWholeTreeAgain(String transport) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            Map<String, byte[]> files = bigTree();
            ObjectId base = remote.commitFiles(BRANCH, files, "base");
            int before = objectCount(remote);
            assertThat(before).isEqualTo(1 + 1 + 5 + 40); // commit, root tree, 5 trees, 40 blobs

            URI uri = uri(transport, remote);
            try (Repository local = fetchTip(uri, "local.git")) {
                files.put("d3/f2.txt", "changed".getBytes(UTF_8));
                ObjectId commit = parentlessCommit(local, files);
                assertThat(new RevWalk(local).parseCommit(commit).getParentCount()).isZero();

                RemoteRefUpdate update = push(local, uri, commit, base);
                assertThat(update.getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);

                // new commit + 46 tree objects, although only 4 are new (blob, tree d3, root tree, commit)
                assertThat(objectCount(remote) - before).isEqualTo(47);
                assertThat(remote.commitCount(BRANCH)).isEqualTo(1);
            }
        }
    }

    /**
     * Workaround: keep a second ref on the remote that points at the current root TREE and move it in the same push.
     * The advertised tree is a "have" for JGit, which marks everything below it uninteresting, so the next parentless
     * push sends only the new objects. Only the very first push (no tree ref yet) sends the whole tree.
     */
    @ParameterizedTest
    @ValueSource(strings = {"file", "http"})
    void parentlessPushWithTreeRefSendsOnlyNewObjects(String transport) throws Exception {
        String treeRef = "refs/publisher/tree";
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            Map<String, byte[]> files = bigTree();
            ObjectId base = remote.commitFiles(BRANCH, files, "base");
            URI uri = uri(transport, remote);

            try (Repository local = fetchTip(uri, "local.git")) {
                ObjectId lastCommit = base;
                ObjectId lastTree = ObjectId.zeroId();
                for (int round = 1; round <= 3; round++) {
                    files.put("d3/f2.txt", ("changed " + round).getBytes(UTF_8));
                    ObjectId commit = parentlessCommit(local, files);
                    ObjectId tree = new RevWalk(local).parseCommit(commit).getTree();
                    int before = objectCount(remote);

                    List<RemoteRefUpdate> updates = List.of(
                            new RemoteRefUpdate(local, commit.name(), REF, true, null, lastCommit),
                            new RemoteRefUpdate(local, tree.name(), treeRef, true, null, lastTree));
                    try (Transport t = Transport.open(local, new URIish(uri.toString()))) {
                        PushResult result = t.push(NullProgressMonitor.INSTANCE, updates);
                        assertThat(result.getRemoteUpdate(REF).getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);
                        assertThat(result.getRemoteUpdate(treeRef).getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);
                    }

                    // round 1 has no tree ref on the remote yet: whole tree. Later rounds: blob, tree d3, root tree, commit.
                    assertThat(objectCount(remote) - before).isEqualTo(round == 1 ? 47 : 4);
                    assertThat(remote.branchTip(BRANCH)).contains(commit);
                    assertThat(remote.repository().resolve(treeRef)).isEqualTo(tree);
                    assertThat(remote.commitCount(BRANCH)).isEqualTo(1);
                    lastCommit = commit;
                    lastTree = tree;
                }
                assertThat(new String(remote.readTree(BRANCH).get("d3/f2.txt"), UTF_8)).isEqualTo("changed 3");
                assertThat(remote.readTree(BRANCH)).hasSize(40);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "http"})
    void forcedPushWithExpectedOldIdIsAcceptedOrRejected(String transport) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            Map<String, byte[]> files = Map.of("a.txt", "1".getBytes(UTF_8));
            ObjectId first = remote.commitFiles(BRANCH, files, "first");
            URI uri = uri(transport, remote);

            try (Repository local = fetchTip(uri, "local.git")) {
                // somebody else pushes in between
                ObjectId competing = remote.commitFiles(BRANCH, Map.of("b.txt", "x".getBytes(UTF_8)), "competing");

                ObjectId ours = parentlessCommit(local, Map.of("a.txt", "2".getBytes(UTF_8)));
                RemoteRefUpdate stale = push(local, uri, ours, first);
                assertThat(stale.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED);
                assertThat(remote.branchTip(BRANCH)).contains(competing);

                // with the right expected id the same forced push wins and replaces the history
                RemoteRefUpdate fresh = push(local, uri, ours, competing);
                assertThat(fresh.getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);
                assertThat(remote.branchTip(BRANCH)).contains(ours);
                assertThat(remote.commitCount(BRANCH)).isEqualTo(1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "http"})
    void createOnlyPushWithZeroId(String transport) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"));
                Repository local = Git.init().setBare(true).setDirectory(tmp.resolve("local.git").toFile()).call()
                        .getRepository()) {
            URI uri = uri(transport, remote);
            ObjectId first = parentlessCommit(local, Map.of("a.txt", "1".getBytes(UTF_8)));

            RemoteRefUpdate created = push(local, uri, first, ObjectId.zeroId());
            assertThat(created.getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);
            assertThat(remote.branchTip(BRANCH)).contains(first);

            // the branch exists now: a second create-only push loses, nothing is overwritten
            ObjectId second = parentlessCommit(local, Map.of("a.txt", "2".getBytes(UTF_8)));
            RemoteRefUpdate again = push(local, uri, second, ObjectId.zeroId());
            assertThat(again.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED);
            assertThat(remote.branchTip(BRANCH)).contains(first);
        }
    }

    // ---- helpers ----

    private static Map<String, byte[]> bigTree() {
        // 5 directories x 8 files
        Map<String, byte[]> files = new TreeMap<>();
        for (int d = 0; d < 5; d++) {
            for (int f = 0; f < 8; f++) {
                files.put("d" + d + "/f" + f + ".txt", ("content " + d + "/" + f).getBytes(UTF_8));
            }
        }
        return files;
    }

    private void seedHistory(BareRepo remote, int commits) {
        for (int i = 1; i <= commits; i++) {
            remote.commitFiles(BRANCH, Map.of("file.txt", ("v" + i).getBytes(UTF_8)), "commit " + i);
        }
    }

    private Repository fetchTip(URI uri, String name) throws Exception {
        Repository local = Git.init().setBare(true).setDirectory(tmp.resolve(name).toFile()).call().getRepository();
        Git.wrap(local)
                .fetch()
                .setRemote(uri.toString())
                .setRefSpecs("+" + REF + ":" + REF)
                .setDepth(1)
                .call();
        return local;
    }

    /** A commit without parents whose tree is exactly {@code files}. Blobs already present are not rewritten. */
    private static ObjectId parentlessCommit(Repository local, Map<String, byte[]> files) throws IOException {
        try (ObjectInserter inserter = local.newObjectInserter()) {
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, file.getValue()));
                builder.add(entry);
            }
            builder.finish();
            CommitBuilder commit = new CommitBuilder();
            PersonIdent ident = new PersonIdent("test", "test@example.invalid");
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("parentless");
            commit.setTreeId(cache.writeTree(inserter));
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return id;
        }
    }

    /** Forced push of {@code commit} to {@link #REF} with the lease {@code expectedOld}; the URL is passed per call. */
    private static RemoteRefUpdate push(Repository local, URI uri, ObjectId commit, ObjectId expectedOld)
            throws Exception {
        RemoteRefUpdate update =
                new RemoteRefUpdate(local, commit.name(), REF, true, null, expectedOld);
        try (Transport transport = Transport.open(local, new URIish(uri.toString()))) {
            PushResult result = transport.push(NullProgressMonitor.INSTANCE, List.of(update));
            return result.getRemoteUpdate(REF);
        }
    }

    private static int commitsReachable(Repository repo, String ref) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(repo.resolve(ref)));
            int count = 0;
            for (RevCommit ignored : walk) {
                count++;
            }
            return count;
        }
    }

    /** Loose objects plus objects stored in packs: the receiving side may keep a pushed pack as it is. */
    private static int objectCount(BareRepo remote) throws IOException {
        ObjectDirectory objects = (ObjectDirectory) remote.repository().getObjectDatabase();
        objects.close(); // forget cached pack list
        int packed = objects.getPacks().stream()
                .mapToInt(p -> (int) java.util.stream.StreamSupport.stream(p.spliterator(), false).count())
                .sum();
        try (Stream<Path> walk = Files.walk(remote.directory().resolve("objects"))) {
            int loose = (int) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getParent().getFileName().toString().length() == 2)
                    .count();
            return loose + packed;
        }
    }

    private URI uri(String transport, BareRepo remote) throws Exception {
        if (transport.equals("file")) {
            return remote.uri();
        }
        StoredConfig config = remote.repository().getConfig();
        config.setBoolean("http", null, "receivepack", true);
        config.save();

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/");
        GitServlet servlet = new GitServlet();
        Map<String, Repository> repos = new HashMap<>();
        repos.put("remote.git", remote.repository());
        servlet.setRepositoryResolver((req, name) -> {
            Repository repo = repos.get(name);
            repo.incrementOpen();
            return repo;
        });
        context.addServlet(new ServletHolder(servlet), "/*");
        server.setHandler(context);
        server.start();
        return URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/remote.git");
    }
}
