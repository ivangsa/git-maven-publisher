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
- **Superseded by D-075:** the single-module layout. The reactor split is T58 (2026-10-25).
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
- **Revised by D-043, D-045:** consumers resolve from a `file://` checkout, not a local HTTP server; the leak tests are gone with the scanner.
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


## D-052 Metadata XML keeps qualified attribute names (R11)
- **Date:** 2026-10-24
- **Decision:** `XmlNode` stores attributes as `prefix:local` (namespace declarations `xmlns`, `xmlns:xsi` and `xsi:schemaLocation` included) and writes them back unchanged. Namespaces are still not interpreted. The T13 tests gain a namespaced round-trip case checked by Maven's strict reader.
- **Reason:** Review 9150059 F1: reading with the local name dropped the prefix, so retention rewrote valid namespaced metadata into a file Maven rejects. This makes D-048 ("unknown elements and attributes stay where they were") true for prefixed attributes.

## D-054 Registry-based authorities cannot hide credentials (R13)
- **Date:** 2026-10-24
- **Decision:** `PublishConfig` rejects a repository URL when `URI.getRawUserInfo()` is set, when the raw authority contains `@` (which `java.net.URI` leaves unparsed for registry-based authorities such as a host with `_`), or when an opaque URI contains `@`. The message still does not echo the URL.
- **Reason:** Review 9150059 F3: `file://user:secret@my_host/repo.git` passed because `getRawUserInfo()` is null for such authorities.


## D-053 Checksum algorithms and nojekyll in PublishConfig (R12)
- **Date:** 2026-10-24
- **Decision:** `PublishConfig` gains `Set<ChecksumAlgorithm> checksumAlgorithms` (non-null, non-empty, copied to an unmodifiable `EnumSet`; default SHA-1 and MD5, `PublishConfig.DEFAULT_CHECKSUMS`) as its last component. `nojekyll` was already in the code (D-043, D-047); design section 3 now lists both. `ChecksumAlgorithm` is public (with `parse`, `extension`, `jcaName`) so adapters can map `checksumAlgorithms` / Gradle's algorithms onto it. T20/T21 pass `config.checksumAlgorithms()` to `Retention.apply` and `IntegrityCheck.verify`.
- **Reason:** Review 9150059 F2/F4: without the configured set in the core config, publish() would have called IntegrityCheck with an empty set and weakened the design section 5 invariant.

## D-055 Checkout shape (T18)
- **Date:** 2026-10-24
- **Decision:** `GitPublisher.checkout()` deletes `workDirectory/git` and `workDirectory/repo` only (not the whole work directory, which adapters may share), inits a bare repo in `git/`, asks the remote for its heads (`ls-remote`) and fetches `refs/heads/<branch>` at depth 1 only when it is advertised; a missing branch, or an empty remote, starts with an empty `repo/`. URL and credentials are passed per call, so `git/config` has no `[remote]` section. `TreeMaterializer` refuses symbolic links, submodules and paths that escape `repo/`: a tree that cannot be reproduced would silently lose them on the next parentless commit. Deleting the work directory clears the read-only flag on Windows. `PublishInfo`, `PublishResult`, `Publication` and `PublisherLog` from design section 3 exist now; `publish` throws `UnsupportedOperationException` until T20. Any failure of checkout is a `PublishException` (retry classification of transport and auth errors belongs to T21).
- **Reason:** Design sections 3, 4 and 8. The `ls-remote` round trip avoids parsing JGit's "Remote does not have ... available" message to detect a missing branch.

## D-056 Change recorder compares Git blob ids (T19)
- **Date:** 2026-10-24
- **Decision:** `ChangeRecorder.record(repository, base, directory)` hashes each regular file as a Git blob (no symlink following) and compares it with the blob ids of the base commit's tree; the result separates `added` and `modified`, each with the file bytes, because the bytes are the "ours" side of the overlay replay and retention rewrites files afterwards. Files removed by the deploy are not reported (retention reports its own deletions). Empty directories are ignored (Git stores none, D-031).
- **Reason:** Design section 4 step 3.i and section 7. Blob ids need no extra object writes and agree with what the commit will contain. Holding the bytes in memory is acceptable under the size limits of design section 10.

## D-057 Commit and single push attempt (T20)
- **Date:** 2026-10-24
- **Decision:** `GitPublication.publish` runs `ChangeRecorder`, `Retention` (on every `-SNAPSHOT` directory that holds an added or modified path, with `config.checksumAlgorithms()`), `IntegrityCheck.verify`, then `TreeCommitter` (blobs, tree and a parentless commit from `repo/`, author and committer from `config.author()` with the current time, regular-file mode only) and one `GitRemote.push`. `push` uses `Transport.push` with two `RemoteRefUpdate`s: the branch, forced, with the lease (`zeroId` when the branch was missing), and `refs/publisher/tree`, forced, no lease, status ignored (D-020, D-027). It returns `PushOutcome.Pushed` (OK or UP_TO_DATE) or `PushOutcome.LeaseFailed` (`REJECTED_REMOTE_CHANGED`); any other status is an `IOException`. T20 turns a lease failure into a `PublishException` ("changed on the remote ... nothing was pushed"); T21 will loop on the outcome instead. Commit message: `Publish <coords joined by ", ">` (`Publish changes` when there are none), then a blank line and `source: <sha>` only when the source commit is known. Dry run builds the commit, reports `pushed=false` and `attempts=0`. `PublishResult.changedPaths` is the recorded added/modified set before retention; `deletedPaths` is what retention removed.
- **Reason:** Design section 4 steps 3.i to 3.vi. A sealed outcome keeps lease failure a value for the retry loop while the single-attempt behavior stays simple.


