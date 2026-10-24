# Context pack: gradle-plugin

**Module:** `gradle-plugin/` · plugin id `io.github.ivangsa.git-maven-publisher` · **separate Gradle build**
with its own wrapper (D-002) · `java-gradle-plugin` · Gradle: latest 8.x and 9.x (D-012).
How it reuses the core packages of the Maven project is **Q10**, decided before T34. Do not assume a published core artifact.

## Purpose
Thin wrapper over the standard `maven-publish` plugin. Never generates artifacts or metadata itself.

## Contract (design §2 Gradle, D-033)
- Extension `gitMavenPublisher`: `repository`, `branch`, `token` (default env `GIT_PUBLISHER_TOKEN`, then Gradle
  property `gitPublisherToken`), `username`=`x-access-token`, `keepSnapshots`, `keepVersions`, `allowReleases`,
  `maxAttempts`, `dryRun`. All `Property<>` types.
- Registers Maven repository `gitPublisher` → `<rootBuildDir>/git-publisher/repo` (the core `repositoryDirectory()`).
- Lifecycle: checkout runs once before any selected publication writes to the local repository. Push runs once, after every
  selected publication participating in the transaction has completed successfully. A failed publication prevents push.
  Cleanup runs regardless of success or failure.
- Participants = `publish*PublicationToGitPublisherRepository` tasks in the task graph (root aggregate or one subproject/publication).
  Each `dependsOn` `gitPublisherCheckout`; `gitPublisherPush` `mustRunAfter` all of them and pushes only if the service recorded
  success for every one. **No unconditional finalizer** (Gradle runs finalizers after failures). Mechanism chosen in T36, recorded in decisions.
- Shared `BuildService` `GitPublisherService`: state and resource coordination, writers limited to one (`maxParallelUsages = 1`).
  It does not establish ordering. `close()` never publishes.
- Configuration-cache compatible: participant set computed at configuration time, no `Project` access at execution time.

## KMP
No special handling. Every publication (root, `jvm`, `js`, native) has its own artifactId, `.module` and POM,
all written to the same repository in one transaction. `.module` files reference timestamped names, so publish in place, never
re-deploy from a staging copy.

## Tests
Gradle TestKit: multi-project single commit; failure in any participant → no push, also with `--continue`; `--parallel` never pushes early;
one selected subproject publishes; `--configuration-cache` reuse keeps all of these. KMP sample (JVM, JS, one native),
`GradleConsumer` resolves the right variant per target from a `file://` checkout of the branch, differential vs sequential `maven-publish` into `file://`,
Maven + Gradle (distinct coordinates) into one repo. CI matrix over supported Gradle versions.

## Files that matter
`docs/design.md` §2 (Gradle), §3, §7, `docs/decisions.md` D-002, D-033, D-037, `docs/questions.md` Q10, `gradle-plugin/`,
core API in `docs/context/core.md`, harness in `docs/context/testing.md`.
