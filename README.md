# git-maven-publisher

Turns a Git repository into a Maven snapshot repository, served as static files by whatever you choose (raw.githubusercontent.com, GitHub Pages, ...).

Status: early development (0.1.0-SNAPSHOT). No release yet. Design and plan live in `docs/`.

## Build

Run `./mvnw verify`. Requires JDK 17 or newer. The default build needs no Docker. CI runs the build on JDK 17 and 21, and the invoker ITs also on Maven 3.10.0 and Maven 4.0.0-rc-7.

The project is a Maven reactor (parent `git-maven-publisher-parent`) with three modules: `core` (`git-maven-publisher-core`, publication logic), `maven-plugin` (`git-maven-publisher-maven-plugin`, packaging `maven-plugin`; the Maven invoker ITs live under `maven-plugin/src/it`) and `test-fixtures` (the test harness and golden files, never deployed). Run the build from the root.

## Publish from Maven

### Setup

Bind the `deploy` goal of the plugin to your project and skip the standard deploy plugin, so that the snapshots go to the branch only:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>it.reactor</groupId>
  <artifactId>property-only</artifactId>
  <version>1.0-SNAPSHOT</version>

  <properties>
    <!-- the standard deploy plugin must not run next to git-maven-publisher -->
    <maven.deploy.skip>true</maven.deploy.skip>
  </properties>

  <build>
    <plugins>
      <plugin>
        <groupId>io.github.ivangsa</groupId>
        <artifactId>git-maven-publisher-maven-plugin</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <executions>
          <execution>
            <goals>
              <goal>deploy</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Run `mvn deploy`. The goal publishes what Maven has deployed in the build (jar, POM, sources, attached files) to `gh-pages` of the repository given by `repository`, as one commit.

### Configuration by property only

Every parameter has a user property `git-publisher.<name>`. Set them on the command line, or in `.mvn/maven.config`, and leave the plugin without a `<configuration>`:

```sh
mvn deploy -Dgit-publisher.branch=snapshots -Dgit-publisher.checksumAlgorithms=SHA-256
```

The repository is `git-publisher.repository` (required, `https` or `file` only, no credentials in the URL), for example `-Dgit-publisher.repository=https://github.com/<owner>/<snapshots-repo>.git`. The other parameters are `serverId`, `nojekyll`, `keepSnapshots` (default 3), `keepVersions`, `checksumAlgorithms` (default `SHA-1,MD5`), `maxAttempts`, `workDirectory`, `dryRun`, `skip`, `commitAuthor` and `allowReleases` (release versions fail unless set).

### Reactor builds

A multi-module build makes one commit: every module that binds the goal checks in, and the last one publishes everything in one push. Use `-T` or `--fail-at-end` as usual.

- If any participating module fails, nothing is pushed.
- Success of modules built after the last participant, and of the rest of the CI pipeline, is not guaranteed. A failing module after that point leaves the publication pushed and the build failed.
- If you need "publish only when everything passed", run the publication as a separate CI stage after validation, as the workflow below does with the `publish` job.

## Authentication

Tokens reach the plugin in one of three ways, in this order: the `settings.xml` server named by `serverId`, the environment variable `GIT_PUBLISHER_TOKEN`, or anonymous (only for `file` repositories). A token never goes on the command line, into the POM, into the remote URL, the Git config or a log line.

### Self-publication with GITHUB_TOKEN

For snapshots of a project that publishes to its own repository, the workflow token is enough. It needs `contents: write`, and the job passes it as `GIT_PUBLISHER_TOKEN`:

```yaml
name: publish-snapshots

on:
  push:
    branches: [main]

permissions:
  contents: write

# Serialize every publication of the coordinates of this project, and of every other workflow that publishes them (design section 7).
concurrency:
  group: maven-snapshots-${{ github.repository }}
  cancel-in-progress: false

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
      - run: ./mvnw -B verify

  # A separate publishing stage: runs only after the whole verify job succeeded.
  publish:
    needs: verify
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
      - name: Publish snapshots
        env:
          # Self-publication to this repository: the workflow token.
          # External users: a fine-grained PAT with Contents: write on the snapshots repository only, for example GIT_PUBLISHER_TOKEN: ${{ secrets.SNAPSHOTS_PAT }}
          GIT_PUBLISHER_TOKEN: ${{ github.token }}
        run: ./mvnw -B deploy -Dgit-publisher.repository=https://github.com/${{ github.repository }}.git -Dgit-publisher.branch=snapshots
```