## D-058 Retry loop and overlay replay (T21)
- **Date:** 2026-10-24
- **Decision:** `GitPublication.publish` records `ours` once (before retention), then loops up to `RetryPolicy.maxAttempts`. From the second attempt on it fetches the tip again, deletes `repo/`, and `OverlayReplay` materializes the new tip and lays `ours` over it with design section 7's rule per path (base = `expectedSha` of the attempt that failed; remote unchanged -> write ours; remote has the same bytes -> keep; otherwise conflict). `expectedSha` then becomes the new tip, so the next replay compares against it. Retention, checksums, `IntegrityCheck` and the commit run again on every attempt. A lease failure and any transport `IOException` are retried with full-jitter back-off (`random(0, min(maxDelay, baseDelay * 2^(attempt-1)))`, warn log line per retry). Fatal, no retry: a `TransportException` whose message says unauthorized/forbidden/authentication/401/403, and `NoRemoteRepositoryException` (a missing repository does not appear by waiting). A conflict is thrown by the replay as `PublishException` listing every path and the serialization hint; the first failed attempt (the lease failure) is the only one before it. The conflict message is already in place for T21b, which adds its own tests. Exhausted budget: `PublishException` "after N attempts: <last reason>; nothing was pushed". `GitRemote` is non-final and `GitPublication.remotes` is a package-private test seam so tests can inject real JGit exceptions (captured from a Jetty server answering 401/503) into the loop; `PublishConfig` allows only https/file, so the loop cannot yet be driven over HTTP (T24).
- **Reason:** Design sections 4 and 7. Auth detection by message is the only handle JGit gives for HTTP 401/403; the tests use the exceptions JGit really throws. The T20 stale-lease tests now use a one-attempt budget, since with the default budget they publish via replay.

## D-059 Conflict detection tests (T21b)
- **Date:** 2026-10-24
- **Decision:** The conflict rule was already implemented by `OverlayReplay` (D-058); T21b adds `GitPublisherConflictTest`, which drives it with the interleaved scenario and real Resolver deploys: different versions of one `groupId:artifactId`, the same snapshot version, and two Maven plugins of one group. `ResolverDeployer.deployPluginGroupMetadata` uploads a group-level `maven-metadata.xml` through the Resolver's metadata upload (Resolver itself does not generate plugin prefix metadata; Maven core does), listing only the deploying plugin. Same-snapshot assertion names the `1.0-SNAPSHOT/` directory because two builds in one second collide on the build-1 files, in another second on the version metadata.
- **Reason:** Design section 7; the tests show the failed publication leaves the remote tree equal to what the winner pushed, with one commit and no token in the message.


## D-060 Review f9bb6b5 fixes: auth classification, permanent rejection, token clearing, atomic push (R15-R18)
- **Date:** 2026-10-24
- **Decision:** (R15, revises D-058) `GitPublication.isFatal` removes every URL from the JGit message, then matches JGit's own phrases: "not authorized" (401), "not permitted" (403, `serviceNotPermitted`), "authentication is required", "authentication not supported", "invalid credentials"; `NoRemoteRepositoryException` stays fatal. A repository named `authentication-snapshots` no longer decides. (R16) `GitRemote.push` throws `PushRejectedException` (an `IOException`, fatal) for branch statuses other than OK, UP_TO_DATE, REJECTED_REMOTE_CHANGED, NOT_ATTEMPTED and AWAITING_REPORT (those two stay retryable), with the remote's message and "the branch must allow force pushes". (R17) design section 8 holds as written: `GitRemote` clears each `UsernamePasswordCredentialsProvider` after its operation, and `GitPublication.close()` clears the `Credentials.Token` of its config, so a config (token) is single-use per publication; a second `checkout()` with the same cleared token would authenticate with NULs. (R18, revises D-027) the branch and `refs/publisher/tree` go in one atomic push (`setPushAtomic(true)`): a lease rejection moves nothing. A two-step push (hint after the branch) was tried first and rejected: JGit resends every tree and blob when only a tree is wanted (`secondAndLaterPushesSendOnlyTheNewObjects` failed with 93 objects instead of 47). If the server answers that it cannot do atomic pushes, the push is repeated with the branch only (no hint). That fallback has no test: JGit's server and GitHub both support atomic pushes and no oracle without it exists. The pack of a failed attempt still reaches the remote object store, unreferenced; only refs are guaranteed unchanged. T21b gains the F5 tests: remote deletes a path we changed, and the same snapshot in the same second / another second with pinned session times and exact paths.
- **Reason:** Review 2026-10-24-f9bb6b5 F1-F5; design sections 4, 7 and 8.

## D-061 Differential IT and token auth tests (T23, T24)
- **Date:** 2026-10-25
- **Decision:** (T23) `GitPublisherDifferentialIT` runs scripts of rounds through `InterleavedPublishScenario` on one bare repo (each round: A checks out, B publishes, A replays and pushes; coordinates disjoint within a round, repeated across rounds so builds accumulate), with retention disabled and `keepSnapshots=2`. Every publication has explicit, unique content so the expected tree can be rebuilt by `SequentialReference`. With retention the expected script keeps only the last N publications per `groupId:artifactId:version`, computed in the test, not by production `Retention`. (T24) `GitPublisherTokenAuthTest` needs real HTTPS because `PublishConfig` accepts only https and file: a Jetty TLS connector with a self-signed certificate for 127.0.0.1 made by the JDK `keytool`, trusted through `SSLContext.setDefault` and `HttpsURLConnection.setDefaultSSLSocketFactory` once per class (the JDK caches the default socket factory, so per-test certificates fail) and restored afterwards. A servlet filter enforces basic auth for exactly one token. JGit itself repeats a rejected basic-auth request up to 3 times inside one connection, so "one attempt" is asserted as at most 3 authenticated requests and no "Attempt n of m" log line (a retry loop would show 15). No production change was needed.
- **Reason:** Design sections 7, 8 and 9; T23 and T24 "Done when".

