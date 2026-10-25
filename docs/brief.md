# Git Maven Publisher: Implementation Brief

Oct 23, 2026 · @Ivan Garcia Sainz-Aja

## Context and goal

Build a small tool that turns any Git repository into a Maven repository for snapshots, served over HTTPS as static files (for example `raw.githubusercontent.com` or GitHub Pages, set up by the user). Consumers only add a repository URL. No GitHub Packages, no Sonatype snapshot service.

The problem is human before it is technical. Maintainers want users to try unreleased work, but Maven Central now limits free publishing per organization (file count, release size, release count), enforced since October 2026. Kotlin Multiplatform libraries hit those limits fast because each target adds files. Central snapshots have also shown HTTP 429 errors and delays of hours for large builds ([Eclipse helpdesk](https://gitlab.eclipse.org/eclipsefdn/helpdesk/-/issues/6909)).

Working name: `git-maven-publisher`. The name is neutral on purpose: this is generic infrastructure, not a ZenWave feature. ZenWave360 is the first real user.

Guiding rule: Maven and Gradle already produce correct artifacts and metadata. We only transport them into Git, keep independent publishers from overwriting each other, and keep the repository small.

## Architecture

Core publication logic plus two thin adapters. The adapters only point the native deploy at a local working tree; the core does everything Git.
The core and the Maven adapter ship as one Maven plugin artifact, with the core in its own Java packages (design §1); the Gradle adapter is a separate build.

&#91;embedded content: architecture · 2 adapters, 1 core, 1 remote\]

The core has no Maven or Gradle dependency, so a standalone CLI can wrap it later if someone needs it.

## Publication flow

One publication is one attempt loop around the native deploy. The publish branch always holds a single commit, so the reachable history is only what is currently published (the host collects unreachable objects later).

1. Fetch the publish branch (shallow, depth 1) into a working directory under `target/` or `build/`. Record the remote SHA as `expectedSha`. If the branch does not exist, start empty and expect no ref.
2. If enabled (`nojekyll`), ensure `.nojekyll` exists at the repository root.
3. Run the native deploy with a `file://` repository pointing at the working tree. Maven Resolver or Gradle reads the existing `maven-metadata.xml`, merges versions and assigns the snapshot timestamp and build number.
4. Apply the retention policy for the coordinates just published (see Retention).
5. Build one new commit with no parents from the full working tree. Message: coordinates, versions and source commit SHA.
6. Push with lease: `refs/heads/<branch>` forced only if the remote still points at `expectedSha`.
7. If the lease fails, someone else published. Fetch again, reset to the new remote tree and replay our changed paths on top (overlay replay, see Concurrency). Then go back to step 4. Default: 5 attempts with jittered backoff. If the other publisher changed one of our paths differently, fail with a clear diagnostic instead.

JGit covers every step without a Git executable: shallow fetch, `CommitBuilder` with an empty parent list, and `RemoteRefUpdate` with an expected old object id. The native deploy runs exactly once. Retries live in the core and never re-run Maven or Gradle tasks, which keeps both adapters thin.

## Maven adapter

A Maven plugin, `git-maven-publisher-maven-plugin`, with one goal `deploy` bound to the `deploy` phase. Users set `maven.deploy.skip=true` or replace `maven-deploy-plugin` entirely.

- Each module registers its main artifact, POM and attached artifacts (sources, javadoc, classifiers, signatures) in a session-scoped collector.
- The last *participating* module (one that binds the goal) triggers the publication: fetch, then one `RepositorySystem.deploy` call with a `RemoteRepository` of type `default` and URL `file://<working tree>`, then the core flow. One commit per reactor build.
- Guarantee: every participating execution completed successfully before the push. Not guaranteed: modules built after the last participant, or the rest of the CI pipeline. A trailing integration-test module can still fail after the publication. Users who need "publish only if everything passed" run publication as a separate CI stage after validation.
- Only `-SNAPSHOT` versions are published by default. A release version fails fast unless `allowReleases=true`.
- Checksums: Resolver writes `.sha1` and `.md5` by default. Expose `checksumAlgorithms` to opt into SHA-256 and SHA-512.
- Signatures: not generated here. If `maven-gpg-plugin` attached `.asc` files, they are published.

Minimal configuration:

```xml
<plugin>
  <groupId>io.github.ivangsa</groupId>
  <artifactId>git-maven-publisher-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration>
    <repository>https://github.com/zenwave360/maven-snapshots.git</repository>
    <branch>gh-pages</branch>
    <serverId>git-snapshots</serverId>
  </configuration>
  <executions>
    <execution>
      <goals><goal>deploy</goal></goals>
    </execution>
  </executions>
</plugin>
```

Also expose a CLI-style property form (`-Dgit-publisher.repository=...`) so CI can publish without editing the POM.

## Gradle adapter

A Gradle settings or project plugin, `io.github.ivangsa.git-maven-publisher`, that wraps the standard `maven-publish` plugin. It never generates artifacts or metadata itself.

- Registers a Maven repository named `gitPublisher` whose URL is the working tree, for example `build/git-publisher/repo`.
- Lifecycle contract: checkout runs once before any selected publication writes to the local repository. Push runs once, after every selected publication participating in the transaction has completed successfully. A failed publication prevents push. Cleanup runs regardless of success or failure.
- Push is not an unconditional finalizer: Gradle runs finalizers even when the finalized task fails. Ordering is explicit; a shared `BuildService` holds state and serializes writes, so a multi-project build produces one commit. Configuration cache compatible.
- Works for the root aggregate and for direct invocation of one subproject or publication.
- Kotlin Multiplatform needs no special handling. Every publication (root, `jvm`, `js`, native targets) has its own artifactId, `.module` file and POM, and Gradle writes them all into the same repository. The `.module` files reference timestamped file names, which is why we publish in place and never re-deploy from a staging copy.
- `./gradlew publishAllPublicationsToGitPublisherRepository` is the single command users run.

Minimal configuration:

```kotlin
plugins {
    kotlin("multiplatform") version "2.2.0"
    `maven-publish`
    id("io.github.ivangsa.git-maven-publisher") version "0.1.0"
}

gitMavenPublisher {
    repository = "https://github.com/zenwave360/maven-snapshots.git"
    branch = "gh-pages"
    token = providers.environmentVariable("GIT_PUBLISHER_TOKEN")
}
```

The Gradle adapter ships in a later phase than Maven, but the core API must be designed for it from day one.

## Authentication

Credentials never live in Git or in committed build files. They follow the same model maintainers already use for Maven Central tokens.

| Source | Maven | Gradle | Notes |
| --- | --- | --- | --- |
| Build tool secrets | `<server>` in `settings.xml`, looked up by `serverId` | `~/.gradle/gradle.properties` | Local development |
| Environment variable | `GIT_PUBLISHER_TOKEN` | `GIT_PUBLISHER_TOKEN` or `ORG_GRADLE_PROJECT_*` | CI default |

HTTPS tokens only, no SSH (D-015). Two scenarios, documented separately:

- **Self-publication.** This project publishes its own snapshots to its own repository with `GITHUB_TOKEN`, once a usable bootstrap version exists.
- **External users.** A fine-grained PAT with `Contents: write` on the destination repository only (it expires, the docs say so), or a GitHub App installation token.


Over HTTPS, JGit uses `UsernamePasswordCredentialsProvider("x-access-token", token)`.

Credential rules: never log the token, never pass it as a command-line argument, never put it in the remote URL or a committed file, never write it into Git config. The plugin does not scan for leaks: it creates no credentials and only hands the token to JGit.

## Concurrency

Publications sharing artifact or metadata paths must be serialized externally. The publisher replays independent changes after a lease rejection and fails on conflicting changes to the same path.

- **Precondition, enforced by CI/CD:** builds that publish the same artifact (`groupId:artifactId`) never run at the same time. The scope is every coordinate a project produces (all Maven modules, all Gradle/KMP publications) and every workflow, in any repository, that can publish them. A `concurrency` group in one repository is not enough if another repository publishes the same coordinates. Shared metadata paths, such as group-level plugin metadata of Maven plugins in one groupId, need the same serialization.
- **Supported:** independent projects publishing different artifacts into one repository at the same time. They touch different files, so after a lease failure the publisher fetches the new tree and copies its own changed paths on top (overlay replay). Identical writes, such as `.nojekyll`, are fine.
- **Conflict:** if the remote changed one of our paths with different content, the publication fails with a diagnostic naming the paths. It never overwrites a remote change. Violating the precondition can therefore make a publication fail.

There is no metadata merge. We do not use `git rebase`: with a single-commit branch there is no history to rebase, and overlay replay is simpler to reason about and to test.

## Snapshot semantics and retention

Only standard timestamped snapshots. Overwriteable `-SNAPSHOT.jar` files are out of scope: neither Maven 3 nor Gradle produces them anymore, and client caching becomes unpredictable without version-level metadata.

Retention runs inside the commit, per coordinates touched in this publication:

Version-level `maven-metadata.xml` maps each extension/classifier combination to its current timestamped snapshot. It is not a history of all retained builds. Older timestamped artifacts may remain available as files according to the retention policy, and retention identifies builds from timestamped file names.

- `keepSnapshots` (default 3): keep the last N timestamped builds per snapshot version. Delete older files and drop any metadata entry that pointed at them in the same commit, so metadata in the published tree never points at a missing file.
- `keepVersions` (default unlimited): optionally drop whole old `-SNAPSHOT` versions and remove them from the artifactId metadata.
- Optional: drop a snapshot version once the same release exists on Maven Central. Phase 2.

The single-commit branch bounds the reachable history to the current content; it does not delete unreachable Git objects immediately, GitHub collects them over time. Repository size is not an MVP concern: ZenWave's volume is small, retention is enough, and size work waits for measurements. Consumers that pinned a deleted timestamp will fail, which is standard snapshot behaviour and must be documented.

## Hosting

The plugin pushes a branch; serving it is the user's job. The core only needs a Git remote, and anything that serves the branch as static files works:

- `https://raw.githubusercontent.com/<owner>/<repo>/<branch>/` for a public repository. No setup.
- GitHub Pages, configured by the user (deploy from the branch, or their own workflow). Set `nojekyll=true` so the plugin writes `.nojekyll`.
- GitLab Pages, Codeberg Pages, a plain web server.

No Git LFS: static servers return the pointer file, not the binary. Hosting limits and caching of the chosen server are the user's concern; the README notes GitHub's.

## Prior art to review before coding

The idea exists in fragments, mostly Gradle-only and mostly unmaintained. None combines Maven, Gradle KMP, concurrency and retention. Review each for ideas and pitfalls, not for reuse.

| Project | What it does | Gap |
| --- | --- | --- |
| [gradle-git-publish](https://github.com/ajoberstar/gradle-git-publish) (ajoberstar) | Copies any files into a Git branch and pushes. Built for docs on GitHub Pages | Not Maven-aware: no metadata merge, no retention, no concurrency handling |
| [gradle-git-repo-plugin](https://github.com/Gan0803/gradle-git-repo-plugin) (unafraid fork) | Clones a Git repo as a local Maven repo for resolution and publishing | Consumers need the plugin; manual commit; old |
| [com.ageet.git-repo](https://plugins.gradle.org/plugin/com.ageet.git-repo) | Git repo as Maven repo, publish and push. Version 3.0.1, May 2026 | Reviewed: fork of Layer's plugin for private repos. Consumers need the plugin, commit and push stay manual, no Maven, no retention |
| [gradle-git-release-plugin](https://github.com/kamatama41/gradle-git-release-plugin) | Releases an artifact into a Git repo for GitHub Pages | Gradle only, last release 2019 |
| [github site-maven-plugin](https://github.github.com/maven-plugins/site-plugin) | Pushes Maven sites to a branch via the GitHub API | Sites, not repositories; abandoned |

## MVP, roadmap and distribution

The MVP is the Maven adapter plus the full core, used by ZenWave360 in production CI. Gradle follows once the core is stable.

1. **Phase 1, core + Maven.** Shallow fetch, native deploy into the working tree, single commit, push with lease, overlay replay of independent changes with conflict detection, `keepSnapshots`, optional `.nojekyll`, HTTPS token auth. Exit: ZenWave360 snapshots served from the branch for two weeks with no manual fixes.
2. **Phase 2, Gradle + KMP.** Gradle plugin on a shared `BuildService`, configuration cache support. Exit: a KMP sample (JVM, JS, one native target) resolves from a clean consumer.
3. **Phase 3, hardening.** `keepVersions`, release-aware cleanup, `verify` goal, Actions templates.

Artifacts:

- `git-maven-publisher-maven-plugin`: one Maven project, Java 17, JGit; core logic in packages with no Maven or Gradle APIs; Maven 3.10+ and Maven 4.
- `git-maven-publisher-gradle-plugin`: separate Gradle build, Gradle 8.x+. How it reuses the core code is decided before Phase 2.

Distribution: own GitHub repository, MIT, groupId `io.github.ivangsa`, published to Maven Central. The Gradle plugin also goes to the Gradle Plugin Portal through the plugin marker. Neutral naming keeps a later donation open (MojoHaus for the Maven plugin, or Commonhaus Foundation for the whole project) without renaming.

## Testing and acceptance

Most tests need no network: JGit can host a bare repository on disk as the remote, and a clean Maven consumer can resolve from a `file://` checkout of the pushed branch.

- **Core unit tests:** retention with metadata rewrite, overlay replay of independent changes, conflict detection, lease failure handling.
- **Interleaving test:** publisher A checks out, publisher B publishes, then A pushes. Distinct artifacts: both end up on the branch, metadata valid. Shared path: A fails with a conflict and B is untouched. A push without a lease is caught losing B's files.
- **Maven integration:** `maven-invoker-plugin` runs a multi-module sample, then a clean consumer resolves the snapshot from a checkout of the bare repo's branch.
- **Gradle integration:** TestKit runs a KMP sample, then a clean KMP consumer resolves and selects the correct variant per target.
- **End-to-end (nightly):** a real GitHub repository, resolved through `raw.githubusercontent.com`.

Acceptance criteria:

- [ ] A Maven multi-module project publishes snapshots with only the plugin block and a token.
- [ ] A Gradle KMP project publishes all targets and `.module` files.
- [ ] Both publish into the same repository without overwriting each other.
- [ ] Clean Maven and Gradle KMP consumers resolve from the published branch.
- [ ] Repeated publication keeps exactly `keepSnapshots` builds with valid metadata.
- [ ] Concurrent publication of distinct artifacts loses no artifacts and corrupts no metadata; conflicting publications fail without overwriting.
- [ ] The branch always holds one commit.
- [ ] The token is never written to the remote URL, Git config or a committed file.

## Risks and open questions

- **GitHub Pages policy.** GitHub forbids Pages as hosting for an online business or commercial SaaS; open source snapshots are not that. Over the soft limits, GitHub may stop serving the site or email suggestions such as a CDN or another host. Keep snapshots in a dedicated repository so the worst case never touches the main repos, and keep the core provider-agnostic.
- **Same-artifact races.** Excluded by the CI serialization precondition. If it is violated, the publication fails; nothing is overwritten.
- **Gradle internals.** The adapter depends on `maven-publish` writing to a `file://` repository and on task naming. Pin tested Gradle versions in CI.
- **Prior art overlap.** `com.ageet.git-repo` was reviewed: it solves private-repo resolution, not public snapshot publishing. No overlap that justifies contributing instead.
- **Open question: Central limits.** Maven Central evaluates its free publishing limits per organization, and an organization can hold several namespaces ([Sonatype](https://central.sonatype.org/publish/maven-central-publishing-limits/)). A new namespace in the same organization does not get its own quota. Confirm in the Usage Center which organization `io.github.ivangsa` lands in.
- **Open question: name.** Final project name and plugin ids before the first Central release, since both are permanent.
