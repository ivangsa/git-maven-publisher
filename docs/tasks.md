# Tasks

**Status:** Review 2026-10-24-9150059 FIX FIRST: R11–R14 open (before B07) · Last finished: T16 (B06) · Next: R11–R14, then B07 (T18, T19)

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
| B04 | T12, T13, T14, T17 | implementer | core | | done |
| ~~B05~~ | ~~T15~~ removed (D-032) | - | - | | removed |
| B06 | T16 (retention) | implementer | core | yes (plan revision + retention) | done |
| B07 | T18, T19 | implementer | core | | todo |
| B08 | T20 (lease) | implementer | core | | todo |
| B09 | T21 (retry + independent replay) | implementer | core | | todo |
| B10 | T21b (conflict detection) | implementer | core | yes (lease + retry + replay + conflicts) | todo |
| B11 | T23, T24 (T22 dropped, D-044) | implementer | core, testing | | todo |
| B12 | T25, T26, T27, T28 | implementer | maven-plugin | | todo |
| B13 | T29, T30, T31 | mechanic | maven-plugin | yes (end of Phase 1 code) | todo |
| B14 | T32 | mechanic | maven-plugin | | blocked (Q4) |
| B15 | T34, T35, T36, T37 | implementer | gradle-plugin | | blocked (Q10) |
| B16 | T38, T39, T40 | implementer | gradle-plugin, testing | | todo |
| B17 | T41, T42 | implementer | gradle-plugin, testing | | todo |
| B18 | T43, T44, T45 | mechanic | gradle-plugin | yes (end of Phase 2) | todo |
| B19 | T47, T48 (retention) | implementer | core | yes (retention) | todo |
| B20 | T49 | implementer | maven-plugin, gradle-plugin | | todo |
| B21 | T50, T51, T52 | mechanic | testing | yes (end of Phase 3) | todo (T51 needs Q7) |
| manual | T33 | maintainer | - | | blocked (Q2, Q9); not dispatched to an agent |

Legend: `[ ]` todo · `[x]` done · `[?]` blocked on a question in `docs/questions.md` · `[!]` failed review verification (see linked review).
Every "Done when" assumes the full build passes (`./mvnw verify`, plus `./gradlew -p gradle-plugin build` once it exists), per the done rule in AGENTS.md.

## Phase 0: test harness (no production code)

- [x] T00 Project plan
  Brief, design, tasks, decisions, questions, CLAUDE.md.
  Done when: files committed as `T00: project plan`.
- [x] T01 Build skeleton
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
- [x] T16 Retention: keepSnapshots
  Delete old builds per touched `-SNAPSHOT` dir and rewrite V-level metadata + checksums.
  Done when: on Resolver trees with 1..6 builds (incl. classifiers and `.module`), exactly N builds remain and `MetadataAssertions` passes.
- [x] T17 Integrity check
  Production `IntegrityCheck` (design §5 invariants).
  Done when: agrees with `MetadataAssertions` on all harness valid/broken trees.
- [ ] T18 Checkout
  `GitPublisher.checkout()`: shallow fetch into `git/`, materialize into `repo/`, `.nojekyll` only when enabled, empty start when branch missing.
  Done when: tests against `BareRepo` with and without branch, with `nojekyll` on and off; no remote URL in `git/config`.
- [ ] T19 Change recorder
  Diff `repo/` against `expectedSha` tree → added/modified paths.
  Done when: unit tests for add, modify, unchanged, nested paths, empty base.
- [ ] T20 Commit and push with lease (single attempt)
  Parentless commit from `repo/`, message format, forced push with expected old id. Also moves `refs/publisher/tree` in the same push, without a lease on it (D-020, D-027).
  Done when: after N publishes the branch has exactly 1 commit; stale lease returns a lease-failure result; the second and later pushes send only the new objects. The branch exists without `refs/publisher/tree` (created by hand): the publish succeeds in one attempt and creates the tree ref. A rejected tree ref never fails or retries a publish (D-027).
- [ ] T21 Retry loop with independent replay
  Back-off with jitter; on lease failure re-fetch, re-materialize, copy `ours` for paths the remote did not change, keep paths the remote changed to identical bytes, then retention and integrity again (design §7). Transport errors retried, auth failures fail fast.
  Done when: a competing push on disjoint coordinates between checkout and push ends with both publications present and valid; two first publications on a missing branch both succeed (identical `.nojekyll`); budget exhaustion raises `PublishException`; an auth failure makes exactly one attempt.
