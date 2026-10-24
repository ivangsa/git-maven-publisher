# Design

Source of truth for contracts. The brief (`docs/brief.md`) says *why*; this file says *what*.
Changes go here first, with a matching entry in `docs/decisions.md`.

## 1. Modules and build

| Module | Path | Build | Depends on | Ships to |
| --- | --- | --- | --- | --- |
| `git-maven-publisher-core` | `core/` | Maven | JGit, JDK only | Central |
| `git-maven-publisher-maven-plugin` | `maven-plugin/` | Maven | core, Maven 3.10 API (provided), Resolver API (provided) | Central |
| `git-maven-publisher-gradle-plugin` | `gradle-plugin/` | **Gradle** (own wrapper) | core (from `mavenLocal()` / Central), Gradle API | Central + Plugin Portal |
| test fixtures | `test-fixtures/` | Maven, not deployed | core (test helpers: bare repos, sequential reference, tree diff, Pages server, golden files) | - |

- Root `pom.xml` is the Maven reactor for `core`, `test-fixtures`, `maven-plugin`. Maven Wrapper pinned to 3.10.x.
- `gradle-plugin/` is a standalone Gradle build. It is **not** in the Maven reactor (D-002).
  CI order: `./mvnw install` → `./gradlew -p gradle-plugin build`. Version kept in sync by `gradle-plugin/gradle.properties`, checked by a CI step.
- Full build = both commands. "Build passes" in the working agreement means both pass (only the Maven one until the Gradle build exists).
- `--release 17`, any JDK ≥ 17 can build. JUnit 5, AssertJ. The default build needs no Docker (D-014).
- Profile `e2e`: Reposilite via Testcontainers (needs Docker). Optional locally, always enabled in CI; CI fails if it runs zero tests.

## 2. Public configuration

### Maven (`git-maven-publisher-maven-plugin`, goal `deploy`, default phase `deploy`)

| Parameter | Property | Default | Notes |
| --- | --- | --- | --- |
| `repository` | `git-publisher.repository` | required | Git URL, `https` or `file` only; other schemes are rejected. Credentials in the URL are rejected. |
| `branch` | `git-publisher.branch` | `gh-pages` | |
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

### Gradle (plugin id `io.github.ivangsa.git-maven-publisher`, project plugin applied to root and/or subprojects)

```kotlin
gitMavenPublisher {
    repository = "https://github.com/org/maven-snapshots.git" // Property<String>, required
    branch = "gh-pages"                                       // Property<String>
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
- Tasks: `gitPublisherCheckout` (before every `publish*PublicationToGitPublisherRepository`), `gitPublisherPush` (finalizer, runs once per build).
- One shared `BuildService` (`GitPublisherService`) owns the core `Publication`. Configuration-cache compatible; no `Project` access at execution time.

## 3. Core API

Package `io.github.ivangsa.gitpublisher`. Adapters only use these types.

```java
record PublishConfig(
    URI repository, String branch, Credentials credentials,
    Path workDirectory,                 // contains repo/ (served tree) and git/ (object store)
    RetentionPolicy retention, RetryPolicy retry,
    PersonIdent author, boolean dryRun) {}

sealed interface Credentials { None, Token(String username, char[] token) }
record RetentionPolicy(int keepSnapshots, int keepVersions) {}
record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {}   // defaults 5, 500ms, 8s

