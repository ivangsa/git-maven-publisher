# Tasks

**Status:** Phase 0 done (T00-T11) · Last finished: B03 (T07, T08, T09, T09b) · Next: review of Phase 0, then B04 (T12, T13, T14, T17)

## Batches

Run in order. Context packs live in `docs/context/`; every dispatch also passes `AGENTS.md`.
Review findings (`R` tasks, `[!]` tasks) always get their own batches, never mixed with new production code.
"Review" = run `adversarial-reviewer` after the batch (AGENTS.md, Orchestration).
Inside a batch, tasks run in the order listed (B04 does T17 before T15/T16 so merge and retention tests can use it).

| Batch | Tasks | Agent | Context pack | Review | Status |
| --- | --- | --- | --- | --- | --- |
| B01 | T01, T02, T03, T10 | mechanic | testing | | done |
| B02 | T04, T05, T06, T11 | implementer | testing | | done |
| B03 | T07, T08, T09, T09b | implementer | testing | yes (end of Phase 0) | done |
| B04 | T12, T13, T14, T17 | implementer | core | | todo |
| B05 | T15 (metadata merge) | implementer | core | | todo |
| B06 | T16 (retention) | implementer | core | yes (merge + retention) | todo |
| B07 | T18, T19 | implementer | core | | todo |
| B08 | T20 (lease) | implementer | core | | todo |
| B09 | T21 (retry) | implementer | core | | todo |
| B10 | T21b (overlay replay) | implementer | core | yes (lease + retry + replay) | todo |
| B11 | T22, T23, T24 | implementer | core, testing | | todo |
| B12 | T25, T26, T27, T28 | implementer | maven-plugin | | todo |
| B13 | T29, T30, T31 | mechanic | maven-plugin | yes (end of Phase 1 code) | todo |
| B14 | T32 | mechanic | maven-plugin | | blocked (Q4) |
| B15 | T34, T35, T36, T37 | implementer | gradle-plugin | | todo |
| B16 | T38, T39, T40 | implementer | gradle-plugin, testing | | todo |
| B17 | T41, T42 | implementer | gradle-plugin, testing | | todo |
| B18 | T43, T44, T45 | mechanic | gradle-plugin | yes (end of Phase 2) | todo |
| B19 | T47, T48 (retention) | implementer | core | yes (retention) | todo |
| B20 | T49 | implementer | maven-plugin, gradle-plugin | | todo |
| B21 | T50, T51, T52 | mechanic | testing | yes (end of Phase 3) | todo (T51 needs Q7) |
| manual | T33 | maintainer | - | | blocked (Q2); not dispatched to an agent |

Legend: `[ ]` todo · `[x]` done · `[?]` blocked on a question in `docs/questions.md` · `[!]` failed review verification (see linked review).
Every "Done when" assumes the full build passes (`./mvnw verify`, plus `./gradlew -p gradle-plugin build` once it exists).

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
- [x] T06 Bare repo and Pages server harness
  `BareRepo` (create on disk, read branch tree, count commits on branch, check out the branch tip into a working tree) and `PagesServer` (embedded Jetty serving a directory as static files).
  Done when: a test commits a file with JGit, checks out the branch tip and downloads the file through `PagesServer`; a missing path returns 404.
- [x] T07 Sequential reference oracle
  `SequentialReference` replays a scripted publication list with `ResolverDeployer`, one after another, into a clean `file://` repo; `TreeNormalizer` (timestamps, build numbers by order, `lastUpdated`/`updated`); `TreeDiff` (file set + normalized metadata, readable report).
  Done when: the same script (2 artifacts, 2 versions, 3 builds each) run twice at different times is equal after normalization; `TreeDiff` reports a crafted missing file, extra version and wrong `latest`; observed `<versions>` ordering recorded in decisions (D-006 confirmed or revised).
- [x] T08 Clean Maven consumer harness
  `MavenConsumer` runs a template consumer project with Maven Verifier, empty local repo, repository URL = `PagesServer` serving a checkout of the branch.
  Done when: an artifact deployed by `ResolverDeployer`, committed to a bare repo, is resolved by the consumer; a missing artifact makes it fail.