## D-062 Maven plugin skeleton (T25)
- **Date:** 2026-10-25
- **Decision:** The adapter compiles against the Maven 3.9.16 API (`maven-plugin-api`, `maven-core`, `maven-settings-builder`, all `provided`) and Resolver API 2.0.24 (`provided`, the version Maven 3.10.0 ships); the 3.9 plugin API is what Maven 3.10 and 4 still run. `DeployMojo` fields carry the design section 2 parameters; `repository` is **not** `required` in the descriptor because a project with `skip=true` need not configure it, and `PublisherParameters.toConfig` fails with "git-publisher.repository is required" otherwise. `PublisherParameters` (record) holds the settings of an execution as written, validates them into a `PublishConfig`, never echoes the repository URL in an error (a malformed URI would put userinfo in the message), and compares two executions by setting name for the reactor check of T26. `ReleaseGuard` treats only a `-SNAPSHOT` suffix (upper case) as a snapshot. `@Parameter` has class retention, so `DeployMojoParametersTest` reads the generated `plugin.xml` instead of reflecting; this also proves the descriptor is generated. Compile-time injected components use Plexus `@Component` (T26/T28), which Maven 3.10 and 4 both honour; `javax.inject` moved to `jakarta.inject` in Maven 4.
- **Reason:** Design section 2, D-037. `skipErrorNoDescriptorsFound` is gone from the POM now that a mojo exists.

## D-063 Reactor collector, trigger and plugin ITs (T26)
- **Date:** 2026-10-25
- **Decision:** `ReactorTransaction` (one per Maven session, kept in `RepositorySystemSession.getData()`, which the `-T` session clones share) holds the participant set and the entries; `checkIn` is `synchronized`, completion is "every participant has an entry", and the call that completes the set gets all entries exactly once. `ParticipantFinder` takes the projects of `session.getProjects()` whose effective `getBuildPlugins()` has an execution of this plugin with the goal `deploy` (phase not `none`); when no project binds it (command-line `mvn ...:deploy`) every project of the session participates. A project that runs the goal but is not in the set fails with an explanation instead of silently growing the set. Skipped participants check in without settings and do not take part in the comparison; any non-skipped pair is compared on every setting except `skip` and `allowReleases` (stricter than design section 2's four, because the single publication uses one participant's `keepSnapshots`, `checksumAlgorithms` and so on). The settings, release guard and `toConfig` validation run before the check-in, so an invalid participant never completes the set. The completing participant publishes with the settings of the first non-skipped entry; `SourceCommit` supplies the `source:` line (`GITHUB_SHA`, else HEAD of the enclosing repository). T26 publishes no artifacts yet (T27 adds the native deploy), so its commits hold `.nojekyll` only; the commit message already lists the participants. Plugin ITs use `maven-invoker-plugin` 3.9.1 (`src/it`, cloned to `target/it`, isolated `target/local-repo`, `addTestClassPath`): Groovy `prebuild`/`verify` scripts are one-liners into `ItSupport` (test sources), which creates the bare repo next to the cloned project, writes `.mvn/maven.config` with `-Dgit-publisher.repository=...` and generates the modules the root POM lists but the IT does not spell out. Invoker CLI options go inside `invoker.goals` (for example `deploy --fail-at-end`, `-T 4`, `-pl b`): `invoker.args` is not an invoker property and was silently ignored in the first run. The license-header check skips `src/it/**`. Groovy 4.0.27 in the invoker runs under JDK 25. Not asserted: that a participant failing after its check-in (a later plugin in the deploy phase) prevents the push; such a failure cannot be observed without a lifecycle participant (D-034).
- **Reason:** Design section 2 (Maven), D-034; T26 "Done when" scenarios.

## D-064 Native deploy into the work tree (T27)
- **Date:** 2026-10-25
- **Decision:** `ProjectArtifacts.collect` mirrors `maven-deploy-plugin`: the main artifact (not for packaging `pom`, where the "main file" is the POM itself), the project POM file as extension `pom`, and every attached artifact with its classifier and the extension of its artifact handler, which for signatures from `maven-gpg-plugin` is the type itself (`jar.asc`, `pom.asc`). It runs at check-in, so a project without a packaged file fails before the set can complete. `NativeDeploy` copies Maven's `RepositorySystemSession` (it is read-only and shared), sets `aether.checksums.algorithms` from the normalized `checksumAlgorithms` (`SHA-1,MD5`), and makes one `RepositorySystem.deploy` of every collected artifact to a `RemoteRepository` `git-publisher`, type `default`, URL `file://<repo/>`. The completing participant calls it between `checkout()` and `publish()`; with no artifacts at all (every active participant empty) the deploy is skipped. Maven's own metadata generators write `maven-metadata.xml` (including plugin prefix metadata for `maven-plugin` projects), so the same session start time and build number apply to all modules of the reactor. `DeployMojo` obtains `RepositorySystem` with Plexus `@Component`. The IT `deploy-sources` (root POM, `lib` with a `jar.asc` attached by `build-helper-maven-plugin` in place of GPG, `app` depending on `lib`, `maven-source-plugin`) verifies one commit, `MetadataAssertions` on an exact checkout, the signature and parent POM files, and a clean Maven consumer resolving both jars and both `sources` classifiers from the checkout.
- **Reason:** Design section 2 and section 4 step 2, D-034; T27 "Done when". Real `.asc` signing needs GPG, which the build machine may not have; the artifact type that reaches the deploy is the same.

