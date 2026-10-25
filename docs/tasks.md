# Tasks

**Status:** Phase 1 done and reviewed; repo public; T61 branch model on (agents push to `develop`) · Phase 2 done on `develop` (B15-B18b, fix batches BR9-BR12; final re-review 2026-10-25-87257db: SHIP); T45 redefined and done by BR16 (Gradle plugin and marker to Maven Central, snapshot dogfood; Q12 closed, D-111), R35 done by BR16 (Q13 answered: one publication per build), T51 dropped (Q7: releases on demand) · validation cadence D-092 · BR17 failed review verification (R43/R46), fixed by BR18 (D-113) · next: develop → main PR, T32b; Phase 3: B19 done (T47, T48), review 2026-10-25-250f684 FIX FIRST, fixed by BR13 (R36-R40, D-107); R41 fixed by BR14 (D-108, T01 restored); re-review 2026-10-25-20d7325 FIX FIRST: R42 fixed by BR15 (D-109), BR15 re-review pending before B20; BL1 done (license switched to MIT, D-110); BR16 done (D-111); re-review 2026-10-25-a3707d0 FIX FIRST: R43-R45 done (BR17, BR18); re-review 2026-10-25-4de4033 FIX FIRST: R47 fixed by BR19 (D-114); snapshots now on zenwave360/maven-snapshots `main` via GitHub Pages (D-115); re-review 2026-10-25-5dc5581 FIX FIRST: R48-R50 fixed by BR20 (D-116), BR20 re-review pending before B20

## Batches

Run in order. Context packs live in `docs/context/`; every dispatch also passes `AGENTS.md`.
Review findings (`R` tasks, `[!]` tasks) always get their own batches, never mixed with new production code.
"Review" = run `adversarial-reviewer` after the batch (AGENTS.md, Orchestration).
Inside a batch, tasks run in the order listed (B04 does T17 before T16 so retention tests can use it).
Plan revision 2026-10-24 (D-032..D-039): BR4, BP1 and BP2 run before B04; B05 (T15) is removed; B06/B10/B11 changed scope.

| Batch | Tasks | Agent | Context pack | Review | Status |
| --- | --- | --- | --- | --- | --- |
| B01 | T01, T02, T03, T10 | mechanic | testing | | done |
| B02 | T04, T05, T06, T11 | implementer | testing | | done |
| B03 | T07, T08, T09, T09b | implementer | testing | yes (end of Phase 0) | done |
| BR1 | R01, R02 | implementer | testing | | done |
| BR2 | R03, R04 | implementer | testing, core | | done |
| BR3 | R05, R06, R07 | implementer | testing | | done |
| BR4 | R09, R10; restore T06, T10, R05 | implementer | testing | | done |
| BP1 | T53, T54 | mechanic | testing | | done |
| BP2 | T55, T56, T57; restore T09, R04 | implementer | testing | | done |
| B04 | T12, T13, T14, T17 | implementer | core | | failed review verification: T17/R24 |
| ~~B05~~ | ~~T15~~ removed (D-032) | - | - | | removed |
| B06 | T16 (retention) | implementer | core | yes (plan revision + retention) | failed review verification: T16/R23 |
| BR5 | R11, R12, R13, R14 | implementer | core, testing | | done |
| B07 | T18, T19 | implementer | core | | done |
| B08 | T20 (lease) | implementer | core | | done |
| B09 | T21 (retry + independent replay) | implementer | core | | done |
| B10 | T21b (conflict detection) | implementer | core | yes (lease + retry + replay + conflicts) | done |
| BR6 | [!] T21 via R15; R16, R17, R18 | implementer | core, testing | | done |
| B11 | T23, T24 (T22 dropped, D-044) | implementer | core, testing | | done |
| B12 | T25, T26, T27, T28 | implementer | maven-plugin | | failed review verification: T26/R25 |
| B13 | T29, T30, T31 | mechanic | maven-plugin | yes (end of Phase 1 code) | done (reviewed 0701e5d: FIX FIRST) |
| BR7 | [!] T20 via R19, [!] T26 via R22, [!] T31 via R21; R20 | implementer | core, maven-plugin, testing | | done |
| BP3 | T58 (multi-module split, D-075) | mechanic | testing | | done |
| B14 | T32a | mechanic | maven-plugin | | done |
| BR8 | [!] T16 via R23, [!] T17 via R24, [!] T26 via R25 | implementer | core, maven-plugin, testing | | done |
| BP4 | T59 (release flow like release-workflows, D-078) | mechanic | maven-plugin, testing | | done |
| BP5 | T60 (self-publication, D-083, D-084) | mechanic | maven-plugin, testing | | done |
| main | T61 (branch model switch, D-083) | orchestrator + maintainer | - | | done |
| manual | T59b | maintainer | - | | partly done (repo public, environments, develop, badges); ruleset in T61 |
| manual | T32b | maintainer | - | | after T59b; not dispatched to an agent |
| B15 | T34, T35, T36, T37 | implementer | gradle-plugin | | done |
| B16 | T38, T39, T40 | implementer | gradle-plugin, testing | | done |
| B17 | T41, T42 | implementer | gradle-plugin, testing | | done |
| B18 | T43, T44, T45 | mechanic | gradle-plugin | | partial: T44 done; T43 needs-implementer (D-093), moved to B18b; T45 moved to BR16 (redefined, D-111) |
| B18b | T43 (Gradle 8.x configuration-cache scan, D-093, D-096) | implementer | gradle-plugin, testing | yes (end of Phase 2) | done |
| BR9 | R26, R29 (Gradle participant failure and skip; restores T36) | implementer | gradle-plugin, testing | | done |
| BR10 | R28, R27, R30, R31 (line endings, Gradle release gate, docs, self-publication consumer; restores T44) | mechanic | gradle-plugin | yes (re-review of BR9, BR10) | done |
| BR11 | R32 (late whenReady action after the success record; restores T36, R26) | implementer | gradle-plugin, testing | yes (re-review of BR11) | done |
| BR12 | R33, R34 (record must be the last action, README late-action rule; R35 moved to BR16) | implementer (opus, escalation) | gradle-plugin, testing | yes (re-review of BR12) | done |
| B19 | T47, T48 (retention) | implementer | core | yes (retention) | done |
| BR13 | R36, R37, R38, R39, R40 (keepVersions keeps touched versions, https release check, release repository in Maven and Gradle, check cost, design wording) | implementer | core, maven-plugin, gradle-plugin | yes (re-review of BR13) | done |
| BR14 | R41 (Maven Wrapper runs on native Windows under PowerShell 5.1 and 7, D-108; restores T01) | mechanic | - | yes (re-review of BR13, BR14) | done |
| BR15 | R42 (release repository URL with a query or fragment) | implementer | core | yes (re-review of BR15) | done |
| BL1 | License switched from Apache 2.0 to MIT (D-110, maintainer decision 2026-10-25; headers, POM licenses, docs; third-party Apache files unchanged) | mechanic | - | | done |
| BR16 | T45 (redefined: Gradle plugin to Maven Central), R35 (docs), T51 dropped (D-111) | implementer | gradle-plugin, testing | yes (re-review of BR15, BL1, BR16) | done |
| BR17 | R43, R44, R45 (release check asks plain path segments only, Pages URL statement corrected, Central upload shows the error body and masks the bearer; D-112) | implementer | core, docs, workflows | yes (re-review of BR17) | done (R43 and R46 fixed by BR18) |
| BR18 | R46 (restores R43; per-segment percent-encoding in the release check; D-113) | implementer | core, docs | yes (re-review of BR17, BR18) | done |
| BR19 | R47 (`;` quoted in release check segments, D-114); snapshots served from zenwave360/maven-snapshots `main` through GitHub Pages (D-115) | implementer | core, docs, workflow, README | yes (re-review of BR19) | done |
| BR20 | R48 (`+` quoted as `%2B`, D-116), R49 (`.nojekyll` in the self-publication), R50 (D-115 revises D-083/D-102/D-111) | implementer | core, docs, workflow | yes (re-review of BR20) | done |
| B20 | T49 | implementer | maven-plugin, gradle-plugin | | todo |
| B21 | T50, T52 (T51 dropped, Q7) | mechanic | testing | yes (end of Phase 3) | todo |
| manual | T33 | maintainer | - | | after T32b (self-publication part after the repo is public, D-074); not dispatched to an agent |