- [x] T09 Concurrency harness
  `ConcurrentPublishScenario`: N threads, barrier start, mix of shared and distinct coordinates, pluggable publisher; final check with `MetadataAssertions` and "every deployed file present".
  Done when: a deliberately broken publisher (force push, no lease) is detected as losing files, and a global-lock reference publisher passes and matches `SequentialReference` via `TreeDiff`.
- [x] T09b Reposilite E2E profile
  Maven profile `e2e`: Testcontainers Reposilite, `ResolverDeployer` deploys over HTTP, `MavenConsumer` resolves from it. CI runs `./mvnw verify -Pe2e` and a step fails the job if the profile executed zero tests.
  Done when: `./mvnw verify -Pe2e` passes locally with Docker; CI green; removing the E2E test makes CI fail.
- [x] T10 Credential leak scanner
  `LeakScanner` checks captured logs, a directory tree and any Git config for a canary string.
  Done when: self-test detects a planted canary in each location and passes on a clean run.
- [x] T11 JGit learning tests
  Learning tests for: depth-1 fetch over `file://` and over JGit HTTP server, parentless commit push sending only new objects, forced push with expected old id (accepted / `REJECTED_REMOTE_CHANGED`), create-only push with `zeroId`.
  Done when: all learning tests pass and any surprise is written to decisions.

## Phase 1: core + Maven

- [ ] T12 Core config and redaction
  `PublishConfig`, `Credentials`, `RetentionPolicy`, `RetryPolicy`, `Redactor`; reject URLs with userinfo.
  Done when: unit tests cover defaults, validation, redaction of https/file URLs and rejection of any other scheme.
- [ ] T13 Metadata model
  StAX reader/writer for G/A/V-level metadata.
  Done when: every golden file round-trips (oracle parse of output equals oracle parse of input).
- [ ] T14 Checksum writer
  Regenerate `.md5/.sha1/.sha256/.sha512` for a file (present algorithms + configured).
  Done when: output matches checksums Resolver wrote for the same file in `ResolverDeployer` trees.
- [ ] T15 Three-way metadata merge
  `MetadataMerger` per design §5.
  Done when: unit tests for every rule row, plus golden-based tests; remote-deleted entries are not resurrected.
- [ ] T16 Retention: keepSnapshots
  Delete old builds per touched `-SNAPSHOT` dir and rewrite V-level metadata + checksums.
  Done when: on Resolver trees with 1..6 builds (incl. classifiers and `.module`), exactly N builds remain and `MetadataAssertions` passes.
- [ ] T17 Integrity check
  Production `IntegrityCheck` (design §5 invariants).
  Done when: agrees with `MetadataAssertions` on all harness valid/broken trees.
- [ ] T18 Checkout
  `GitPublisher.checkout()`: shallow fetch into `git/`, materialize into `repo/`, `.nojekyll`, empty start when branch missing.
  Done when: tests against `BareRepo` with and without branch; no remote URL in `git/config`.
- [ ] T19 Change recorder
  Diff `repo/` against `expectedSha` tree → added/modified paths.
  Done when: unit tests for add, modify, unchanged, nested paths, empty base.
- [ ] T20 Commit and push with lease (single attempt)
  Parentless commit from `repo/`, message format, forced push with expected old id. Also moves `refs/publisher/tree` in the same push (D-020).
  Done when: after N publishes the branch has exactly 1 commit; stale lease returns a lease-failure result; the second and later pushes send only the new objects.
- [ ] T21 Retry loop
  Back-off with jitter; on lease failure re-fetch, re-materialize and copy `ours` for paths the remote did not change; a path changed on both sides raises `PublishException` (until T21b); transport errors retried, auth failures fail fast.
  Done when: a competing push on disjoint coordinates between checkout and push ends with both publications present and valid; budget exhaustion raises `PublishException`; an auth failure makes exactly one attempt.
- [ ] T21b Overlay replay with metadata merge
  Paths changed on both sides: `maven-metadata.xml` → three-way merge (design §5) + regenerated checksums, then retention and integrity again; any other file → `PublishException`.
  Done when: competing pushes on the same artifactId (different versions) and on the same snapshot version end with merged valid metadata; entries removed by the remote's retention are not resurrected.