## D-065 Maven credentials and the HTTPS ITs (T28)
- **Date:** 2026-10-25
- **Decision:** `CredentialsResolver` resolves in the order of design section 8: the `settings.xml` server named by `serverId` (looked up in `session.getRequest().getServers()`, the list Maven builds its authentication selector from; its password decrypted by the injected `SettingsDecrypter`, username default `x-access-token`), then `GIT_PUBLISHER_TOKEN`, then anonymous. A `serverId` that is not found, or a server without a password, logs a warning (no secret) and falls through; a password that Maven cannot decrypt fails with the server id and Maven's reason. The token only reaches `Credentials.Token`, which the core clears on `Publication.close()` (D-060). The resolution runs in the participant that completes the set, from the first active entry's `serverId`; all participants must agree on `serverId` (D-063). ITs `token-server-id` and `token-env` run Maven against `ItServer`, a real JGit `GitServlet` behind TLS (self-signed certificate for 127.0.0.1 by `keytool`) and a filter that answers 401 to anything but one token, started by the Groovy prebuild in the invoker JVM (registered in `System.getProperties()` as an `AutoCloseable`, because the hooks run in separate class loaders) and stopped by verify. Maven's JVM trusts it through `.mvn/jvm.config` (`javax.net.ssl.trustStore` = the JDK roots plus the server certificate, PKCS12). In `token-server-id` the server accepts only the token stored encrypted in `src/it/settings.xml` (master password in `src/it/settings-security.xml`, found through `-Dsettings.security`; both generated once with plexus-cipher and committed), while the invoker sets `GIT_PUBLISHER_TOKEN` to a different value for every IT, so the test also proves that the server wins over the environment; in `token-env` the server accepts the environment token. Verify scripts assert one commit, and that neither token appears in Maven's output, the work directory's Git config, the remote's config or any published file. The invoker passes its own `-s <temp settings>` that overrides `-s` from `.mvn/maven.config`, so the shared `src/it/settings.xml` is the plugin-level `settingsFile`. Unit tests (`CredentialsResolverTest`) cover order, defaults, fall-through warnings and the decryption failure; the real decryption is the IT.
- **Reason:** Design sections 2 and 8, D-035; T28 "Done when". The wrong-token fail-fast behaviour is the core's (T24) and needs no second test here.

## D-066 Maven 4 matrix and the legacy settings password (T29)
- **Date:** 2026-10-25
- **Decision:** CI job `maven-matrix` runs the full `mvn -B verify` (invoker ITs included) on JDK 17 with the Maven distributions `3.10.0` (latest 3.10.x) and `4.0.0-rc-7` (latest 4.0), downloaded from Maven Central and checked against the published SHA-512. The existing `build` job keeps `./mvnw verify` on JDK 17 and 21. `pom.xml` sets the invoker `mavenOpts` to `-Dsettings.security=${project.basedir}/src/it/settings-security.xml`, so every forked IT build can decrypt the legacy encrypted password of `src/it/settings.xml`.
- **Reason:** Maven 4.0.0-rc-7 fails the settings build with "Could not decrypt password" when the legacy master password file is not named, even in ITs that never read the server (the settings file is global to all ITs). Maven 3.10.0 ignores it until a server is used. Found by running the full build with rc-7 locally; the rerun with the fix passes. Maven 4 also warns about the legacy encryption; re-encrypting with the Maven 4 scheme would break Maven 3.10, so the legacy format stays for now.
- **Revised by:** none. The Maven 4 rc tag must be updated when Maven 4.0 GA ships (D-012).

## D-067 Property-only and README snippet ITs (T30, T31)
- **Date:** 2026-10-25
- **Decision:** (T30) `src/it/property-only` has a plugin without `<configuration>`; the IT passes branch and checksum algorithm with `-Dgit-publisher.*` on the command line, the repository comes from `.mvn/maven.config` as in every IT. `ItSupport.assertPropertyOnlyPublication` checks the branch, the single commit and the configured checksum files only. (T31) The README's code blocks are copied from files under `src/it` (the POM of `property-only`, the goal line of its `invoker.properties`, `src/it/settings.xml`, and `.github/workflows/publish.yml` of the `readme-workflow` IT, which also carries the concurrency group and the separate `publish` job). `ReadmeSnippetsTest` fails when a fenced block is not a contiguous part of an IT file (a leading `mvn ` is ignored). The build command stays inline for that reason. The workflow file is inert in the IT; only actionlint (T50) checks it as YAML.
- **Reason:** T30 and T31 "Done when". Plugin version in the example POMs is the literal `0.1.0-SNAPSHOT`, so a version bump needs the example POMs updated with the README. GitHub Pages limits checked against docs.github.com on 2026-10-25.

## D-068 A refused tree hint never fails the branch (R19)
- **Date:** 2026-10-25
- **Decision:** `GitRemote.push` keeps the atomic push of D-060 (branch with lease plus hint). When the branch status of that push is a refusal (not OK, UP_TO_DATE, the lease failure or an incomplete report), it pushes the branch alone, non-atomically, with the same lease and force. That second status decides: OK is a success (the hint stays where it was), the lease failure is a lease failure, anything else is the permanent refusal of D-060. The hint is pushed again by the next publication. No ref moves for a publication whose branch update failed, as in R18.
- **Reason:** Review 2026-10-25-0701e5d F1: an atomic push made a remote that refuses `refs/publisher/tree` refuse every publication, against D-027 and T20. Cost: one extra push, only when the first one is refused. Revises D-060 for this case; design section 4 step 5 amended.

## D-069 Repository mismatch IT and design section 2 (R22)
- **Date:** 2026-10-25
- **Decision:** The invoker IT `config-mismatch-repository` gives module `b` a POM `<repository>` that differs from the command-line one; it asserts the build fails with the settings-differ message naming exactly `repository`, nothing is checked out and the bare repo is untouched. `config-mismatch` keeps its `branch` case. Design section 2 now states the two rules that D-063 added: the comparison covers every setting except `skip` and `allowReleases`, and with no project binding the goal every project of the session participates.
- **Reason:** Review 2026-10-25-0701e5d F4: T26 names a repository mismatch and the binding design must describe the shipped behaviour (AGENTS.md precedence).