Legend: `[ ]` todo · `[x]` done · `[-]` dropped by the maintainer · `[?]` blocked on a question in `docs/questions.md` · `[!]` failed review verification (see linked review).
Every "Done when" assumes the full build passes (`./mvnw verify`, plus `./gradlew -p gradle-plugin build` once it exists), per the done rule in AGENTS.md.

## Phase 0: test harness (no production code)

- [x] T00 Project plan
  Brief, design, tasks, decisions, questions, CLAUDE.md.
  Done when: files committed as `T00: project plan`.
- [x] T01 Build skeleton ([review 2026-10-25-3044006](reviews/2026-10-25-3044006.md), F1; restored by R41)
  Root `pom.xml` (Java 17 release, Apache 2.0, groupId `io.github.ivangsa`), Maven Wrapper 3.10.x, empty `core`, `test-fixtures`, `maven-plugin` modules, `.gitignore`, `LICENSE`, README stub, license-header check.
  Done when: `./mvnw verify` passes on a clean clone.
- [x] T02 CI workflow
  GitHub Actions: `./mvnw verify` on ubuntu (JDK 17, 21); cache `~/.m2`. (`-Pe2e` and its zero-tests guard are added in T09b.)
  Done when: the workflow is green on the pushed commit (private repo: Actions minutes apply).
- [x] T03 Golden metadata fixtures
  Download real files into `test-fixtures/src/main/resources/golden/`: A-level `org/apache/commons/commons-lang3/maven-metadata.xml`; G-level `org/apache/maven/plugins/maven-metadata.xml`; KMP `org.jetbrains.kotlinx:kotlinx-datetime` latest release: A-level metadata, `.module` and `.pom` for the root, `-jvm`, `-js` and `-linuxx64` artifactIds (Maven Central); V-level metadata of the current `maven-resolver-api` `-SNAPSHOT` from `https://repository.apache.org/content/repositories/snapshots/`. `fixtures/README.md` lists URL and fetch date per file.
  Done when: a fixture test parses every golden file with `maven-repository-metadata` and all KMP `.module` files with a JSON parser.
- [x] T04 Metadata assertions harness
  `MetadataAssertions.assertValidRepository(Path)`: oracle parse, all invariants from design §5 (snapshotVersions → files, versions → dirs, latest ∈ versions, checksums match).
  Done when: passes on a valid fixture tree and fails, with a precise message, on one crafted broken tree per invariant.
- [x] T05 Resolver deployer harness
  `ResolverDeployer` deploys jar, pom, sources, classifier and `.module` artifacts to a `file://` directory with `maven-resolver-supplier`, configurable checksum algorithms.
  Done when: three deploys of the same snapshot produce build numbers 1..3 and a tree that passes `MetadataAssertions`.
- [x] T06 Bare repo and Pages server harness (`PagesServer` removed by T56, D-043)
  `BareRepo` (create on disk, read branch tree, count commits on branch, check out the branch tip into a working tree) and `PagesServer` (embedded Jetty serving a directory as static files).
  Done when: a test commits a file with JGit, checks out the branch tip and downloads the file through `PagesServer`; a missing path returns 404.
- [x] T07 Sequential reference oracle
  `SequentialReference` replays a scripted publication list with `ResolverDeployer`, one after another, into a clean `file://` repo; `TreeNormalizer` (timestamps, build numbers by order, `lastUpdated`/`updated`); `TreeDiff` (file set + normalized metadata, readable report).
  Done when: the same script (2 artifacts, 2 versions, 3 builds each) run twice at different times is equal after normalization; `TreeDiff` reports a crafted missing file, extra version and wrong `latest`; observed `<versions>` ordering recorded in decisions (D-006 confirmed or revised).
- [x] T08 Clean Maven consumer harness
  `MavenConsumer` runs a template consumer project with Maven Verifier, empty local repo, repository URL = a `file://` URL to an exact checkout of the branch (was `PagesServer` until T56).
  Done when: an artifact deployed by `ResolverDeployer`, committed to a bare repo, is resolved by the consumer; a missing artifact makes it fail.
- [x] T09 Concurrency harness (reworked by T55 into `InterleavedPublishScenario`, D-044; [review F1](reviews/2026-10-24-32f382a.md), R08 superseded by D-039)
  `ConcurrentPublishScenario`: N threads, barrier start, mix of shared and distinct coordinates, pluggable publisher; final check with `MetadataAssertions` and "every deployed file present".
  Done when: a deliberately broken publisher (force push, no lease) is detected as losing files, and a global-lock reference publisher passes and matches `SequentialReference` via `TreeDiff`.
- [x] T09b Reposilite E2E profile (removed by T54, D-038)
  Maven profile `e2e`: Testcontainers Reposilite, `ResolverDeployer` deploys over HTTP, `MavenConsumer` resolves from it. CI runs `./mvnw verify -Pe2e` and a step fails the job if the profile executed zero tests.
  Done when: `./mvnw verify -Pe2e` passes locally with Docker; CI green; removing the E2E test makes CI fail.
- ~~[x] T10 Credential leak scanner~~ withdrawn (D-045, removed by T57)
  `LeakScanner` checks captured logs, a directory tree and any Git config for a canary string.
  Done when: self-test detects a planted canary in each location and passes on a clean run.
- [x] T11 JGit learning tests
  Learning tests for: depth-1 fetch over `file://` and over JGit HTTP server, parentless commit push sending only new objects, forced push with expected old id (accepted / `REJECTED_REMOTE_CHANGED`), create-only push with `zeroId`.
  Done when: all learning tests pass and any surprise is written to decisions.

## Plan revision (2026-10-24, D-032..D-038)

- [x] T53 Single Maven module
  Collapse `core/`, `test-fixtures/`, `maven-plugin/` into one root project, packaging `maven-plugin`, artifact `git-maven-publisher-maven-plugin` (D-037).
  Harness to `src/test/java/io/github/ivangsa/gitpublisher/fixtures/` (incl. `review/` tests, moved unchanged), golden files to `src/test/resources/golden/`,
  `src/it/` reserved for invoker ITs. `maven-plugin-plugin` with `skipErrorNoDescriptorsFound` until T25. Architecture test: classes in `io.github.ivangsa.gitpublisher`
  outside `.maven` import no `org.apache.maven` / `org.gradle` (self-test: a planted violation is reported). Update README, CI paths, `.gitignore`. CI moves to `actions/setup-java@v5`.
  Done when: `./mvnw verify` runs every test that passed before the move (same count or more, none skipped); no module directories remain; CI green.
