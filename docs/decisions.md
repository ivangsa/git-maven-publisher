# Decisions

Newest last. Format: date · decision · reason · alternatives rejected.

## D-001 Plain Markdown workflow
- **Date:** 2026-10-23
- **Decision:** Plan lives in `docs/design.md`, `docs/tasks.md`, `docs/decisions.md`, `docs/questions.md`; working agreement in `CLAUDE.md`.
- **Reason:** Requested by the maintainer; no tooling to learn or maintain.
- **Rejected:** Spec Kit, OpenSpec.

## D-002 Gradle plugin is a separate Gradle build
- **Date:** 2026-10-23
- **Decision:** `core` and `maven-plugin` build with Maven; `gradle-plugin/` is a standalone Gradle build that consumes `core` from `mavenLocal()` in CI.
- **Reason:** `java-gradle-plugin` generates the plugin descriptor, the plugin marker publication, TestKit's plugin-under-test metadata, and Plugin Portal publishing. None of that has a maintained Maven equivalent. Gradle API jars are not on Central in a usable form.
- **Rejected:** Everything in Maven (no Gradle API on Central, hand-written marker POM, no TestKit integration). Everything in Gradle (Maven plugin descriptor generation and invoker ITs are native to Maven; the Maven plugin is the MVP).

## D-003 Served tree and Git object store are separate directories
- **Date:** 2026-10-23
- **Decision:** `workDirectory/git` is a bare JGit repo with no configured remote; `workDirectory/repo` is a plain directory the native deploy writes into. Trees are built from the filesystem with `DirCache` + `ObjectInserter`.
- **Reason:** No `.git` inside the deploy target; nothing about the remote (URL, credentials) is ever written to Git config; no index or checkout state to keep consistent.
- **Rejected:** Normal clone with work tree (writes remote URL to `.git/config`, `.git` visible to the deploy target).

## D-004 Three-way metadata merge in overlay replay
- **Date:** 2026-10-23
- **Decision:** `result = remote ∪ (ours − base)`.
- **Reason:** A plain union of remote and ours would resurrect entries the remote removed by retention, producing metadata that points at deleted files.
- **Rejected:** Two-way union as written in the brief.

## D-005 Own metadata model in core
- **Date:** 2026-10-23
- **Decision:** Small StAX reader/writer in core. `maven-repository-metadata` is used only in tests, as an oracle parser.
- **Reason:** Core must not depend on Maven APIs (brief). Using Maven's reader as a test oracle catches XML our model gets wrong.
- **Rejected:** Depending on `maven-repository-metadata` at runtime.

## D-006 No version comparison in merge or retention
- **Date:** 2026-10-23
- **Decision:** Merge decides `latest`/`release` by `lastUpdated`; `keepVersions` orders by V-level `lastUpdated`; `<versions>` keeps remote order plus appended new ones.
- **Reason:** Matches what Resolver does on deploy (latest = last deployed), and avoids reimplementing `ComparableVersion` in core. The sequential reference oracle (T07) confirms the ordering.
- **Rejected:** Porting `ComparableVersion` into core; depending on `maven-artifact`.

## D-007 Maven reactor trigger: last participating project
- **Date:** 2026-10-23
- **Decision:** A session-scoped collector counts projects in `session.getProjects()` that execute the goal; the last one to report runs checkout → one `RepositorySystem.deploy` → publish. Thread-safe for `-T`.
- **Reason:** Same approach as `maven-deploy-plugin`'s `deployAtEnd`; no `<extensions>true</extensions>` needed. A failed module stops the build before the trigger, so nothing partial is published.
- **Rejected:** `AbstractMavenLifecycleParticipant` (needs build extension), deploying per module (one commit per module, many pushes).

## D-008 Real Resolver as the native deploy in core tests
- **Date:** 2026-10-23
- **Decision:** Core tests deploy through `maven-resolver-supplier` (test scope) into the work tree, not through hand-written fake files.
- **Reason:** Fakes would encode our assumptions about file names and metadata; Resolver is the oracle.
- **Rejected:** Synthetic artifact writers.

## D-009 Private GitHub repository, fixed commit window (answers Q1)
- **Date:** 2026-10-24
- **Decision:** Code lives in private `github.com/ivangsa/git-maven-publisher`. Commits are dated within 24–25 Oct 2026, 09:00–12:30 / 20:00–23:00 +0200 (rule in `CLAUDE.md`).
- **Reason:** Maintainer's choice.
- **Rejected:** Public repo from day one (can be switched before the first release).

