# Design

Source of truth for contracts. The brief (`docs/brief.md`) says *why*; this file says *what*.
Changes go here first, with a matching entry in `docs/decisions.md`.

## 1. Build and layout

A Maven reactor (D-075, revises D-037) rooted at `pom.xml`, parent `io.github.ivangsa:git-maven-publisher-parent`, with four modules:
`test-fixtures`, `core`, `maven-plugin`. Parent, core and Maven plugin are published; `test-fixtures` is never deployed.
The Gradle adapter is a separate Gradle build in `gradle-plugin/` (D-002), Phase 2, which depends on the published `git-maven-publisher-core` (D-075).

| Part | Location | Notes |
| --- | --- | --- |
| Parent POM | `pom.xml` | Artifact `git-maven-publisher-parent` (packaging `pom`): versions, shared plugins, license check, POM metadata. |
| Publication logic ("core") | `core/src/main/java/io/github/ivangsa/gitpublisher/` | Artifact `git-maven-publisher-core`. JGit + JDK only. No `org.apache.maven` or `org.gradle` imports, enforced by an architecture test in `core/src/test`. |
| Maven adapter | `maven-plugin/src/main/java/io/github/ivangsa/gitpublisher/maven/` | Artifact `git-maven-publisher-maven-plugin` (packaging `maven-plugin`). Mojo, reactor collector. Maven API and Resolver API `provided`. |
| Test helpers (harness) | `test-fixtures/src/main/java/io/github/ivangsa/gitpublisher/fixtures/` | Bare repos, sequential reference, tree diff, consumers, interleaved publish scenarios. Self-tests in `test-fixtures/src/test`. Not deployed (`maven.deploy.skip`) and not in any release bundle. |
| Golden files | `test-fixtures/src/main/resources/golden/` | Source URL + fetch date in `golden/README.md`. |
| Plugin ITs | `maven-plugin/src/it/` | `maven-invoker-plugin`; verify scripts use the harness through `addTestClassPath`. |
| Gradle adapter | `gradle-plugin/` | Own wrapper; depends on `git-maven-publisher-core` (D-075). |

- Dependencies follow the module that uses them: core has JGit at compile scope; test-fixtures has JGit and the Maven oracles; the Maven plugin has the Maven API `provided`. Test-fixtures never depends on core, so the reactor has no cycle.
- Maven Wrapper pinned to 3.10.x. `--release 17`, any JDK ≥ 17 can build. JUnit 5, AssertJ.
- Full build = `./mvnw verify` from the root (all modules, all unit tests, ITs and invoker ITs), plus `./mvnw install` and `./gradlew -p gradle-plugin build` once the Gradle build exists. No Docker needed.
- Three artifacts are published for the first release (T32a): parent, core and Maven plugin.

## 2. Public configuration

### Maven (`git-maven-publisher-maven-plugin`, goal `deploy`, default phase `deploy`)

| Parameter | Property | Default | Notes |
| --- | --- | --- | --- |
| `repository` | `git-publisher.repository` | required | Git URL, `https` or `file` only; other schemes are rejected. Credentials in the URL are rejected. |
| `branch` | `git-publisher.branch` | `gh-pages` | |
| `nojekyll` | `git-publisher.nojekyll` | `false` | Writes an empty `.nojekyll` at the root on every publish, for users who serve the branch with GitHub Pages (D-043). |
| `serverId` | `git-publisher.serverId` | none | `settings.xml` `<server>`; password = token, username defaults to `x-access-token`. Decrypted via `SettingsDecrypter`. |
| `keepSnapshots` | `git-publisher.keepSnapshots` | `3` | ≥ 1 |
| `keepVersions` | `git-publisher.keepVersions` | `-1` (unlimited) | Phase 3 |
| `allowReleases` | `git-publisher.allowReleases` | `false` | Release version fails fast otherwise. |
| `checksumAlgorithms` | `git-publisher.checksumAlgorithms` | `SHA-1,MD5` | Passed to Resolver (`aether.checksums.algorithms`). |
| `maxAttempts` | `git-publisher.maxAttempts` | `5` | |
| `workDirectory` | `git-publisher.workDirectory` | `${session.executionRootDirectory}/target/git-publisher` | |
| `dryRun` | `git-publisher.dryRun` | `false` | Builds the commit, skips push. |
| `skip` | `git-publisher.skip` | `false` | |
| `commitAuthor` | `git-publisher.commitAuthor` | `git-maven-publisher <git-maven-publisher@users.noreply.github.com>` | |