- [x] T54 Remove Reposilite
  Delete `ReposiliteE2E`, the `e2e` profile, Testcontainers, the HTTP constructor of `ResolverDeployer` if nothing else uses it, and the CI `-Pe2e` step and zero-test guard (D-038).
  Done when: no Reposilite/Testcontainers/`e2e` reference remains in code, POM or CI; CI green without Docker.
- [x] T55 Deterministic interleaving harness (D-044)
  Replace `ConcurrentPublishScenario` and its N-thread tests with `InterleavedPublishScenario`: publisher A checks out, publisher B publishes in full
  (checkout, deploy, push), then A deploys and pushes. Pluggable publisher, unique content per publication. Final checks: `MetadataAssertions` on an exact
  branch checkout, every file A and B deployed present with identical content, `TreeDiff` against `SequentialReference` (any order for disjoint coordinates).
  Shared-path variant: B's files untouched and A reports an error. Remove `pushed()`/`pushOrder()`, `overlayWithoutRenumbering`, the barrier/thread machinery and
  `ConcurrentPublishScenarioOrderTest`. Reference publishers: `globalLock` (correct) and `forcePushWithoutLease` (broken).
  Done when: self-tests show the no-lease publisher is caught losing B's files on distinct coordinates, the reference publisher passes and matches the
  sequential reference, and an overwriting publisher fails the shared-path check; decisions record what was removed. Then T09 and R04 go back to `[x]`.
- [x] T56 Remove `PagesServer`
  Delete `PagesServer` and its tests; `MavenConsumer` resolves from a `file://` URL to an exact checkout of the branch (D-043). Keep Jetty only for JGit's HTTP server.
  Done when: no `PagesServer` reference remains; `MavenConsumerTest` resolves a deployed artifact from the checkout and fails on a missing one.
- [x] T57 Remove the leak scanner
  Delete `LeakScanner`, `LeakScannerTest` and the review tests `R05LeakScannerBasicAuthTest` and `R10EncodedFindingRedactionTest` (D-045 records why).
  Done when: no `LeakScanner` or canary reference remains in code; full build passes.

## Phase 1: core + Maven

- [x] T12 Core config
  `PublishConfig` (incl. `nojekyll`, default false), `Credentials`, `RetentionPolicy`, `RetryPolicy`; reject URLs with userinfo.
  Done when: unit tests cover defaults, validation, rejection of URLs with userinfo and of any scheme other than https/file; `Token.toString()` does not contain the token.
- [x] T13 Metadata model
  StAX reader/writer for G/A/V-level metadata.
  Done when: every golden file round-trips (oracle parse of output equals oracle parse of input).
- [x] T14 Checksum writer
  Regenerate `.md5/.sha1/.sha256/.sha512` for a file (present algorithms + configured).
  Done when: output matches checksums Resolver wrote for the same file in `ResolverDeployer` trees.
- ~~T15 Three-way metadata merge~~ removed (D-032): no metadata merge; concurrent changes to one path are a conflict (T21b).
- [x] T16 Retention: keepSnapshots ([revalidation F2](reviews/2026-10-25-be6b417.md), R23)
  Delete old builds per touched `-SNAPSHOT` dir and rewrite V-level metadata + checksums.
  Done when: on Resolver trees with 1..6 builds (incl. classifiers and `.module`), exactly N builds remain and `MetadataAssertions` passes.
- [x] T17 Integrity check ([revalidation F3](reviews/2026-10-25-be6b417.md), R24)
  Production `IntegrityCheck` (design §5 invariants).
  Done when: agrees with `MetadataAssertions` on all harness valid/broken trees.
- [x] T18 Checkout
  `GitPublisher.checkout()`: shallow fetch into `git/`, materialize into `repo/`, `.nojekyll` only when enabled, empty start when branch missing.
  Done when: tests against `BareRepo` with and without branch, with `nojekyll` on and off; no remote URL in `git/config`.
- [x] T19 Change recorder
  Diff `repo/` against `expectedSha` tree → added/modified paths.
  Done when: unit tests for add, modify, unchanged, nested paths, empty base.
- [x] T20 Commit and push with lease (single attempt) (restored via R19)
  Parentless commit from `repo/`, message format, forced push with expected old id. Also moves `refs/publisher/tree` in the same push, without a lease on it (D-020, D-027).
  Done when: after N publishes the branch has exactly 1 commit; stale lease returns a lease-failure result; the second and later pushes send only the new objects. The branch exists without `refs/publisher/tree` (created by hand): the publish succeeds in one attempt and creates the tree ref. A rejected tree ref never fails or retries a publish (D-027).
- [x] T21 Retry loop with independent replay (restored via R15)
  Back-off with jitter; on lease failure re-fetch, re-materialize, copy `ours` for paths the remote did not change, keep paths the remote changed to identical bytes, then retention and integrity again (design §7). Transport errors retried, auth failures fail fast.
  Done when: a competing push on disjoint coordinates between checkout and push ends with both publications present and valid; two first publications on a missing branch both succeed (identical `.nojekyll`); budget exhaustion raises `PublishException`; an auth failure makes exactly one attempt.
- [x] T21b Conflict detection and diagnostics
  A path changed on both sides with different content, or changed by us and deleted by the remote, is a conflict (design §7): `PublishException`, no retry, nothing pushed. The message lists every conflicting path and the external-serialization hint.
  Done when: competing publications of the same `groupId:artifactId` (different versions, and the same snapshot version) end with one published and the other failing with that diagnostic; the remote tree is unchanged by the failed one; two Maven plugins in one groupId touching G-level metadata conflict the same way; no token or URL userinfo in the message.
- ~~T22 Concurrency IT~~ dropped (D-044): the deterministic interleaving tests of T21, T21b and T23 cover the race; no N-thread stress test.
- [x] T23 Differential IT through GitPublisher
  Same script of distinct coordinates through `SequentialReference` (expected) and through interleaved `GitPublisher` checkouts (`InterleavedPublishScenario`) with forced lease failures and overlay replay (actual); retention disabled and enabled.
  Done when: `TreeDiff` reports no difference for every script.
- [x] T24 HTTPS token auth
  `Token` credentials against JGit HTTP server with basic auth; wrong token fails fast without retries.
  Done when: publish succeeds with the right token; a wrong token fails after one attempt; the token is not in `git/config`, the remote URL or the exception message.
- [x] T25 Maven plugin skeleton
  `deploy` mojo, all parameters from design §2, release guard, `skip`.
  Done when: plugin descriptor generated; unit tests for release guard and parameter defaults.
- [x] T26 Reactor collector and trigger (restored via R22; [revalidation F1](reviews/2026-10-25-be6b417.md), R25)
  Participants, check-in, configuration check and guarantee per design §2 (Maven) and D-034. Session-scoped collector, `-T` safe.
  Done when: invoker ITs: 3 modules (one `pom` packaging) produce exactly one commit, with `-T 4` too; a participant with `skip=true` still lets the others publish;
  a failing participant with `--fail-at-end` produces no push; `-pl` on one module publishes only it; mismatched `repository` between modules fails before checkout;
  a trailing non-participating module that fails after the last participant leaves the publication pushed and the build failed (documented behaviour).
- [x] T27 Native deploy into work tree
  One `RepositorySystem.deploy` to `file://repo/`, `checksumAlgorithms`, attached artifacts and `.asc` files.
  Done when: invoker IT (`src/it`) multi-module → bare repo → `MavenConsumer` resolves main jar and sources.
- [x] T28 Maven credentials
  `serverId` (incl. encrypted password) and `GIT_PUBLISHER_TOKEN`.
  Done when: invoker ITs for both sources against the HTTP git server.