- [ ] T22 Concurrency IT
  `ConcurrentPublishScenario` with the real `GitPublisher` (8 threads, shared + distinct coordinates).
  Done when: passes 20 repeated runs in CI time budget.
- [ ] T23 Differential IT through GitPublisher
  Same script through `SequentialReference` (expected) and through concurrent `GitPublisher` checkouts with forced lease failures and overlay replay (actual); retention disabled and enabled.
  Done when: `TreeDiff` reports no difference for every script.
- [ ] T24 HTTPS token auth and leak test
  `Token` credentials against JGit HTTP server with basic auth; wrong token fails fast without retries.
  Done when: publish succeeds with canary token and `LeakScanner` finds nothing.
- [ ] T25 Maven plugin skeleton
  `deploy` mojo, all parameters from design §2, release guard, `skip`.
  Done when: plugin descriptor generated; unit tests for release guard and parameter defaults.
- [ ] T26 Reactor collector and trigger
  Session-scoped collector, last participating project triggers, `-T` safe.
  Done when: invoker IT with 3 modules (one `pom` packaging) produces exactly one commit; with `-T 4` too.
- [ ] T27 Native deploy into work tree
  One `RepositorySystem.deploy` to `file://repo/`, `checksumAlgorithms`, attached artifacts and `.asc` files.
  Done when: invoker IT multi-module → bare repo → `MavenConsumer` resolves main jar and sources.
- [ ] T28 Maven credentials
  `serverId` (incl. encrypted password) and `GIT_PUBLISHER_TOKEN`.
  Done when: invoker ITs for both sources against the HTTP git server; `LeakScanner` clean on build log and work dir.
- [ ] T29 Maven 4 compatibility
  CI matrix runs invoker ITs on the latest Maven 3.10.x and the latest Maven 4.0 (RC until GA).
  Done when: both matrix legs green.
- [ ] T30 Property-only configuration
  Publish with `-Dgit-publisher.*` and no `<configuration>`.
  Done when: invoker IT passes.
- [ ] T31 Maven user docs
  README: setup, token creation (fine-grained PAT expiry), GitHub Pages setup, Actions snippet with `concurrency`, retention caveat.
  Done when: every snippet in the README is copied from a passing IT.
- [ ] T32 Release pipeline
  Central publishing (sources, javadoc, signing) for core and maven-plugin, release workflow. (Needs Q4.)
  Done when: a dry-run release produces signed bundles that pass Central validation.
- [ ] T33 Dogfooding and Phase 1 exit (manual, maintainer)
  Publish this project's snapshots and ZenWave360's with the plugin. (Needs Q2.)
  Done when: two weeks of ZenWave360 snapshots served from Pages with no manual fixes.

## Phase 2: Gradle + KMP

- [ ] T34 Gradle build skeleton
  `gradle-plugin/` with wrapper, `java-gradle-plugin`, core from `mavenLocal()`, CI step, version sync check.
  Done when: `./gradlew -p gradle-plugin build` passes locally and in CI.
- [ ] T35 Extension and repository
  `gitMavenPublisher` extension (design §2), registers `gitPublisher` Maven repository.
  Done when: TestKit test shows the repository and publish tasks for a `java-library` project.
- [ ] T36 BuildService and lifecycle tasks
  `GitPublisherService`, `gitPublisherCheckout`, `gitPublisherPush` finalizer.
  Done when: TestKit multi-project build (3 projects) yields exactly one commit and a valid tree.
- [ ] T37 Configuration cache
  Done when: TestKit runs the publish twice with `--configuration-cache`, second run reuses the cache, both succeed.
- [ ] T38 Gradle credentials
  `token` property, env var, `gitPublisherToken` Gradle property.
  Done when: TestKit against HTTP git server with canary; `LeakScanner` clean.
- [ ] T39 KMP publish
  KMP sample (JVM, JS, one native target) publishes all publications.
  Done when: every expected artifactId has `.module`, POM, metadata; `MetadataAssertions` passes.
- [ ] T40 Clean Gradle KMP consumer
  `GradleConsumer` harness (TestKit, empty caches) resolves through `PagesServer`.
  Done when: consumer selects the correct variant for JVM, JS and native targets.
