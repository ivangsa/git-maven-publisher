# git-maven-publisher 0.1.0

First release. git-maven-publisher turns a Git repository into a Maven snapshot repository, served as static files by whatever you choose (raw.githubusercontent.com, GitHub Pages, ...).

## Artifacts

- `io.github.ivangsa:git-maven-publisher-maven-plugin:0.1.0`: Maven plugin, goal `deploy`. It publishes what the build deployed to a Git branch, as one commit per reactor build.
- `io.github.ivangsa:git-maven-publisher-core:0.1.0`: the publication logic (JGit and JDK only), for other build tools.
- `io.github.ivangsa:git-maven-publisher-parent:0.1.0`: the parent POM of the two modules above.

## Notes

- Snapshots only in practice: retention keeps the newest builds per `-SNAPSHOT` version (`keepSnapshots`, default 3).
- Credentials come from a `settings.xml` server, from `GIT_PUBLISHER_TOKEN`, or anonymously. The token is never logged and never put in a URL.
- Serving the branch is the user's job.