- [x] T29 Maven 4 compatibility
  CI matrix runs invoker ITs on the latest Maven 3.10.x and the latest Maven 4.0 (RC until GA).
  Done when: both matrix legs green.
- [x] T30 Property-only configuration
  Publish with `-Dgit-publisher.*` and no `<configuration>`.
  Done when: invoker IT passes.
- [x] T31 Maven user docs (restored via R21)
  README: setup; the two authentication scenarios (self-publication with `GITHUB_TOKEN`; external users with a fine-grained PAT, expiry); serving the branch (raw.githubusercontent.com, Pages by hand with `nojekyll`, any static server);
  serialization precondition (all coordinates of a project, every workflow that can publish them, shared group-level plugin metadata) with an Actions `concurrency` snippet;
  reactor guarantee and the "separate publishing stage" recommendation; retention caveat and the accepted cache risk; GitHub hosting limits note.
  Done when: every snippet in the README is copied from a file under `src/it` (POM, goal line, settings run by ITs); the workflow YAML is validated as a workflow by `actionlint` (T50) and the README says so (D-070).
- [x] T58 Multi-module split (D-075)
  Reactor parent `git-maven-publisher-parent`; modules `core/` (jar `git-maven-publisher-core`: package `io.github.ivangsa.gitpublisher` and its tests, the architecture test),
  `maven-plugin/` (`git-maven-publisher-maven-plugin`: package `...gitpublisher.maven`, its tests, `src/it`), `test-fixtures/` (harness and golden files, not deployed and not in any Central bundle).
  Review tests move with the classes they test, unchanged. Update design §1 and §2, AGENTS.md build line, README, CI and the context packs; Maven 3.10.x and 4.0 legs stay green.
  Done when: `./mvnw verify` runs every test and invoker IT that passed before the split (same count or more, none skipped); no source remains in the root `src/`; CI green.
- [x] T32a Release pipeline, local part (D-072, D-077)
  Central publishing of `git-maven-publisher-parent`, `-core` and `-maven-plugin` (sources, javadoc, signatures, POM metadata Central requires) with `central-publishing-maven-plugin`, `autoPublish` false.
  Release workflow on `workflow_dispatch` only, environment `maven-central-upload`, secrets `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `SIGN_KEY`, `SIGN_KEY_PASS`; reuse `zenwave360/release-workflows` `release-maven.yml` if its inputs fit, else a local workflow with the same secret names.
  No other workflow references `maven-central-upload`. No agent dispatches the workflow, uploads to Central or uses the maintainer's key.
  Done when: a local release build with `skipPublishing` and a throwaway GPG key generated for the check produces the bundle; a check verifies, for each published artifact, jar, sources, javadoc, POM, `.asc` signatures valid for that key and checksums, and the POM fields Central requires (name, description, url, licenses, developers, scm); the release workflow passes `actionlint`.
- [x] T59 Release flow like `zenwave360/release-workflows` (D-078)
  Caller `release-from-notes.yml` for `release-maven.yml` pinned to a full SHA, named secrets; delete the interim `release.yml`. `maven-release-plugin` config per D-078;
  `sign-maven-plugin` with `SIGN_KEY`/`SIGN_KEY_PASS` instead of the bc signer; JaCoCo `jacoco.csv` for `core` and `maven-plugin`; `release-notes/release-notes.v0.1.0.md`;
  `publish-maven-snapshots.yml` caller (verify only). `test-fixtures` stays out of every bundle and of `release:prepare` deploys.
  No agent dispatches a workflow, uploads to Central or uses the maintainer's key (D-072).
  Done when: `actionlint` passes on every workflow; `./mvnw -B release:prepare -DdryRun=true -DreleaseVersion=0.1.0` passes; the T32a bundle check passes on a bundle signed by `sign-maven-plugin` with a throwaway key; `jacoco.csv` exists for both published jar modules after `./mvnw verify`; only `release-from-notes.yml` names `maven-central-upload`.
- [x] T60 Self-publication to `zenwave360/maven-snapshots` (D-083)
  Workflow on push to `develop` (and `workflow_dispatch`): verify; then, in environment `git-publisher-snapshots`, `./mvnw install` and the installed plugin's `deploy` goal by full coordinates
  to `https://github.com/zenwave360/maven-snapshots.git`, branch `main` (D-115; first `gh-pages`), token `GIT_PUBLISHER_TOKEN`, concurrency group `maven-snapshots-io.github.ivangsa`; then a clean consumer job
  (empty local repository) resolves `git-maven-publisher-maven-plugin` and `git-maven-publisher-core` at the pushed snapshot version from `https://www.zenwave360.io/maven-snapshots/` (GitHub Pages; first raw.githubusercontent.com `gh-pages`, D-115).
  The existing `publish-maven-snapshots.yml` (verify through release-workflows) is folded in or kept as the verify job. No step writes the token to disk or logs; only this workflow names `git-publisher-snapshots`.
  Done when: `actionlint` passes; the workflow's commands are the ones an invoker IT already runs (deploy goal by coordinates); the first run on `develop` publishes and the consumer job resolves, reported with the run id.
- [x] T61 Branch model switch (D-083)
  `main` ruleset: pull request required, no bypass, no force push, no deletion. AGENTS.md: agents commit and push to `develop`; `main` changes only through PRs. CI runs on `develop` and on PRs to `main`.
  Done when: a direct push to `main` is rejected and the next agent batch lands on `develop` with CI green.
- [ ] T59b Release prerequisites (manual, maintainer)
  Create `develop` and the orphan `badges` branch; when the repository is public: `main` ruleset requiring PRs with no bypass, environment `maven-central-upload` with a required reviewer and deployment branches `main`, Actions workflow permissions read-only. Then AGENTS.md switches agents to `develop`.
  Done when: the settings above are in place.
- [ ] T32b First Central upload (manual, maintainer)
  Dispatch `release-from-notes.yml` once with version 0.1.0; inspect the deployment in the Central portal; publish or drop it.
  Done when: the Central portal validates the deployment.
- [ ] T33 Dogfooding and Phase 1 exit (manual, maintainer)
  ZenWave360's snapshots with the PAT (D-073) after T32b; this project's own snapshots through T60 into the same repository (D-083). Serving is set up by hand.
  Done when: two weeks of ZenWave360 snapshots resolved from the published branch with no manual fixes.

## Phase 2: Gradle + KMP

- [x] T34 Gradle build skeleton
  `gradle-plugin/` with wrapper, `java-gradle-plugin`, depends on `git-maven-publisher-core` from `mavenLocal()` after `./mvnw install` (D-075), CI step, version sync check.
  Done when: `./gradlew -p gradle-plugin build` passes locally and in CI.
- [x] T35 Extension and repository
  `gitMavenPublisher` extension (design §2), registers `gitPublisher` Maven repository.
  Done when: TestKit test shows the repository and publish tasks for a `java-library` project.
- [x] T36 BuildService and lifecycle tasks ([review 2026-10-25-8439f3c](reviews/2026-10-25-8439f3c.md), F1, R26; [review 2026-10-25-53fd0c7](reviews/2026-10-25-53fd0c7.md), F1, R32)
  `GitPublisherService`, `gitPublisherCheckout`, guarded `gitPublisherPush` per design §2 (Gradle) and D-033; no unconditional finalizer, no publishing in `close()`. Record the mechanism in decisions.
  Done when: TestKit: a multi-project build (3 projects) yields exactly one commit and a valid tree; a failing participant yields no push, also with `--continue`;
  with `--parallel` push never starts before every participant finished; publishing one selected subproject works.