## D-010 Keep the name `git-maven-publisher` (answers Q3)
- **Date:** 2026-10-24
- **Decision:** Project `git-maven-publisher`, Maven prefix `git-maven-publisher`, Gradle id `io.github.ivangsa.git-maven-publisher`.
- **Reason:** Maintainer's choice; neutral and descriptive.
- **Rejected:** Deciding later.

## D-011 User property prefix `git-publisher.*` (answers Q5)
- **Date:** 2026-10-24
- **Decision:** Maven user properties are `git-publisher.<parameter>`.
- **Reason:** Short; matches `GIT_PUBLISHER_TOKEN` and the Gradle names.
- **Rejected:** `git-maven-publisher.*`.

## D-012 Supported versions: latest only, Maven 3.10+ (answers Q6)
- **Date:** 2026-10-24
- **Decision:** Maven 3.10.0+ (wrapper and plugin prerequisite), CI on the latest Maven 3.10.x and the latest Maven 4.0
  (4.0.0-rc-7 today, GA when released). Gradle: CI on the latest 8.x and the latest 9.x (9.8.2 today).
- **Reason:** Maintainer has hit pipeline changes with older 3.9.x on GitHub Ubuntu images; testing only current versions keeps the matrix cheap.
  Maven 3.10 may bundle a newer Maven Resolver than 3.9; T05 checks which one and records it here.
- **Rejected:** Maven 3.9.6+ and Gradle 8.5+ with a wider matrix.

## D-013 Batch sizing by the orchestrator
- **Date:** 2026-10-24
- **Decision:** 21 batches plus one manual task (see docs/tasks.md). T21 split into T21 (retry) and T21b (overlay replay).
  T17 (integrity check) moved before merge and retention. Reviews run at 6 checkpoints instead of after every critical batch.
  T33 is manual. T03 names its golden sources.
- **Reason:** Merge, retention, lease, retry and replay stay isolated where bugs hide, without paying for an opus review after each one.
  Concrete fixture sources keep the cheapest agent from choosing the oracle.
- **Rejected:** Reviewing every critical batch alone; 3–6 tasks as a hard rule.

## D-014 Sequential native deploys are the primary oracle; Reposilite is E2E only (answers Q8)
- **Date:** 2026-10-24
- **Decision:**
  - Primary oracle, always on, no Docker: the same publications deployed sequentially by the native tool
    (Maven Resolver; Gradle `maven-publish`) into a clean `file://` repo give the expected tree. Our concurrent path
    (separate checkouts, failed lease, overlay replay, metadata merge) gives the actual tree. Compared with timestamps
    and build numbers normalized (`SequentialReference`, `TreeNormalizer`, `TreeDiff`).
  - Consumer tests: embedded static HTTP server (Jetty) serving a working tree checked out from the pushed branch;
    clean Maven and Gradle KMP consumers resolve from it.
  - Reposilite: optional profile `e2e` (Testcontainers), always enabled in CI. CI fails if that profile runs zero tests.
- **Reason:** In a Maven deploy the client computes the metadata and the server only stores files, so Reposilite is not
  an independent metadata oracle. The native tools running without concurrency are.
- **Rejected:** Reposilite as differential oracle in the default build, Reposilite jar as a child process,
  an opt-in Docker profile as the main oracle.

## D-015 HTTPS with a token only, no SSH
- **Date:** 2026-10-23
- **Decision:** Authentication is HTTPS with a token only. No SSH support.
  Supported tokens: fine-grained PAT (`Contents: write` on the snapshots repo), `GITHUB_TOKEN` (same-repo publishing only),
  GitHub App installation token (minted in CI, for example with `actions/create-github-app-token`).
- **Reason:** The only benefit of SSH was deploy keys (single-repo scope, no expiry). A GitHub App installation token gives
  the same scope over HTTPS, is short-lived, and needs no key handling. SSH would add Apache MINA SSHD, key and
  `known_hosts` handling, and more security surface for no user-visible gain.
- **Rejected:** SSH deploy keys via JGit Apache MINA SSHD (former T46).
- **Revisit:** only if a real user needs a case a token cannot cover.

