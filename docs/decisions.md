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
- **Revised by D-037:** there is no separately built `core` artifact any more; how the Gradle build reuses the core packages is Q10.

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
- **Superseded by D-032:** no metadata merge; concurrent changes to one path are a conflict.

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
- **Revised by D-032:** the merge part is gone; the rule still applies to `keepVersions` and to `<versions>` order written by Resolver.

## D-007 Maven reactor trigger: last participating project
- **Date:** 2026-10-23
- **Decision:** A session-scoped collector counts projects in `session.getProjects()` that execute the goal; the last one to report runs checkout → one `RepositorySystem.deploy` → publish. Thread-safe for `-T`.
- **Reason:** Same approach as `maven-deploy-plugin`'s `deployAtEnd`; no `<extensions>true</extensions>` needed. A failed module stops the build before the trigger, so nothing partial is published.
- **Rejected:** `AbstractMavenLifecycleParticipant` (needs build extension), deploying per module (one commit per module, many pushes).
- **Revised by D-034:** the "nothing partial is published" reason is wrong for non-participating modules that build after the last participant.

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
- **Revised by D-038:** Reposilite and the `e2e` profile are removed. The sequential reference stays the primary oracle.
- **Revised by D-043:** consumers resolve from a `file://` checkout; no embedded HTTP server stands in for Pages.

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
- **Superseded in part by D-037:** one Maven module with `maven-plugin` packaging replaces the parent + `core`/`test-fixtures`/`maven-plugin` layout. Version pins and the license check stay.

## D-017 Golden fixtures: sources and layout (T03)
- **Date:** 2026-10-23
- **Decision:**
  - Golden files live in `test-fixtures/src/main/resources/golden/` at their repository paths. Source URLs and fetch date are in `golden/README.md` (the `fixtures/README.md` of the T03 text).
  - KMP "latest release" is the `<release>` Central declares for `kotlinx-datetime`: `0.8.0-0.6.x-compat`. Its `-jvm`, `-js` and `-linuxx64` artifacts exist. The plain `0.8.0` line exists too and is not used.
  - V-level golden is the latest `maven-resolver-api` snapshot on repository.apache.org: `2.0.25-SNAPSHOT`.
  - G-level metadata has no groupId/artifactId, so the test checks `<plugins>` for that file only.
- **Reason:** Take the source the task names, not a choice made by the implementer. Central's `<release>` is the definition of "latest release".
- **Rejected:** Picking `0.8.0` by hand (would hide what Central actually declares as release).
- **Revised by D-037:** golden files move to `src/test/resources/golden/`.

## D-018 LeakScanner scope and redaction (T10)
- **Date:** 2026-10-23
- **Decision:** `LeakScanner` (test-fixtures) scans three things: captured text, a directory tree (entry names and bytes of regular files, binary included), and Git config files (`config` inside a `.git` directory or next to `HEAD` and `objects/`). Every finding is redacted: the canary is replaced by `<redacted>` before it appears in a message, so the scanner's own output cannot leak.
- **Reason:** The brief's rule is that a canary must never appear in logs, the work directory or Git config. Scanning names as well as bytes covers the case where a token ends up in a path.
- **Rejected:** Scanning compressed archives or Git objects (not in the planned checks; Git objects are stored compressed and are not credentials-bearing configs). Printing the canary in findings.
- **Superseded by D-045.**

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
- **Superseded by D-044:** the N-thread barrier scenario is replaced by deterministic interleaving.

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
- **Superseded by D-038.**

## D-025 Builds are ordered by build number (R01)
- **Date:** 2026-10-24
- **Decision:** Retention (design 6) and the V-level merge (design 5) order builds by `(buildNumber, timestamp)`, not by timestamp. The merge picks per
  `(classifier, extension)` the entry of the build with the greater build number instead of the greater `updated`, and `<snapshot>` is the
  `(timestamp, buildNumber)` of that build. New invariant, enforced by `MetadataAssertions` (and so by `IntegrityCheck`, T17):
  `<snapshot>` names a build that has files in the directory.
