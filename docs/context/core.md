# Context pack: core

**Location:** package `io.github.ivangsa.gitpublisher` in module `core/` (artifact `git-maven-publisher-core`, D-075) · Java 17 · JGit + JDK only.
**No `org.apache.maven` or `org.gradle` imports** in core packages (architecture test). Maven libraries are allowed in test scope as oracles.

## Purpose
Owns everything Git: shallow fetch of the publish branch, a served tree the native deploy writes into,
retention, one parentless commit, push with lease, retry with overlay replay of independent changes. Adapters only run the native deploy.

## Contract (design §3)
`GitPublisher(PublishConfig, PublisherLog).checkout()` → `Publication` with `repositoryDirectory()`, `expectedSha()`,
`publish(PublishInfo)` → `PublishResult`. Config records: `PublishConfig`, `Credentials` (None, Token; HTTPS tokens only, D-015),
`RetentionPolicy(keepSnapshots=3, keepVersions=-1)`, `RetryPolicy(5, 500ms, 8s)`.
Layout: `workDirectory/git` = bare object store, no remote configured; `workDirectory/repo` = served tree, no `.git`.

## Algorithm (design §4)
checkout: clean, depth-1 fetch, `expectedSha` or empty, materialize, `.nojekyll` if enabled →
deploy (adapter) → record `ours` (diff vs base, before retention) → retention → regenerate metadata checksums →
`IntegrityCheck` (fail, never push) → parentless commit `Publish <coords>` + `source: <sha>` →
forced push with expected old id (`zeroId` if branch missing) plus `refs/publisher/tree` as a hint (D-020, D-027) →
on lease failure: back-off, re-fetch, overlay replay, repeat.

## Rules that bite
- **Concurrency contract (design §7, D-032):** publications sharing a path are serialized by CI. No metadata merge.
  Replay per path: remote unchanged → write ours; remote changed to identical bytes → keep; anything else → conflict →
  `PublishException` listing the paths and the serialization hint, no retry, no push. Never overwrite a remote change.
- An artifact is `groupId:artifactId`, never an artifactId alone.
- Metadata model (StAX) exists for retention and `IntegrityCheck` only; Resolver/Gradle write metadata.
- `<snapshotVersions>` lists the current file per `(classifier, extension)`, not a history. Retention finds builds from file names.
- Retention: a build = files sharing `<yyyyMMdd.HHmmss>-<n>`; order by `(buildNumber, timestamp)` (D-025); keep newest N;
  drop metadata entries for deleted files; never delete non-timestamped files (design §6).
- Invariants hold within each published tree (design §5), including `<snapshot>` names a build with files and non-empty version dirs (D-031).
- Retry lease failures and transport errors within `maxAttempts`; auth failures and conflicts fail fast.
- Security (design §8): reject URLs with userinfo, token as `char[]` passed only to JGit's credentials provider,
  never in the remote URL, Git config or a log line. No leak scanner (D-045).

## Files that matter
`docs/design.md` §3–§8, `docs/decisions.md` D-003, D-005, D-020, D-025, D-027, D-031, D-032,
`core/src/main/java/io/github/ivangsa/gitpublisher/`, test helpers in `test-fixtures/src/main/java/io/github/ivangsa/gitpublisher/fixtures/`
(see `docs/context/testing.md`).