## D-016 Build skeleton choices (T01)
- **Date:** 2026-10-24
- **Decision:**
  - Root artifact `git-maven-publisher-parent` (packaging `pom`), modules `core/`, `test-fixtures/`, `maven-plugin/`, all `0.1.0-SNAPSHOT`.
  - Maven Wrapper pinned to Maven 3.10.0, the newest 3.10.x on Maven Central (3.10.1 and 3.10.2 do not exist). Wrapper plugin 3.3.4, distribution type `only-script`.
  - `maven-plugin/` is a plain `jar` until T25 switches it to `maven-plugin` packaging, because it has no mojos yet.
  - Versions pinned as root properties. JUnit 5 via `junit-bom` 5.14.0 (not JUnit 6). AssertJ 3.27.6 (not the 4.0 milestone).
  - License header check: `com.mycila:license-maven-plugin` 5.1.2, bound to `verify`, checks `**/src/**/*.java` against `src/license/header.txt`.
- **Reason:** Pin only what Central serves as stable. The header check is the only enforced rule until real sources exist.
- **Rejected:** Maven 3.10.1+ (not published); JUnit 6 and AssertJ 4 milestones (not stable); maven-plugin packaging before any mojo exists.

## D-017 Golden fixtures: sources and layout (T03)
- **Date:** 2026-10-23
- **Decision:**
  - Golden files live in `test-fixtures/src/main/resources/golden/` at their repository paths. Source URLs and fetch date are in `golden/README.md` (the `fixtures/README.md` of the T03 text).
  - KMP "latest release" is the `<release>` Central declares for `kotlinx-datetime`: `0.8.0-0.6.x-compat`. Its `-jvm`, `-js` and `-linuxx64` artifacts exist. The plain `0.8.0` line exists too and is not used.
  - V-level golden is the latest `maven-resolver-api` snapshot on repository.apache.org: `2.0.25-SNAPSHOT`.
  - G-level metadata has no groupId/artifactId, so the test checks `<plugins>` for that file only.
- **Reason:** Take the source the task names, not a choice made by the implementer. Central's `<release>` is the definition of "latest release".
- **Rejected:** Picking `0.8.0` by hand (would hide what Central actually declares as release).

## D-018 LeakScanner scope and redaction (T10)
- **Date:** 2026-10-23
- **Decision:** `LeakScanner` (test-fixtures) scans three things: captured text, a directory tree (entry names and bytes of regular files, binary included), and Git config files (`config` inside a `.git` directory or next to `HEAD` and `objects/`). Every finding is redacted: the canary is replaced by `<redacted>` before it appears in a message, so the scanner's own output cannot leak.
- **Reason:** The brief's rule is that a canary must never appear in logs, the work directory or Git config. Scanning names as well as bytes covers the case where a token ends up in a path.
- **Rejected:** Scanning compressed archives or Git objects (not in the planned checks; Git objects are stored compressed and are not credentials-bearing configs). Printing the canary in findings.

## D-019 Resolver in test fixtures: `maven-resolver-supplier-mvn3` 2.0.24 (T05)
- **Date:** 2026-10-24
- **Decision:** `ResolverDeployer` uses `org.apache.maven.resolver:maven-resolver-supplier-mvn3:2.0.24`
  (`RepositorySystemSupplier` + `SessionBuilderSupplier`). Maven 3.10.0 (the wrapper) bundles Resolver 2.0.24, so the
  test engine is the same version as the one `mvn deploy` runs in CI. Checksums via `aether.checksums.algorithms`.
  Other pins: JGit 7.8.0, Jetty 12.1.14, `maven-repository-metadata` moved to compile scope of `test-fixtures`
  (its main code hosts `MetadataAssertions`).
- **Reason:** The artifact literally named `maven-resolver-supplier` stops at `2.0.0-alpha-8`; in 2.0 the supplier is
  split into `-mvn3` and `-mvn4`. The Maven plugin targets 3.10, so `-mvn3`.
- **Rejected:** `maven-resolver-supplier` 1.9.x (older than what Maven 3.10 bundles), the alpha supplier, `-mvn4`.