Token resolution order: `serverId` server (if configured and found) → env `GIT_PUBLISHER_TOKEN` → anonymous (only useful for `file://`).
No token parameter or property: tokens never go on the command line or into the POM.

#### Reactor transaction (D-034)

- **Participants:** projects in `session.getProjects()` (the reactor after `-pl`/`-am`/`-rf` filtering) whose effective model,
  with active profiles, binds this plugin's `deploy` goal. Every participant checks in when its execution runs, including
  with `skip=true` (it checks in with nothing to publish). A project that does not bind the goal is not a participant,
  except when no project in the session binds it (the goal was started from the command line, `mvn ...:deploy`): then
  Maven runs it in every project of the session and every project is a participant (D-063). A project that runs the goal
  without being in the set fails with an explanation; the set never grows.
- **Configuration:** every non-skipped participant must resolve the same value for every setting except `skip` and
  `allowReleases`: `repository`, `branch`, `workDirectory`, `serverId` (the credentials source), `nojekyll`, `dryRun`,
  `commitAuthor`, `keepSnapshots`, `keepVersions`, `checksumAlgorithms` and `maxAttempts`, because the single publication
  uses one set of them. A mismatch fails the build before checkout and names the differing settings. A skipped
  participant checks in without settings and is not compared. Per-module `skip` is allowed.
- **Trigger:** the participant that completes the set runs checkout → one `RepositorySystem.deploy` of everything collected →
  publish. Thread-safe under `-T`: completion is a counter over the participant set, not reactor order.
- **Guarantee:** push happens only after every participating execution completed successfully. If a participant fails
  (also with `--fail-at-end`), the set never completes and nothing is pushed.
- **Not guaranteed:** that the whole reactor or CI pipeline succeeds. A non-participating module built after the last
  participant (for example an integration-test module) can fail after the push; the publication stays. Users who need
  "publish only if everything passed" run publication as a separate CI stage after validation (documented, T31).
- If every participant is skipped, nothing is checked out or pushed.

### Gradle (plugin id `io.github.ivangsa.git-maven-publisher`, project plugin applied to root and/or subprojects)

```kotlin
gitMavenPublisher {
    repository = "https://github.com/org/maven-snapshots.git" // Property<String>, required
    branch = "gh-pages"                                       // Property<String>
    nojekyll = false                                          // Property<Boolean>, writes .nojekyll at the root
    token = providers.environmentVariable("GIT_PUBLISHER_TOKEN") // Property<String>, default: that env var, then gradle property gitPublisherToken
    username = "x-access-token"                               // Property<String>
    keepSnapshots = 3                                         // Property<Int>
    keepVersions = -1                                         // Property<Int>, Phase 3
    allowReleases = false
    maxAttempts = 5
    dryRun = false
}
```

- Registers Maven repository `gitPublisher` → `<rootBuildDir>/git-publisher/repo`.
- One shared `BuildService` (`GitPublisherService`) holds the core `Publication` and the per-task outcomes.
  Sharing the service coordinates state and resources; it does **not** order tasks. Ordering is explicit (below).

#### Publication lifecycle (D-033)

> Checkout runs once before any selected publication writes to the local repository. Push runs once, after every
> selected publication participating in the transaction has completed successfully. A failed publication prevents push.
> Cleanup runs regardless of success or failure.

