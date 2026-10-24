# Context pack: maven-plugin

**Location:** the single Maven project (D-037), packaging `maven-plugin`, artifact `git-maven-publisher-maven-plugin`,
adapter package `io.github.ivangsa.gitpublisher.maven` · Maven 3.10+ and Maven 4 (D-012) · Maven API and Resolver API `provided`.

## Purpose
Thin adapter. One goal `deploy`, default phase `deploy`. Users set `maven.deploy.skip=true` or replace
`maven-deploy-plugin`. It never generates artifacts or metadata: Resolver does.

## Reactor transaction (design §2 Maven, D-034)
1. Participants = projects in `session.getProjects()` whose effective model (active profiles included) binds this goal.
   Every participant checks in when its execution runs, also with `skip=true`. Release versions fail fast unless `allowReleases`.
   Each participant registers main artifact, POM and attached artifacts (sources, javadoc, classifiers, `.asc`) in a session-scoped, thread-safe collector.
2. All participants must resolve the same `repository`, `branch`, `workDirectory` and credentials source; mismatch fails before checkout.
3. The participant that completes the set triggers: `GitPublisher.checkout()` → **one** `RepositorySystem.deploy` to a
   `RemoteRepository` type `default`, URL `file://<repositoryDirectory>` → `publication.publish(...)`. One commit per reactor build, also with `-T`.
4. Guarantee: push only after every participating execution succeeded (a failed participant, also with `--fail-at-end`, means no push).
   **Not** guaranteed: success of modules after the last participant or of the CI pipeline. A trailing failing non-participant leaves the publication pushed.
5. Checksums: session copy with `aether.checksums.algorithms` from `checksumAlgorithms` (default `SHA-1,MD5`).

## Parameters (design §2)
`repository` (required), `branch`=gh-pages, `nojekyll`=false, `serverId`, `keepSnapshots`=3, `keepVersions`=-1, `allowReleases`=false,
`checksumAlgorithms`, `maxAttempts`=5, `workDirectory`=`${session.executionRootDirectory}/target/git-publisher`,
`dryRun`, `skip`, `commitAuthor`. All have a user property `git-publisher.<name>`.

## Credentials (design §8, D-035)
`serverId` server from `settings.xml` (password = token, username default `x-access-token`, decrypt with
`SettingsDecrypter`) → env `GIT_PUBLISHER_TOKEN` → anonymous. No token parameter or property.
Never log the token; never put it in a URL, command line, exception message or file.
Docs cover two scenarios: self-publication with `GITHUB_TOKEN`, and external users with a fine-grained PAT. Serving the branch is the user's job (D-043).

## Tests
`maven-invoker-plugin` ITs in `src/it`: multi-module user projects → bare repo on disk → `MavenConsumer` resolves from a `file://` checkout
(verify scripts use the harness with `addTestClassPath`). Trigger ITs: `-T 4`, skipped participant, failing participant with `-fae`,
trailing failing non-participant.
CI runs ITs on the latest Maven 3.10.x and the latest Maven 4.0.

## Files that matter
`docs/design.md` §2 (Maven), §7, §8, `docs/decisions.md` D-007, D-034, D-035, D-037,
`src/main/java/io/github/ivangsa/gitpublisher/maven/`, `src/it/`, core API in `docs/context/core.md`.