## D-070 README workflow snippet and settings wording (R21)
- **Date:** 2026-10-25
- **Decision:** T31's "Done when" is amended: README snippets are copied from files under `src/it` (checked by `ReadmeSnippetsTest`); the POM, goal line and settings snippets are exercised by ITs, while the workflow YAML is validated as a workflow by `actionlint` in T50, whose "Done when" already requires the guide snippet to be the template. The README states this under the workflow. Running the `run:` command in the IT was not chosen: it needs the GitHub Actions context (`github.repository`, `github.token`) and `./mvnw`, which the IT cannot reproduce faithfully without substituting the very parts the snippet shows. The settings snippet loses the test-only comment (now "an encrypted token; replace it with your own"); the ciphertext stays because `token-server-id` decrypts it, and the README says it is an example value. The `-Dsettings.security` sentence now says only what D-066 observed: it is needed when the master password file is not at the default location, as in this plugin's ITs.
- **Reason:** Review 2026-10-25-0701e5d F3 and F5.

## D-071 The atomic fallback follows JGit's own error (R20)
- **Date:** 2026-10-25
- **Decision:** `GitRemote.push` falls back to the branch-only, non-atomic push only when the `TransportException` message is JGit's `atomicPushNotSupported` text (`JGitText`, localized), alone or after the `<url>: ` prefix that JGit adds. A word in the URL no longer matters: any other transport error propagates and is retried under the back-off budget (design section 4 step 3.vi). `JGitText` is a JGit internal class; the alternative (reading the advertised capabilities) needs a second connection.
- **Reason:** Review 2026-10-25-0701e5d F2; D-060 left the fallback untested, `R20AtomicFallbackTest` now covers it against a real JGit HTTPS server.