- [ ] T41 Gradle differential IT
  Expected: KMP publications run sequentially by Gradle `maven-publish` into a clean `file://` repo. Actual: the same publications through the plugin with concurrent builds and a forced lease failure.
  Done when: `TreeDiff` reports no difference.
- [ ] T42 Maven and Gradle in one repository
  Done when: IT publishes a Maven project and a Gradle KMP project concurrently into one bare repo; both consumers resolve.
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
  Done when: tests drop oldest snapshot versions and A-level metadata passes `MetadataAssertions`.
- [ ] T48 Release-aware cleanup
  Drop a `-SNAPSHOT` version once the release exists on a configurable release repo (default Central).
  Done when: IT against a `PagesServer`-hosted fake release repo.
- [ ] T49 verify goal and task
  Checks branch exists, single commit, `.nojekyll`, no LFS pointers, consumer URL serves metadata.
  Done when: IT passes on a good setup and reports each failure on crafted bad setups.
- [ ] T50 GitHub Actions templates
  Publish workflow with `concurrency` group; Pages deploy workflow with `upload-pages-artifact`/`deploy-pages`.
  Done when: templates linted with `actionlint` in CI and used by the dogfood repo.
- [ ] T51 Nightly end-to-end
  Scheduled workflow publishing to a real GitHub Pages repo and resolving with clean consumers. (Needs Q7.)
  Done when: three consecutive green nightly runs.
- [ ] T52 Guide: publishing with a GitHub App token in GitHub Actions
  Docs page: create the App (`Contents: write`), install it on the snapshots repo, mint the token with `actions/create-github-app-token`, pass it as `GIT_PUBLISHER_TOKEN`; compare with fine-grained PAT and `GITHUB_TOKEN`.
  Done when: the workflow snippet in the guide is the one used by T50's template and passes `actionlint`.

## Review findings

From [review 2026-10-24-f65192f](reviews/2026-10-24-f65192f.md) (end of Phase 0, verdict FIX FIRST). All before B04.
Review tests live in `test-fixtures/src/test/java/io/github/ivangsa/gitpublisher/fixtures/review/`.

- [ ] R01 Build order by build number; `<snapshot>` invariant (critical, F1)
  Design §5/§6: order builds by build number (tie-break timestamp), not by timestamp; merge picks per key by the same order.
  Add the §5 invariant "`<snapshot>` names a build with files in the directory" to `MetadataAssertions`. Decision entry.
  Done when: `R01SnapshotElementInvariantTest` passes; design and decisions updated.
- [ ] R02 `ResolverDeployer` stamps like one Maven session (F2)
  Set `maven.startTime` per deployer session; allow an explicit start time; `ConcurrentPublishScenario` takes it at the barrier.
  Done when: `R02ResolverDeployerSessionStartTimeTest` passes; a harness self-test deploys "started earlier, deployed later" and records the result in decisions.
- [ ] R03 Tree ref is a hint, not a lease (design only, F3)
  Design §4.5 and D-020: push `refs/publisher/tree` without a lease on it (or with the fetched id) and ignore its status; only the branch status decides OK/retry. Extend T20 "Done when" with: branch exists without the tree ref → publish succeeds in one attempt.
  Done when: design, decisions and T20 updated.
- [ ] R04 Same-snapshot concurrency: build number collisions and reference order (F4)
  Design §7: replay renumbers a colliding build to remote max + 1 (or another recorded decision). Harness: unique content per publication in `ConcurrentPublishScenario`; record push order and replay that order in `assertMatchesSequentialReference`.
  Done when: design/decisions updated; a harness self-test shows two same-second publications of one snapshot are no longer counted as "nothing lost".
- [ ] R05 LeakScanner detects encoded tokens; e2e profile hygiene (F5, F8)
  Base64 (basic auth, three alignments) and URL-encoded canary in text, trees and Git config. E2E as a separate failsafe execution so `*IT` still runs under `-Pe2e`; CI guard counts `tests - skipped`.
  Done when: `R05LeakScannerBasicAuthTest` passes.
- [ ] R06 TreeNormalizer normalizes `.module` contents (minor, F6)
  Done when: `R06TreeNormalizerModuleFileTest` passes.
- [ ] R07 Version directory must not be empty (minor, F7)
  Done when: `R07EmptyVersionDirectoryTest` passes.