This file is copied from the integration test `readme-workflow`, which builds and deploys the same project layout; the workflow itself is not run by the build and is checked as a workflow file by `actionlint` (task T50).

### External users with a fine-grained PAT

To publish to another repository, create a fine-grained personal access token from an account that can write to the snapshots repository, select that repository only, and grant `Contents: Read and write`. Give it an expiry date: the publication fails with an authentication error once the token expires, and the fix is a new token. Store it as a secret of the publishing repository and pass it as `GIT_PUBLISHER_TOKEN` (see the workflow above), or keep it in `settings.xml` with an encrypted password and select the server with `-Dgit-publisher.serverId=snapshots-server`:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <servers>
    <server>
      <id>snapshots-server</id>
      <!-- an encrypted token; replace it with your own -->
      <password>{RndOaLhHnTsHBKJQlvzAfZ/npMlKIHgkOePT3U8nhAnc7eISwNCPbyQUQFFjz93s}</password>
    </server>
  </servers>
</settings>
```

The password is encrypted with the master password in `~/.m2/settings-security.xml`. Maven 3.10 creates both with `mvn --encrypt-master-password` and `mvn --encrypt-password`. Maven 4 uses its own scheme (`mvn --enc`) and can still read legacy encrypted passwords. If the master password file is not at the default location, name it with `-Dsettings.security=<path>`; this plugin's own integration tests do that. The `<password>` above is an example value that those tests decrypt, not a secret: use your own.

## Serving the branch

The plugin pushes the branch and nothing else. Serving it is your job:

- **raw.githubusercontent.com**: for a public repository, the files are at `https://raw.githubusercontent.com/<owner>/<repo>/<branch>/`. Private repositories are not served anonymously this way.
- **GitHub Pages, set up by hand**: choose the branch as the Pages source. Set `-Dgit-publisher.nojekyll=true` so that Pages does not run Jekyll on the branch; the plugin then writes an empty `.nojekyll` on every publish. Pages on a private repository needs a paid plan.
- **Any static server**: serve a checkout of the branch.

The plugin does not configure Pages.

## Serialization

Publications that touch the same paths must not run at the same time. The plugin does not serialize them; the CI pipeline must:

- Serialize every coordinate that a project produces: all Maven modules of a reactor, and all Gradle publications when that plugin arrives.
- Cover every workflow that can publish those coordinates, in every repository. A `concurrency` group in one repository does not protect a snapshots repository that another repository also publishes to.
- Treat shared group-level plugin metadata (the `maven-metadata.xml` of a Maven plugin groupId) as a shared path.

The workflow above does this with one `concurrency` group per snapshots repository; `cancel-in-progress: false` lets a running publication finish instead of cancelling it. When two publications still collide, the second fails with a conflict that names every conflicting path, and nothing is pushed for it.

## Retention and caches

`keepSnapshots` (default 3) keeps that many builds of each `-SNAPSHOT` directory and deletes older ones. Consumers that cache metadata for a few minutes (raw.githubusercontent.com, the Pages CDN) can read metadata that names a deleted file until the cache expires. That needs more than `keepSnapshots` publications of one snapshot inside the cache window. This is an accepted risk of the MVP: each published tree is internally consistent, and there is no grace period.

## GitHub hosting limits

GitHub's limits are the ones of whatever serves the branch. For GitHub Pages, the published site may be no larger than 1 GB, the source repository has a recommended limit of 1 GB, the soft bandwidth limit is 100 GB per month, and there is a soft limit of 10 Pages builds per hour. Each publication is a single parentless commit, so the reachable history stays as large as the current content; unreachable objects are removed by GitHub at its own pace.

## License

Apache License 2.0, see [LICENSE](LICENSE).