- [x] T37 Configuration cache
  Done when: TestKit runs the T36 scenarios twice with `--configuration-cache`, the second run reuses the cache, and every T36 guarantee holds in both runs.
- [x] T38 Gradle credentials
  `token` property, env var, `gitPublisherToken` Gradle property.
  Done when: TestKit against HTTP git server with each token source; a wrong token fails fast.
- [x] T39 KMP publish
  KMP sample (JVM, JS, one native target) publishes all publications.
  Done when: every expected artifactId has `.module`, POM, metadata; `MetadataAssertions` passes.
- [x] T40 Clean Gradle KMP consumer
  `GradleConsumer` harness (TestKit, empty caches) resolves from a `file://` checkout of the branch.
  Done when: consumer selects the correct variant for JVM, JS and native targets.
- [x] T41 Gradle differential IT
  Expected: KMP publications run sequentially by Gradle `maven-publish` into a clean `file://` repo. Actual: the same publications through the plugin, with a concurrent publisher of other coordinates forcing a lease failure.
  Done when: `TreeDiff` reports no difference.
- [x] T42 Maven and Gradle in one repository
  Done when: IT publishes a Maven project and a Gradle KMP project (distinct coordinates) concurrently into one bare repo; both consumers resolve.
- [x] T43 Gradle version matrix
  CI matrix over the latest Gradle 8.x and the latest 9.x.
  Done when: all legs green.
- [x] T44 Gradle user docs ([review 2026-10-25-8439f3c](reviews/2026-10-25-8439f3c.md), F3, R28; restored by R28)
  Done when: README Gradle snippets are copied from passing TestKit builds.
- [x] T45 Gradle plugin publishing to Maven Central (redefined by the maintainer 2026-10-25: no Plugin Portal account, Q12 closed; D-111)
  `maven-publish` and `signing` in `gradle-plugin/build.gradle`: the plugin jar and its marker with POM metadata, sources and javadoc jars, signed from `SIGN_KEY`/`SIGN_KEY_PASS`, as a Central Portal bundle uploaded by the release job (`USER_MANAGED`, published by hand). Snapshots: the self-publication workflow publishes the jar and marker to `zenwave360/maven-snapshots` with this plugin, and a clean consumer applies the plugin by its id.
  Done when: the bundle built at a release version with a throwaway key passes `scripts/verify-central-bundle.py --gradle-plugin`, `actionlint` passes, and the snapshot publication and its consumer job are green on `develop`.

## Phase 3: hardening

- [x] T47 keepVersions
  Design §6.
  Done when: tests drop oldest snapshot versions per `groupId:artifactId` and A-level metadata passes `MetadataAssertions`.
- [x] T48 Release-aware cleanup
  Drop a `-SNAPSHOT` version once the release exists on a configurable release repo (default Central).
  Done when: IT against a fake release repo served from a `file://` directory (`PagesServer` was removed, D-043).
- [ ] T49 verify goal and task
  Checks branch exists, single commit, `.nojekyll` present when `nojekyll` is enabled, no LFS pointers. Optional: a user-given consumer URL serves metadata.
  Done when: IT passes on a good setup and reports each failure on crafted bad setups.
- [ ] T50 GitHub Actions templates
  Publish workflow with a `concurrency` group covering every coordinate of the project (PAT and `GITHUB_TOKEN` variants). No Pages deployment template (D-043).
  Done when: templates linted with `actionlint` in CI and used by the dogfood repo.
- [-] T51 Nightly end-to-end (dropped: Q7 answered 2026-10-25, releases on demand, no nightly; D-111)
  Was: scheduled workflow publishing to a real public GitHub repo. The self-publication consumer jobs (T60, T45) are the end-to-end check on every push to `develop`.
- [ ] T52 Guide: publishing with a GitHub App token in GitHub Actions
  Docs page: create the App (`Contents: write`), install it on the snapshots repo, mint the token with `actions/create-github-app-token`, pass it as `GIT_PUBLISHER_TOKEN`; compare with fine-grained PAT and `GITHUB_TOKEN`.
  Done when: the workflow snippet in the guide is the one used by T50's template and passes `actionlint`.

## Review findings

From [review 2026-10-24-f65192f](reviews/2026-10-24-f65192f.md) (end of Phase 0, verdict FIX FIRST). All before B04.
Review tests live in `test-fixtures/src/test/java/io/github/ivangsa/gitpublisher/fixtures/review/` (moved to `src/test/java/io/github/ivangsa/gitpublisher/fixtures/review/` by T53).

- [x] R01 Build order by build number; `<snapshot>` invariant (critical, F1)
  Design §5/§6: order builds by build number (tie-break timestamp), not by timestamp; merge picks per key by the same order.
  Add the §5 invariant "`<snapshot>` names a build with files in the directory" to `MetadataAssertions`. Decision entry.
  Done when: `R01SnapshotElementInvariantTest` passes; design and decisions updated.
- [x] R02 `ResolverDeployer` stamps like one Maven session (F2)
  Set `maven.startTime` per deployer session; allow an explicit start time; `ConcurrentPublishScenario` takes it at the barrier.
  Done when: `R02ResolverDeployerSessionStartTimeTest` passes; a harness self-test deploys "started earlier, deployed later" and records the result in decisions.
- [x] R03 Tree ref is a hint, not a lease (design only, F3)
  Design §4.5 and D-020: push `refs/publisher/tree` without a lease on it (or with the fetched id) and ignore its status; only the branch status decides OK/retry. Extend T20 "Done when" with: branch exists without the tree ref → publish succeeds in one attempt.
  Done when: design, decisions and T20 updated.
- [x] R04 Same-snapshot concurrency: build number collisions and reference order (F4; [revalidation F1](reviews/2026-10-24-32f382a.md)) (renumbering and push order superseded by D-032; harness reworked and claim restored by T55)
  Design §7: replay renumbers a colliding build to remote max + 1 (or another recorded decision). Harness: unique content per publication in `ConcurrentPublishScenario`; record push order and replay that order in `assertMatchesSequentialReference`.
  Done when: design/decisions updated; a harness self-test shows two same-second publications of one snapshot are no longer counted as "nothing lost".
- [x] R05 LeakScanner detects encoded tokens; e2e profile hygiene (F5, F8) (withdrawn with the scanner, D-045, T57)
  Base64 (basic auth, three alignments) and URL-encoded canary in text, trees and Git config. E2E as a separate failsafe execution so `*IT` still runs under `-Pe2e`; CI guard counts `tests - skipped`.
  Done when: `R05LeakScannerBasicAuthTest` passes.
- [x] R06 TreeNormalizer normalizes `.module` contents (minor, F6)
  Done when: `R06TreeNormalizerModuleFileTest` passes.
- [x] R07 Version directory must not be empty (minor, F7)
  Done when: `R07EmptyVersionDirectoryTest` passes.

From [review 2026-10-24-32f382a](reviews/2026-10-24-32f382a.md) (Phase 0 revalidation, verdict FIX FIRST). All before B04.

- ~~R08 Accept valid collision renumbering in concurrency loss checks (major, F1)~~ superseded by D-032/D-039: renumbering is no longer
  part of the design, so `R08RenumberedPublicationTest` specified removed behaviour and was deleted with the plan revision. Its remaining intent
  (collapsed publications, wrong content and loss still detected) is in T55, which restores T09/R04.