- **Reason:** Maven stamps snapshots with the session start (`maven.startTime`), so a build that started earlier but deployed later gets an older
  timestamp and a higher build number, and Maven's metadata points at it. Ordering by timestamp made retention delete that build (review F1).
- **Rejected:** Ordering by timestamp with build number as tie-break (the bug); recomputing `<snapshot>` from file names (hides inconsistent input).
- **Revised by D-032:** the merge half no longer exists; ordering by build number for retention and the `<snapshot>` invariant stay.

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
- **Superseded by D-032:** no renumbering; same-snapshot concurrency is outside the contract and ends in a conflict. Unique content per publication stays in the harness; the push-order reference is removed (T55).

## D-029 LeakScanner looks for encoded canaries; e2e is a separate failsafe execution (R05)
- **Date:** 2026-10-24
- **Decision:** `LeakScanner` searches, in text, file names, file contents and Git config, for the raw canary, the URL-encoded canary, and the
  Base64 canary (standard and URL-safe alphabets) at all three alignments (the stable middle of the encoding of 0, 1 and 2 bytes of prefix plus the canary),
  which covers `Authorization: Basic base64(user:canary)` for any username. Findings name the form ("Base64 canary") and never echo a secret.
  Profile `e2e` adds a second failsafe execution (`e2e`, includes `**/*E2E.java`, reports in `target/failsafe-reports-e2e`) instead of replacing the includes
  of the default one, so `*IT` keeps running under `-Pe2e`. The CI guard sums `tests - skipped` over `failsafe-reports-e2e`.
- **Reason:** Review F5 and F8. A wire/debug log carries the token as Base64; a skipped E2E (`disabledWithoutDocker`) counted as executed.
- **Rejected:** Decoding every Base64 run found in the text (heavier, more false positives); a username list for the basic-auth form (the alignment forms already cover any username).
- **Revised by D-038:** the `e2e` part is gone with the profile. The encoded-canary checks stay.
- **Superseded by D-045** (scanner part).

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

## D-032 Same-path concurrency is serialized by CI; replay without metadata merge
- **Date:** 2026-10-24
- **Decision:** Publications that share a path (same `groupId:artifactId`, or a shared metadata path such as G-level plugin metadata) must be
  serialized externally, across every workflow that can publish them, for all coordinates a project produces. The core keeps lease, retry and
  overlay replay for independent publishers, and replays only independent changes: a path the remote did not change is written, a path the
  remote changed to identical bytes is kept (`.nojekyll`), anything else is a conflict → `PublishException` naming the paths, no retry, no push.
  `MetadataMerger`, the three-way merge (D-004), same-snapshot renumbering (D-028) and task T15 are removed. The metadata model stays for retention
  and `IntegrityCheck`. Concurrency tests publish distinct coordinates with disjoint metadata paths; a conflict test proves safe failure.
- **Reason:** Maintainer decision: builds of the same artifact never run concurrently, and different source repositories publish different
  artifacts. A general merge was the riskiest code in the plan (reviews F1, F4) for a case the pipeline excludes. Failing on conflicts keeps the
  "never overwrite a remote change" property when the precondition is violated.
- **Rejected:** keeping the merge for robustness (no supported use case); last-writer-wins on conflicts (silent loss).
- **Traceability:** T15 removed; T21b rewritten as conflict detection; T22/T23 use distinct coordinates; brief "Concurrency" rewritten.

## D-033 Gradle lifecycle: guarded push after all participants, no unconditional finalizer
- **Date:** 2026-10-24
- **Decision:** Contract in design §2 (Gradle): checkout once before any participating publication writes; push once after every participating
  publication succeeded; a failed publication prevents push (also with `--continue` and `--parallel`); cleanup always. Participants = the
  `publish*PublicationToGitPublisherRepository` tasks in the task graph. Ordering is explicit (`dependsOn` checkout, push `mustRunAfter` every participant);
  the BuildService coordinates state and serializes writers (`maxParallelUsages = 1`) but is not relied on for ordering. `BuildService.close()` never publishes.
  The mechanism (guarded finalizer or Flow API) is decided in T36.