- **Participants:** the `publish*PublicationToGitPublisherRepository` tasks in the task graph of this invocation. This covers the
  root aggregate (`publish`, `publishAllPublicationsToGitPublisherRepository`) and direct invocation of one subproject or one publication.
  The participant set is computed from the task graph at configuration time and stored as task input (configuration cache).
- **Ordering:** every participant `dependsOn` `gitPublisherCheckout`. `gitPublisherPush` `mustRunAfter` every participant and is
  added to the graph whenever a participant is.
- **Push guard:** `gitPublisherPush` pushes only if the service recorded success for every participant. A failed or not-executed
  participant (also under `--continue` and `--parallel`) means no push, with a log line naming the failed tasks.
  An unconditional finalizer that pushes is forbidden: Gradle runs finalizers after a failed task.
  Mechanism (guarded finalizer or Flow API `FlowAction` with `BuildWorkResult`) is chosen in T36 and recorded in decisions.
- **Writes:** participants write into one local repository directory. They declare `usesService` and the service limits
  concurrent writers to one (`maxParallelUsages = 1`), so two publications never write metadata at the same time.
- **Cleanup:** releases temporary state only. `BuildService.close()` never publishes.
- Configuration-cache compatible: no `Project` access at execution time.

## 3. Core API

Package `io.github.ivangsa.gitpublisher`. Adapters only use these types.

```java
record PublishConfig(
    URI repository, String branch, Credentials credentials,
    Path workDirectory,                 // contains repo/ (served tree) and git/ (object store)
    RetentionPolicy retention, RetryPolicy retry,
    PersonIdent author, boolean dryRun,
    boolean nojekyll,                   // write .nojekyll at the tree root (D-043)
    Set<ChecksumAlgorithm> checksumAlgorithms) {}   // non-empty; default SHA-1, MD5; passed to Retention and IntegrityCheck (D-053)

sealed interface Credentials { None, Token(String username, char[] token) }
record RetentionPolicy(int keepSnapshots, int keepVersions) {}
record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {}   // defaults 5, 500ms, 8s

final class GitPublisher {
    GitPublisher(PublishConfig config, PublisherLog log);
    Publication checkout() throws PublishException;       // step 1-2: shallow fetch, materialize tree, .nojekyll if enabled
}

interface Publication extends AutoCloseable {
    Path repositoryDirectory();                           // where the native deploy writes (file:// target)
    Optional<ObjectId> expectedSha();                     // empty = branch did not exist
    PublishResult publish(PublishInfo info) throws PublishException; // steps 4-7, incl. retries
}

record PublishInfo(List<String> coordinates /* g:a:v */, Optional<String> sourceCommit) {}
record PublishResult(ObjectId commit, int attempts, Set<String> changedPaths, Set<String> deletedPaths, boolean pushed) {}
interface PublisherLog { void info(String m); void warn(String m); void debug(String m); }  // adapters bridge to Maven Log / Gradle Logger
```

Internals (not API): `GitRemote` (fetch/push with lease), `TreeMaterializer`, `ChangeRecorder`, `OverlayReplay`,
`MetadataModel` (read/write, for retention and integrity), `Retention`, `IntegrityCheck`, `ChecksumWriter`.

Git layout: object store in `workDirectory/git` (bare JGit repo, no remote configured), served tree in `workDirectory/repo`.
No `.git` inside the served tree, no URL or credentials written to any Git config. Fetch and push pass the URL per call.

## 4. Publication algorithm