- [x] R09 Materialize an exact branch checkout on reused destinations (major, F2)
  Remove stale paths and handle file/directory transitions when checking out the branch tip.
  Done when: `R09ExactBranchCheckoutTest` passes, Pages serves only current branch files, and existing checkout/404 checks pass. Restore T06 only after the full build passes.
- [x] R10 Redact encoded credentials from scanner diagnostics (minor, F3) (withdrawn with the scanner, D-045, T57)
  Cover source labels and entry paths as well as raw canaries; diagnostics must not echo reversible encoded forms.
  Done when: both `R10EncodedFindingRedactionTest` cases and all prior scanner tests pass. Restore T10/R05 only after the full build passes.

From [review 2026-10-24-9150059](reviews/2026-10-24-9150059.md) (plan revision + retention, verdict FIX FIRST). All before B07.
Review tests live in `src/test/java/io/github/ivangsa/gitpublisher/review/` (R11 is in the core package because the classes under test are package-private).

- [x] R11 Keep prefixed attribute names when rewriting metadata (major, F1)
  `XmlNode` must write back qualified attribute names and namespace declarations; a namespaced round-trip case in the T13 tests.
  Done when: `R11NamespacedMetadataRewriteTest` passes.
- [x] R12 Checksum algorithms and `nojekyll` in the core config; document fixes (major, F2, F4)
  Design §3 and a decision: `PublishConfig` carries the configured checksum algorithms (non-empty, default SHA-1, MD5) and `nojekyll`;
  T20/T21 pass them to `Retention` and `IntegrityCheck`. Fix T48 (`PagesServer`), mark D-038 "Revised by D-043, D-045".
  Done when: unit tests for the default and the validation; design, decisions and tasks updated.
- [x] R13 Reject credentials in registry-based URL authorities (minor, F3)
  Done when: `R13RegistryAuthorityUserInfoTest` passes and the existing `PublishConfigTest` cases still pass.
- [x] R14 Interleaving harness: require the interleaving for "both published" (minor, F5)
  An assertion that fails when the publisher never reached `Publication.checkedOut()`, used by the T21/T23 tests; the lock reference keeps its path.
  Done when: a self-test shows a non-interleaving publisher fails the new assertion and `replayOrFail` passes it.

From [review 2026-10-24-f9bb6b5](reviews/2026-10-24-f9bb6b5.md) (lease + retry + replay + conflicts, verdict FIX FIRST). All before B11.
Review tests live in `src/test/java/io/github/ivangsa/gitpublisher/review/` (R15 and R16 are in the core package because the classes under test are package-private).

- [x] R15 Classify auth failures by status, not by words in the message (major, F1)
  A 403 (`serviceNotPermitted`) fails fast like a 401; the URL inside the JGit message never decides. Cover 401 and 403 from a real `GitServlet`. Restore T21 after the full build passes.
  Done when: `R15AuthFailureClassificationTest` passes.
- [x] R16 A permanent push rejection fails fast (minor, F2)
  Branch statuses other than OK, UP_TO_DATE and REJECTED_REMOTE_CHANGED are fatal with the remote's reason and a force-push hint; design §4 and a decision.
  Done when: `R16PermanentPushRejectionTest` passes.
- [x] R17 Clear the token on close (minor, F3)
  Clear JGit credentials providers after each operation and the token on `Publication.close()`, or amend design §8 with a decision naming the owner.
  Done when: `R17TokenClearedOnCloseTest` passes (or is updated with that decision).
- [x] R18 A failed publication moves no ref (minor, F4)
  Move `refs/publisher/tree` only after the branch update succeeded, or amend design §4/§7 and D-027 and reword "nothing was pushed".
  Also add the T21b tests from F5 (remote deletes a path we changed; same-snapshot conflict with pinned start times and exact paths).
  Done when: `R18FailedPublicationMovesNoRefTest` passes (or is updated with that decision).

From [review 2026-10-25-0701e5d](reviews/2026-10-25-0701e5d.md) (end of Phase 1 code, verdict FIX FIRST). All before B14.
Review tests live in `src/test/java/io/github/ivangsa/gitpublisher/review/` (core package, as R15-R18).

- [x] R19 A refused tree hint must not fail the publication (major, F1)
  Since the atomic push (D-060), a remote that refuses `refs/publisher/tree` refuses the branch too and the publication fails as a
  "permanent rejection". Keep "a failed publication moves no ref" without letting the hint fail the branch (for example a branch-only
  retry with the lease when the atomic push was refused for a reason other than the lease), or amend design §4.6, D-027 and T20 with a decision.
  Done when: `R19RejectedTreeHintTest` passes. Restore T20 after the full build passes.
- [x] R20 Decide the atomic fallback on JGit's condition, not on the URL (minor, F2)
  Done when: both tests of `R20AtomicFallbackTest` pass.
- [x] R21 README snippets proven by ITs; settings and Maven 4 wording (minor, F3, F5)
  Either the readme-workflow IT runs the `run:` command of `publish.yml` (repository and token source substituted), or a decision amends
  T31's "Done when" for workflow YAML (validated by T50) and the README says so. No test-only comment or test ciphertext presented as an example
  in the settings snippet; the `-Dsettings.security` sentence only claims what is shown.
  Done when: `ReadmeSnippetsTest` and the ITs pass with the chosen rule written in decisions. Restore T31 after the full build passes.
- [x] R22 Repository mismatch IT; design §2 matches the participant and configuration rules (minor, F4)
  Invoker IT with a module that sets a different `<repository>`: build fails before checkout, nothing pushed. Design §2 describes the full
  settings comparison and the command-line rule of D-063.
  Done when: the IT passes and design §2 is updated. Restore T26 after the full build passes.

From [review 2026-10-25-be6b417](reviews/2026-10-25-be6b417.md) (all committed Phase 1 code, verdict FIX FIRST).
Review performed on `review/phase1-be6b417`; uncommitted BP4/T59 work is excluded.

- [x] R23 Recognize snapshot builds after the complete artifactId (major, F2)
  Anchor filename recognition to the canonical artifactId/base-version prefix; preserve unrelated files.
  Done when: both `R23RetentionFilenameBoundaryTest` regressions pass and the full build passes. Restore T16.
- [x] R24 Reject duplicate metadata fields that Maven cannot parse (minor, F3)
  Keep core free of Maven runtime dependencies and preserve the namespace/unknown-element contracts.
  Done when: `R24IntegrityOracleParsingTest` passes, prior metadata tests pass and the full build passes. Restore T17.
- [x] R25 Prevent an early push when one project binds multiple deploy executions (major, F1)
  Count all participating executions or reject repeated bindings before checkout/push; keep existing reactor guarantees.
  Done when: `review-repeated-execution` and its `R25RepeatedExecutionReview` no-push assertion pass, all existing
  participant/skip/parallel/failure ITs pass and the full build passes. Restore T26.

From [review 2026-10-25-8439f3c](reviews/2026-10-25-8439f3c.md) (end of Phase 2, verdict FIX FIRST).
Review test: `gradle-plugin/src/test/java/io/github/ivangsa/gitpublisher/gradle/review/R26LateParticipantFailureReviewTest.java`.

- [x] R26 A participant that fails after its publish action must not be pushed (major, F1; [review 2026-10-25-53fd0c7](reviews/2026-10-25-53fd0c7.md), F1, R32)
  Record a participant's success only when the task as a whole succeeded (no action of the task may run after the record); configuration cache kept.
  Done when: `R26LateParticipantFailureReviewTest` passes (default and `--continue`), all lifecycle and configuration-cache tests pass and the full build passes. Restore T36.