- **Reason:** Gradle runs finalizers even when the finalized task fails, so "push is a finalizer" would publish partial results.
- **Rejected:** unconditional finalizer; publishing from `BuildService.close()`; relying on service sharing for ordering.

## D-034 Maven reactor guarantee: all participants succeeded, not the whole reactor
- **Date:** 2026-10-24
- **Decision:** Push happens only after every participating execution completed successfully (participants and checks in design §2, Maven).
  The plugin does not claim that the whole reactor or CI pipeline succeeded. A failing non-participating module after the last participant leaves
  the publication pushed; a regression test asserts this. Users who need the stronger guarantee publish in a separate CI stage after validation.
  Skipped participants still check in; configuration mismatches between participants fail before checkout.
- **Reason:** Review of the plan: D-007's "a failed module stops the build before the trigger" is false for trailing modules, and a thread-safe
  collector does not prove reactor success. A participant that never checks in would silently prevent publication.
- **Rejected:** `AbstractMavenLifecycleParticipant.afterSessionEnd` (needs `<extensions>true</extensions>`; still not the whole CI pipeline).

## D-035 Two authentication scenarios; Pages deployed explicitly for self-publication
- **Date:** 2026-10-24
- **Decision:** Self-publication uses `GITHUB_TOKEN` (`contents: write`) to its own repository and deploys Pages explicitly in the same workflow
  after a successful publish. External users use a fine-grained PAT (or GitHub App token) scoped to the destination repository; Pages deploys from the
  branch or from a push-triggered workflow in the destination repo. Self-publication starts only after a usable bootstrap version exists (T33).
- **Reason:** Pushes made with `GITHUB_TOKEN` do not trigger workflows; whether they trigger branch-source Pages builds is unverified, so it is not relied on.
  Authorization to push and deployment to Pages are separate concerns.
- **Open:** Q9 (Pages on this private repository).
- **Revised by D-043:** no Pages deployment by the plugin or its templates; serving the branch is the user's job.

## D-036 Repository size and HTTP cache risk
- **Date:** 2026-10-24
- **Decision:** No size budgeting or large-repository work in the MVP; retention stays. A parentless branch bounds reachable history only; unreachable
  objects are collected by the host later. The risk that cached metadata names a file already deleted by retention is accepted, documented, with no
  grace period. Each published tree stays internally consistent.
- **Reason:** Maintainer decision: ZenWave's volume is small. The brief overstated both "never grows" and "never serves a broken mix".

## D-037 One Maven module with `maven-plugin` packaging
- **Date:** 2026-10-24
- **Decision:** One root Maven project, packaging `maven-plugin`, artifact `git-maven-publisher-maven-plugin`. Core logic in package
  `io.github.ivangsa.gitpublisher` with an architecture test forbidding `org.apache.maven`/`org.gradle` imports; Maven adapter in `...gitpublisher.maven`;
  harness in `src/test/java/.../fixtures`; golden files in `src/test/resources/golden`; invoker ITs in `src/it` (harness via `addTestClassPath`).
  Until T25 adds the first mojo, `maven-plugin-plugin` runs with `skipErrorNoDescriptorsFound`. Multi-module *user* projects stay tested (T26, T27).
  The Gradle build's reuse of the core packages is open (Q10) and decided before Phase 2.
- **Reason:** Maintainer decision: one published artifact is enough for the MVP; three modules added build and release work for no user.
- **Rejected:** keeping `core`, `test-fixtures`, `maven-plugin` modules.
- **Traceability:** T53 performs the migration; T32 releases one artifact; T34 depends on Q10.

