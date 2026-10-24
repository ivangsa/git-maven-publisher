# Context pack: testing

**Modules:** root build (`pom.xml`, Maven Wrapper 3.10.x, CI) and `test-fixtures/` (Maven, not deployed).
JUnit 5, AssertJ, Jetty (embedded). `--release 17`. Default build needs no Docker. Profile `e2e` (Reposilite, Testcontainers) needs Docker and always runs in CI (D-014).

## Purpose
Phase 0 builds the oracles before any production code. Our own tests can confirm our own wrong assumptions,
so the harness relies on things we do not control. **Do not replace an oracle with a fake.**

## Oracles and helpers (design §9)
| Helper | Oracle | Task |
| --- | --- | --- |
| golden files + `fixtures/README.md` (URL, date) | Maven Central metadata, a KMP lib with `.module`, public snapshot repo | T03 |
| `MetadataAssertions.assertValidRepository(Path)` | `maven-repository-metadata` parser + design §5 invariants | T04 |
| `ResolverDeployer` | real Maven Resolver (`maven-resolver-supplier`) deploying to `file://` | T05 |
| `BareRepo`, `PagesServer` | bare JGit repo on disk + branch checkout; embedded Jetty serving that working tree | T06 |
| `SequentialReference`, `TreeNormalizer`, `TreeDiff` | **primary oracle**: native deploys one after another into a clean `file://` repo = expected; our concurrent path = actual | T07 |
| `MavenConsumer` | clean Maven project (Maven Verifier, empty local repo) | T08 |
| `ConcurrentPublishScenario` | N threads, one bare repo; must catch a broken (no-lease) publisher | T09 |
| Reposilite E2E (`-Pe2e`) | interop smoke with a real repository manager; not a metadata oracle; CI fails on zero tests | T09b |
| `LeakScanner` | canary token in logs, dirs, Git config | T10 |
| JGit learning tests | real JGit over `file://` and JGit HTTP server | T11 |

## Invariants every test asserts (design §5)
snapshotVersions → existing files; versions → existing dirs; `latest`/`release` ∈ versions; checksum files present
and matching; XML parses with Maven's reader. Branch has exactly one commit.

## Rules
- Every harness helper has a self-test that proves it detects the failure it is meant to catch.
- Tests: `*Test` (surefire), `*IT` (failsafe), `*E2E` (profile `e2e`). No `@Disabled`.
- Why no repository-manager oracle: in a Maven deploy the client computes metadata, the server only stores files.
- Review tests under `src/test/.../review/` are never edited by implementers.

## Files that matter
`docs/design.md` §1 and §9, `pom.xml`, `test-fixtures/src/main/java/`, `test-fixtures/src/main/resources/golden/`,
`.github/workflows/`.