- [ ] T21b Conflict detection and diagnostics
  A path changed on both sides with different content, or changed by us and deleted by the remote, is a conflict (design §7): `PublishException`, no retry, nothing pushed. The message lists every conflicting path and the external-serialization hint.
  Done when: competing publications of the same `groupId:artifactId` (different versions, and the same snapshot version) end with one published and the other failing with that diagnostic; the remote tree is unchanged by the failed one; two Maven plugins in one groupId touching G-level metadata conflict the same way; no token or URL userinfo in the message.
- ~~T22 Concurrency IT~~ dropped (D-044): the deterministic interleaving tests of T21, T21b and T23 cover the race; no N-thread stress test.
- [ ] T23 Differential IT through GitPublisher
  Same script of distinct coordinates through `SequentialReference` (expected) and through interleaved `GitPublisher` checkouts (`InterleavedPublishScenario`) with forced lease failures and overlay replay (actual); retention disabled and enabled.
  Done when: `TreeDiff` reports no difference for every script.
- [ ] T24 HTTPS token auth
  `Token` credentials against JGit HTTP server with basic auth; wrong token fails fast without retries.
  Done when: publish succeeds with the right token; a wrong token fails after one attempt; the token is not in `git/config`, the remote URL or the exception message.
- [ ] T25 Maven plugin skeleton
  `deploy` mojo, all parameters from design §2, release guard, `skip`.
  Done when: plugin descriptor generated; unit tests for release guard and parameter defaults.
- [ ] T26 Reactor collector and trigger
  Participants, check-in, configuration check and guarantee per design §2 (Maven) and D-034. Session-scoped collector, `-T` safe.
  Done when: invoker ITs: 3 modules (one `pom` packaging) produce exactly one commit, with `-T 4` too; a participant with `skip=true` still lets the others publish;
  a failing participant with `--fail-at-end` produces no push; `-pl` on one module publishes only it; mismatched `repository` between modules fails before checkout;
  a trailing non-participating module that fails after the last participant leaves the publication pushed and the build failed (documented behaviour).
- [ ] T27 Native deploy into work tree
  One `RepositorySystem.deploy` to `file://repo/`, `checksumAlgorithms`, attached artifacts and `.asc` files.
  Done when: invoker IT (`src/it`) multi-module → bare repo → `MavenConsumer` resolves main jar and sources.
- [ ] T28 Maven credentials
  `serverId` (incl. encrypted password) and `GIT_PUBLISHER_TOKEN`.
  Done when: invoker ITs for both sources against the HTTP git server.
- [ ] T29 Maven 4 compatibility
  CI matrix runs invoker ITs on the latest Maven 3.10.x and the latest Maven 4.0 (RC until GA).
  Done when: both matrix legs green.
- [ ] T30 Property-only configuration
  Publish with `-Dgit-publisher.*` and no `<configuration>`.
  Done when: invoker IT passes.
- [ ] T31 Maven user docs
  README: setup; the two authentication scenarios (self-publication with `GITHUB_TOKEN`; external users with a fine-grained PAT, expiry); serving the branch (raw.githubusercontent.com, Pages by hand with `nojekyll`, any static server);
  serialization precondition (all coordinates of a project, every workflow that can publish them, shared group-level plugin metadata) with an Actions `concurrency` snippet;
  reactor guarantee and the "separate publishing stage" recommendation; retention caveat and the accepted cache risk; GitHub hosting limits note.
  Done when: every snippet in the README is copied from a passing IT.
- [ ] T32 Release pipeline
  Central publishing (sources, javadoc, signing) of the single `git-maven-publisher-maven-plugin` artifact, release workflow. (Needs Q4.)
  Done when: a dry-run release produces signed bundles that pass Central validation.
- [ ] T33 Dogfooding and Phase 1 exit (manual, maintainer)
  After a usable bootstrap version exists: publish ZenWave360's snapshots with a PAT (Q2), and this project's own snapshots with `GITHUB_TOKEN` (Q9). Serving is set up by hand.
  Done when: two weeks of ZenWave360 snapshots resolved from the published branch with no manual fixes.

## Phase 2: Gradle + KMP

- [ ] T34 Gradle build skeleton
  `gradle-plugin/` with wrapper, `java-gradle-plugin`, core reuse as decided in Q10, CI step, version sync check.
  Done when: `./gradlew -p gradle-plugin build` passes locally and in CI.
- [ ] T35 Extension and repository
  `gitMavenPublisher` extension (design §2), registers `gitPublisher` Maven repository.
  Done when: TestKit test shows the repository and publish tasks for a `java-library` project.
