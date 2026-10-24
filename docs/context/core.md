# Context pack: core

**Module:** `core/` · artifact `git-maven-publisher-core` · Maven build · Java 17 · JGit + JDK only.
**No Maven or Gradle APIs at runtime** (Maven libraries allowed in test scope as oracles).

## Purpose
Owns everything Git: shallow fetch of the publish branch, a served tree the native deploy writes into,
retention, one parentless commit, push with lease, retry with overlay replay. Adapters only run the native deploy.

## Contract (design §3)
`GitPublisher(PublishConfig, PublisherLog).checkout()` → `Publication` with `repositoryDirectory()`, `expectedSha()`,
`publish(PublishInfo)` → `PublishResult`. Config records: `PublishConfig`, `Credentials` (None, Token; HTTPS tokens only, D-015),
`RetentionPolicy(keepSnapshots=3, keepVersions=-1)`, `RetryPolicy(5, 500ms, 8s)`.
Layout: `workDirectory/git` = bare object store, no remote configured; `workDirectory/repo` = served tree, no `.git`.

## Algorithm (design §4)
checkout: clean, depth-1 fetch, `expectedSha` or empty, materialize, `.nojekyll` →
deploy (adapter) → record `ours` (diff vs base, before retention) → retention → regenerate metadata checksums →
`IntegrityCheck` (fail, never push) → parentless commit `Publish <coords>` + `source: <sha>` →
forced push with expected old id (`zeroId` if branch missing) → on lease failure: back-off, re-fetch, overlay replay, repeat.

## Rules that bite
- Metadata merge is **three-way**: `remote ∪ (ours − base)` (D-004). Rules per field: design §5 table.
- No version comparison: `latest`/`release` by `lastUpdated`; `<versions>` remote order + appended (D-006).
- snapshotVersions keyed by `(classifier, extension)`, newer `updated` wins; `buildNumber` = max.
- Retention: a build = files sharing `<yyyyMMdd.HHmmss>-<n>`; keep newest N; drop metadata entries for deleted files;
  never delete non-timestamped files (design §6).
- Replay: same non-metadata path changed on both sides with different content → `PublishException`.
- Retry lease failures and transport errors within `maxAttempts`; auth failures fail fast (design §7).
- Security (design §8): redact userinfo in every logged URI, reject URLs with userinfo, token as `char[]`,
  nothing written to Git config.

## Files that matter
`docs/design.md` §3–§8, `docs/decisions.md` D-003..D-006, `core/src/main/java/io/github/ivangsa/gitpublisher/`,
test helpers in `test-fixtures/` (see `docs/context/testing.md`).
