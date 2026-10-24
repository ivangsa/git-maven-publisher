# Context pack: maven-plugin

**Module:** `maven-plugin/` · artifact `git-maven-publisher-maven-plugin` · Maven 3.10+ and Maven 4 (D-012) ·
Maven API and Resolver API `provided` · depends on `core`.

## Purpose
Thin adapter. One goal `deploy`, default phase `deploy`. Users set `maven.deploy.skip=true` or replace
`maven-deploy-plugin`. It never generates artifacts or metadata: Resolver does.

## Flow (brief "Maven adapter", D-007)
1. Every module: fail fast on release versions unless `allowReleases`; register main artifact, POM and attached
   artifacts (sources, javadoc, classifiers, `.asc`) in a session-scoped, thread-safe collector.
2. The last participating project in `session.getProjects()` triggers: `GitPublisher.checkout()` →
   **one** `RepositorySystem.deploy` to a `RemoteRepository` type `default`, URL `file://<repositoryDirectory>` →
   `publication.publish(...)`. One commit per reactor build, also with `-T`.
3. Checksums: session copy with `aether.checksums.algorithms` from `checksumAlgorithms` (default `SHA-1,MD5`).

## Parameters (design §2)
`repository` (required), `branch`=gh-pages, `serverId`, `keepSnapshots`=3, `keepVersions`=-1, `allowReleases`=false,
`checksumAlgorithms`, `maxAttempts`=5, `workDirectory`=`${session.executionRootDirectory}/target/git-publisher`,
`dryRun`, `skip`, `commitAuthor`. All have a user property `git-publisher.<name>`.

## Credentials
`serverId` server from `settings.xml` (password = token, username default `x-access-token`, decrypt with
`SettingsDecrypter`) → env `GIT_PUBLISHER_TOKEN` → anonymous. No token parameter or property.
Never log the token; never put it in a URL, exception message or file.

## Tests
`maven-invoker-plugin` ITs: multi-module sample → bare repo on disk → `MavenConsumer` resolves via `PagesServer`.
`LeakScanner` with a canary token on build logs and work dir. CI runs ITs on the latest Maven 3.10.x and the latest Maven 4.0.

## Files that matter
`docs/design.md` §2 (Maven), §8, `docs/decisions.md` D-007, `maven-plugin/src/main/java/`,
`maven-plugin/src/it/`, core API in `docs/context/core.md`.
