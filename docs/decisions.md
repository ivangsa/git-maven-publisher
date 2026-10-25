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

## D-084 Self-publication job shape: install, then the installed plugin with package (T60, under D-083)
- **Date:** 2026-10-25
- **Decision:** `.github/workflows/publish-maven-snapshots.yml` has three jobs. `verify` is the reusable verify call (job renamed from `publish-snapshot`, inputs unchanged). `publish` needs `verify`, runs only on `refs/heads/develop`, names environment `git-publisher-snapshots`, uses concurrency group `maven-snapshots-io.github.ivangsa` (`cancel-in-progress: false`) and checks out with `persist-credentials: false`. `consumer` needs `publish`, runs in a fresh job and resolves both artifacts from `https://raw.githubusercontent.com/zenwave360/maven-snapshots/gh-pages/`.
  - `publish` runs, in order: (1) `./mvnw -B -ntp install -DskipTests -Dinvoker.skip=true` without secrets, so the plugin and core are in the local repository; (2) `./mvnw -B -ntp -pl '!test-fixtures' package -DskipTests -Dinvoker.skip=true io.github.ivangsa:git-maven-publisher-maven-plugin:<version>:deploy -Dgit-publisher.repository=https://github.com/zenwave360/maven-snapshots.git -Dgit-publisher.branch=gh-pages`. `GIT_PUBLISHER_TOKEN` is set only in step (2).
  - Refinement of D-083's "`./mvnw install`": step (2) uses `package`, not `install`. Maven gives a goal run alone no packaged artifact (`ProjectArtifacts` fails on a null main file), so the packaging phase must run in the same invocation. `install` there failed on Windows: core's installed jar was still open by the plugin realm that loads it, and the rename failed. `package` writes nothing to the local repository.
  - `test-fixtures` is excluded because it is never deployed. The parent POM is published because consumers resolve it. A goal started from the command line makes every session project a participant (D-063), so the publication is one commit with the parent, core and plugin.
  - The version is read with `help:evaluate` and must match `X.Y.Z-SNAPSHOT`; anything else fails before publication.
  - `consumer` runs `maven-dependency-plugin` 3.8.1 `get` twice (plugin, then core) with `-Dmaven.repo.local` under `RUNNER_TEMP`, Central and the snapshots branch as repositories, and fails unless a jar is present for each. Raw CDN caching could delay a fresh snapshot; a failure there is reported, not retried.
  - The reusable verify job still uses its default `snapshot-environment` (`maven-central-snapshots`) because `publish-snapshots` is false. It does not deploy. Only the `publish` job names `git-publisher-snapshots`.
- **Reason:** T60 "Done when": the publish and consumer steps are the plugin's own deploy goal by coordinates, the token reaches Maven only through the environment, and no step writes it to disk or logs.
- **Evidence (BP5):** `actionlint` 1.7.12 clean on `.github/workflows/*.yml`. A local dry run of the publish steps against a bare `file://` repository (scratch directory, not zenwave360/maven-snapshots) published parent, core and plugin in one commit. The consumer command, run against a clone of that `gh-pages` branch with an empty local repository, resolved both artifacts. `./mvnw -B -ntp verify` BUILD SUCCESS: surefire 62, 110, 4, 48; invoker 15 passed, 0 failed, 0 skipped. Not verified locally: raw.githubusercontent serving and the GitHub-hosted run, which is the first run on `develop`.
- **Not done here:** the README self-publication snippet still uses concurrency group `maven-snapshots-${{ github.repository }}` and the `GITHUB_TOKEN` scenario; D-083 names group `maven-snapshots-io.github.ivangsa` for every publisher of these coordinates. Documentation follow-up, not part of T60.

## D-085 Gradle build skeleton: root wrapper, version from the root pom, build.gradle in Groovy (T34)
- **Date:** 2026-10-25
- **Decision:**
  - The Gradle wrapper (Gradle 9.5.0) lives at the repository root (`gradlew`, `gradle/wrapper/`), so that the documented command `./gradlew -p gradle-plugin build` works as written. `gradle-plugin/` is still its own Gradle build (own `settings.gradle`, D-002); the root has no Gradle settings and no Gradle project.
  - `gradle-plugin/build.gradle` (Groovy DSL) reads `<version>` of the root `pom.xml` through `providers.fileContents` (a change invalidates the configuration cache) and uses it for `version` and for the core dependency (D-083). `versionSyncCheck` (part of `check`) fails when `gradle.properties` declares a version or the Gradle version differs from the pom.
  - Dependencies: `git-maven-publisher-core` and, for TestKit tests, `git-maven-publisher-test-fixtures` (`BareRepo`, `MetadataAssertions`; the oracles of design section 9), both from `mavenLocal()` restricted to group `io.github.ivangsa`, so `./mvnw install -DskipTests` must run first. The plugin itself compiles with `--release 17`.
  - CI: job `gradle-plugin` in `ci.yml` (JDK 17 and 21) runs `./mvnw install -DskipTests` then `./gradlew -p gradle-plugin build`.
- **Reason:** the task's "Done when" names the root-relative command; Groovy DSL parses the pom without extra dependencies.
- **Rejected:** wrapper inside `gradle-plugin/` (the documented command would not exist); Kotlin DSL (needs an XML parser dependency in the build script).

## D-086 Gradle lifecycle mechanism: guarded finalizer plus per-task success records (T36, under D-033)
- **Date:** 2026-10-25
- **Decision:** The mechanism D-033 left open is a guarded finalizer, not the Flow API.
  - Every `publish*PublicationToGitPublisherRepository` task (matched by task name, because the repository is assigned after the task is created) `dependsOn` its project's `gitPublisherCheckout`, `usesService` the shared service, is `finalizedBy` `gitPublisherPush`, and ends with a `doLast` action that records its task path in `GitPublisherService`. A task that failed never reaches its last action, so it is never recorded. (Where that action is added: revised by D-097.)
  - `gitPublisherPush` `mustRunAfter` every candidate participant of the build (all projects); `mustRunAfter` ignores tasks not in the graph. It is finalizer only to enter the graph with a participant. Its action calls `GitPublisherService.publishIfComplete(participants, coordinates)`, which pushes only if every participant path in the push task's `participants` input was recorded, once per build. Otherwise it logs the incomplete task paths and pushes nothing. The finalizer therefore is not "unconditional": the guard decides, and the push is never executed from `close()`.
  - `participants` and `coordinates` (task inputs of the push task) are computed in `taskGraph.whenReady` from the selected `PublishToMavenRepository` tasks (root aggregate, one subproject, or one publication) and stored with the task, so the configuration cache does not need the graph again.
  - `gitPublisherCheckout` calls `GitPublisherService.checkout()`, idempotent: whichever project's checkout runs first does the work; every participant depends on a checkout, so the checkout precedes any write. After the push attempt `release()` closes the publication whatever the outcome (cleanup). `close()` only releases.
  - One service per build, `maxParallelUsages = 1`, configured from the first project that applies the plugin. Projects applying it with different settings fail the build at the end of configuration (`taskGraph.whenReady`). A participant whose version is not a snapshot fails the build there too unless `allowReleases` is set (same rule as the Maven plugin).
- **Reason:** Flow API `FlowAction` runs after the build and cannot add a task to the graph, so its failure and logging are worse; a task keeps the push visible in the task output and in `--dry-run`. `OperationCompletionListener` events are delivered asynchronously and may arrive after the finalizer started, so the outcome is recorded by the participant itself.
- **Known limit (revised by D-098: the build now fails):** a participant that Gradle skips (for example `onlyIf` false) is never recorded and blocks the push; the log names it. The Maven plugin treats skipped participants as non-participating; the Gradle equivalent is to exclude the task from the graph (`-x`), which removes it from `participants`.
- **Open for T38:** the token reaches the build service as a parameter, so a configuration cache entry holds it. Gradle encrypts cache entries, but the credentials task decides whether to resolve the token at execution time instead. Resolved by D-087.
- **Rejected:** unconditional finalizer; Flow API `FlowAction` with `BuildWorkResult`; `OperationCompletionListener`; publishing from `BuildService.close()`.