1. `checkout()`: clean `workDirectory`; shallow fetch (depth 1) `refs/heads/<branch>`; `expectedSha` = fetched id or empty. Materialize tree into `repo/`. Write `.nojekyll` if `nojekyll` is enabled.
2. Adapter runs the native deploy into `repo/` (exactly once).
3. `publish()`:
   1. `ChangeRecorder`: diff `repo/` against `expectedSha` tree → `ours` = added/modified paths with content (before retention).
   2. Retention for touched coordinates (§6). Regenerate checksums of every rewritten metadata file (§5).
   3. `IntegrityCheck` (§5). Violation = bug → fail, never push.
   4. Build tree from `repo/` (DirCache + ObjectInserter), commit with **no parents**, message:
      `Publish <coords...>` + blank line + `source: <sha>`.
   5. Push `RemoteRefUpdate(force=true, expectedOldObjectId = expectedSha or zeroId)`, together with a second update that force-moves `refs/publisher/tree` to the new root tree, **without a lease** (`expectedOldObjectId` unset). The tree ref is a transfer hint, not a lease: without it JGit resends the whole tree for a parentless commit (D-020). The push is **atomic** (`Transport.setPushAtomic`), so a branch update rejected by the lease moves no ref and the hint never points at a failed publication; if the server does not support atomic pushes the hint is skipped (D-060). Only the branch status decides (D-027): when the atomic push is refused for a reason other than the lease (for example the remote refuses the hint ref and aborts the transaction), the branch is pushed again alone with the same lease, and the hint is left for a later publication (D-068). A branch status other than OK, UP_TO_DATE or the lease failure (protected branch, hook, size limit, non-fast-forward denial) is a permanent refusal: fail fast with the remote's reason and the hint that the branch must allow force pushes (D-060).
   6. Branch update `OK` → done, whatever the status of the tree ref (it may be missing, stale or rejected, for example when the branch was created by hand; the next push repairs it). Branch `REJECTED_REMOTE_CHANGED` / lease failure → back-off, re-fetch, `OverlayReplay` (§7), go to 3.ii.
      Transport `IOException` → back-off and retry within the same budget. Auth failure → fail fast. Replay conflict → fail fast (§7).

## 5. Metadata rules

Three files, all `maven-metadata.xml`: G-level (`<plugins>`), A-level (`<versions>`), V-level (`<snapshot>`, `<snapshotVersions>`).
Metadata is written by the native deploy. The core reads and rewrites it only for retention (§6) and checks it in `IntegrityCheck`.
Parsing/writing: own small StAX model in core (no `maven-repository-metadata` dependency at runtime, D-005). Unknown elements preserved where possible.
There is no metadata merge: concurrent changes to one metadata file are a conflict (§7, D-032).

V-level `<snapshotVersions>` maps each `(classifier, extension)` to its **current** timestamped file. It is not a history of
retained builds. Older timestamped files may stay on disk according to retention; retention finds builds from file names (§6).

Invariants (checked by `IntegrityCheck` before commit, and by every test via the harness). They hold within each published tree:
- Every `<snapshotVersion>` resolves to an existing file `a-<value>[-classifier].<extension>`.
- V-level `<snapshot>` (timestamp, buildNumber) names a build that has files in the directory.
- Every `<version>` in A-level metadata has an existing directory with at least one regular file below it (Git stores no empty directories, D-031).
- `latest`/`release` are members of `<versions>`.
- Every metadata file has checksum files for the configured algorithms, and they match.
- XML is well formed and parses with the oracle parser (`maven-repository-metadata`, test scope).

Checksum files of rewritten metadata are regenerated for every algorithm already present next to it, plus the configured ones.

## 6. Retention

Runs per touched `g:a:v-SNAPSHOT` directory, inside the same commit.

- A *build* = all files whose name contains the same `<yyyyMMdd.HHmmss>-<buildNumber>` (artifacts, `.pom`, `.module`, classifiers, `.asc`, checksums).
  Builds are identified from file names, not from `<snapshotVersions>` (which only lists the current one per key).