- [x] R27 Publish the Gradle plugin only after core is on Central (major, F2; gate replaced by a publication order in the Central Portal, D-111)
  `release-from-notes.yml` job `gradle-plugin` runs while the Central deployment (`autoPublish` false) is unpublished. Gate `publishPlugins` on core X being
  resolvable from Central (check step that fails, or a separate maintainer-dispatched step after the Central publish); fix the workflow comment; record in decisions.
  Done when: `actionlint` passes and the workflow cannot reach `publishPlugins` while `git-maven-publisher-core:X` is absent from Central.
- [x] R28 README Gradle snippet test independent of line endings (minor, F3)
  Normalize both sides in `ReadmeGradleSnippetsTest` or pin `gradle-plugin/src/test/resources/readme/build.gradle` to `eol=lf`; record the test edit in decisions.
  Done when: the Gradle build passes in a fresh Windows checkout with `core.autocrlf=true` and the full build passes. Restore T44.
- [x] R29 Skipped Gradle participant: test and decide (minor, F4)
  TestKit test for a participant skipped with `onlyIf { false }`: nothing pushed and the build fails naming the task (or, if kept green, the README states the limit; decision either way).
  Done when: the test passes and the behaviour is in decisions and README.
- [x] R30 Documentation conflicts (minor, F5, F6)
  README: Gradle 8.x stores `-P` values in the configuration cache entry (D-096); the generic self-publication snippet uses a placeholder concurrency group, not
  `maven-snapshots-io.github.ivangsa` (keep `ReadmeSnippetsTest` and its copy source in step); bring review 470075b (`docs/reviews/2026-10-25-d441064.md`) onto `develop`.
  Done when: README and decisions updated, `ReadmeSnippetsTest` and `ReadmeGradleSnippetsTest` pass.
- [x] R31 Self-publication consumer checks the build just pushed (minor, F7)
  The consumer job compares the resolved snapshot (timestamp/build number in `maven-metadata.xml` or the pushed commit) with the `publish` job's output.
  Done when: `actionlint` passes and the job fails when the branch still serves an older build of the version.

From [review 2026-10-25-53fd0c7](reviews/2026-10-25-53fd0c7.md) (re-review of BR9, BR10, verdict FIX FIRST).
Review test: `gradle-plugin/src/test/java/io/github/ivangsa/gitpublisher/gradle/review/R32LateWhenReadyActionReviewTest.java`.

- [x] R32 A participant action added in a later `taskGraph.whenReady` must not run after the success record (major, F1; [review 2026-10-25-66f46ec](reviews/2026-10-25-66f46ec.md), F1, R33)
  The record must fail closed: a participant whose last action is not the record (for example, `RecordSuccess` checks `task.getActions()` at
  execution time, with one record action per participant instead of one per project applying the plugin) stays unrecorded, so D-098 refuses
  the push; or another mechanism that sees the task's final outcome before the finalizer. Configuration cache kept. Correct the D-097 claim.
  Done when: `R32LateWhenReadyActionReviewTest` passes (default, `--continue`, `--configuration-cache`), `R26LateParticipantFailureReviewTest` and all
  lifecycle and configuration-cache tests pass and the full build passes. Restore T36 and R26.

From [review 2026-10-25-66f46ec](reviews/2026-10-25-66f46ec.md) (re-review of BR11, verdict FIX FIRST).
Review test: `gradle-plugin/src/test/java/io/github/ivangsa/gitpublisher/gradle/review/R33CountPreservingLateActionReviewTest.java`.

- [x] R33 The success record must check that it is the last action, not only the action count (minor, F1)
  A later `whenReady` that removes one action and adds a failing `doLast`, or wraps the record via `setActions`, keeps the count and still pushes.
  For example, add the record as a named action (`doLast("<unique name>", ...)`) and, at execution time, require both the stored count and that the last
  element of `task.getActions()` is a `org.gradle.api.Describable` with the record's display name; otherwise fail closed. Configuration cache kept. Correct D-103.
  Done when: `R33CountPreservingLateActionReviewTest` passes (5 cases), R32, R26, `LateActionFailsClosedTest` and all lifecycle and configuration-cache tests pass,
  and the full build passes. Restore R32.
- [x] R34 README: late actions fail the build; D-097 Limit revised (minor, F2)
  README Gradle section states that publication tasks must not get actions after the task graph is ready and that any such action, even a successful one,
  fails the build with nothing pushed (D-103). Mark the D-097 "Limit" bullet as revised by D-103.
  Done when: README and decisions updated, `ReadmeGradleSnippetsTest` passes.
- [x] R35 Composite builds: decide one publication or one per build (minor, F3; Q13 answered A: one publication per build, docs only, D-111)
  An included build's failed participant does not stop the root build's push (two services, two pushes). Ask the maintainer in docs/questions.md; record the
  answer in design section 2, decisions and README; if one publication per invocation, make the push fail closed across included builds with a TestKit test.
  Done when: the decision is recorded and, if behaviour changes, its test passes with the full build.

From [review 2026-10-25-250f684](reviews/2026-10-25-250f684.md) (Phase 3 retention, B19, verdict FIX FIRST). All before B20.
Review tests: `core/src/test/java/io/github/ivangsa/gitpublisher/review/R36KeepVersionsKeepsTheVersionJustPublishedReviewTest.java`,
`core/src/test/java/io/github/ivangsa/gitpublisher/review/R37ReleaseCheckOverHttpsReviewTest.java`.

- [x] R36 keepVersions never drops a version touched by this publication (major, F1)
  Maven's V-level `lastUpdated` is the session start, so a long build deployed last can rank oldest and its own version is deleted, then pushed.
  Touched versions are always kept and count toward N; only untouched versions, ranked by `lastUpdated`, are dropped. Update design §6 and D-106.
  Done when: `R36KeepVersionsKeepsTheVersionJustPublishedReviewTest` and `VersionRetentionTest` pass and the full build passes.