## D-087 Gradle credentials: the token is resolved at execution time, never stored in the configuration cache (T38)
- **Date:** 2026-10-25
- **Decision:**
  - `gitMavenPublisher.token` has no convention. `GitPublisherService` resolves the token when it builds the core `PublishConfig` (inside `checkout`), in this order: the explicit `token` parameter, environment variable `GIT_PUBLISHER_TOKEN`, Gradle property `gitPublisherToken`. The last two are read through the `ProviderFactory` injected into the service, so no provider or value for them exists at configuration time and nothing about them is serialized (design section 2 order is unchanged, the read moved from configuration time to execution time).
  - An explicit `token = "..."` in a build script is configuration and is stored with it. That is a known, documented limit (the secret is already in the user's build file); the supported ways to keep the secret out of the cache are the environment variable and the Gradle property. A build that sets `token = providers.environmentVariable(...)` is stored too (Gradle records the value source and its result).
  - The wrong or missing token fails in `gitPublisherCheckout` (core classifies HTTP 401/403 as an authentication failure, no retry), before any publication task starts; the message never contains the token.
- **Test changed:** T35's `ExtensionAndRepositoryTest.tokenDefaultsToTheEnvironmentVariableThenTheGradleProperty` asserted the removed convention (`extension.token` equal to the environment variable or property). It is now `tokenIsNotResolvedAtConfigurationTime` (the extension value stays unset); the sources and their order are asserted by `GradleCredentialsTest`, against a real server. Not a review test.
- **Reason:** carry-over from B15: with the token as a service parameter fed by `providers.environmentVariable`, the configuration cache entry held a credential. Resolving at execution time also means a changed token never invalidates the cache.
- **Evidence:** `GradleCredentialsTest` (TestKit, real JGit HTTPS server that accepts exactly one token, trust store passed to the daemon): each source succeeds, order extension > environment > property, wrong/missing token fails in checkout with nothing pushed, and the configuration cache entry is scanned: for an environment, `gradle.properties` and command line (`-P`) token the entry holds no token, while a marker (the repository URL) and the explicit-token positive control are found, so the scan can see inside the entry. Entries are encrypted by default, so the test turns that off with the internal option `-Dorg.gradle.internal.configuration-cache.encryption=false` (Gradle 9.5.0); Gradle stores a string without its last character's high bit, so the scan searches for the text minus its last character.
- **Rejected:** keeping the token as a `Property` parameter and relying on cache encryption (the key lives in the user's Gradle home, the entry can be copied between machines by CI cache actions); a `tokenEnvironmentVariable` property to name a different variable (not asked for).

## D-088 KMP sample: Kotlin 2.2.21, the host's native target, `.module` files name `-SNAPSHOT` files (T39, under D-075)
- **Date:** 2026-10-25
- **Decision:**
  - The KMP sample of the Gradle tests (`KmpSample`) is a real `org.jetbrains.kotlin.multiplatform` 2.2.21 library `com.acme:kmplib:1.0-SNAPSHOT` with `jvm()`, `js { nodejs() }` and one native target, written by the test and built by TestKit. The plugin's classpath is injected, the Kotlin plugin comes from the Gradle Plugin Portal.
  - The native target is the one the host can compile (`mingwX64` on Windows, `linuxX64` on Linux, `macosArm64`/`macosX64` on macOS): Kotlin publishes no klib for a target it cannot build on the host, so a fixed target would drop out of `publish` on some machines. The expected artifactIds are `kmplib`, `kmplib-jvm`, `kmplib-js` and `kmplib-<target in lower case>`. The first run on a machine downloads Kotlin/Native for that version (`~/.konan`), which takes minutes; the TestKit directory under `build/` keeps the Gradle caches for later runs.
  - Observed with Gradle 9.5.0 and KGP 2.2.21: all publications of one `publish` run share one timestamp and build number. The `.module` files do not name timestamped files: `url` is `kmplib-jvm-1.0-SNAPSHOT.jar` and an `available-at` is `../../kmplib-js/1.0-SNAPSHOT/kmplib-js-1.0-SNAPSHOT.module`; the Gradle consumer maps `-SNAPSHOT` in a file name to the timestamp of the module it read. `docs/context/gradle-plugin.md` says "`.module` files reference timestamped names"; that is imprecise (the consequence stays: publish in place, all targets in one transaction, same timestamp for every module). `KmpPublishTest` asserts the mapped names exist and that every referenced module was published; T40 asserts the real consumer resolves them.
- **Reason:** T39 "Done when": every expected artifactId has `.module`, POM and Maven metadata and `MetadataAssertions` passes.

## D-089 Clean Gradle KMP consumer: empty Gradle user home, `file://` checkout, daemon exits after one second (T40)
- **Date:** 2026-10-25
- **Decision:** `GradleConsumer` (test source of `gradle-plugin`; the Gradle counterpart of `MavenConsumer`, which lives in the Maven-built `test-fixtures` that has no Gradle dependency) is a new KMP project (Kotlin 2.2.21, `jvm`, `js`, the host's native target, D-088) with the checkout of the pushed branch as its only repository for `com.acme:kmplib:1.0-SNAPSHOT` (Maven Central only for the Kotlin standard library). It runs through TestKit with its own, initially empty, Gradle user home, and prints the artifacts of `jvmCompileClasspath`, `jsCompileClasspath` and `<native>CompileKlibraries`.
  - Assertions: each target resolves exactly its own module (`kmplib-jvm` to a `.jar`, `kmplib-js` and `kmplib-<native>` to `.klib`) and the unique snapshot that was published (`1.0-SNAPSHOT:<timestamp>-<build>`); no target gets another target's module. The file name in Gradle's cache is `...-1.0-SNAPSHOT.jar`, the timestamp is in the component id.
  - Self-test of the harness: with the JS module removed from the checkout the consumer fails and names `kmplib-js`.
  - TestKit offers no way to stop its daemon and rejects `--no-daemon`, so the consumer's `gradle.properties` sets `org.gradle.daemon.idletimeout=1000` and the test deletes the Gradle user home after the daemon exited (best effort, one minute, the directory is temporary). The consumer does not get the plugin under test on its classpath.
- **Reason:** T40 "Done when" (design section 9: clean Gradle KMP project resolving from a checkout of the pushed branch, not a fake).

## D-090 Gradle differential IT: the reference is Gradle's own publish in the same build; the lease failure is forced by a pause task (T41)
- **Date:** 2026-10-25
- **Decision:** `KmpDifferentialTest` publishes the KMP sample once. The sample's build also declares a plain `maven { name = 'reference' }` file repository, so one `publish` run writes the same publications (same jars, POMs, `.module` files) with Gradle's own `maven-publish` into a clean directory (expected) and through the plugin into the bare repository (actual). `TreeDiff` (with `TreeNormalizer`, which renumbers timestamps and build numbers per snapshot directory and ignores metadata timestamps) must report no difference.
  - The lease failure is real: the sample build gets a task `concurrentPublisher` (`dependsOn gitPublisherCheckout`, `gitPublisherPush` `mustRunAfter` it, `publish` `dependsOn` it) that writes a ready marker and waits for a go marker. The test, on the other thread of the same JVM, then deploys other coordinates (`org.other:thing:2.0-SNAPSHOT`) with the real Maven Resolver and commits them on the branch tip (`BareRepo.commitFiles`), then releases the build. The build output must contain the core's "moved; replayed" line; the other coordinates must be present; they are then removed from the checkout before the comparison.
  - Same bytes on both sides, because the two repositories are written by one build; the Gradle-versus-Gradle comparison therefore tests the transport (checkout, overlay, replay, metadata merge, commit), not the Kotlin build. Harness self-test: the comparison fails when one `.module` is deleted from the Git side.
  - The pause hook is `KmpSample.pauseBetweenCheckoutAndPush`, reused by T42.
- **Result:** no difference on the first run on Windows (Gradle 9.5.0, KGP 2.2.21); no plugin change was needed.
- **Context fix:** `docs/context/gradle-plugin.md` said `.module` files reference timestamped names; corrected to D-088 (they name `-SNAPSHOT` files, the consumer maps them to the module's timestamp).
- **Rejected:** two separate builds for expected and actual (jar bytes and timestamps would differ for reasons unrelated to the transport); a fake concurrent publisher (design section 9).

## D-091 Maven and Gradle in one repository: real Maven through Maven Verifier, deterministic overlap (T42)
- **Date:** 2026-10-25
- **Decision:** `MixedRepositoryTest` pauses the Gradle KMP publication between checkout and push (D-090 hook), runs a plain Java project through the real Maven (Maven Verifier, `deploy` bound to the installed `git-maven-publisher-maven-plugin` of the same version, `-Dgit-publisher.repository`/`.branch`) to completion, then releases Gradle, which hits the lease failure and replays. Then a `MavenConsumer` resolves `org.mvn:mavenlib:1.0-SNAPSHOT` and a `GradleConsumer` resolves the KMP library for all three targets, both from a `file://` checkout of the branch; `MetadataAssertions` validates the tree.
  - The overlap is deterministic (one defined interleaving), not a free race, like `InterleavedPublishScenario`.
  - `gradle-plugin/build.gradle`: system property `gitMavenPublisher.version` (from the root pom) tells the test which installed Maven plugin to run; `maven-settings-builder` (test scope in test-fixtures, so not transitive) is added as a test dependency for Maven Verifier. Maven Verifier finds Maven through `maven.home` or `mvn` on the PATH; the test needs `mvn` on the PATH (CI runners have it) and `./mvnw install` first.
- **Reason:** T42 "Done when": a Maven project and a Gradle KMP project with distinct coordinates publish concurrently into one bare repository and both consumers resolve.

## D-092 Validation cadence: focused tests per task, one full build and one push per batch, CI as the orchestrator's check
- **Date:** 2026-10-25
- **Decision:** requirements, tasks and commits stay as they are (one commit per task, tick in the same commit). Validation frequency changes:
  - During a task the agent runs the focused tests of the module it changes. At the end of the batch it runs the full build once
    (`./mvnw -B verify`, `./mvnw -B install`, `./gradlew -p gradle-plugin build`) and pushes once. An earlier full build is needed only when a task
    changes shared build infrastructure that later tasks depend on. Nothing is pushed before the full build passes; a red final build is fixed before the push.
  - The orchestrator no longer reruns the full build locally after each batch. Its check is CI green in every job on the batch's last pushed commit
    (CI is not controlled by the agent and runs the full build on JDK 17/21, Maven 3.10/4.0-rc and the Gradle build). It builds locally only if CI fails,
    the report says PARTIAL or BLOCKED, or the batch changed something CI does not run.
  - Done rule unchanged in substance: no failing and no skipped tests, every review test included, now evaluated per batch on its final state.
  - Unchanged: test oracles (design section 9), adversarial reviews at the marked batches, R tasks before the next T task.
- **Reason:** the maintainer approved it after an assessment of Phases 1-2. A full Maven build takes 10-12 minutes; per-task full builds, per-task pushes
  (each starts CI and the snapshot self-publication) and an identical orchestrator rerun cost most of each batch's time without adding evidence.
  The Phase 1 defects that escaped green builds were found by adversarial review, not by reruns.
- **Trade-off:** a broken intermediate commit can exist locally inside a batch and failure localisation is coarser; focused tests per task and the
  single push keep `develop` green. Batch grouping stays by shared subsystem and validation needs, not by a fixed task count.


## D-093 Gradle version matrix: whole build per leg, not committed; T43 needs-implementer (T43)
- **Date:** 2026-10-25
- **Decision:** The CI leg for Gradle version `V` runs `./gradlew wrapper --gradle-version V` on the runner (not committed) and then `./gradlew -p gradle-plugin build`, so compile, TestKit and the KMP sample run under `V`. Legs: latest 8.x `8.14.6` and latest 9.x `9.8.2` (from services.gradle.org, 2026-10-25), crossed with JDK 17 and 21. The repository wrapper stays on 9.5.0 for local builds (D-085).
- **Finding:** a local build under Gradle 8.14.6 (`gradle -p gradle-plugin build`) compiled and passed 60 of 64 tests. The 4 failures are in `GradleCredentialsTest` (`theConfigurationCacheEntryHoldsNoEnvironmentToken`, `theCommandLineGradlePropertyIsNotInTheConfigurationCacheEntryEither`, `theConfigurationCacheEntryHoldsNoGradlePropertyToken`, `aTokenSetInTheBuildScriptIsStoredInTheEntry`). Their positive control fails: the scan cannot read the 8.14.6 configuration-cache entry, because the encryption switch of D-087 (an internal option of Gradle 9.5) does not give a readable entry on 8.14.6. The plugin logic is not the cause as far as the scan shows; the fix needs a decision on how the scan works per Gradle version, so the leg is not green yet.
- **Status:** T43 is not ticked and its matrix is not committed (the patch is kept outside the repository). A red leg may not be pushed. Next implementer batch: make the scan work on 8.x (or accept a per-version oracle with a decision), then commit the matrix.

## D-094 README Gradle snippets: the setup build is a resource that TestKit runs (T44)
- **Date:** 2026-10-25
- **Decision:** The one `groovy` block of README.md is the setup build. Its text is `gradle-plugin/src/test/resources/readme/build.gradle`; `ReadmeGradleSnippetsTest` asserts equality and runs that file through TestKit with the repository placeholder `https://github.com/<owner>/<snapshots-repo>.git` replaced by a bare repository. The `sh` block `./gradlew publishAllPublicationsToGitPublisherRepository` must be the task the test runs. The test asserts one commit and a valid tree (`MetadataAssertions`).
- **Self-publication snippet:** its concurrency group is `maven-snapshots-io.github.ivangsa` (D-083). The copy source `maven-plugin/src/it/readme-workflow/.github/workflows/publish.yml` is changed to the same group, so `ReadmeSnippetsTest` (R21) still matches. The snippet's `main` branch and `GITHUB_TOKEN` scenario still differ from D-083 (`gh-pages` of `zenwave360/maven-snapshots`, `GIT_PUBLISHER_TOKEN` in an environment); this is the documentation follow-up noted in D-084, not changed here.
- **Reason:** T44 "Done when": README Gradle snippets are copied from passing TestKit builds.

## D-095 Plugin Portal configuration and the Gradle release job (T45, under D-083)
- **Superseded by D-111** (2026-10-25, maintainer decision): no Plugin Portal account; the Gradle plugin goes to Maven Central. History for the Portal configuration only.
- **Date:** 2026-10-25
- **Decision:**
  - `gradle-plugin/build.gradle` applies `com.gradle.plugin-publish` 2.2.1 (latest on the Gradle Plugin Portal, 2026-10-25). `gradlePlugin` sets `website` and `vcsUrl` (the repository of the root pom) and `tags` for the plugin. The plugin id, implementation class, display name and description are unchanged.
  - `.github/workflows/release-from-notes.yml` has a job `gradle-plugin` with `needs: release`, environment `maven-central-upload`. It checks out tag `v<version>` (D-079), checks that the root pom declares `<version>`, installs core (`./mvnw install -DskipTests -Dinvoker.skip=true`) and runs `./gradlew -p gradle-plugin publishPlugins`. Secrets `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` are passed by name and read by the plugin from the environment. The pin of `release-maven.yml` (D-079) is unchanged.
  - Evidence: `validatePlugins` passes. The portal refuses `-SNAPSHOT` versions, so `publishPlugins --validate-only` can only run on a release version. On a scratch copy at version `0.1.0` (outside the repository), `publishPlugins --validate-only` passes every local check and stops at "Missing publishing keys". The agents do not read or create the keys, so the portal-side check is open: Q12.
  - T45 is marked `[?]` until Q12 is answered.
- **Reason:** T45 "Done when" needs the portal's validation; the release path needs the publication after the Maven release (D-083).

## D-096 Configuration-cache scan on Gradle 8.x and 9.x: both option names, a probe for what Gradle stores of the command line (T43, under D-087, D-093)
- **Date:** 2026-10-25
- **Decision:**
  - D-093's finding was a wrong option name, not a missing capability. Gradle 8.14 turns entry encryption off with `-Dorg.gradle.configuration-cache.internal.encryption=false`; Gradle 9.5 uses `-Dorg.gradle.internal.configuration-cache.encryption=false` (D-087). `GradleCredentialsTest` passes both on every run (a build ignores the one it does not know), so every leg reads the entry with the same assertions, the positive controls included. No test is skipped or conditional.
  - Once the entry was readable on 8.14, one more difference showed: Gradle 8.x stores every command-line `-P` value in `buildfingerprint.bin` whether or not anything reads it (Gradle's own copy of the start parameters, `startParameterProperties`); 9.x does not. This is not the plugin: the plugin reads the property at execution time (D-087) and adds nothing. `theCommandLineGradlePropertyIsNotInTheConfigurationCacheEntryEither` therefore passes a second `-P` property that nothing reads (`unreadProbeProperty`) and asserts, per file of the entry, that the token occurs exactly as often as the probe value. On 9.x both counts are 0 (the plain "no token" assertion); on 8.x both are 1 in the fingerprint (Gradle's copy); a copy added by the plugin, in any file, would make the counts differ and fail.
  - The `gradle.properties` and environment sources keep the plain assertion (no token anywhere in the entry) on both versions.
  - Documented limit, to be stated in user docs when T44 is next touched: on Gradle 8.x a token passed with `-P` is stored in the configuration cache entry by Gradle itself; prefer the environment variable or `gradle.properties` (or run without `--configuration-cache`).
  - CI: the legs rewrite `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` with `sed` (not committed). D-093's `./gradlew wrapper` cannot run, because the root has no Gradle build (D-085). Legs: 8.14.6 and 9.8.2 x JDK 17 and 21.
- **Why the probe is not weaker:** the control is the same property type and the same run; it distinguishes "Gradle stores the command line" from "the plugin stored the token" on any version, and still fails on 9.x if either copy appears.
- **Rejected:** a version-conditional skip or assumption; dropping the `-P` check on 8.x; encrypting with `GRADLE_ENCRYPTION_KEY` (hides the token from the scan).

## D-097 Gradle lifecycle: the success record is added when the task graph is ready, so it is the last action (R26, under D-086)
- **Date:** 2026-10-25
- **Decision:** `RecordSuccess` is no longer added in `tasks.withType(PublishToMavenRepository).configureEach`. It is added with `doLast` to every participant in `taskGraph.whenReady`, where the push inputs are already computed. Actions that build scripts and other plugins add to a participant while configuring (`doLast` in a subproject script, `tasks.named(...) { doLast }`) were all added before the graph is ready, so they run before the record, and a task that failed in any of them never records. Adding the action in `whenReady` keeps the configuration cache: the cache stores the task with its actions after `whenReady`, and the record action is the same serializable class as before.
- **Reason:** with `configureEach` the record was the first `doLast` of the task, so every later `doLast` ran after it; if one failed, the task was FAILED but recorded, and the finalizer `gitPublisherPush` pushed (review 2026-10-25-8439f3c, F1). The T36 tests only injected failures with `doFirst`.
- **Correction (D-103):** the claim "all added before the graph is ready" is wrong: a `whenReady` registered after the plugin's own (subproject script, later plugin) adds its action after the record. The record no longer relies on being last; see D-103.
- **Limit (revised by D-103, D-104):** an action added while the build is executing (from another task's action) would still follow the record. Under D-103 and D-104 such an action fails closed: the record finds a changed action count, records nothing, and the push is refused; README (Gradle section) states that any action change after the task graph is ready fails the build with nothing pushed. Gradle offers no hook that sees a task's final state synchronously with the finalizer: `OperationCompletionListener` events are asynchronous (D-086) and `taskGraph.afterTask` is not configuration-cache compatible. Not a supported way to extend a task.
- **Evidence:** `R26LateParticipantFailureReviewTest` (default and `--continue`) and `AbstractLifecycleTest.aParticipantThatFailsInALaterActionMeansNoPush` (four schedules, also twice with `--configuration-cache`, second run reusing the entry).
- **Rejected:** a task-state listener (`TaskExecutionListener`, not configuration-cache compatible); a `BuildService` that is an `OperationCompletionListener` (asynchronous, races the finalizer, D-086).

## D-098 Gradle: a skipped participant fails the build and nothing is pushed (R29, revises the known limit of D-086)
- **Date:** 2026-10-25
- **Decision:** when `gitPublisherPush` finds a participant that did not record success, nothing is pushed and the push task fails the build with `git-maven-publisher: nothing is pushed because these publication tasks did not complete successfully (failed, skipped or not run): <paths>`. This covers a participant skipped by `onlyIf { false }` or `enabled = false`, one that did not run because another failed, and one that failed. The previous behaviour (a warning and a green build that published nothing) is removed.
- **Why it differs from Maven:** the Maven adapter knows its participants from the reactor and the explicit `skip` parameter, and a module with `skip=true` is declared non-participating (design section 2, Maven). In Gradle the participant set is every publication task in the task graph; Gradle skip conditions are evaluated at execution time and the plugin cannot tell "the user excluded this publication" from "something went wrong". A refused push must not look like success (design section 2: "no push, with a log line"; the build outcome is ours to choose). A user who wants to publish a subset runs those tasks or excludes the task with `-x` (the task leaves the graph and the participants), which is the Gradle way to say "does not participate".
- **Consequence:** when a participant failed, the build reports its failure and, in addition, the push task failure naming it. Accepted noise.
- **Evidence:** `AbstractLifecycleTest.aSkippedParticipantFailsTheBuildNamingTheTaskAndPushesNothing` (four schedules, twice with `--configuration-cache`): the task shows SKIPPED, the build fails naming it, and the branch has no commit. README Gradle section states it.
- **Rejected:** keeping the build green with a warning; treating a skipped participant as non-participating (cannot be told apart from a failure of an earlier action chain that stops execution, e.g. `StopExecutionException`).

## D-099 README setup build is compared with line endings normalized on both sides (R28, restores T44)
- **Date:** 2026-10-25
- **Decision:**
  - `.gitattributes` pins `gradle-plugin/src/test/resources/readme/build.gradle` to `text eol=lf`, so the setup build has LF line endings in every checkout, including Git for Windows with `core.autocrlf=true`.
  - `ReadmeGradleSnippetsTest` normalizes CRLF to LF on both sides of the comparison: the setup build and the README block are both compared after normalization, and the setup build that TestKit runs is normalized too. The assertions keep their meaning (the same text, modulo line endings).
- **Test edit:** only the line-ending handling changed (new helper `lineFeeds`, used where the test reads the setup build and the README). No assertion was removed or weakened. Reason: the test was provably wrong on CRLF checkouts (review 2026-10-25-8439f3c, F3): both strings were the same text with different line endings.
- **Evidence:** a fresh clone of the commit before this change, `git clone -c core.autocrlf=true . target/r28-clone` (`build.gradle` checked out as CRLF), failed `theGroovySnippetIsTheSetupBuildThatTheTestRuns` in `./gradlew -p gradle-plugin test --tests '*ReadmeGradleSnippetsTest*'`. The same clone after this change passes (see the batch report).
- **Reason:** T44 "Done when" needs the Gradle build to pass in a fresh Windows checkout; both fixes are needed, the pin for the file and the normalization for any other checkout that rewrites line endings.

## D-100 Gradle plugin release waits for core on Central: a gate step before publishPlugins (R27, revises the ordering of D-083 and D-095)
- **Superseded by D-111** (the gate step is replaced by a publication order in the Central Portal). History for the gate.
- **Date:** 2026-10-25
- **Decision:** the job `gradle-plugin` of `release-from-notes.yml` has a step `Check that core is on Maven Central`, after the version check and the core install and before `publishPlugins`. It runs `curl --fail --location` on `https://repo1.maven.org/maven2/io/github/ivangsa/git-maven-publisher-core/<version>/git-maven-publisher-core-<version>.pom`, and when that fails it prints the URL and the instruction (publish the Central deployment of this release, wait until Central serves it, re-run the job) and exits 1. A failing step stops the job, so `publishPlugins` is not reached. The step has no secret; the secrets stay in the `publishPlugins` step, and the environment `maven-central-upload` and the D-079 pin are unchanged.
- **Comment fix:** the job comment no longer says core is already on Central. The Maven release uploads a deployment with `autoPublish` false, so core is on Central only after the maintainer publishes it (review 2026-10-25-8439f3c, F2).
- **Why this mechanism:** the release caller already waits for the maintainer at the environment's required reviewer; a check that reads Central itself tells the truth at the moment the plugin would be published, needs no second dispatch and no state passed between workflows, and fails closed (a Central outage also stops the job, which the re-run covers). The alternative, a separate maintainer-dispatched job after the Central publish, moves the ordering into the maintainer's hands without a check.
- **Limit:** the check looks at the POM only; Central publishes a deployment's files together, so a served POM means the jar is served too. Central's replication may take minutes after the publish: the step then fails, and the re-run after a short wait passes.
- **Evidence:** `actionlint` 1.7.7 passes on `.github/workflows/*.yml` (shellcheck not installed, so shell lint is not run). The gate command was run against the release URL of an absent version (HTTP 404, exit 1, the message printed) and against a Central POM that exists (`org/apache/maven/maven-core/3.9.9`, exit 0). The workflow was not dispatched and `publishPlugins` was not run.

## D-101 Documentation conflicts: Gradle 8.x `-P` tokens, generic self-publication group, previous review on develop (R30, revises D-094 and D-096 follow-up)
- **Date:** 2026-10-25
- **Decision:**
  - README "Authentication" and "Publish from Gradle" state the limit of D-096: on Gradle 8.x a token passed with `-P` is stored in the configuration cache entry by Gradle itself, whether or not the build reads it; Gradle 9.x does not store it. The environment variable is recommended (the plugin reads it at execution time); `gradle.properties` is the next choice; if `-P` is used, run without `--configuration-cache`. The earlier wording "a token never goes on the command line" is removed, because the Gradle limit contradicts it.
  - The generic self-publication snippet in README (and its copy source `maven-plugin/src/it/readme-workflow/.github/workflows/publish.yml`, kept in step for `ReadmeSnippetsTest`) uses the placeholder group `maven-snapshots-<groupId>`, with a sentence telling the user to replace `<groupId>` and to use the same group in every workflow that publishes those coordinates to that repository. D-094's concrete group `maven-snapshots-io.github.ivangsa` is kept for this project's own workflow `.github/workflows/publish-maven-snapshots.yml` (D-083), which is not a generic snippet.
  - The `main` branch and `GITHUB_TOKEN` scenario of the generic snippet are unchanged: they match design section 8. This project's own `develop` setup is in `publish-maven-snapshots.yml` (T60), not in the README snippet, so the D-084 follow-up is closed for the generic snippet only.
  - Review 2026-10-25-d441064 (commit 470075b on `origin/review/t59-br8-d441064`) is brought onto `develop` as `docs/reviews/2026-10-25-d441064.md`, file only, byte for byte from that commit. `docs/reviews/LAST_REVIEWED` is unchanged (it names 8439f3c, the latest review).
- **Reason:** review 2026-10-25-8439f3c, F5 and F6: the user docs contradicted D-096, and the generic snippet would make a user's publications serialize under another project's group name.
- **Evidence:** `ReadmeSnippetsTest` (every README snippet is copied from an IT file) and `ReadmeGradleSnippetsTest` pass (see the batch report).

## D-102 Self-publication consumer checks the build just pushed (R31, under D-083, D-084)
- **Date:** 2026-10-25
- **Decision:**
  - The `publish` job reads back the build numbers that its push left on the branch: after the deploy step, a shallow anonymous clone of `gh-pages` of `zenwave360/maven-snapshots` (no token; the repository is public) and the `<buildNumber>` of `maven-metadata.xml` of `git-maven-publisher-core` and `git-maven-publisher-maven-plugin` for `<version>-SNAPSHOT`. It exposes them as job outputs `core-build` and `plugin-build`. The step fails when a build number is missing.
  - The `consumer` job first waits, before it resolves anything, until `raw.githubusercontent.com` serves the same version-level metadata with a build number at least as large as each published one (15 attempts, 30 seconds apart; the job timeout is 20 minutes). It fails naming the served and the expected builds when the branch still serves an older build. Only then does the dependency resolution run.
  - Build numbers, not timestamps, are compared: Maven's deployer increments `buildNumber` per deployment of a version directory, so the served number is the monotonic one; the timestamp is only text. "At least as large" lets a newer publication from another run pass, as the check is about the branch not being older than this run's push.
- **Reason:** review 2026-10-25-8439f3c, F7: the old check passed for any jar of `X-SNAPSHOT`, so after the first run an older or CDN-cached build satisfied it.
- **Evidence:** `actionlint` 1.7.7 passes (shellcheck is not installed, so the shell is not linted). The consumer step and the read-back step were run as extracted from the workflow, in bash with `sleep` replaced, against three served states of the metadata (older build 5, equal 6, newer 7; expected 6): older fails with exit 1 and the message, equal and newer pass. The first green run on `develop` after the push is checked in the batch report.
- **Limit:** the check proves the branch serves a build at least as new as this run's push; it does not compare the jar contents. A CDN that stays stale for longer than the 7.5 minute window fails the job, which is the intended outcome (re-run it).

## D-103 Gradle lifecycle: the success record fails closed against any action order (R32, revises D-097)
- **Date:** 2026-10-25
- **Decision:**
  - One `RecordSuccess` per participant for the whole build (a Gradle-level set of recorded task paths, `io.github.ivangsa.gitpublisher.recordedParticipants`, so the `whenReady` of the second and later projects applying the plugin adds nothing). It is added with `doLast` in the plugin's `taskGraph.whenReady` and stores `expectedActions` = number of actions of the task right after adding it.
  - When it runs it compares `task.getActions().size()` with `expectedActions`. If they differ (an action was added after it, by a later `whenReady`, a later plugin, or during execution; or actions were removed) it records nothing and logs a warning naming the task. The participant stays unrecorded, so `gitPublisherPush` refuses the push and fails the build (D-098). The check holds for any callback order and any number of projects; it does not depend on winning an ordering race. **Corrected by D-104:** the count alone does not prove "the record is last": a change that keeps the size (remove one action and add one, or wrap the record via `setActions`) still pushed (review 2026-10-25-66f46ec, F1). D-104 adds the checks that make it hold.
  - Consequence: a late action that succeeds also makes the build fail and nothing is pushed, because its outcome was not known when the record ran. Accepted: publication tasks must not get actions after the task graph is ready.
  - (Revised by D-104: the record is now recognised by a unique `Describable` display name.) The count is used because `task.getActions()` returns Gradle-internal wrappers (`AbstractTask$TaskActionWrapper`, `ClosureTaskAction`) that cannot be recognised as ours through the public API. The count is stored in the serialized action, so the configuration cache keeps working (second run reuses the entry, the late-action scenarios hold in both runs).
- **Reason:** review 2026-10-25-53fd0c7, F1: D-097 put the record last only among actions known when the plugin's callback runs.
- **Evidence:** `R32LateWhenReadyActionReviewTest` (default, `--continue`, `--configuration-cache`), `R26LateParticipantFailureReviewTest`, `LateActionFailsClosedTest` (a harmless late action: build fails naming the task, nothing pushed, three schedules), the lifecycle tests.
- **Rejected:** `BuildEventsListenerRegistry.onTaskCompletion` with a BuildService: delivery is asynchronous (D-086) and no guarantee exists that the event arrives before the finalizer runs, in particular with `--parallel`; internal Gradle types to find our own action in the list.

## D-104 Gradle lifecycle: the success record counts only when Gradle runs it, unwrapped, as the last action (R33, corrects D-103)
- **Date:** 2026-10-25
- **Decision:** `RecordSuccess` is still added once per participant in the plugin's `taskGraph.whenReady`, now with `doLast(name, record)`, and it implements `org.gradle.api.Describable` with the same name: `git-maven-publisher success record of <task path> <random UUID>`. The name is generated when the record is created and stored in the serialized action, so a configuration cache entry keeps it; no other callback can have known it. When the record runs it records only if all three checks hold, otherwise it records nothing, logs a warning naming the task and the reason, and `gitPublisherPush` refuses the push and fails the build (D-098):
  1. Count: `task.getActions().size()` equals the count stored when the record was added (D-103).
  2. Last: the last element of `task.getActions()` is a `Describable` whose display name is the record's name (Gradle's action wrapper reports the name of a `Describable` action, or `Execute <name>` for a named action; both are accepted), and no other element carries it.
  3. Unwrapped: every stack frame above `RecordSuccess.execute` belongs to a class in `org.gradle.`, `java.`, `jdk.` or `sun.` (JDK `StackWalker`). A wrapper set with `setActions`, an earlier action that calls the record, or any build script, plugin or Groovy/Kotlin code between Gradle's executor and the record shows up as a frame of another class.
  - Together: Gradle's executor runs the record directly as a task action (3); the only element of the list that carries the record is the last one (2), with nothing added or removed (1). So nothing of the task runs after the record: it records only when the task completed.
  - A refused call does not poison a later genuine one: if an earlier action calls the record and succeeds, the record refuses that call and records when Gradle runs it as the last action.
- **Remaining limit (not provable through Gradle's public API):** code that deliberately defeats the checks (a wrapper class in an `org.gradle.` package or one built from Gradle-internal action types such as `org.gradle.internal.Actions` composites together with the record's exact display name, or reflection into the service). Gradle exposes no public "currently executing action" or "action identity" API; the record cannot prove its identity among the list elements except through its display name. Checks 2 and 3 rest on observed Gradle behaviour (the action wrapper reports the action's display name; Gradle's executor frames are Gradle or JDK classes), verified by the lifecycle tests on Gradle 8.14, 9.5.0 and 9.8.2 (CI runs 8.14.6 and 9.8.2); if a Gradle version changes either, the record fails closed (nothing is pushed, the warning names the reason), which the CI matrix would show.
- **Consequence:** a plugin that wraps every task action (for example for timing) or adds frames between Gradle's executor and task actions makes the publication fail closed. Accepted: publication tasks must not get, lose or have wrapped actions after the task graph is ready (README, Gradle section).
- **Reason:** review 2026-10-25-66f46ec, F1: D-103's count is a proxy for "the record is last"; a later `whenReady` that removes one action and adds a failing one, or wraps the record via `setActions`, kept the count and pushed after a failed participant.
- **Evidence:** `R33CountPreservingLateActionReviewTest` (remove-and-add: default, `--continue`, `--configuration-cache`; wrap-last: default, `--continue`), `RecordRunsLastAndUnwrappedTest` (an earlier action that calls the record and then fails; a wrapper that copies the record's display name and fails after delegating; default and `--continue`), `R32LateWhenReadyActionReviewTest`, `R26LateParticipantFailureReviewTest`, `LateActionFailsClosedTest`, `LifecycleTest` and `ConfigurationCacheLifecycleTest` (pushes still happen with `--parallel`, `--continue`, a selected subproject, configuration cache store and reuse).
- **Rejected:** comparing the last element's `hashCode`/`equals` with the record (depends on Gradle's internal wrapper `equals`; the display name gives the same identity with less coupling); executing the last action again as a probe (runs user code twice); storing a reference to Gradle's wrapper in the record (a cycle the configuration cache does not promise to preserve); stack checks that name Gradle's internal executor classes (break between Gradle versions; the prefix check only needs Gradle's own frames to be Gradle's).

## D-105 History squashed; one commit per batch; normal commit dates
- **Date:** 2026-10-25 (applied after Phase 2)
- **Decision:** the maintainer had the Phase 0–2 history (130 commits) squashed into 22 signed commits: one per stretch of work between two reviews,
  with every review commit kept as it was; `main` and `develop` were force-pushed with lease (ruleset 25787740 off for the push, on again).
  Commit SHAs quoted in review files, `LAST_REVIEWED`, tasks.md and older decisions refer to the pre-squash history and are kept as historical text.
  From now on:
  - one signed commit per batch, carrying the ticks, the batch row and the status line (`B19: T47, T48 <summary>`); review commits stay separate;
  - no bookkeeping-only commits from the orchestrator: corrections ride along with the next commit that goes out;
  - normal commit dates; the fixed 2026-10-24/25 window applied to Phases 0–2 only;
  - the reviewer pushes a `review/<sha>` branch; the orchestrator brings it onto `develop` and the fix batch pushes review and fixes together.
- **Reason:** the maintainer found the commit and build volume excessive; D-092 already cut the builds per batch.

## D-106 keepVersions and release-aware cleanup in `Retention` (T47, T48) (revised by D-107: touched versions are never dropped by keepVersions; the adapter gap is closed)
- **Date:** 2026-10-25
- **Decision:** `Retention.apply` runs, after the per-version build retention (unchanged, D-025, D-080), `VersionRetention` once per touched `groupId:artifactId` (the parent of each touched `-SNAPSHOT` directory): first the release-aware cleanup, then `keepVersions`. Both delete whole `*-SNAPSHOT` sibling directories (the files are added to the deleted paths), remove the names from the A-level `<versions>`, move `<latest>` to the last remaining version when it named a dropped one, and regenerate the A-level checksums. Release directories, loose files and the A-level metadata are never deleted. `keepVersions` ranks by V-level `lastUpdated` (missing = oldest, ties by name); it can drop a version published now if its `lastUpdated` is the oldest.
  Release-aware cleanup is opt-in: `RetentionPolicy` gets a third component `releaseRepository` (`URI`, `null` = off; the two-argument constructor is kept; `https` or `file` only, no userinfo). `RetentionPolicy.CENTRAL` is the Central URL for callers that want the default repository. The check is `<repo>/<groupPath>/<artifactId>/<base>/<artifactId>-<base>.pom`: `Files.isRegularFile` for `file:`, an HTTP `HEAD` (200 only, 10 s connect, 15 s request) for `https`. Any error keeps the version. The versions published now are never dropped by the release check.
- **Reason:** the brief calls the feature optional; making Central the silent default would add a network call to every publication and to every test. Fail-safe on doubt avoids deleting snapshots because the network was down.
- **Gap:** the Maven and Gradle adapters do not expose a release-repository parameter yet (`keepVersions` is already wired); that is a small follow-up for the adapter batches.
- **Evidence:** `VersionRetentionTest` (real Resolver deploys, `MetadataAssertions`, a `file://` release repository, canonical timestamped file names after retention, unrelated files kept) and `GitPublisherPublishTest.keepVersionsAndReleaseCleanupRunBeforeTheCommit` (four real publications through Git).
- **Rejected:** a default of Central; a `PagesServer`-style fake HTTP server (D-043); protecting the touched version from `keepVersions`.

## D-107 Retention after the Phase 3 review: touched versions stay, the https check, the adapters (R36-R40)
- **Date:** 2026-10-25
- **Decision:**
  - **R36, revises D-106.** `keepVersions` never drops a version this publication touched, whatever its `lastUpdated`, and the touched versions count toward N. Only the other `-SNAPSHOT` versions are ranked (V-level `lastUpdated`, newest first, none = oldest, ties by name) and the oldest beyond `max(0, N - touched)` go. With more than N versions published at once all of them stay. The brief ("drop old versions") and D-106 disagreed on the version published now; design section 6 now follows the brief's intent, because Maven stamps `lastUpdated` with the session start and a long build deployed last would otherwise delete its own deploy and still push.
  - **R37.** "Release exists" is defined in design section 6: `file:` a regular file; `https` a `HEAD` whose final answer (redirects followed, CDNs need it) is 200 with a content type that is not HTML (`text/html`, `application/xhtml+xml`). Everything else is doubt and keeps the version. A server may omit the content type of a POM; that is accepted. The https branch now has a test (`R37ReleaseCheckOverHttpsReviewTest`, `ReleaseCheckTest`) with the JDK's in-process servers: D-043's reason against a fake server does not hold because the code under test is our own client.
  - **R38, closes the D-106 gap.** Maven `git-publisher.releaseRepository` (parameter `releaseRepository`) and Gradle `gitMavenPublisher.releaseRepository` are strings, off when unset or blank; `central` (any case) means `RetentionPolicy.CENTRAL`, otherwise an `https` or `file` URL (`RetentionPolicy.parseReleaseRepository`; messages never echo the value). The value is part of the per-build consistency checks of both adapters. Tests: invoker IT `release-cleanup`, TestKit `ReleaseCleanupTest`. The invoker's Groovy hook cannot load the Resolver (class path clash with the invoker plugin), so the IT seeds its remote with hand-written files and checksums. The README documents the option in prose only: its snippets must be copied from ITs and the Gradle snippet must equal `readme/build.gradle`, and the IT passes the file URL through `maven.config`.
  - **R39.** `ReleaseCheck` owns one `HttpClient` (default `ProxySelector`) and the answers of one publication; `GitPublication.publish` creates it once and passes it to every `Retention.apply` of the retry loop, so each question is asked once. A failure other than a plain 404 (HTTP status, HTML page, timeout, error) is logged once as a warning with scheme, host and path only (no query; the policy already rejects userinfo).
  - **R40.** Design section 6: "never deleted" now applies to *build* retention; `keepVersions` and release cleanup remove whole version directories, including the V-level metadata, plain jars and user files inside.
- **Reason:** review 2026-10-25-250f684 (F1-F5).
- **Rejected:** `keepVersions` ranking touched versions too (deletes the deploy just made); treating any 200 as a release (login pages); caching only successes (a blackholed network would wait 10 s per version per attempt).

## D-108 Maven Wrapper on native Windows: the home lookup accepts PowerShell 5.1 and 7 (R41, F1)
- **Decision:** No newer official Maven Wrapper fixes this: 3.3.4 is the latest release on Maven Central (checked 2026-10-25), and regenerating with it would keep the same expression. `mvnw.cmd` is therefore patched by hand. Only the expression that picks the Maven home root changes: `(Get-Item $MAVEN_M2_PATH).Target` is read once; when it is empty or `$null` the plain `.m2` path is used, otherwise the first element of `@($target)` is the link target. The rest of the generated 3.3.4 script is unchanged, and Maven stays 3.10.0 and wrapper 3.3.4.
- **Reason:** review 2026-10-25-3044006 F1. Under PowerShell 7.6.8 the `Target` of a plain directory is `$null`, and `$null[0]` aborts the wrapper with "Cannot index into a null array" (reproduced by running the wrapper's PowerShell part under pwsh 7.6.8). Under Windows PowerShell 5.1 the property is an empty collection, so the old code worked there only because of that. A junction `MAVEN_USER_HOME` still resolves to its target under both.
- **Proof:** `cmd //c mvnw.cmd -v` and `mvnw.cmd -B verify` from Windows PowerShell 5.1 pass; the wrapper's PowerShell part fails under pwsh 7.6.8 before the patch and passes after it, for a plain directory and for a junction; `mvnw.cmd -v` from pwsh 7.6.8 passes after the patch.
- **Rejected:** regenerating with 3.3.4 (same bug); replacing the wrapper with a different one; a `try/catch` around the lookup (hides a real link target); changing `MAVEN_USER_HOME` handling.
- **Note:** `./mvnw wrapper:wrapper` or a wrapper upgrade overwrites this patch; re-apply it or check `mvnw.cmd` after any regeneration.
- **Reason for the batch:** review 2026-10-25-3044006 (FIX FIRST), R41 (BR14).

## D-109 A release repository URL has no query or fragment (R42, F1)
- **Decision:** `RetentionPolicy` rejects a `releaseRepository` with a query or a fragment (`IllegalArgumentException`, message "releaseRepository must not contain a query or a fragment", never the value), next to the existing scheme and userinfo checks. `ReleaseCheck.exists` also answers "no" without a request for such a URL, so a caller that skips the policy cannot ask the repository root in place of the POM. Design sections 4 (option table) and 6 say so.
- **Reason:** review 2026-10-25-20d7325 F1. Appending `/<groupPath>/...pom` to the whole string put the path into the query or fragment and sent `HEAD /maven2/`; a non-HTML 200 there made every untouched `-SNAPSHOT` version look released.
- **Why reject, not resolve:** a query (signed URL, token) is a credential in the same class as userinfo, which is already rejected; keeping it would mean sending it on every HEAD and following redirects with it. Failing at configuration is loud and cheap; a repository that needs a signed query cannot be laid out as a Maven directory tree anyway.
- **Rejected:** resolving the POM path against the URL path and keeping the query (secret in requests, redirect leaks).
- **Evidence:** `R42ReleaseRepositoryQueryReviewTest`; `ReleaseCheckTest.theServerSeesTheExactPomPath` (JDK in-process server records `HEAD /maven2/org/acme/lib/1.0/lib-1.0.pom`, with and without trailing slash) and `aQueryOrFragmentIsNeverAskedAndPolicyRejectsItWithoutEchoingIt`. The older `ReleaseCheckTest` case that passed a query no longer does.

## D-110 License switched from Apache 2.0 to MIT (BL1, maintainer decision 2026-10-25)
- **Decision:** the project is licensed under the MIT License. `LICENSE` is the maintainer's text, kept verbatim (MIT, "Copyright (c) 2026 ZenWave's Authors and Contributors"). The copyright holder in source headers is the same: `license-header.txt` reads "Copyright (c) 2026 ZenWave's Authors and Contributors" and "Licensed under the MIT License; see the LICENSE file in the project root." (the same text in the POM, Gradle and Python script headers).
- **Applied to:** the POM `<licenses>` of the parent, `core` and `maven-plugin` (`MIT License`, `https://opensource.org/licenses/MIT`; `distribution` stays `repo`); the header of every project source file, re-applied with `./mvnw license:format` (the `license-maven-plugin` check still runs on `**/src/**/*.java`, which also covers `gradle-plugin/src`, so Gradle Java sources were rewritten by the same goal); `gradle-plugin/build.gradle` and `settings.gradle` by hand, in the same text (no license plugin runs on them); `scripts/verify-central-bundle.py` header (its checks look for a license name and URL, and do not check the value); the README License section; the stack line in AGENTS.md; the distribution line in docs/brief.md. The Gradle build has no `licenses` metadata to change: the Plugin Portal publication takes its metadata from `gradlePlugin` (website, vcsUrl), which does not carry a license.
- **Stays Apache 2.0 (not ours, byte for byte):** `mvnw`, `mvnw.cmd`, `gradlew`, `gradlew.bat` (ASF and Gradle headers); `.mvn/wrapper/*` and `gradle/wrapper/*`; the golden third-party POMs and metadata under `test-fixtures/src/main/resources/golden/` (Apache Commons, Maven, Maven Resolver, kotlinx-datetime fixtures). Past text keeps its history: `docs/tasks.md` T01 ("Apache 2.0", the task as done), this file's earlier entries, and the reviews under `docs/reviews/`. `Apache MINA SSHD` (in the rejected SSH option, former T46) is a library name.
- **Exclusions:** `license-maven-plugin` checks `**/src/**/*.java` only, so the golden fixtures (resources) and the wrappers are not checked; `maven-plugin/src/it/**` stays excluded (unchanged).
- **Review tests:** the header of the files under `review/` folders changed too (the check needs it). Only the comment block changed; no assertion or test code was touched.
- **Reason:** maintainer decision, 2026-10-25 (batch BL1). It revises the license part of the brief, the header decisions (the license check of D-016 and the header template location of D-076) and the AGENTS.md stack line; no behaviour changed.

## D-111 Gradle plugin to Maven Central, snapshot dogfood, composite builds, no nightly (T45, R35, T51; answers Q7, Q12, Q13; supersedes D-095, D-100)
- **Date:** 2026-10-25 (batch BR16, maintainer decisions)
- **Decision, release:**
  - No Gradle Plugin Portal account. `com.gradle.plugin-publish` is removed (D-095 is history). `gradle-plugin/build.gradle` applies `maven-publish` and `signing`. The publications are the ones `java-gradle-plugin` creates: `pluginMaven` (jar, `.module`, POM) and the marker `io.github.ivangsa.git-maven-publisher:io.github.ivangsa.git-maven-publisher.gradle.plugin` (POM only, depends on the jar). Every POM has name, description, url, MIT license (D-110), developer and scm; the jar has sources and javadoc jars (`withSourcesJar()`, `withJavadocJar()`, doclint off for javadoc).
  - Signing: `SIGN_KEY` (armored private key) and `SIGN_KEY_PASS` from the environment, `useInMemoryPgpKeys`, nothing written to disk or Gradle properties. Signing is applied only when `SIGN_KEY` is present, so local builds and the snapshot publication are unsigned.
  - The bundle: the publications go to a local repository `centralStaging` (`build/central-staging`, cleaned first), and `centralBundle` (Zip) writes `build/central-bundle/git-maven-publisher-gradle-plugin-<version>-central-bundle.zip` from it, without `maven-metadata.xml*` and without checksums of signatures. It fails for a `-SNAPSHOT` version and without `SIGN_KEY`. Gradle writes md5, sha1, sha256 and sha512 of every file.
  - Upload: no extra Gradle plugin. The job calls the Central Portal API (`POST https://central.sonatype.com/api/v1/publisher/upload?name=...&publishingType=USER_MANAGED`, bearer `base64(CENTRAL_USERNAME:CENTRAL_TOKEN)`) with `curl`; the header goes to curl on stdin, not on the command line. Reasons: the bundle is the same artifact the local check inspects, one curl call is easier to audit than a third-party plugin that would also get the credentials, and `USER_MANAGED` is the equivalent of `autoPublish` false. The credentials are only in the upload step; the signing key only in the bundle step.
  - Ordering (replaces the gate of D-100): the job `gradle-plugin` of `release-from-notes.yml` no longer waits for core on Central. Uploading a deployment does not need its dependencies to be public, and a gate that fails until the maintainer has published would only force a re-run. Both deployments wait in the Central Portal; the maintainer publishes core first, then the plugin deployment (the job summary says so). Consequence: if the plugin deployment is published before core, the plugin POM names a core version that Central does not serve yet, until the core deployment is published. The job `needs: release`, environment `maven-central-upload`, secrets by name only in the steps that need them, and the pin of D-079 are unchanged.
  - Resolution: the marker lives only on Central. Verified by the orchestrator: `plugins.gradle.org/m2` answers 303 to `repo.maven.apache.org` for markers that exist only on Central (for example `org.ajoberstar.git-publish` 6.0.0), so `plugins { id(...) version ... }` with the default `gradlePluginPortal()` resolves releases from Central. The groupId of the marker is under the verified namespace `io.github.ivangsa`.
- **Decision, snapshots (dogfood):** the job `publish` of `publish-maven-snapshots.yml`, after the Maven deploy, runs `./gradlew -p gradle-plugin publishToMavenLocal` and then `./gradlew -p gradle-plugin -I gradle-plugin/self-publish.init.gradle publishAllPublicationsToGitPublisherRepository`. The init script puts the plugin just installed from the same commit on its classpath and applies it to the build that produced it; the plugin then publishes the jar and the marker to `zenwave360/maven-snapshots`, branch `gh-pages`, in one commit, with `GIT_PUBLISHER_TOKEN` only in that step (the script reads `PLUGIN_VERSION`, and optionally `SELF_PUBLISH_REPOSITORY` and `SELF_PUBLISH_BRANCH`, which the local proof used). The job reads back the build numbers of the jar and the marker; the `consumer` job waits until `https://raw.githubusercontent.com/zenwave360/maven-snapshots/gh-pages/` serves at least those builds (30 s apart, 20 attempts), then applies the plugin by its id in an empty Gradle user home with the `pluginManagement` block of the README (the raw URL of the branch, then `gradlePluginPortal()`), requires the `gitMavenPublisher` extension, and compares the sha1 of the jar it loaded with the `.jar.sha1` that the branch serves for the build in its metadata (D-102 style: "at least as new" for the number, equality for the content).
- **Decision, README and test:** README sections "Composite builds" and "Where the plugin comes from" (releases from Central through `gradlePluginPortal()`; the `settings.gradle` snippet for snapshots). `ReadmeGradleSnippetsTest` checks the snippet against `src/test/resources/readme/settings.gradle` and resolves the plugin by its id from the marker in a TestKit build: the test task depends on `publishAllPublicationsToCentralStagingRepository` and the settings file of the README has the URL replaced by that repository (plus `mavenLocal()` for core); no plugin classpath is injected.
- **Decision, R35 (Q13, answer A):** composite builds publish once per build, as design section 2 now says. Each build has its own service and its own push; for all-or-nothing, publish from one build per invocation. No code change.
- **Decision, T51 (Q7):** dropped. Releases are on demand, no nightly. The consumer jobs of the self-publication workflow are the end-to-end check. Q7, Q12, Q13 are closed; T45 is redefined (no `publishPlugins --validate-only`).
- **Check script:** `scripts/verify-central-bundle.py --gradle-plugin` expects the plugin files (POM, jar, sources, javadoc, `.module`) and the POM-only marker, requires an `.asc` for each file that gpg verifies against the given fingerprint, md5 and sha1 (sha256 and sha512 when present), the POM fields, a marker POM that depends on the plugin jar of the same version, valid module metadata, and ignores zip directory entries (`zip -r`, which Central documents, writes them too). The Maven mode is unchanged.
- **Evidence:** a copy of the tree at version 0.1.0 (scratch directory; the 0.1.0 install in the local Maven repository was removed afterwards), a throwaway RSA-2048 key in a short GnuPG home and env vars only, `./gradlew -p gradle-plugin centralBundle`, then the checker: PASS for 6 files (plugin pom, jar, sources, javadoc, module and marker pom), all signed by the throwaway fingerprint; a wrong fingerprint fails the signature checks. The home and the key were removed. `actionlint` 1.7.12 passes on `.github/workflows/*.yml`. The init script was run against a bare `file://` repository: one commit with the jar, sources, javadoc, module, POM and the marker POM at a timestamped snapshot version; a clean Gradle user home then applied the plugin by its id from a checkout of that branch. Nothing was uploaded to Central, no workflow was dispatched, no secret was read or created.
- **Limits:** the Central upload itself (Portal API response, validation of the bundle by Central, namespace permission) can only be proven by the first real release; the maintainer reviews the deployment in the Portal before publishing. The marker resolves from Central through the Plugin Portal redirect only after the deployment is published and Central serves it.
- **Test edit (not a review test):** `ReadmeSnippetsTest` (Maven) skips the new `groovy settings.gradle` block like the `groovy` one; `ReadmeGradleSnippetsTest` checks it. Without this the Maven build failed on the new README snippet.
- **Correction (first run on `develop`, d4f9598):** the maintainer's URL `https://www.zenwave.io/maven-snapshots/` is not served by GitHub Pages. `zenwave360.github.io/maven-snapshots/` answers 301 to it, but `www.zenwave.io` is a Webflow site and answers 404 for `/maven-snapshots/...` (Pages itself reports the build as `built`; core's metadata 404s there too). The publish job succeeded (jar and marker pushed as build 1), the consumer waited 10 minutes and failed. The README snippet, `settings.gradle` test resource, and the consumer job use `https://raw.githubusercontent.com/zenwave360/maven-snapshots/gh-pages/` instead, which already serves the Maven snapshots of this project (D-102). If the maintainer fixes the domain routing, the URL in the README can be changed to it with the test resource.