- Order builds by `(buildNumber, timestamp)`, newest first: Maven stamps the session start time, so a build that started earlier but deployed later has an older timestamp and a higher build number, and it is the one the metadata points at (D-025). Keep the first `keepSnapshots`, delete the rest.
- Rewrite V-level metadata: drop `<snapshotVersion>` entries whose file was deleted. If the newest build is kept, `<snapshot>` is unchanged.
- `keepVersions` (Phase 3): per touched `groupId:artifactId`, order `-SNAPSHOT` versions by V-level `lastUpdated`, delete the oldest directories beyond N, remove them from A-level `<versions>`, fix `latest`.
- Non-timestamped files are never deleted by retention.

## 7. Concurrency contract, retry and lease

### Precondition: external serialization (D-032)

- Publications that share any path must not run concurrently. The CI/CD pipeline enforces this, not the publisher.
- An artifact is identified by `groupId:artifactId` (an artifactId alone is not unique). Two publications share paths when they
  touch the same `groupId:artifactId` (any version), or a shared metadata path: G-level plugin metadata of Maven plugins in one
  groupId, or a path that is both G-level and A-level metadata.
- The serialization scope is every coordinate a project produces: all Maven modules of a reactor, all Gradle/KMP publications.
- It covers every workflow that can publish those coordinates. A `concurrency` group local to one source repository is not enough
  when another repository can publish the same coordinates.
- Violating the precondition can make a publication fail (below). It never silently overwrites a remote change.

### Supported concurrency

Independent publishers with disjoint paths sharing one destination repository. Lease + retry + overlay replay keep both publications.