- [ ] T36 BuildService and lifecycle tasks
  `GitPublisherService`, `gitPublisherCheckout`, guarded `gitPublisherPush` per design §2 (Gradle) and D-033; no unconditional finalizer, no publishing in `close()`. Record the mechanism in decisions.
  Done when: TestKit: a multi-project build (3 projects) yields exactly one commit and a valid tree; a failing participant yields no push, also with `--continue`;
  with `--parallel` push never starts before every participant finished; publishing one selected subproject works.
- [ ] T37 Configuration cache
  Done when: TestKit runs the T36 scenarios twice with `--configuration-cache`, the second run reuses the cache, and every T36 guarantee holds in both runs.
- [ ] T38 Gradle credentials
  `token` property, env var, `gitPublisherToken` Gradle property.
  Done when: TestKit against HTTP git server with each token source; a wrong token fails fast.
- [ ] T39 KMP publish
  KMP sample (JVM, JS, one native target) publishes all publications.
  Done when: every expected artifactId has `.module`, POM, metadata; `MetadataAssertions` passes.
- [ ] T40 Clean Gradle KMP consumer
  `GradleConsumer` harness (TestKit, empty caches) resolves from a `file://` checkout of the branch.
  Done when: consumer selects the correct variant for JVM, JS and native targets.
- [ ] T41 Gradle differential IT
  Expected: KMP publications run sequentially by Gradle `maven-publish` into a clean `file://` repo. Actual: the same publications through the plugin, with a concurrent publisher of other coordinates forcing a lease failure.
  Done when: `TreeDiff` reports no difference.
- [ ] T42 Maven and Gradle in one repository
  Done when: IT publishes a Maven project and a Gradle KMP project (distinct coordinates) concurrently into one bare repo; both consumers resolve.
- [ ] T43 Gradle version matrix
  CI matrix over the latest Gradle 8.x and the latest 9.x.
  Done when: all legs green.
- [ ] T44 Gradle user docs
  Done when: README Gradle snippets are copied from passing TestKit builds.
- [ ] T45 Plugin Portal publishing
  Plugin marker and portal config.
  Done when: `publishPlugins --validate-only` passes.

## Phase 3: hardening

- [ ] T47 keepVersions
  Design §6.
  Done when: tests drop oldest snapshot versions per `groupId:artifactId` and A-level metadata passes `MetadataAssertions`.
- [ ] T48 Release-aware cleanup
  Drop a `-SNAPSHOT` version once the release exists on a configurable release repo (default Central).
  Done when: IT against a `PagesServer`-hosted fake release repo.
- [ ] T49 verify goal and task
  Checks branch exists, single commit, `.nojekyll` present when `nojekyll` is enabled, no LFS pointers. Optional: a user-given consumer URL serves metadata.
  Done when: IT passes on a good setup and reports each failure on crafted bad setups.
- [ ] T50 GitHub Actions templates
  Publish workflow with a `concurrency` group covering every coordinate of the project (PAT and `GITHUB_TOKEN` variants). No Pages deployment template (D-043).
  Done when: templates linted with `actionlint` in CI and used by the dogfood repo.
- [ ] T51 Nightly end-to-end
  Scheduled workflow publishing to a real public GitHub repo and resolving with clean consumers through `raw.githubusercontent.com`. (Needs Q7.)
  Done when: three consecutive green nightly runs.
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

- [ ] R11 Keep prefixed attribute names when rewriting metadata (major, F1)
  `XmlNode` must write back qualified attribute names and namespace declarations; a namespaced round-trip case in the T13 tests.
  Done when: `R11NamespacedMetadataRewriteTest` passes.
- [ ] R12 Checksum algorithms and `nojekyll` in the core config; document fixes (major, F2, F4)
  Design §3 and a decision: `PublishConfig` carries the configured checksum algorithms (non-empty, default SHA-1, MD5) and `nojekyll`;
  T20/T21 pass them to `Retention` and `IntegrityCheck`. Fix T48 (`PagesServer`), mark D-038 "Revised by D-043, D-045".
  Done when: unit tests for the default and the validation; design, decisions and tasks updated.
- [ ] R13 Reject credentials in registry-based URL authorities (minor, F3)
  Done when: `R13RegistryAuthorityUserInfoTest` passes and the existing `PublishConfigTest` cases still pass.
- [ ] R14 Interleaving harness: require the interleaving for "both published" (minor, F5)
  An assertion that fails when the publisher never reached `Publication.checkedOut()`, used by the T21/T23 tests; the lock reference keeps its path.
  Done when: a self-test shows a non-interleaving publisher fails the new assertion and `replayOrFail` passes it.