- [x] R37 The https release check treats a redirect to an HTML page as doubt (minor, F3)
  `followRedirects(NORMAL)` plus "final status 200" makes a login/landing page look like a release POM. Keep following redirects (CDNs), but a final
  `text/html` response keeps the version. Record in decisions (the https branch now has a test; D-043's reason does not apply to our own client).
  Done when: `R37ReleaseCheckOverHttpsReviewTest` passes and the full build passes.
- [x] R38 Maven and Gradle expose the release repository (major, F2)
  Maven `git-publisher.releaseRepository`, Gradle `releaseRepository` (URI, off by default; Central available by name or URL), passed to `RetentionPolicy`,
  part of the per-build consistency checks (`PublisherParameters` differing list, Gradle shared-service parameters), README entry. Closes the D-106 gap.
  Done when: an invoker IT and a TestKit test drop a released `-SNAPSHOT` version against a `file://` release repository, and the full build passes.
- [x] R39 Release check: one client, default proxy, answers kept across attempts, a warning on failure (minor, F4)
  One `HttpClient` per `Retention.apply` with `ProxySelector.getDefault()`; remember answers per version for the attempts of one publication; one warning
  (URL without query) when a check fails for a reason other than 404.
  Done when: a unit test shows a second attempt makes no new check for the same version, and the full build passes.
- [x] R40 Design §6 wording: whole-directory drops (minor, F5)
  "Non-timestamped files are never deleted by retention" applies to build retention only; keepVersions and release cleanup delete whole version directories.
  Done when: design §6 updated.

From [review 2026-10-25-3044006](reviews/2026-10-25-3044006.md) (committed-code validation, verdict FIX FIRST).

- [x] R41 Maven Wrapper runs on native Windows (critical, F1; D-108)
  The committed `mvnw.cmd` indexes the null `DirectoryInfo.Target` property while determining its distribution directory, so every
  Codex-prescribed native-Windows wrapper invocation fails before Maven starts. Update the wrapper to handle a normal non-link
  `.m2` directory and prove `mvnw.cmd --batch-mode --no-transfer-progress -s C:/Users/ivangsa/.codex/maven/settings.xml verify`
  passes from a clean checkout.

From [review 2026-10-25-20d7325](reviews/2026-10-25-20d7325.md) (re-review of BR13 and BR14, verdict FIX FIRST).
Review test: `core/src/test/java/io/github/ivangsa/gitpublisher/review/R42ReleaseRepositoryQueryReviewTest.java`.

- [x] R42 A release repository URL with a query or fragment never makes the repository root count as a release (minor, F1)
  `ReleaseCheck.exists` appends the POM path to the whole URL string, so `?sig=...` or `#x` sends `HEAD <repo root>`; a non-HTML 200 there
  drops every untouched `-SNAPSHOT` version. Reject such URLs in `RetentionPolicy` (message without the value) or resolve the POM path against
  the URL's path; record the choice in decisions.
  Done when: `R42ReleaseRepositoryQueryReviewTest` passes and the full build passes.

From [review 2026-10-25-a3707d0](reviews/2026-10-25-a3707d0.md) (re-review of BR15, BL1 and BR16, verdict FIX FIRST).
Review test: `core/src/test/java/io/github/ivangsa/gitpublisher/review/R43ReleaseCheckPathSegmentReviewTest.java`.

- [x] R43 The release check asks exactly the POM path, whatever the directory names (minor, F1; [review 2026-10-25-03ebe01](reviews/2026-10-25-03ebe01.md), F1)
  `ReleaseCheck.exists` appends artifactId and base version (directory names of the tree) to the URL string unencoded; a `?` in a name sends the request to
  another resource (`HEAD /maven2/org/acme/lib/1.0` for `1.0?rc-SNAPSHOT`), and a non-HTML 200 there deletes the version. Percent-encode each path segment, or
  answer "no" without a request for a segment with URI-reserved characters; record the choice in decisions.
  Done when: `R43ReleaseCheckPathSegmentReviewTest` passes and the full build passes.
- [x] R44 Correct the GitHub Pages statement of D-111 and the snapshots workflow comment (minor, F2)
  Pages serves the branch at `https://www.zenwave360.io/maven-snapshots/` (`zenwave360.github.io` redirects there); `www.zenwave.io` was a wrong URL, not a Pages fault.
  Fix the D-111 correction note and the comment in `publish-maven-snapshots.yml`. If the maintainer wants the Pages URL in the README snippet, change
  `gradle-plugin/src/test/resources/readme/settings.gradle` and `SNAPSHOTS_SITE` in `ReadmeGradleSnippetsTest` with it.
  Done when: decisions and the workflow comment are correct, `actionlint` passes and the full build passes.
- [x] R45 Show Central's answer when the Gradle plugin upload fails, and mask the derived bearer (minor, F3)
  `release-from-notes.yml` upload step: on a curl failure print `deployment-id.txt` (the response body) to stderr before exiting non-zero; add
  `::add-mask::` for the base64 bearer before the curl call.
  Done when: `actionlint` passes and the step prints the body on a non-2xx answer (shown with a local run of the step's script against a stub server).

From [review 2026-10-25-03ebe01](reviews/2026-10-25-03ebe01.md) (focused re-review of BR17, verdict FIX FIRST).
Review test: `core/src/test/java/io/github/ivangsa/gitpublisher/review/R46ReleaseCheckValidMavenVersionReviewTest.java`.

- [x] R46 Preserve release-aware cleanup for Maven-valid URI-safe versions (minor, F1)
  R43's ASCII allow-list rejects Maven-valid versions such as `1.0@rc`, so `ReleaseCheck` returns doubt without asking
  the POM path and release-aware cleanup retains `1.0@rc-SNAPSHOT` forever. Reject only characters that could alter
  the URL path, or percent-encode every valid path segment; retain R43's no-request behavior for `?` and `#`.
  Done when: `R43ReleaseCheckPathSegmentReviewTest` and `R46ReleaseCheckValidMavenVersionReviewTest` pass, then the full build passes.

From [review 2026-10-25-4de4033](reviews/2026-10-25-4de4033.md) (re-review of BR17 and BR18, verdict FIX FIRST).
Review test: `core/src/test/java/io/github/ivangsa/gitpublisher/review/R47ReleaseCheckPathParameterReviewTest.java`.

- [x] R47 The release check quotes `;` so a servlet-container repository cannot drop it as a path parameter (minor, F1)
  The URI constructor leaves `;` literal; Tomcat/Jetty (Nexus, Artifactory) strip `;...` from a segment, so the group directory `acme;rc` asks
  `org/acme;rc/lib/1.0/lib-1.0.pom`, the server answers the POM of `org.acme:lib:1.0`, and the unreleased `1.0-SNAPSHOT` of `org.acme;rc` is deleted.
  Quote `;` as `%3B` in every segment, or answer "no" without a request for a segment containing it; correct D-113's "exactly one segment" claim.
  Done when: `R47ReleaseCheckPathParameterReviewTest`, R43 and R46 pass, then the full build passes.

From [review 2026-10-25-5dc5581](reviews/2026-10-25-5dc5581.md) (re-review of BR19, verdict FIX FIRST).
Review test: `core/src/test/java/io/github/ivangsa/gitpublisher/review/R48ReleaseCheckPlusSignReviewTest.java`.

- [x] R48 The release check quotes `+` so an S3/CloudFront release repository is asked for the right key (minor, F1)
  S3 decodes a literal `+` in the path as a space, so `1.0+build` asks for `.../1.0 build/lib-1.0 build.pom`, gets 404 and the released snapshot is kept forever.
  Quote `+` as `%2B` in every segment (like `;`, D-114) and correct D-114's claim that only `;` has a path meaning on common servers.
  Done when: `R48ReleaseCheckPlusSignReviewTest`, R43, R46 and R47 pass, then the full build passes.
- [x] R49 The self-publication writes `.nojekyll`, as the README tells Pages users (minor, F2)
  D-115 serves this project's snapshots through Pages from `main`, but neither the Maven deploy step nor `gradle-plugin/self-publish.init.gradle` enables `nojekyll`; the branch has no `.nojekyll`.
  Set `-Dgit-publisher.nojekyll=true` and `nojekyll = true`, or record in D-115 why Jekyll runs on the branch.
  Done when: both publish commands enable `nojekyll` (or D-115 records the reason), `actionlint` passes, and the full build passes.
- [x] R50 D-115 revises D-083's "never `main`" rule explicitly (minor, bookkeeping, F3)
  Mark D-083 (branch, "never `main`"), D-102 (clone of `gh-pages`, raw URL) and D-111 (branch `gh-pages`) as revised by D-115; record that replacing `main`'s history is accepted.
  Fix "Never main." in `.github/workflows/publish-maven-snapshots.yml` (header) and the branch/URL in the T60 text.
  Done when: no document or workflow comment says this project publishes to `gh-pages` or never to `main`, and the full build passes.