- Lease = `expectedOldObjectId` on a forced `RemoteRefUpdate`. Missing branch → `ObjectId.zeroId()` (create only).
- Back-off: full jitter, `delay = random(0, min(maxDelay, baseDelay * 2^(attempt-1)))`.
- On lease failure: fetch new tip, materialize into fresh `repo/`, replay `ours` path by path (base = tree of the attempt's `expectedSha`):

| Remote since base | Action |
| --- | --- |
| path unchanged | write ours |
| path changed to the same bytes as ours (for example `.nojekyll` on two first publications) | keep, no conflict |
| path changed or deleted with different content | **conflict** |

- Checksum files are ordinary paths under the same rule. Then retention, checksums and `IntegrityCheck` run again (§4 3.ii).
- Conflict → `PublishException`, no retry, nothing pushed. The message lists every conflicting path and says that publications
  sharing these paths must be serialized externally (design §7).
- After `maxAttempts` lease failures → `PublishException` with the last rejection reason. Nothing partial is ever pushed.

## 8. Authentication and serving the branch

HTTPS with a token only (D-015). Two documented scenarios (D-035):

| Scenario | Token | Destination |
| --- | --- | --- |
| **Self-publication** (this project's own snapshots, after a bootstrap version exists) | `GITHUB_TOKEN`, `contents: write` | a branch of the workflow's own repository |
| **External users** (publishing to another repository) | fine-grained PAT, `Contents: write` on the destination repo only, expires; or a GitHub App installation token | dedicated snapshots repository |

- All tokens reach the plugin the same way: Maven `settings.xml` server (`serverId`), Gradle property (`token` / `gitPublisherToken`),
  or env `GIT_PUBLISHER_TOKEN`. JGit gets `UsernamePasswordCredentialsProvider(username, token)`, username default `x-access-token`.
- The token is never written to the remote URL, Git config, a committed file or a log line; repository URLs with userinfo are rejected
  at config time. Plain unit tests cover these rules. There is no leak scanner (D-045): the plugin creates no credentials and only
  passes the token to JGit.
- Token held as `char[]`, wrapped in `UsernamePasswordCredentialsProvider` per operation, cleared on `close()`: each provider is cleared after its operation and `Publication.close()` clears the `Credentials.Token` of the config (D-060). Auth failures (HTTP 401/403) are recognised from JGit's message with URLs removed, never from the repository name.

**Serving the branch is the user's responsibility (D-043).** The plugin pushes the branch and nothing else. Users serve it however they
like: `https://raw.githubusercontent.com/<owner>/<repo>/<branch>/` for a public repository, GitHub Pages configured by hand, or any static
file server. The plugin deploys nothing to Pages. `nojekyll=true` writes `.nojekyll` for Pages users. The README documents the options.

## 9. Test strategy

Oracles we do not control, built in Phase 0 before production code (D-014, D-038).
In a Maven deploy the client computes the metadata and the server only stores files, so a repository manager is not an
independent metadata oracle. The native deploy tools are.

| Oracle | What it checks | Where |
| --- | --- | --- |
| **Sequential reference** (primary) | Expected tree = the same publications deployed one after another by the native tool (Maven Resolver; Gradle `maven-publish` in Phase 2) into a clean `file://` repo. Actual tree = the same output transported through Git (separate checkouts, failed lease, overlay replay of independent changes). `TreeNormalizer` replaces timestamps, build numbers (by order), `lastUpdated`/`updated`; `TreeDiff` must report no difference. This is a comparison of direct Resolver output with output through Git, not a repository-manager oracle. | `fixtures` (`SequentialReference`, `TreeNormalizer`, `TreeDiff`), ITs |
| **Real Maven Resolver** (`maven-resolver-supplier-mvn3`, test scope) | Produces artifacts and metadata exactly like `mvn deploy`; one deployer is one session with one `maven.startTime` (settable, D-026). Used as the "native deploy" in core tests and to build the sequential reference. | `fixtures` |
| **Golden files** from Maven Central and public snapshot repos | Parser round-trip and retention on real-world shapes, incl. a KMP library (`.module`, many artifactIds). | `test-fixtures/src/main/resources/golden` |
| **Clean consumers** | Plain Maven project (Maven Verifier, empty local repo) and Gradle KMP project (TestKit) resolve from a `file://` URL pointing at a working tree checked out from the pushed branch, i.e. exactly what any static file server would serve (D-043). | `fixtures` (`MavenConsumer`), plugin ITs |
| **Real Git** | Lease, retry and replay against bare repos on disk and JGit's HTTP server. | ITs |
| **Interleaving** | Deterministic: publisher A checks out, publisher B publishes in full, then A pushes (D-044). Distinct coordinates → A's lease fails, A replays, both publications on the branch, invariants hold. Shared path → A fails with a conflict, B's files untouched. A push without a lease is caught losing B's files. | `fixtures` (`InterleavedPublishScenario`), ITs |
| **Maven oracle parser** (`maven-repository-metadata`) | Our XML must parse with Maven's own reader. | all tests via `MetadataAssertions` |
| **Negative tests** | Crafted broken trees for every invariant. | `fixtures` self-tests |

Do not replace any of these with a fake repository manager or synthetic artifacts that reproduce our own assumptions.

Levels: unit (`*Test`, surefire), integration (`*IT`, failsafe), plugin ITs (`maven-invoker-plugin` in `maven-plugin/src/it`, Gradle TestKit), nightly E2E against a real GitHub repository.
The default build runs everything. No `@Disabled`, no skipped tests in a passing build.

## 10. Operational limits (D-036)

- **Size.** The single parentless commit bounds the *reachable* history to the current content. Unreachable objects are removed
  by the host's garbage collection at its own pace, not immediately. Retention (`keepSnapshots`) is the size control in the MVP.
  Limits of whatever serves the branch are the user's concern; the README notes GitHub's (Pages: about 1 GB site, soft 100 GB/month bandwidth; verify against current GitHub docs). No size
  budgeting or large-repository optimization until measurements justify it.
- **HTTP caches (accepted risk).** HTTP caches in front of the branch keep files for a while (Pages CDN about 10 minutes, `raw.githubusercontent.com` a few minutes). A consumer can get cached metadata that names a
  timestamped file which retention already deleted; the resolution then fails until the cache expires. This needs more than
  `keepSnapshots` publications of one snapshot inside the cache window. Accepted for the MVP, documented, no grace period.
  Each published tree is internally consistent (§5 invariants).
