# Open questions

Each entry: question · blocks · recommendation. Answered questions move to `docs/decisions.md`.

## Q2 Snapshot repository and token for dogfooding
- **Blocks:** T33.
- **Question:** Does `zenwave360/maven-snapshots` exist, and who creates the fine-grained PAT (`Contents: write`, that repo only) and stores it as `GIT_PUBLISHER_TOKEN` in the publishing repos?
- **Recommendation:** Dedicated public repo `zenwave360/maven-snapshots` with Pages from `gh-pages`. You create the PAT and the secrets; I write the workflow.

## Q4 Central organization for `io.github.ivangsa`
- **Blocks:** T32.
- **Question:** Which Central organization does the namespace land in (shared quota with other namespaces)?
- **Recommendation:** Check the Central Usage Center before T32. Only releases go to Central; snapshots go to our own Git repo, so the impact is small.

## Q7 Nightly end-to-end repository
- **Blocks:** T51.
- **Question:** Which real repo can the nightly job publish to (it gets reset), and which secret?
- **Recommendation:** A throwaway public repo `ivangsa/git-maven-publisher-e2e` (resolved through `raw.githubusercontent.com`) and its own fine-grained PAT stored as `E2E_TOKEN` in this repo.

## Q9 Serving self-published snapshots from a private repository
- **Blocks:** T33 (self-publication part).
- **Question:** This repository is private (D-009). Consumers need anonymous read access: `raw.githubusercontent.com` does not serve private repos anonymously, and Pages on a private repo needs a paid plan. Do we make the repo public before self-publication, or publish this project's snapshots to a separate public repository?
- **Recommendation:** Make the repository public before self-publication starts; until then T33 covers only ZenWave360 (Q2).

## Q10 How the Gradle build reuses the core packages
- **Blocks:** T34 (start of Phase 2).
- **Question:** With one Maven artifact (D-037) there is no separate core jar. Options: (a) split a `git-maven-publisher-core` artifact out again at Phase 2; (b) the Gradle build compiles the core package sources from `../src/main/java` (filtered to `io.github.ivangsa.gitpublisher`, excluding `maven/`); (c) depend on the Maven plugin jar.
- **Recommendation:** (a) when Phase 2 starts: a published core jar is what the Plugin Portal artifact needs at runtime anyway; (c) drags Maven-plugin packaging into Gradle.