## D-038 Reposilite removed
- **Date:** 2026-10-24
- **Decision:** No Reposilite, no Testcontainers, no `e2e` profile, no Docker requirement (T09b's work is removed by T54). Independent checks kept: real Resolver
  deploys to `file://`, golden metadata, Maven's metadata reader, clean Maven and Gradle consumers over a local HTTP server, real Git lease/replay tests,
  concurrent publishers on distinct artifacts, negative integrity and leak tests. The sequential reference compares direct Resolver output with output
  transported through Git; it is not described as a repository-manager oracle.
- **Reason:** Maintainer decision. D-014 already established that a repository manager is not a metadata oracle; the smoke test cost Docker in CI.
- **Rejected:** replacing Reposilite with a fake repository manager (would reproduce our own assumptions).

## D-039 Review test R08 removed: it specifies renumbering, which D-032 dropped
- **Date:** 2026-10-24
- **Decision:** `review/R08RenumberedPublicationTest` (review 2026-10-24-32f382a, F1) is deleted. Task R08 is superseded. T09 and R04 stay `[!]` until T55
  reworks the harness for the revised contract and shows that collapsed publications, wrong content and loss are still detected.
- **Reason:** The test is provably wrong under the current design: it requires `ConcurrentPublishScenario` to accept a colliding snapshot build renamed to
  remote max + 1 (D-028). D-032 removes renumbering; publications of one snapshot are serialized by CI, and when they are not, replay must fail with a conflict.
  A harness that accepted the rename would accept behaviour the design now forbids. The review's F1 was correct for the design it reviewed (32f382a).
- **Rejected:** keeping R08 and implementing renumbering anyway (contradicts the maintainer decision behind D-032).

## D-040 Checkout makes the destination exact; scanner omits unsafe labels (R09, R10)
- **Date:** 2026-10-24
- **Decision:** `BareRepo.checkout` removes everything inside the target directory before writing the branch tip, so a reused destination holds exactly the branch files, including file/directory transitions. `LeakScanner` replaces a whole source label or path with `<redacted>` when it contains any tracked representation (raw, URL-encoded, Base64), instead of replacing only the raw canary.
- **Reason:** Review 32f382a F2 and F3. Stale files make Pages serve deleted artifacts; encoded canaries in labels were echoed in findings (D-018, D-029).
- **Rejected:** Deleting only paths missing from the new tree (more code, same result for a plain tree); masking only the matched fragment (leaves recoverable remnants of the encoding).
- **Revised by D-045:** the scanner part is gone with the scanner. The exact checkout stays.

## D-041 Architecture rule checked by source scan; single-module layout (T53)
- **Date:** 2026-10-24
- **Decision:**
  - `ArchitectureTest` reads the source text of `src/main/java/io/github/ivangsa/gitpublisher` (except `maven/`) and fails on any code line naming `org.apache.maven.` or `org.gradle.`. Comment lines are skipped. A self-test plants violations in a temporary tree and checks that they are reported and that the adapter package is exempt.
  - Harness and golden files move to `src/test` (D-037). Their dependencies stay at `test` scope in the single project; production dependencies (JGit, Maven API, Resolver API) are added by the tasks that use them.
  - CI uses `actions/setup-java@v5`. `.gitattributes` keeps golden files byte for byte under the new path. `.gitignore` is unchanged: `target/` already matches at any depth.
- **Reason:** The layering rule needs an enforcing test, and the layout must keep harness dependencies out of the plugin artifact.
- **Rejected:** ArchUnit (a new test dependency for one rule); compile-scope JGit before any production code uses it.

## D-042 Reposilite code removed with the profile (T54)
- **Date:** 2026-10-24
- **Decision:** Removed `ReposiliteE2E`, the `e2e` Maven profile and its failsafe execution, the Testcontainers dependency and version, the CI `-Pe2e` build and the zero-test guard.
  `ResolverDeployer` loses its HTTP constructor (and basic-auth path): its only caller was the Reposilite test. Deployers write to a `file://` directory only, as D-038 requires.
- **Reason:** D-038. The E2E test was the only user of Docker, Testcontainers and HTTP deploys in the harness.
- **Rejected:** keeping the HTTP constructor for a future repository-manager test (no such test is planned; it would be untested code).

## D-043 Serving the branch is the user's job; `.nojekyll` is opt-in; no `PagesServer`
- **Date:** 2026-10-24
- **Decision:** The plugin pushes the branch and nothing else. Users serve it however they like (`raw.githubusercontent.com` for public repos, GitHub Pages
  set up by hand, any static server). New option `nojekyll` (Maven `git-publisher.nojekyll`, Gradle `nojekyll`), default `false`, writes `.nojekyll` for Pages users.
  No Pages deployment in the plugin, in self-publication or in the T50 templates. Tests: `PagesServer` is removed (T56); consumers resolve from a `file://`
  URL to an exact checkout of the pushed branch. Jetty stays only for JGit's HTTP server.
- **Reason:** Maintainer decision. Pages is one hosting option among several; the files a static server returns are exactly the checked-out branch, so an HTTP
  stand-in added code without testing anything of ours.
- **Rejected:** Pages as the required host; `.nojekyll` always written.

## D-044 Concurrency is tested by deterministic interleaving
- **Date:** 2026-10-24
- **Decision:** `InterleavedPublishScenario` replaces `ConcurrentPublishScenario`: A checks out, B publishes in full, then A pushes. Distinct coordinates must both
  survive (lease failure + replay); a shared path must leave B untouched and fail A; a push without a lease is caught losing B's files. The N-thread stress test (T22)
  is dropped. Lease and replay stay: a `concurrency` group in GitHub Actions only serializes workflows inside one repository, and several repositories publish
  into one snapshots repository, where a force push without a lease would silently delete the other project's files.
- **Reason:** Maintainer review. The risk is one specific interleaving; a deterministic test reproduces it every time, a thread race only sometimes.
- **Rejected:** no concurrency tests (the lease is the one guard against silent loss); N-thread stress runs.

## D-045 No credential leak scanner
- **Date:** 2026-10-24
- **Decision:** `LeakScanner`, its tests, the review tests `R05LeakScannerBasicAuthTest` and `R10EncodedFindingRedactionTest`, the canary checks of T24/T28/T38 and
  `Redactor` are removed (T57). T10, R05 (scanner part) and R10 are withdrawn. The design keeps plain rules with ordinary unit tests: reject URLs with userinfo,
  pass the token only to JGit's credentials provider, never write it to the remote URL, Git config, a committed file or a log line.
- **Reason:** Maintainer decision: the plugin creates no credentials and handles them like any deploy plugin (Maven Central publishing does not scan for leaks).
  The review tests are removed under AGENTS.md's "provably wrong" rule: they specify a component the design no longer has.
- **Rejected:** keeping the scanner as an extra safety net (two review rounds hardened it, a cost out of proportion to the risk).

## D-046 Interleaving harness shape (T55)
- **Date:** 2026-10-24
- **Decision:** `InterleavedPublishScenario` runs on one thread: publisher A calls `Publication.checkedOut()` after reading the branch, and B publishes in full inside that call; then A continues. A publisher that never calls it (a lock) is run A then B, and `interleaved()` is false. `ReferencePublishers` has `globalLock` (correct), `forcePushWithoutLease` (broken) and `replayOrFail` (overlays its changed files, throws on a path changed on both sides; the passing interleaved case and the shared-path failure). Sequential-reference comparison accepts A-then-B or B-then-A, since distinct coordinates are order independent.
- **Removed:** `ConcurrentPublishScenario` and its tests (N threads, barrier), `pushed()`/`pushOrder()` and the replay-in-push-order check, `overlayWithoutRenumbering`, the `beforePush` barrier of the broken publisher, `ConcurrentPublishScenarioOrderTest` (its intent, same-second collisions, wrong content and loss still detected, lives on in `InterleavedPublishScenarioTest`).
- **Reason:** D-044: one specific interleaving, reproduced every time. `replayOrFail` is a third publisher beyond the two the task names, needed to show a passing interleaving and a "fails safely" shared-path case.

## D-047 Core config shape (T12)
- **Date:** 2026-10-24
- **Decision:** `PublishConfig` is the design's record plus `boolean nojekyll` (last component); `PublishConfig.of(repository, workDirectory)` gives the defaults. `Credentials.Token` is a final class, not a record, so `toString()` hides the token and `equals` compares contents; it copies the `char[]` in and out and has `clear()`. `keepVersions` must be `-1` or at least 1; `RetryPolicy` requires `maxDelay >= baseDelay`. Branch names are checked with JGit's ref-name rules plus no leading `-` and no whitespace. Validation messages never echo the repository URL (it may hold a secret put there by mistake): they name the scheme only. JGit moved from test to compile scope; it is the only runtime dependency of the core.
- **Reason:** Design section 8 (token never in a log line or string form) and D-045 (plain unit tests instead of a scanner).

## D-048 Metadata model sits on an element tree (T13)
- **Date:** 2026-10-24
- **Decision:** `MetadataModel` (package-private) wraps `XmlNode`, a small element tree read and written with StAX (DTD and external entities off, namespaces not interpreted). Typed getters read the tree; the only mutators are the ones retention needs (`removeSnapshotVersionsIf`, `removeVersions`, `setLatest`, `setRelease`, `setLastUpdated`). Unknown elements and attributes stay where they were. Output is UTF-8, two-space indentation, `<x/>` for empty elements. Comments are dropped.
- **Reason:** Design section 5 asks to preserve unknown elements where possible; a tree does that with no per-element code, and the round-trip test uses Maven's reader and writer as the oracle.

## D-049 Checksum file format and algorithm set (T14)
- **Date:** 2026-10-24
- **Decision:** `ChecksumWriter` writes the lower-case hex digest with no newline and no file name, which is byte-identical to what Maven Resolver writes (tested against Resolver trees with SHA-1/MD5 and with all four algorithms). Algorithms written = those already present next to the file plus the configured ones. `ChecksumAlgorithm` knows `md5`, `sha1`, `sha256`, `sha512` and parses Resolver names (`SHA-1`) as well as extensions.
- **Reason:** Design section 5 (checksums of rewritten metadata for every algorithm present plus the configured ones).

## D-050 IntegrityCheck mirrors MetadataAssertions (T17)
- **Date:** 2026-10-24
- **Decision:** `IntegrityCheck.violations(root, requiredChecksums)` uses the same checks and the same message texts as `MetadataAssertions` (the harness, which parses with Maven's reader); only the parse failure text differs and is a single line. `verify` throws `PublishException` listing every violation and saying nothing was pushed. `PublishException` is a checked exception, as the `publish` signature in design section 3 implies. Agreement is tested on a Resolver tree (two versions, three builds, a `.module`) and 12 crafted breakages, for three required-checksum sets. The test found that Resolver does not write `<latest>`/`<release>` for snapshot-only artifacts, so those two invariants are only exercised on crafted metadata.
- **Reason:** Design section 5 and D-031: the production check and the oracle must not drift.

## D-051 Retention API and build identification (T16)
- **Date:** 2026-10-24
- **Decision:** `Retention.apply(root, touchedVersionDirectories, RetentionPolicy, checksums)` (package-private) returns the deleted files relative to `root`. A build is found per file name with `<version-without-SNAPSHOT>-<yyyyMMdd.HHmmss>-<n>` followed by `-` or `.`; builds are ordered newest first by `(buildNumber, timestamp)` (D-025). Files without that stamp (plain `lib-1.0-SNAPSHOT.jar`, README) are never touched. Directories not ending in `-SNAPSHOT`, missing, or not listed are ignored. `maven-metadata.xml` is rewritten (and its checksums regenerated for present plus configured algorithms) only when a `<snapshotVersion>` entry was dropped, so untouched metadata stays byte-identical. `<snapshot>` and `lastUpdated` are not changed. `keepVersions` is not part of T16 (Phase 3).
- **Reason:** Design section 6; Resolver lists only the current file per `(classifier, extension)`, so a classifier last deployed in an old build leaves a dangling entry that must be dropped.