## D-072 Central organization ZenWave360; T32 split (answers Q4)
- **Date:** 2026-10-25
- **Decision:** Releases go to Maven Central under the maintainer's Central account (ivangsa@gmail.com), organization ZenWave360, namespace `io.github.ivangsa` (verified). The organization's free quota is shared with the ZenWave360 namespaces. GroupId stays `io.github.ivangsa`.
  T32 is split. T32a (agent): release build and workflow, checked locally; no upload. T32b (maintainer): dispatches the first real upload, which waits in the Central portal until it is published by hand.
  Secrets: environment `maven-central-upload` holds `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `SIGN_KEY`, `SIGN_KEY_PASS` (same names as `zenwave360/release-workflows`); no repository-level copies. Signing key `17B99A4248FBC02FA5DFF04A2CD85313E133BC68`, the same key that signs the ZenWave360 releases.
  While the repository is private on GitHub Free the environment has no reviewer or branch policy. Therefore only the release workflow may reference `maven-central-upload`, it runs on `workflow_dispatch` only, and no agent dispatches it.
- **Reason:** Maintainer answer. An environment without protection rules exposes its secrets to any workflow that names it.
- **Rejected:** a separate Central organization; uploading to the portal from an agent task.

## D-073 Snapshot repository for dogfooding (answers Q2)
- **Date:** 2026-10-25
- **Decision:** `zenwave360/maven-snapshots` exists and is public. The maintainer created the fine-grained PAT (`Contents: write`, that repository only) and stores it as `GIT_PUBLISHER_TOKEN` (environment secret) in the repositories that publish. T33 stays manual and starts after T32b.
- **Reason:** Maintainer answer.

## D-074 The repository stays private until the maintainer opens it (answers Q9)
- **Date:** 2026-10-25
- **Decision:** No agent changes the repository visibility. The maintainer makes it public. Self-publication of this project's snapshots (part of T33) waits until then.
- **Reason:** Maintainer answer.

## D-075 Multi-module layout: core, Maven plugin, Gradle plugin (answers Q10, revises D-037)
- **Date:** 2026-10-25
- **Decision:** The repository becomes a Maven reactor with modules `core/` (jar `git-maven-publisher-core`) and `maven-plugin/` (`git-maven-publisher-maven-plugin`, depends on core), plus an unpublished `test-fixtures/` module for the harness. The parent POM `git-maven-publisher-parent` is published too, because Central requires the parent of a published POM.
  `gradle-plugin/` is a separate Gradle build (Plugin Portal, plugin marker, TestKit) that depends on `git-maven-publisher-core`; CI runs `./mvnw install` before `./gradlew -p gradle-plugin build`. The split happens now (T58), so the first release (T32) already has the final artifact layout.
- **Reason:** Maintainer choice. A published core jar is how tools with both a Maven and a Gradle plugin are usually built (for example `jib-core`, `spotless-lib`). Compiling `../src/main/java` from Gradle (Q10 option b) works, but it duplicates the core's dependencies in two builds and hides the coupling.
- **Rejected:** keeping the single artifact (D-037) with source sharing; building the Gradle plugin with Maven.
- **Traceability:** T58 performs the split; T32a releases the three Maven artifacts; T34 consumes the core jar.

## D-076 Reactor details of the split (T58)
- **Date:** 2026-10-25
- **Decision:**
  - Root `pom.xml` is the parent `git-maven-publisher-parent` (packaging `pom`) with modules `test-fixtures`, `core`, `maven-plugin`. It owns versions, POM metadata, surefire/failsafe configuration and the license check. Each module declares its own production and harness dependencies.
  - `test-fixtures` does not depend on core: none of its code imports a core class (the only mentions are in comments). Core and Maven tests use it at test scope, so the reactor has no cycle.
  - `test-fixtures` sets `maven.deploy.skip=true`. Golden files are classpath resources in `test-fixtures/src/main/resources/golden`. `MetadataModelTest` (core) reads them through `../test-fixtures/src/main/resources/golden` in the source tree, with no copy.
  - The reactor runs from the root, so `maven-invoker-plugin` `projectsDirectory` and `settingsFile` use `${project.basedir}`. `ReadmeSnippetsTest` reads `../README.md`.
  - The license header template moves from `src/license/header.txt` to `license-header.txt` at the root, so that no `src/` remains at the root. `license/` was rejected because `LICENSE` already exists and the file system is case-insensitive on Windows.
  - Moves use `git mv`, so file history follows the classes. Review tests moved unchanged with their classes (`core/src/test/.../review`, `maven-plugin/...`, `test-fixtures/src/test/.../fixtures/review`).
  - Evidence (`./mvnw clean verify`, before and after): surefire 215 tests, 0 failures, 0 skipped before; after, 62 (test-fixtures) + 107 (core) + 46 (maven-plugin) = 215. Failsafe 4 before and after (`GitPublisherDifferentialIT`). Invoker ITs 13 passed before and after.
- **Reason:** The split must keep the same test set and build from a clean checkout. Shared harness and golden files stay in one place, and nothing is copied or duplicated.
- **Rejected:** `test-fixtures` depending on core (cycle); copying golden files into each module or into a temp directory per test; keeping source in the root `src/`.
- **Revises:** D-037 layout only (see D-037 "Superseded by D-075").

## D-077 Release pipeline, local part (T32a, under D-072)
- **Date:** 2026-10-25
- **Decision:**
  - The release workflow is the repository's own `.github/workflows/release.yml`: `workflow_dispatch` only, `version` input (`X.Y.Z` or `X.Y.Z-rc.N`), environment `maven-central-upload`, secrets `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `SIGN_KEY`, `SIGN_KEY_PASS`, passed by name. It is the only file that names the environment.
  - Steps: `./mvnw verify` at the current version (CI's build, invoker ITs included); `versions:set` to the release version (not committed, not tagged); `./mvnw -Prelease deploy -DskipTests -Dinvoker.skip=true`. Tests run once, at the snapshot version, because the invoker ITs pin the plugin version (`0.1.0-SNAPSHOT`), so they cannot run at a release version. Only the version string differs between the two builds.
  - Profile `release` (root POM): sources, javadoc, `maven-gpg-plugin` (`signer` `bc`), `central-publishing-maven-plugin` 0.12.0 with `autoPublish` false. It is not part of the normal build.
  - Signing is in-process: the armored key comes from env `MAVEN_GPG_KEY` (the `SIGN_KEY` secret), the passphrase from `MAVEN_GPG_PASSPHRASE`. No `gpg` executable, no keyring import on the runner.
  - Plugin parameters: `skipUpload` (`-Dcentral.skipUpload=true`) writes the bundle without uploading. `skipPublishing` leaves a module's artifacts out of the bundle altogether; `test-fixtures` sets `central.skipPublishing=true` and is absent from every bundle. The task text says "skipPublishing" for the local build: `skipPublishing` alone would give an empty bundle, so the local build uses `skipUpload`.
  - Each published POM (core, maven-plugin) declares name, description, url, licenses, developers and scm itself. The uploaded file is the raw POM, so inherited fields from the parent would not be in it.
  - Check: `scripts/verify-central-bundle.py <bundle.zip> <version> <fingerprint> <gnupg-homedir>` (run with `-I`). It checks the exact file set per artifact, `.asc` against the given key, `.md5`/`.sha1` (and `.sha256`/`.sha512` when present), POM fields, jar contents, and that no `test-fixtures` entry exists.
  - Evidence (this batch): local release build on a scratch copy at version `0.1.0` with a throwaway RSA-2048 key (generated in a short GnuPG home outside the repository, removed afterwards): `verify` passed (surefire 215, failsafe 4, invoker 13), deploy with `skipUpload` produced `central-bundle.zip` with 9 files (parent pom; core jar, sources, javadoc, pom; maven-plugin jar, sources, javadoc, pom), and the checker printed PASS. A bundle checked with another key failed.
  - The workflow does not commit, tag, create a GitHub release or sync a develop branch. Those steps are not in T32a; T32b decides them (see docs/questions.md).
- **Reason:** Maintainer's D-072 limits: the release workflow is the only uploader, no agent dispatches it, and no GPG key or secret is read by an agent. The steps above are the smallest flow that yields the bundle checked here.
- **Rejected:**
  - Calling `zenwave360/release-workflows/.github/workflows/release-maven.yml`. Its inputs fit, but its job graph needs a `develop` branch (sync-develop), a `badges` branch and JaCoCo CSVs (publish-coverage-badges, which runs after the upload), and it expects the key already imported, which it does not do. This repository has none of the first two, so the reusable would fail after the upload.
  - `maven-gpg-plugin` with the default `gpg` signer on Windows: the `gpg-agent` it launches could not start with a Windows home directory (checked in this batch).
  - `crazy-max/ghaction-import-gpg` (used by the maintainer's playground workflow): needs a `gpg` binary and a keyring, which `signer` `bc` avoids.
  - Version properties in the invoker IT POMs: the README snippet test (`ReadmeSnippetsTest`) requires README snippets to be copied from the ITs, so the README would change too.
- **Note (T32a, same batch):** the full build failed once on `PublisherParametersTest.theDefaultCommitAuthorIsTheDocumentedOne`, which compared two `PersonIdent`s created a second apart (`PersonIdent.equals` includes the instant). The assertion now compares name and email, the documented identity of the default author. Production code is unchanged.

## D-078 Release flow follows `zenwave360/release-workflows` (answers Q11, revises D-077)
- **Date:** 2026-10-25
- **Decision:** Releases use the maintainer's standard pipeline: a thin caller `.github/workflows/release-from-notes.yml` calls `zenwave360/release-workflows/.github/workflows/release-maven.yml`, pinned to a full commit SHA, with named secrets (never `secrets: inherit`).
  The pipeline runs validate, `release:prepare` (PR to `main`, tag `v<version>`), deploy to Central (`autoPublish` false), GitHub release, and sync of `develop`. It replaces the interim `release.yml` from T32a, so one workflow still names `maven-central-upload`.
  The repository adapts to the pipeline, not the other way round:
  - `maven-release-plugin` with `pushChanges=false`, `tagNameFormat=v@{project.version}`, `autoVersionSubmodules=true`;
  - signing with `sign-maven-plugin` reading `SIGN_KEY`/`SIGN_KEY_PASS`, as in the ZenWave360 POMs (the bc signer of `maven-gpg-plugin` reads other variable names);
  - JaCoCo `jacoco.csv` per published module;
  - `release-notes/release-notes.v<version>.md`;
  - an orphan `badges` branch and a `develop` branch;
  - a `publish-maven-snapshots.yml` caller for the post-release dispatch (verify only until self-publication, D-074).
  Branch model: once `main` requires PRs (T59b), agents commit and push to `develop`; `main` changes only through release PRs. AGENTS.md changes in T59b, not before.
- **Reason:** Maintainer request: one release flow for all their libraries. The T32a note that the reusable workflow "does not import the signing key" holds only for `maven-gpg-plugin`; `sign-maven-plugin` reads the variables the pipeline sets.
- **Constraint:** while the repository is private on GitHub Free, the environment reviewer, deployment branch policy and the `main` ruleset are unavailable. The pipeline runs, but its security model is complete only after the repository is public (D-074).
- **Traceability:** T59 (agent) adapts the build and workflows; T59b (maintainer) sets up branches, ruleset and environment rules; T32b then dispatches the first release through this flow.

## D-080 Retention recognizes builds by the complete filename prefix (R23)
- **Date:** 2026-10-25
- **Decision:** `Retention` matches `^<artifactId>-<baseVersion>-<timestamp>-<buildNumber>(?=[-.])` with the artifactId taken from the directory above the `-SNAPSHOT` directory. It no longer searches for the timestamp anywhere in the name.
- **Reason:** Review be6b417 F2: a legal artifactId that contains a `<version>-<timestamp>-<n>` fragment hid the real builds, and an unrelated file such as `notes-1.0-20200101.010101-0.txt` was deleted. The review tests `R23RetentionFilenameBoundaryTest` stay unchanged.

## D-081 Metadata reader rejects duplicated known fields (R24)
- **Date:** 2026-10-25
- **Decision:** `MetadataModel.read` throws `IOException` when a known single-valued element occurs twice in its parent: `groupId`, `artifactId`, `version`, `versioning`, `plugins` in `metadata`; `latest`, `release`, `snapshot`, `versions`, `lastUpdated`, `snapshotVersions` in `versioning`; `timestamp`, `buildNumber`, `localCopy` in `snapshot`; `classifier`, `extension`, `value`, `updated` in `snapshotVersion`; `name`, `prefix`, `artifactId` in `plugin`. `IntegrityCheck` reports it as "does not parse". Unknown elements are still kept and may repeat.
- **Reason:** Review be6b417 F3: Maven's reader rejects a repeated known tag, while the StAX model silently used the first. No Maven runtime dependency is added to core (D-005).

## D-082 Each bound execution is a participant (R25)
- **Date:** 2026-10-25
- **Decision:** `ParticipantFinder` counts executions, not only projects. A project that binds `deploy` once keeps the identity `g:a:v`; with several binding executions each one is `g:a:v@executionId` (`DeployMojo` gets its execution id from `${mojoExecution}`). `Entry` carries the execution, `ReactorPublisher` de-duplicates coordinates and artifacts so repeated executions of one project deploy it once. Settings of executions of one project are compared like those of different projects.
- **Rejected:** failing the build for repeated bindings: `R25RepeatedExecutionReview` expects the failing second execution's own error (`unknown checksum algorithm 'INVALID'`) with nothing pushed.
- **Reason:** Review be6b417 F1: the first execution completed the project-level set and pushed before the second execution failed (D-034).

## D-079 Release build wiring for the release-maven.yml reusable (T59, under D-078)
- **Date:** 2026-10-25
- **Decision:**
  - The release profile `release` activates on the environment variable `SIGN_KEY` (`env.SIGN_KEY` activation), not on `-Prelease`. The reusable runs `mvn deploy` without profile flags, so the profile must follow the secret. Local builds without `SIGN_KEY` (CI, `./mvnw verify`) never activate it. `-Prelease` still works.
  - Signing uses `sign-maven-plugin` 1.0.0 (`sign` goal, env `SIGN_KEY` and `SIGN_KEY_PASS`), as in the ZenWave360 POMs. It replaces `maven-gpg-plugin` with the `bc` signer of D-077, so `MAVEN_GPG_KEY` and `MAVEN_GPG_PASSPHRASE` are no longer read. The plugin skips itself when no key is present.
  - The release profile sets `invoker.skip=true`. The plugin ITs pin `0.1.0-SNAPSHOT` (D-077), so the reusable's `mvn deploy` at the release commit cannot run them. Release versions get unit and failsafe tests only; the ITs ran in CI at the snapshot version.
  - `maven-release-plugin` 3.3.1 in the root build: `pushChanges=false`, `tagNameFormat=v@{project.version}`, `autoVersionSubmodules=true` (D-078), and `preparationGoals` `clean verify -Dinvoker.skip=true`, for the same reason.
  - JaCoCo 0.8.15 in `core` and `maven-plugin`: `prepare-agent` and `report` (bound to `verify`) write `target/site/jacoco/jacoco.csv`. Failsafe coverage is not included.
  - `release-from-notes.yml` calls `release-maven.yml` pinned to `3c40e2706b34b436b2c6e6dbaa431fed329c5f0a`, passes `central-environment: maven-central-upload` explicitly, passes the four secrets by name, and grants `contents: write`, `pull-requests: write` and `actions: write` at job level. A called job cannot get more than the caller grants. The README example grants `contents: read` only, which would stop `prepare-release` and `sync-develop`.
  - `publish-maven-snapshots.yml` calls `publish-snapshots-maven.yml` with `publish-snapshots: false` (verify only, D-074) and the jacoco paths of `core` and `maven-plugin`.
  - `release-notes/release-notes.v0.1.0.md` is the notes file that `validate-release.yml` checks.
  - `.github/workflows/release.yml` (T32a interim) is deleted. `release-from-notes.yml` is the only workflow that names `maven-central-upload`.
- **Pin discrepancy:** the task named `git -C ../release-workflows log -1 --format=%H` as main's SHA. That local checkout is on `develop` (`c3d25f9`). The SHA of origin `main` from `gh api repos/zenwave360/release-workflows/commits/main` is `3c40e2706b34b436b2c6e6dbaa431fed329c5f0a`, and the four files read (`release-maven.yml`, `publish-snapshots-maven.yml`, `validate-release.yml`, `README.md`) are byte-identical at that SHA and on `develop`. The pin is the origin `main` SHA.
- **Reason:** The reusable workflow is the maintainer's standard pipeline (D-078). Its `mvn deploy` has no profile flags, and its jobs need write grants from the caller.
- **Not done here (later tasks):** `enforce-snapshot-version` enforcer execution (the snapshot reusable invokes it only when `publish-snapshots: true`, so it belongs to T33); the `badges` branch, `develop` branch and environment rules (T59b). Until T59b the `develop` push trigger and `badges` job have no branch to act on.
- **Evidence (BP4):** `./mvnw -B -ntp verify` BUILD SUCCESS (surefire 62 + 107 + 46, failsafe 4, invoker 13 passed, none failed or skipped; `core/target/site/jacoco/jacoco.csv` and `maven-plugin/target/site/jacoco/jacoco.csv` written). `actionlint` 1.7.12 clean on `.github/workflows/*.yml`. `./mvnw -B release:prepare -DdryRun=true -DreleaseVersion=0.1.0` BUILD SUCCESS in a scratch clone (the preparation build ran `clean verify -Dinvoker.skip=true`, the invoker ITs were skipped). Bundle check: a copy at 0.1.0 with a throwaway RSA-2048 key in a short GnuPG home outside the repository, `SIGN_KEY`/`SIGN_KEY_PASS` in env, `./mvnw -B deploy -Dcentral.skipUpload=true` with no profile flags: BUILD SUCCESS, the release profile activated, invoker ITs skipped, and `scripts/verify-central-bundle.py` printed PASS for 9 artifacts (no `test-fixtures` entry). The throwaway home and key were removed. Later, HEAD moved to `879f260` (review commit with R23, R24, R25 review tests of BR8). The full build of the T59 tree then fails in `core`: `R23RetentionFilenameBoundaryTest` (2 tests) and `R24IntegrityOracleParsingTest` (1 test). A clean clone of `879f260` without the BP4 changes fails the same two tests, so the failure is not caused by T59. T59 is not ticked until the first green full build (done rule, AGENTS.md).

## D-083 Self-publication to `zenwave360/maven-snapshots`; Maven and Gradle in one release (revises D-073, D-074, D-078)
- **Date:** 2026-10-25
- **Decision:** The repository is public (maintainer's go, 2026-10-25). Environment `maven-central-upload` now has the maintainer as required reviewer and deploys only from `main`.
  - **Reuse:** `zenwave360/release-workflows` is public, and its `environment:` and `secrets:` resolve in the calling repository. So this repository, outside the ZenWave360 organization, needs no organization trust: it calls the workflows pinned by SHA and keeps its own secrets in its own environments.
  - **Release, Maven and Gradle:** `release-from-notes.yml` keeps `release-maven.yml`, which handles the version input, `release:prepare`, tag, Central, GitHub release and `develop` sync. A caller job that `needs` it checks out `v<version>`, installs core and publishes the Gradle plugin to the Plugin Portal. The Gradle build reads its version from the root `pom.xml`, so `release:prepare` bumps both builds. `release-gradle.yml` is not used: it expects a Gradle build that owns the version and publishes to Central.
  - **Self-publication (dogfooding):** on every push to `develop`, the plugin publishes this project's snapshots with itself. Steps: `./mvnw install`, then the installed plugin's `deploy` goal by full coordinates. Target: `https://github.com/zenwave360/maven-snapshots.git`, branch `gh-pages`, never `main` (that repository's `main` holds older content, and a publication replaces the branch's history). Token: `GIT_PUBLISHER_TOKEN` in environment `git-publisher-snapshots` (deploys only from `develop`). Concurrency group `maven-snapshots-io.github.ivangsa`, shared by every workflow that publishes `io.github.ivangsa` coordinates. A clean consumer job then resolves the published plugin and core from `https://raw.githubusercontent.com/zenwave360/maven-snapshots/gh-pages/`.
  - **Branch model:** `develop` (created) receives agent and day-to-day commits; `main` changes through release PRs once its ruleset is on. The `badges` branch is created for coverage badges.
- **Reason:** Maintainer answers: self-publish into the ZenWave360 snapshot repository; reuse the shared release workflows, and keep their concept (version and next version in, everything else done by the workflow) for the Gradle half.
- **Traceability:** T60 self-publication; T61 branch-model switch; T45 publishes the Gradle plugin from the release caller job; T33 now covers ZenWave360 projects and this project in `zenwave360/maven-snapshots`.
