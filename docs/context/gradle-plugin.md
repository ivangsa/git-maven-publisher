# Context pack: gradle-plugin

**Module:** `gradle-plugin/` · plugin id `io.github.ivangsa.git-maven-publisher` · **separate Gradle build**
with its own wrapper (D-002) · `java-gradle-plugin` · Gradle: latest 8.x and 9.x (D-012) · depends on `core` from `mavenLocal()`.
CI order: `./mvnw install` then `./gradlew -p gradle-plugin build`. Version synced via `gradle-plugin/gradle.properties`.

## Purpose
Thin wrapper over the standard `maven-publish` plugin. Never generates artifacts or metadata itself.

## Contract (design §2)
- Extension `gitMavenPublisher`: `repository`, `branch`, `token` (default env `GIT_PUBLISHER_TOKEN`, then Gradle
  property `gitPublisherToken`), `username`=`x-access-token`, `keepSnapshots`, `keepVersions`, `allowReleases`,
  `maxAttempts`, `dryRun`. All `Property<>` types.
- Registers Maven repository `gitPublisher` → `<rootBuildDir>/git-publisher/repo` (the core `repositoryDirectory()`).
- `gitPublisherCheckout` runs before every `publish*PublicationToGitPublisherRepository`;
  `gitPublisherPush` is a finalizer that runs once.
- Shared `BuildService` `GitPublisherService` owns the core `Publication`: one commit per multi-project build.
- Configuration-cache compatible: no `Project` access at execution time.

## KMP
No special handling. Every publication (root, `jvm`, `js`, native) has its own artifactId, `.module` and POM,
all written to the same repository. `.module` files reference timestamped names, so publish in place, never
re-deploy from a staging copy.

## Tests
Gradle TestKit: multi-project single commit, `--configuration-cache` reuse, KMP sample (JVM, JS, one native),
`GradleConsumer` resolves the right variant per target through `PagesServer`, differential vs sequential `maven-publish` into `file://`,
Maven + Gradle into one repo. CI matrix over supported Gradle versions.

## Files that matter
`docs/design.md` §2 (Gradle), §3, `docs/decisions.md` D-002, `gradle-plugin/`, core API in `docs/context/core.md`,
harness in `docs/context/testing.md`.
