# Context pack: testing

**Location:** the single Maven project (D-037): `pom.xml`, Maven Wrapper 3.10.x, CI in `.github/workflows/`.
Harness in `src/test/java/io/github/ivangsa/gitpublisher/fixtures/`, golden files in `src/test/resources/golden/`, plugin ITs in `src/it/`.
JUnit 5, AssertJ, Jetty (only for JGit's HTTP server). `--release 17`. No Docker, no Testcontainers, no Reposilite (D-038).

## Purpose
Phase 0 built the oracles before any production code. Our own tests can confirm our own wrong assumptions,
so the harness relies on things we do not control. **Do not replace an oracle with a fake.**

## Oracles and helpers (design §9)
| Helper | Oracle | Task |
| --- | --- | --- |
| golden files + `golden/README.md` (URL, date) | Maven Central metadata, a KMP lib with `.module`, public snapshot repo | T03 |
| `MetadataAssertions.assertValidRepository(Path)` | `maven-repository-metadata` parser + design §5 invariants | T04 |
| `ResolverDeployer` | real Maven Resolver (`maven-resolver-supplier-mvn3`) deploying to `file://`, one session = one `maven.startTime` | T05, R02 |
| `BareRepo` | bare JGit repo on disk (stands in for GitHub) + exact branch checkout | T06, R09 |
| `SequentialReference`, `TreeNormalizer`, `TreeDiff` | **primary**: native deploys one after another into a clean `file://` repo = expected; the same output transported through Git = actual | T07 |
| `MavenConsumer` | clean Maven project (Maven Verifier, empty local repo) resolving from a `file://` checkout of the branch | T08, T56 |
| `InterleavedPublishScenario` | deterministic: A checks out, B publishes, A pushes; must catch a no-lease publisher | T09, T55 |
| JGit learning tests | real JGit over `file://` and JGit HTTP server | T11 |

## Concurrency contract under test (design §7, D-032, D-044)
Interleaved publishers with distinct coordinates and disjoint metadata paths must both end up on the branch.
Publications sharing a path are outside the contract: tests assert they **fail safely** (conflict, diagnostic, nothing overwritten),
not that they merge.

## Invariants every test asserts (design §5)
snapshotVersions → existing files; `<snapshot>` names a build with files; versions → non-empty dirs; `latest`/`release` ∈ versions;
checksum files present and matching; XML parses with Maven's reader. Branch has exactly one commit.

## Rules
- Every harness helper has a self-test that proves it detects the failure it is meant to catch.
- Tests: `*Test` (surefire), `*IT` (failsafe), invoker ITs in `src/it`. No `@Disabled`, no skipped tests.
- Why no repository-manager oracle: in a Maven deploy the client computes metadata, the server only stores files.
- Review tests under `src/test/.../review/` are never edited by implementers.

## Files that matter
`docs/design.md` §1 and §9, `pom.xml`, `src/test/java/io/github/ivangsa/gitpublisher/fixtures/`, `src/test/resources/golden/`,
`.github/workflows/`.