## D-020 JGit learning results; parentless pushes resend the whole tree (T11)
- **Date:** 2026-10-24
- **Findings** (`JGitLearningTest`, every case over `file://` and JGit's own HTTP server, JGit 7.8.0):
  - Depth-1 fetch into an empty bare repo works on both transports. It writes `shallow` and no `[remote]` section or URL
    in `config` when the URL is passed per call (`setRemote(uri)`), as D-003 requires.
  - Forced push with an expected old id (`RemoteRefUpdate(..., expectedOldObjectId)` via `Transport.push`) is `OK` when
    the remote tip equals the id and `REJECTED_REMOTE_CHANGED` otherwise, identically on both transports.
    `ObjectId.zeroId()` is create-only: `OK` on a missing branch, `REJECTED_REMOTE_CHANGED` once it exists.
  - JGit's HTTP server accepts pushes only with `http.receivepack=true` in the bare repo config (test setup).
  - **Surprise:** a parentless commit pushed to a branch whose tip is known locally still sends the complete tree
    (47 objects for a 40-file tree where 4 are new). JGit's `PackWriter` only excludes the trees of boundary commits
    of the walk, and a parentless commit has none. Correct but wasteful: every publish would upload the whole repository.
  - **Workaround, proven:** a second ref on the remote pointing at the current root *tree* (`refs/publisher/tree`),
    moved in the same push with its own expected old id. The advertised tree is a "have", so the next push sends only the
    new objects (4 in the test). Only the first push (no tree ref yet) sends everything.
- **Decision:** `GitRemote` (T20) pushes the branch and `refs/publisher/tree` together. design.md §3/§4 are updated.
  The remote keeps one extra ref outside `refs/heads`; it is not served by Pages and not fetched by consumers.
- **Risk:** not yet verified against GitHub (custom refs should be accepted; T51 nightly will show it). If GitHub
  rejects it, fall back to the plain push, which stays correct.
- **Rejected:** a parent commit to the old tip (history grows, violates the single-commit rule); writing packs by hand.

## D-021 Sequential reference: observed Resolver metadata and normalization (T07)
- **Date:** 2026-10-24
- **Findings** (`SequentialReferenceTest`, Resolver 2.0.24, script: 2 artifacts, 2 versions, 3 builds each, 2.0 deployed before 1.0):
  - A-level `<versions>` keeps **deploy order** (`2.0-SNAPSHOT`, `1.0-SNAPSHOT`), it is not sorted. D-006 confirmed:
    "remote order, then our new versions appended" is what the native tool does.
  - **Surprise:** for snapshot-only artifacts Resolver writes **no** `<latest>` and no `<release>` in A-level metadata,
    only `<versions>` and `<lastUpdated>`. The merge rules for `latest`/`release` (design §5) apply only when a side has the element;
    the merger must not invent `latest` for snapshots, or the differential tests (T23) will fail.
- **Decision:** `TreeNormalizer` replaces `lastUpdated`/`updated` with `0`, renumbers builds per directory by `(timestamp, buildNumber)`
  to `TS-1..n` in file names and in V-level `timestamp`/`buildNumber`/`value`, and does not compare the content of
  `maven-metadata.xml.*` checksums (they follow from the content; `MetadataAssertions` verifies them).
  Build numbers that map to no file are left raw so a wrong number shows up in `TreeDiff`.
- **Rejected:** comparing raw checksums of metadata (they would differ with every normalized value).

## D-022 MavenConsumer: Maven Verifier 2.0.0, the build's own Maven, Central for plugins (T08)
- **Date:** 2026-10-24
- **Decision:** `MavenConsumer` (test-fixtures main) generates a one-dependency-list project, runs
  `maven-dependency-plugin:3.8.1:resolve` through `org.apache.maven.shared:maven-verifier:2.0.0` with an empty local repository and an own
  `settings.xml` (no user mirrors or profiles), `-B -U`, repository = given URL (snapshots + releases, `updatePolicy=always`).
  The Maven used is `maven.home`, passed to surefire by `test-fixtures/pom.xml` (the wrapper's 3.10.0). A failed resolution returns
  `success=false` plus `log.txt`; the file check on the local repository is the proof of success.
- **Reason:** Resolution must be Maven's own, from nothing cached. The dependency plugin is downloaded from Central into the empty
  local repository, so the test needs network access (the build already does).
- **Rejected:** Embedding Maven (`maven-embedder`, not the user's Maven); a shared local repository (would hide missing files).

## D-023 Concurrency harness shape (T09)
- **Date:** 2026-10-24
- **Decision:** `ConcurrentPublishScenario` runs one thread per `SequentialReference.Step` (barrier start). The publisher under test receives a
  `Publication` (step, bare repo, branch, own work dir, `DeployAction`) and must call the deploy action exactly once; the harness
  records every non-metadata file that deploy wrote, plus the order the deploys completed. Checks: no publisher error, every recorded
  file on the branch with identical content, `MetadataAssertions` on a checkout, and `TreeDiff` against `SequentialReference` replaying the recorded order.
  `ReferencePublishers.globalLock()` is the correct reference; `forcePushWithoutLease(beforePush)` is the broken one, made deterministic by a
  barrier between deploy and push (every thread reads the same stale tip). The broken publisher serializes only the physical git write:
  concurrent writers of identical objects fail on Windows file renames, which would be noise, not the race under test.
- **Reason:** Metadata files have no unique names, but timestamped artifacts do, so "every deployed file present" needs no assumptions about the publisher.
  A timing-dependent broken publisher would make the self-test flaky.
- **Rejected:** Asserting commit count in the scenario (the production publisher asserts exactly one commit itself); detecting loss only through metadata.

## D-024 Reposilite E2E profile (T09b)
- **Date:** 2026-10-24
- **Decision:** Profile `e2e` in `test-fixtures/pom.xml` binds failsafe (`**/*E2E.java`, `failIfNoTests=true`). `ReposiliteE2E` starts
  `dzikoysk/reposilite:3.6.4` (Testcontainers 2.0.5, token via `REPOSILITE_OPTS=--token admin:<throwaway>`), `ResolverDeployer`
  (new HTTP constructor with basic auth) deploys 3 builds to `/snapshots`, then `MavenConsumer` resolves jar and sources from it.
  Testcontainers is a test-scope dependency always, so the E2E class compiles in the default build; the default build does not run it.
  CI runs `./mvnw verify -Pe2e` and a second step sums the `tests` of `failsafe-reports/TEST-*E2E.xml` and fails when it is 0.
  Two layers: failsafe itself fails ("No tests were executed") when the E2E class is removed (verified locally), the CI script guards against an empty or missing report.
- **Reason:** Reposilite is an interop smoke test only (D-014). A guard that cannot silently pass keeps the profile from rotting.
- **Rejected:** Running `-Pe2e` only in a separate CI job (a second JDK/Maven setup for one test).

## D-025 Builds are ordered by build number (R01)
- **Date:** 2026-10-24
- **Decision:** Retention (design 6) and the V-level merge (design 5) order builds by `(buildNumber, timestamp)`, not by timestamp. The merge picks per
  `(classifier, extension)` the entry of the build with the greater build number instead of the greater `updated`, and `<snapshot>` is the
  `(timestamp, buildNumber)` of that build. New invariant, enforced by `MetadataAssertions` (and so by `IntegrityCheck`, T17):
  `<snapshot>` names a build that has files in the directory.
- **Reason:** Maven stamps snapshots with the session start (`maven.startTime`), so a build that started earlier but deployed later gets an older
  timestamp and a higher build number, and Maven's metadata points at it. Ordering by timestamp made retention delete that build (review F1).
- **Rejected:** Ordering by timestamp with build number as tie-break (the bug); recomputing `<snapshot>` from file names (hides inconsistent input).

## D-026 ResolverDeployer is one Maven session (R02)
- **Date:** 2026-10-24
- **Decision:** `ResolverDeployer` sets `maven.startTime` once per instance (default: construction time; explicit `Instant` constructor). Every snapshot it
  deploys has that timestamp; build numbers still increase. `ConcurrentPublishScenario` takes the start time right after the barrier, before checkout.
  Self-test `ResolverDeployerStartTimeTest`: B (started 60 s ago) deploys first, A (started 600 s ago) deploys last; result is build 1 with the newer
  timestamp, build 2 with the older timestamp, and `<snapshot>` = build 2 with the older timestamp, as Maven does.
- **Reason:** The oracle must reproduce what `mvn deploy` stamps; otherwise out-of-order timestamps (D-025) can never occur in tests.
  Side effect: `SequentialReference.replay` now stamps a whole script with one timestamp; names stay unique through build numbers, and `TreeNormalizer` orders by build number.

## D-027 The tree ref is a hint, not a lease (R03)
- **Date:** 2026-10-24
- **Decision:** The push of `refs/publisher/tree` (D-020) is forced with no expected old id. Its status is ignored: only the branch update decides OK or
  retry (design 4.5). The old design expected "the tree of `expectedSha`" and treated a rejection like a lease failure.
- **Reason:** Review F3. When the branch exists without the tree ref (created by hand, by a Pages setup or by plain `git push`), that lease can never
  succeed, but the branch update in the same push does. Every attempt then moved the branch and was rejected again until `maxAttempts`, and every later publish failed the same way.
  Evidence: JGit 7.8.0 over `file://`: branch `OK`, tree ref `REJECTED_REMOTE_CHANGED`.
- **T20 Done when** extended: branch without tree ref publishes in one attempt. If the server rejects the custom ref outright, the publish is still correct (D-020 fallback).
- **Rejected:** Atomic push (`setAtomic(true)`): not supported everywhere and would make the hint block the publish; lease with the fetched id of the tree ref (an extra fetch for a hint).

## D-028 Same-snapshot collisions are renumbered; the reference replays push order (R04)
- **Date:** 2026-10-24
- **Decision (design 7):** when replay finds that our snapshot build number is already taken on the remote for that version, our build is
  renumbered to remote max + 1 (files and checksums renamed, our metadata entries rewritten) before the three-way merge. This replaces "should be impossible".
  **Harness:** `ConcurrentPublishScenario` gives every publication of a step without explicit parts unique content (jar, pom, sources carry the publication
  index), so collapsing two builds into one is a lost file. `deployedFiles` is a list of (path, content), not a map: a path written twice with different content
  is always reported lost. The scenario takes an optional session clock (tests fix it to force same-second builds).
  Publishers call `Publication.pushed()` right after their push; `assertMatchesSequentialReference` replays `pushOrder()`, not `deployOrder()`, and fails
  clearly if the publisher did not report every push. Self-tests: `ConcurrentPublishScenarioOrderTest` (`overlayWithoutRenumbering` is caught; a publication
  pair pushed against deploy order matches the push-order reference and not the deploy-order one).
- **Reason:** Review F4. Two same-second builds of one snapshot produce identical file names with different content, which byte-identical test content hid.
  `<versions>` follow push order (design 5), so a deploy-order reference flags a correct publisher.
- **Risk:** `pushed()` is called after the push returns, so two publishers that push back to back can report in the other order. T22/T23 must report
  from a hook right after the lease-winning push, or force the push order in the differential scripts.
- **Rejected:** Reading push order from branch history (the branch has one parentless commit); keeping `deployOrder()` as the reference and sorting metadata away.

## D-029 LeakScanner looks for encoded canaries; e2e is a separate failsafe execution (R05)
- **Date:** 2026-10-24
- **Decision:** `LeakScanner` searches, in text, file names, file contents and Git config, for the raw canary, the URL-encoded canary, and the
  Base64 canary (standard and URL-safe alphabets) at all three alignments (the stable middle of the encoding of 0, 1 and 2 bytes of prefix plus the canary),
  which covers `Authorization: Basic base64(user:canary)` for any username. Findings name the form ("Base64 canary") and never echo a secret.
  Profile `e2e` adds a second failsafe execution (`e2e`, includes `**/*E2E.java`, reports in `target/failsafe-reports-e2e`) instead of replacing the includes
  of the default one, so `*IT` keeps running under `-Pe2e`. The CI guard sums `tests - skipped` over `failsafe-reports-e2e`.
- **Reason:** Review F5 and F8. A wire/debug log carries the token as Base64; a skipped E2E (`disabledWithoutDocker`) counted as executed.
- **Rejected:** Decoding every Base64 run found in the text (heavier, more false positives); a username list for the basic-auth form (the alignment forms already cover any username).

## D-030 TreeNormalizer normalizes `.module` files (R06)
- **Date:** 2026-10-24
- **Decision:** `.module` contents get the same timestamp/build-number normalization as file names (UTF-8, CRLF folded). Checksum files of `.module`
  (`.md5/.sha1/.sha256/.sha512`) are compared by name only, like metadata checksums. A real content difference in a `.module` is still reported.
- **Reason:** Review F6. Gradle writes timestamped names into `variants[].files[].url`; without this T41 can never pass.

## D-031 A version directory must contain a regular file (R07)
- **Date:** 2026-10-24
- **Decision:** Invariant "every `<version>` has a directory" requires at least one regular file below that directory (`MetadataAssertions`; the production
  `IntegrityCheck` of T17 must agree). Design section 5 wording unchanged in meaning: Git stores no empty directories, so the pushed branch is the judge.
- **Reason:** Review F7. `keepVersions` (T47) empties version directories; the tree passes in `repo/` and fails on the branch.