final class GitPublisher {
    GitPublisher(PublishConfig config, PublisherLog log);
    Publication checkout() throws PublishException;       // step 1-2: shallow fetch, materialize tree, .nojekyll
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

Internals (not API): `GitRemote` (fetch/push with lease), `TreeMaterializer`, `ChangeRecorder`, `OverlayReplay`, `MetadataMerger`, `Retention`, `IntegrityCheck`, `ChecksumWriter`, `Redactor`.

Git layout: object store in `workDirectory/git` (bare JGit repo, no remote configured), served tree in `workDirectory/repo`.
No `.git` inside the served tree, no URL or credentials written to any Git config. Fetch and push pass the URL per call.

## 4. Publication algorithm

1. `checkout()`: clean `workDirectory`; shallow fetch (depth 1) `refs/heads/<branch>`; `expectedSha` = fetched id or empty. Materialize tree into `repo/`. Write `.nojekyll`.
2. Adapter runs the native deploy into `repo/` (exactly once).
3. `publish()`:
   1. `ChangeRecorder`: diff `repo/` against `expectedSha` tree → `ours` = added/modified paths with content (before retention).
   2. Retention for touched coordinates (§6). Regenerate checksums of every rewritten metadata file (§5).
   3. `IntegrityCheck` (§5). Violation = bug → fail, never push.
   4. Build tree from `repo/` (DirCache + ObjectInserter), commit with **no parents**, message:
      `Publish <coords...>` + blank line + `source: <sha>`.
   5. Push `RemoteRefUpdate(force=true, expectedOldObjectId = expectedSha or zeroId)`, together with a second update that moves `refs/publisher/tree` to the new root tree (expected old id = the tree of `expectedSha`, or zeroId). Without it JGit resends the whole tree for a parentless commit (D-020).
   6. `OK` → done. `REJECTED_REMOTE_CHANGED` / lease failure → back-off, re-fetch, `OverlayReplay`, go to 3.ii.
      Transport `IOException` → back-off and retry within the same budget. Auth failure → fail fast.

## 5. Metadata rules

Three files, all `maven-metadata.xml`: G-level (`<plugins>`), A-level (`<versions>`), V-level (`<snapshot>`, `<snapshotVersions>`).
Parsing/writing: own small StAX model in core (no `maven-repository-metadata` dependency, D-005). Unknown elements preserved where possible.

**Three-way merge** in replay: `result = remote ∪ (ours − base)`, where base = metadata at `expectedSha` of the attempt that produced `ours`.
Entries the remote deleted (e.g. by its retention) are not resurrected.

| Level | Field | Rule |
| --- | --- | --- |
| G | `plugins` | Union keyed by `prefix`; ours wins on equal key. |
| A | `versions` | Remote order, then our new versions appended (matches Resolver append behaviour; confirmed by the sequential reference, T07). |
| A | `latest`, `release` | Value from the side with the greater `lastUpdated`. `release` only if releases allowed. |
| A/V | `lastUpdated` | max of both. |
| V | `snapshotVersions` | Union keyed by `(classifier, extension)`; per key the entry with greater `updated` wins. |
| V | `snapshot` | `timestamp` from the newest entry; `buildNumber` = max of both. |

Invariants (checked by `IntegrityCheck` before commit, and by every test via the harness):
- Every `<snapshotVersion>` resolves to an existing file `a-<value>[-classifier].<extension>`.
- Every `<version>` in A-level metadata has an existing directory.
- `latest`/`release` are members of `<versions>`.
- Every metadata file has checksum files for the configured algorithms, and they match.
- XML is well formed and parses with the oracle parser (`maven-repository-metadata`, test scope).

Checksum files of rewritten metadata are regenerated for every algorithm already present next to it, plus the configured ones.

## 6. Retention

Runs per touched `g:a:v-SNAPSHOT` directory, inside the same commit.

- A *build* = all files whose name contains the same `<yyyyMMdd.HHmmss>-<buildNumber>` (artifacts, `.pom`, `.module`, classifiers, `.asc`, checksums).
- Order builds by `(timestamp, buildNumber)`, newest first. Keep the first `keepSnapshots`, delete the rest.
- Rewrite V-level metadata: drop `<snapshotVersion>` entries whose file was deleted. If the newest build is kept, `<snapshot>` is unchanged.
- `keepVersions` (Phase 3): per touched artifactId, order `-SNAPSHOT` versions by V-level `lastUpdated`, delete the oldest directories beyond N, remove them from A-level `<versions>`, fix `latest`.
- Non-timestamped files are never deleted by retention.

## 7. Retry and lease

- Lease = `expectedOldObjectId` on a forced `RemoteRefUpdate`. Missing branch → `ObjectId.zeroId()` (create only).
- Back-off: full jitter, `delay = random(0, min(maxDelay, baseDelay * 2^(attempt-1)))`.
- On lease failure: fetch new tip, materialize into fresh `repo/`, replay `ours`:
  - path absent in new remote, or unchanged since base → copy ours;
  - path changed on both sides and is `maven-metadata.xml` → three-way merge (§5);
  - checksum of a metadata file → regenerated after merge;
  - any other file changed on both sides with different content → `PublishException` (should be impossible with timestamped names).
- After `maxAttempts` → `PublishException` with the last rejection reason. Nothing partial is ever pushed.

## 8. Authentication and security

HTTPS with a token only (D-015). Supported tokens:

| Token | Scope | Typical use |
| --- | --- | --- |
| Fine-grained PAT | `Contents: write` on the snapshots repo only; expires | Local development, CI in another repo |
| `GITHUB_TOKEN` | The workflow's own repo only | Publishing to a branch of the same repo |
| GitHub App installation token | Repos the App is installed on; short-lived | CI, minted per run (for example `actions/create-github-app-token`) |

All three reach the plugin the same way: Maven `settings.xml` server (`serverId`), Gradle property (`token` /
`gitPublisherToken`), or env `GIT_PUBLISHER_TOKEN`. JGit gets `UsernamePasswordCredentialsProvider(username, token)`,
username default `x-access-token`.

- `Redactor` strips userinfo from every URI before logging; all log calls go through it.
- Token held as `char[]`, wrapped in `UsernamePasswordCredentialsProvider` per operation, cleared on `close()`.
- Repository URLs with userinfo are rejected at config time.
- Test: a canary token is used in integration tests; the build fails if it appears in logs, `workDirectory`, or any Git config.

## 9. Test strategy

Oracles we do not control, built in Phase 0 before production code (D-014).
In a Maven deploy the client computes the metadata and the server only stores files, so a repository manager is not an
independent metadata oracle. The native deploy tools are.

| Oracle | What it checks | Where |
| --- | --- | --- |
| **Sequential reference** (primary, always on, no Docker) | Expected tree = the same publications deployed one after another by the native tool (Maven Resolver; Gradle `maven-publish` in Phase 2) into a clean `file://` repo. Actual tree = our concurrent path (separate checkouts, failed lease, overlay replay, metadata merge). `TreeNormalizer` replaces timestamps, build numbers (by order), `lastUpdated`/`updated`; `TreeDiff` must report no difference. | `test-fixtures` (`SequentialReference`, `TreeNormalizer`, `TreeDiff`), core ITs, plugin ITs |
| **Real Maven Resolver** (`maven-resolver-supplier`, test scope) | Produces artifacts and metadata exactly like `mvn deploy`. Used as the "native deploy" in core tests and to build the sequential reference. | `test-fixtures` |
| **Golden files** from Maven Central and public snapshot repos | Parser round-trip, merge and retention on real-world shapes, incl. a KMP library (`.module`, many artifactIds). Source URL + date in `fixtures/README.md`. | `test-fixtures/src/main/resources/golden` |
| **Clean consumers** | Plain Maven project (Maven Verifier, empty local repo) and Gradle KMP project (TestKit) resolve from an embedded static HTTP server (Jetty) serving a working tree checked out from the pushed branch, i.e. what Pages would serve. | `test-fixtures` (`PagesServer`), plugin ITs |
| **Concurrency** | N threads publishing to one bare repo on disk; same and different coordinates. Assert no lost files, valid XML, invariants hold. | core ITs |
| **Maven oracle parser** (`maven-repository-metadata`) | Our XML must parse with Maven's own reader. | all tests via `MetadataAssertions` |
| **Reposilite** (E2E profile `e2e`, Testcontainers) | Interop smoke only: deploys over HTTP to a real repository manager and consumers resolving from it. Not a metadata oracle. | `test-fixtures`, CI |

Levels: unit (`*Test`, surefire), integration (`*IT`, failsafe), E2E (`*E2E`, profile `e2e`, Docker), plugin ITs (`maven-invoker-plugin`, Gradle TestKit), nightly E2E against a real GitHub Pages repo.
The default build runs everything except `e2e`. CI runs `-Pe2e` and fails if the profile executed zero tests. No `@Disabled`, no skipped tests in a passing build.
