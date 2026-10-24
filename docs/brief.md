# Git Maven Publisher: Implementation Brief

Oct 23, 2026 · @Ivan Garcia Sainz-Aja

## Context and goal

Build a small tool that turns any Git repository into a Maven repository for snapshots, served over HTTPS by GitHub Pages. Consumers only add a repository URL. No GitHub Packages, no Sonatype snapshot service.

The problem is human before it is technical. Maintainers want users to try unreleased work, but Maven Central now limits free publishing per organization (file count, release size, release count), enforced since October 2026. Kotlin Multiplatform libraries hit those limits fast because each target adds files. Central snapshots have also shown HTTP 429 errors and delays of hours for large builds ([Eclipse helpdesk](https://gitlab.eclipse.org/eclipsefdn/helpdesk/-/issues/6909)).

Working name: `git-maven-publisher`. The name is neutral on purpose: this is generic infrastructure, not a ZenWave feature. ZenWave360 is the first real user.

Guiding rule: Maven and Gradle already produce correct artifacts and metadata. We only transport them into Git, keep them consistent under concurrency, and keep the repository small.

## Architecture

A shared core plus two thin adapters. The adapters only point the native deploy at a local working tree; the core does everything Git.

&#91;embedded content: architecture · 2 adapters, 1 core, 1 remote\]

The core has no Maven or Gradle dependency, so a standalone CLI can wrap it later if someone needs it.

## Publication flow

One publication is one attempt loop around the native deploy. The publish branch always holds a single commit, so the repository never grows beyond what is currently published.

1. Fetch the publish branch (shallow, depth 1) into a working directory under `target/` or `build/`. Record the remote SHA as `expectedSha`. If the branch does not exist, start empty and expect no ref.
2. Ensure `.nojekyll` exists at the repository root.
3. Run the native deploy with a `file://` repository pointing at the working tree. Maven Resolver or Gradle reads the existing `maven-metadata.xml`, merges versions and assigns the snapshot timestamp and build number.
4. Apply the retention policy for the coordinates just published (see Retention).
5. Build one new commit with no parents from the full working tree. Message: coordinates, versions and source commit SHA.
6. Push with lease: `refs/heads/<branch>` forced only if the remote still points at `expectedSha`.
7. If the lease fails, someone else published. Fetch again, reset to the new remote tree and replay our changed paths on top (overlay replay, see Concurrency). Then go back to step 4. Default: 5 attempts with jittered backoff.

JGit covers every step without a Git executable: shallow fetch, `CommitBuilder` with an empty parent list, and `RemoteRefUpdate` with an expected old object id. The native deploy runs exactly once. Retries live in the core and never re-run Maven or Gradle tasks, which keeps both adapters thin.

## Maven adapter

A Maven plugin, `git-maven-publisher-maven-plugin`, with one goal `deploy` bound to the `deploy` phase. Users set `maven.deploy.skip=true` or replace `maven-deploy-plugin` entirely.

- Each module registers its main artifact, POM and attached artifacts (sources, javadoc, classifiers, signatures) in a session-scoped collector.
- The last module of the reactor triggers the publication: fetch, then one `RepositorySystem.deploy` call with a `RemoteRepository` of type `default` and URL `file://<working tree>`, then the core flow. One commit per reactor build.
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
- `gitPublisherCheckout` runs before every `publish*PublicationToGitPublisherRepository` task. `gitPublisherPush` is a finalizer that runs once after all of them.
- The Git lifecycle lives in a shared `BuildService`, so a multi-project build produces one commit. Required for configuration cache compatibility.
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
| SSH key | `~/.ssh` or a key path, via JGit Apache MINA SSHD | same | Phase 2 |

Token types for GitHub:

- Fine-grained PAT with `Contents: write` on the snapshots repository only. MVP default. Expires, so the docs must say so.
- `GITHUB_TOKEN` from Actions works only when the snapshots live in the same repository as the workflow, for example a `gh-pages` branch of the library repo.
- Deploy key (SSH, write access) has the tightest scope and does not expire. Recommended once SSH is supported.

Over HTTPS, JGit uses `UsernamePasswordCredentialsProvider("x-access-token", token)`.

Redaction rules: never log the token, never put it in the remote URL, strip credentials from any URL before logging, and never write them into `.git/config` of the working tree.

## Concurrency

Independent projects almost never conflict, because Maven metadata lives per artifactId and per version. Two projects with different coordinates touch disjoint paths.

Overlay replay, owned by the core:

1. After the native deploy, diff the working tree against `expectedSha` and record our changed paths.
2. On a lease failure, fetch the new remote tree and copy our recorded paths on top.
3. If the remote also changed one of our paths, only metadata can legitimately collide. Merge it: union of `<versions>` and `<snapshotVersions>`, `latest` and `lastUpdated` recomputed from the merged set. Artifact files have timestamped names, so they never collide.
4. Re-apply retention, rebuild the single commit, push with the new lease.

Same-artifact concurrency (two builds of the same snapshot at once) is the only lossy case. The merged metadata keeps both timestamps and the newer one wins as latest. Recommend a CI `concurrency` group per project anyway; the docs ship a ready-made GitHub Actions snippet.

We do not use `git rebase`: with a single-commit branch there is no history to rebase, and overlay replay is simpler to reason about and to test.

## Snapshot semantics and retention

Only standard timestamped snapshots. Overwriteable `-SNAPSHOT.jar` files are out of scope: neither Maven 3 nor Gradle produces them anymore, and client caching becomes unpredictable without version-level metadata.

Retention runs inside the commit, per coordinates touched in this publication:

- `keepSnapshots` (default 3): keep the last N timestamped builds per snapshot version. Delete older files and remove their entries from `<snapshotVersions>` in the same commit, so metadata never points at a missing file.
- `keepVersions` (default unlimited): optionally drop whole old `-SNAPSHOT` versions and remove them from the artifactId metadata.
- Optional: drop a snapshot version once the same release exists on Maven Central. Phase 2.

The single-commit branch bounds repository size to the current content. GitHub garbage-collects the unreachable commits over time. Consumers that pinned a deleted timestamp will fail, which is standard snapshot behaviour and must be documented.

## Hosting

GitHub Pages is the first target, not a dependency. The core only needs a Git remote; anything that serves the branch as static files works (GitLab Pages, Codeberg Pages, a plain web server).

GitHub Pages setup, documented and checked by a `verify` goal:

- Pages source: deploy from branch `gh-pages`, folder `/`. Simplest option. Branch builds have a soft hourly build limit, so heavy publishers should switch to the next option.
- Alternative: a small Actions workflow on push to the branch, using `upload-pages-artifact` and `deploy-pages` with its own `concurrency` group. Shipped as a template.
- `.nojekyll` at the root, written by the publisher on every commit.
- No Git LFS: Pages serves the pointer file, not the binary.
- Consumer URL: `https://<owner>.github.io/<repo>/`. Path prefix handled by the URL itself; nothing to configure.

Operational notes (approximate, verify against current GitHub docs before release): published site around 1 GB, soft bandwidth around 100 GB per month, CDN cache of about 10 minutes. Timestamped file names are immutable, so caching only delays new metadata; it never serves a broken mix.

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

1. **Phase 1, core + Maven.** Shallow fetch, native deploy into the working tree, single commit, push with lease, overlay replay with metadata merge, `keepSnapshots`, `.nojekyll`, HTTPS token auth. Exit: ZenWave360 snapshots served from Pages for two weeks with no manual fixes.
2. **Phase 2, Gradle + KMP.** Gradle plugin on a shared `BuildService`, configuration cache support. Exit: a KMP sample (JVM, JS, one native target) resolves from a clean consumer.
3. **Phase 3, hardening.** SSH deploy keys, `keepVersions`, release-aware cleanup, `verify` goal, Actions templates.

Modules:

- `git-maven-publisher-core`: Java 17, JGit, no Maven or Gradle APIs.
- `git-maven-publisher-maven-plugin`: Maven 3.9+ and Maven 4, Resolver API.
- `git-maven-publisher-gradle-plugin`: Gradle 8.x+.

Distribution: own GitHub repository, Apache 2.0, groupId `io.github.ivangsa`, published to Maven Central. The Gradle plugin also goes to the Gradle Plugin Portal through the plugin marker. Neutral naming keeps a later donation open (MojoHaus for the Maven plugin, or Commonhaus Foundation for the whole project) without renaming.

## Testing and acceptance

Most tests need no network: JGit can host a bare repository on disk as the remote, and a local HTTP server can stand in for Pages.

- **Core unit tests:** metadata merge (union, latest, lastUpdated), retention with metadata rewrite, overlay replay, lease failure handling.
- **Concurrency test:** N threads publish different and identical coordinates to one bare repo. Assert no lost files, valid XML, every metadata entry points at an existing file.
- **Maven integration:** `maven-invoker-plugin` runs a multi-module sample, then a clean consumer resolves the snapshot from a local HTTP server over the bare repo.
- **Gradle integration:** TestKit runs a KMP sample, then a clean KMP consumer resolves and selects the correct variant per target.
- **End-to-end (nightly):** a real GitHub repository with Pages enabled.

Acceptance criteria:

- [ ] A Maven multi-module project publishes snapshots with only the plugin block and a token.
- [ ] A Gradle KMP project publishes all targets and `.module` files.
- [ ] Both publish into the same repository without overwriting each other.
- [ ] Clean Maven and Gradle KMP consumers resolve from the Pages URL.
- [ ] Repeated publication keeps exactly `keepSnapshots` builds with valid metadata.
- [ ] Concurrent publication loses no artifacts and corrupts no metadata.
- [ ] The branch always holds one commit.
- [ ] No token appears in logs or in the working tree.

## Risks and open questions

- **GitHub Pages policy.** GitHub forbids Pages as hosting for an online business or commercial SaaS; open source snapshots are not that. Over the soft limits, GitHub may stop serving the site or email suggestions such as a CDN or another host. Keep snapshots in a dedicated repository so the worst case never touches the main repos, and keep the core provider-agnostic.
- **Lossy same-artifact races.** Mitigated by metadata merge and CI concurrency groups, not eliminated.
- **Gradle internals.** The adapter depends on `maven-publish` writing to a `file://` repository and on task naming. Pin tested Gradle versions in CI.
- **Prior art overlap.** `com.ageet.git-repo` was reviewed: it solves private-repo resolution, not public snapshot publishing. No overlap that justifies contributing instead.
- **Open question: Central limits.** Maven Central evaluates its free publishing limits per organization, and an organization can hold several namespaces ([Sonatype](https://central.sonatype.org/publish/maven-central-publishing-limits/)). A new namespace in the same organization does not get its own quota. Confirm in the Usage Center which organization `io.github.ivangsa` lands in.
- **Open question: name.** Final project name and plugin ids before the first Central release, since both are permanent.
