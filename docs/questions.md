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
- **Recommendation:** A throwaway public repo `ivangsa/git-maven-publisher-e2e` with Pages enabled and its own fine-grained PAT stored as `E2E_TOKEN` in this repo.
